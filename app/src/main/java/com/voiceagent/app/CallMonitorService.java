package com.voiceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class CallMonitorService extends Service {

    private static final String TAG = "CallMonitorService";
    private static final String CHANNEL_ID = "voice_agent_call_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int FOREGROUND_NOTIFICATION_ID = 1002;
    
    public static String ACTION_ANSWER = "com.voiceagent.app.ACTION_ANSWER";
    public static String ACTION_REJECT = "com.voiceagent.app.ACTION_REJECT";
    public static String ACTION_START = "com.voiceagent.app.START";
    public static String ACTION_STOP = "com.voiceagent.app.STOP";
    
    public static CallListener listener;
    
    private TelephonyManager telephonyManager;
    private AudioManager audioManager;
    private PhoneStateListener phoneStateListener;
    private boolean isListeningForCalls = false;
    private String currentCallNumber = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    public interface CallListener {
        void onCallRinging(String number);
        void onCallAnswered();
        void onCallEnded();
        void onCallDisconnected();
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        
        if (intent != null) {
            String action = intent.getAction();
            Log.d(TAG, "Action: " + action);
            
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        
        // Start foreground with notification
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
        
        // Start monitoring calls
        startCallMonitoring();
        
        return START_STICKY;
    }
    
    private void startCallMonitoring() {
        if (isListeningForCalls) {
            Log.d(TAG, "Already listening for calls");
            return;
        }
        
        try {
            // Check permission first
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) 
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "READ_PHONE_STATE permission not granted");
                return;
            }
            
            telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            
            if (telephonyManager != null) {
                phoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        super.onCallStateChanged(state, phoneNumber);
                        Log.d(TAG, "Call state: " + state + ", number: " + phoneNumber);
                        
                        switch (state) {
                            case TelephonyManager.CALL_STATE_RINGING:
                                Log.d(TAG, "=== CALL RINGING ===");
                                currentCallNumber = phoneNumber != null ? phoneNumber : "Unknown";
                                showIncomingCallNotification(currentCallNumber);
                                if (listener != null) {
                                    listener.onCallRinging(currentCallNumber);
                                }
                                break;
                                
                            case TelephonyManager.CALL_STATE_OFFHOOK:
                                Log.d(TAG, "=== CALL OFFHOOK (ANSWERED) ===");
                                if (listener != null) {
                                    listener.onCallAnswered();
                                }
                                break;
                                
                            case TelephonyManager.CALL_STATE_IDLE:
                                Log.d(TAG, "=== CALL IDLE ===");
                                dismissCallNotification();
                                currentCallNumber = null;
                                if (listener != null) {
                                    listener.onCallEnded();
                                }
                                break;
                        }
                    }
                };
                
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                isListeningForCalls = true;
                Log.d(TAG, "Started listening for calls");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting call monitoring: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Voice Agent",
                    NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Call notifications");
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 500, 200, 500});
                channel.setShowBadge(true);
                
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification channel: " + e.getMessage());
        }
    }
    
    private Notification createForegroundNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_white)
            .setContentTitle("Voice Agent")
            .setContentText("Active - Monitoring calls")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .build();
    }
    
    private void showIncomingCallNotification(String phoneNumber) {
        Log.d(TAG, "Showing incoming call notification for: " + phoneNumber);
        
        // Full screen intent - opens app when tapped
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setAction(ACTION_ANSWER);
        fullScreenIntent.putExtra("phone_number", phoneNumber);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Confirm action
        Intent confirmIntent = new Intent(this, MainActivity.class);
        confirmIntent.setAction(ACTION_ANSWER);
        confirmIntent.putExtra("phone_number", phoneNumber);
        confirmIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent confirmPendingIntent = PendingIntent.getActivity(
            this, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_white)
            .setContentTitle("📞 Incoming Call")
            .setContentText("From: " + phoneNumber)
            .setStyle(new NotificationCompat.BigTextStyle().bigText("From: " + phoneNumber))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(new long[]{0, 500, 200, 500})
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_mic_white, "✅ CONFIRM", confirmPendingIntent);
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }
    
    private void dismissCallNotification() {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing notification: " + e.getMessage());
        }
    }
    
    public void answerCall() {
        Log.d(TAG, "Attempting to answer call...");
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) 
                            == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.acceptRingingCall();
                        Log.d(TAG, "Call answered via TelecomManager");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "TelecomManager error: " + e.getMessage());
        }
        
        // Fallback 1: Intent
        try {
            Intent intent = new Intent(Intent.ACTION_ANSWER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "Call answered via ACTION_ANSWER");
        } catch (Exception e) {
            Log.e(TAG, "ACTION_ANSWER error: " + e.getMessage());
            
            // Fallback 2: Key event
            try {
                Runtime.getRuntime().exec("input keyevent 5");
                Log.d(TAG, "Call answered via keyevent");
            } catch (Exception ex) {
                Log.e(TAG, "keyevent error: " + ex.getMessage());
            }
        }
    }
    
    public void enableSpeakerMode() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, 
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            
            Log.d(TAG, "Speaker enabled successfully");
        } catch (Exception e) {
            Log.e(TAG, "Speaker error: " + e.getMessage());
        }
    }
    
    public void disableSpeakerMode() {
        try {
            if (audioManager != null) {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception e) {
            Log.e(TAG, "Disable speaker error: " + e.getMessage());
        }
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        
        if (telephonyManager != null && phoneStateListener != null && isListeningForCalls) {
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping listener: " + e.getMessage());
            }
            isListeningForCalls = false;
        }
        
        super.onDestroy();
    }
}
