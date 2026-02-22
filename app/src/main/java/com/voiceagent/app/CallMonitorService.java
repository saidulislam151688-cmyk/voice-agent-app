package com.voiceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
    
    public static final String ACTION_ANSWER = "com.voiceagent.app.ACTION_ANSWER";
    public static final String ACTION_REJECT = "com.voiceagent.app.ACTION_REJECT";
    public static final String ACTION_START_SERVICE = "com.voiceagent.app.START_SERVICE";
    public static final String ACTION_STOP_SERVICE = "com.voiceagent.app.STOP_SERVICE";
    
    public static CallListener listener;
    
    private TelephonyManager telephonyManager;
    private AudioManager audioManager;
    private boolean isListeningForCalls = false;
    private String currentCallNumber = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    public interface CallListener {
        void onIncomingCall(String number);
        void onCallAnswered();
        void onCallEnded();
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
            
            if (ACTION_STOP_SERVICE.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
        startCallMonitoring();
        
        return START_STICKY;
    }
    
    private void startCallMonitoring() {
        if (isListeningForCalls) return;
        
        try {
            telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            
            if (telephonyManager != null) {
                telephonyManager.listen(new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        super.onCallStateChanged(state, phoneNumber);
                        Log.d(TAG, "Call state changed: " + state + ", number: " + phoneNumber);
                        
                        switch (state) {
                            case TelephonyManager.CALL_STATE_RINGING:
                                Log.d(TAG, "=== INCOMING CALL ===");
                                currentCallNumber = phoneNumber != null ? phoneNumber : "Unknown";
                                showCallNotification(currentCallNumber);
                                if (listener != null) {
                                    listener.onIncomingCall(currentCallNumber);
                                }
                                break;
                                
                            case TelephonyManager.CALL_STATE_OFFHOOK:
                                Log.d(TAG, "Call answered/active");
                                if (listener != null) {
                                    listener.onCallAnswered();
                                }
                                break;
                                
                            case TelephonyManager.CALL_STATE_IDLE:
                                Log.d(TAG, "Call idle");
                                currentCallNumber = null;
                                dismissCallNotification();
                                if (listener != null) {
                                    listener.onCallEnded();
                                }
                                break;
                        }
                    }
                }, PhoneStateListener.LISTEN_CALL_STATE);
                
                isListeningForCalls = true;
                Log.d(TAG, "Started monitoring calls");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting call monitoring: " + e.getMessage());
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Voice Agent Calls",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Call notifications for Voice Agent");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
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
            .setContentTitle("Voice Agent Active")
            .setContentText("Monitoring for incoming calls...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .build();
    }
    
    private void showCallNotification(String phoneNumber) {
        Log.d(TAG, "Showing call notification for: " + phoneNumber);
        
        // Confirm action
        Intent confirmIntent = new Intent(this, CallActionReceiver.class);
        confirmIntent.setAction(ACTION_ANSWER);
        confirmIntent.putExtra("phone_number", phoneNumber);
        PendingIntent confirmPendingIntent = PendingIntent.getBroadcast(
            this, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Reject action
        Intent rejectIntent = new Intent(this, CallActionReceiver.class);
        rejectIntent.setAction(ACTION_REJECT);
        rejectIntent.putExtra("phone_number", phoneNumber);
        PendingIntent rejectPendingIntent = PendingIntent.getBroadcast(
            this, 1, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Full screen intent
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setAction(ACTION_ANSWER);
        fullScreenIntent.putExtra("phone_number", phoneNumber);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_white)
            .setContentTitle("📞 Incoming Call")
            .setContentText("From: " + phoneNumber)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(new long[]{0, 500, 200, 500})
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_mic_white, "✅ CONFIRM", confirmPendingIntent)
            .addAction(R.drawable.ic_mic_white, "❌ REJECT", rejectPendingIntent);
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }
    
    private void dismissCallNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }
    
    public void answerCall() {
        Log.d(TAG, "Attempting to answer call...");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) 
                        == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall();
                    Log.d(TAG, "Call answered via TelecomManager");
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "TelecomManager error: " + e.getMessage());
            }
        }
        
        // Fallback methods
        try {
            Intent intent = new Intent(Intent.ACTION_ANSWER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "Call answered via ACTION_ANSWER");
        } catch (Exception e) {
            Log.e(TAG, "ACTION_ANSWER error: " + e.getMessage());
            
            try {
                Runtime.getRuntime().exec("input keyevent 5");
                Log.d(TAG, "Call answered via keyevent");
            } catch (Exception ex) {
                Log.e(TAG, "keyevent error: " + ex.getMessage());
            }
        }
    }
    
    public void enableSpeaker() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, 
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            Log.d(TAG, "Speaker enabled");
        } catch (Exception e) {
            Log.e(TAG, "Speaker error: " + e.getMessage());
        }
    }
    
    public void rejectCall() {
        try {
            // Try to reject the call using TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    // Unfortunately, TelecomManager doesn't have a direct endCall method
                    // We need to use ITelecomService or just ignore the call
                }
            }
            // Fallback: just dismiss the notification
            // The user will need to manually reject on their phone
        } catch (Exception e) {
            Log.e(TAG, "Error rejecting call: " + e.getMessage());
        }
        dismissCallNotification();
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        
        if (telephonyManager != null && isListeningForCalls) {
            telephonyManager.listen(null, PhoneStateListener.LISTEN_NONE);
            isListeningForCalls = false;
        }
        
        super.onDestroy();
    }
}
