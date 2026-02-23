package com.voiceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class CallMonitorService extends Service {

    private static final String TAG = "CallMonitorService";
    
    public static CallListener listener;
    public static CallAnswerListener answerListener;
    
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
    
    public interface CallAnswerListener {
        void onAnswerRequested(String number);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.init(this);
        AppLogger.d("Service created");
        
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannels();
        
        registerAnswerReceiver();
    }
    
    private void registerAnswerReceiver() {
        IntentFilter filter = new IntentFilter(AppConstants.ACTION_ANSWER);
        try {
            registerReceiver(answerReceiver, filter);
        } catch (Exception e) {
            AppLogger.e("Error registering receiver", e);
        }
    }
    
    private final BroadcastReceiver answerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AppConstants.ACTION_ANSWER.equals(intent.getAction())) {
                String number = intent.getStringExtra(AppConstants.EXTRA_PHONE_NUMBER);
                AppLogger.d("Received answer action for: " + number);
                answerCall();
            }
        }
    };
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLogger.d("onStartCommand called");
        
        if (intent != null) {
            String action = intent.getAction();
            AppLogger.d("Action: " + action);
            
            if (AppConstants.ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            
            if (AppConstants.ACTION_ANSWER.equals(action)) {
                String number = intent.getStringExtra(AppConstants.EXTRA_PHONE_NUMBER);
                answerCall();
            }
        }
        
        startForeground(AppConstants.NOTIFICATION_ID_FOREGROUND, createForegroundNotification());
        startCallMonitoring();
        
        return START_STICKY;
    }
    
    private void startCallMonitoring() {
        if (isListeningForCalls) {
            AppLogger.d("Already listening for calls");
            return;
        }
        
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) 
                    != PackageManager.PERMISSION_GRANTED) {
                AppLogger.e("READ_PHONE_STATE permission not granted");
                return;
            }
            
            telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            
            if (telephonyManager != null) {
                phoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        super.onCallStateChanged(state, phoneNumber);
                        AppLogger.d("Call state: " + state + ", number: " + phoneNumber);
                        
                        switch (state) {
                            case TelephonyManager.CALL_STATE_RINGING:
                                AppLogger.d("=== CALL RINGING ===");
                                currentCallNumber = phoneNumber != null ? phoneNumber : "Unknown";
                                showIncomingCallNotification(currentCallNumber);
                                if (listener != null) {
                                    listener.onCallRinging(currentCallNumber);
                                }
                                break;
                                
                            case TelephonyManager.CALL_STATE_OFFHOOK:
                                AppLogger.d("=== CALL OFFHOOK (ANSWERED) ===");
                                if (listener != null) {
                                    listener.onCallAnswered();
                                }
                                break;
                                
                            case TelephonyManager.CALL_STATE_IDLE:
                                AppLogger.d("=== CALL IDLE ===");
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
                AppLogger.d("Started listening for calls");
            }
        } catch (Exception e) {
            AppLogger.e("Error starting call monitoring", e);
        }
    }
    
    private void createNotificationChannels() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager == null) return;
                
                // Call notification channel
                NotificationChannel callChannel = new NotificationChannel(
                    AppConstants.CHANNEL_ID_CALLS,
                    getString(R.string.notification_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH
                );
                callChannel.setDescription("Incoming call notifications");
                callChannel.enableVibration(true);
                callChannel.setVibrationPattern(new long[]{0, 500, 200, 500});
                callChannel.setShowBadge(true);
                callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                manager.createNotificationChannel(callChannel);
                
                // Error notification channel
                NotificationChannel errorChannel = new NotificationChannel(
                    AppConstants.CHANNEL_ID_ERRORS,
                    getString(R.string.notification_channel_errors),
                    NotificationManager.IMPORTANCE_DEFAULT
                );
                errorChannel.setDescription("Error notifications");
                manager.createNotificationChannel(errorChannel);
                
                AppLogger.d("Notification channels created");
            }
        } catch (Exception e) {
            AppLogger.e("Error creating notification channels", e);
        }
    }
    
    private Notification createForegroundNotification() {
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, AppConstants.CHANNEL_ID_CALLS)
            .setSmallIcon(R.drawable.ic_mic_white)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_monitoring))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .build();
    }
    
    private void showIncomingCallNotification(String phoneNumber) {
        AppLogger.d("Showing incoming call notification for: " + phoneNumber);
        
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent answerIntent = new Intent(this, CallMonitorService.class);
        answerIntent.setAction(AppConstants.ACTION_ANSWER);
        answerIntent.putExtra(AppConstants.EXTRA_PHONE_NUMBER, phoneNumber);
        PendingIntent answerPendingIntent = PendingIntent.getService(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent dismissIntent = new Intent(this, MainActivity.class);
        dismissIntent.setAction(AppConstants.ACTION_REJECT);
        dismissIntent.putExtra(AppConstants.EXTRA_PHONE_NUMBER, phoneNumber);
        PendingIntent dismissPendingIntent = PendingIntent.getActivity(
            this, 2, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        String notificationText = getString(R.string.from_number, phoneNumber);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, AppConstants.CHANNEL_ID_CALLS)
            .setSmallIcon(R.drawable.ic_mic_white)
            .setContentTitle(getString(R.string.incoming_call))
            .setContentText(notificationText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationText + "\n" + getString(R.string.call_transferring)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setVibrate(new long[]{0, 500, 200, 500})
            .setContentIntent(mainPendingIntent)
            .setFullScreenIntent(mainPendingIntent, true)
            .addAction(R.drawable.ic_mic_white, getString(R.string.notification_action_answer), answerPendingIntent)
            .addAction(R.drawable.ic_mic_white, getString(R.string.notification_action_dismiss), dismissPendingIntent);
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(AppConstants.NOTIFICATION_ID_INCOMING_CALL, builder.build());
            AppLogger.d("Notification shown successfully");
        } else {
            AppLogger.e("NotificationManager is null");
        }
    }
    
    private void dismissCallNotification() {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(AppConstants.NOTIFICATION_ID_INCOMING_CALL);
            }
        } catch (Exception e) {
            AppLogger.e("Error dismissing notification", e);
        }
    }
    
    public void answerCall() {
        AppLogger.d("Attempting to answer call...");
        
        if (answerListener != null) {
            answerListener.onAnswerRequested(currentCallNumber);
        }
        
        // Try TelecomManager first
        boolean answered = tryAnswerWithTelecomManager();
        
        if (!answered) {
            // Fallback to ACTION_ANSWER
            answered = tryAnswerWithActionAnswer();
        }
        
        if (!answered) {
            // Final fallback to keyevent
            answered = tryAnswerWithKeyEvent();
        }
    }
    
    private boolean tryAnswerWithTelecomManager() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) 
                            == PackageManager.PERMISSION_GRANTED) {
                        if (telecomManager.isRinging()) {
                            telecomManager.acceptRingingCall();
                            AppLogger.d("Call answered via TelecomManager");
                            
                            mainHandler.postDelayed(() -> {
                                if (listener != null) {
                                    listener.onCallAnswered();
                                }
                            }, 500);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.e("TelecomManager error", e);
        }
        return false;
    }
    
    private boolean tryAnswerWithActionAnswer() {
        try {
            Intent intent = new Intent(Intent.ACTION_ANSWER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            AppLogger.d("Call answered via ACTION_ANSWER");
            return true;
        } catch (Exception e) {
            AppLogger.e("ACTION_ANSWER error", e);
        }
        return false;
    }
    
    private boolean tryAnswerWithKeyEvent() {
        try {
            Runtime.getRuntime().exec("input keyevent 5");
            AppLogger.d("Call answered via keyevent");
            return true;
        } catch (Exception e) {
            AppLogger.e("keyevent error", e);
        }
        return false;
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
            
            AppLogger.d("Speaker enabled successfully");
        } catch (Exception e) {
            AppLogger.e("Speaker error", e);
        }
    }
    
    public void disableSpeakerMode() {
        try {
            if (audioManager != null) {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception e) {
            AppLogger.e("Disable speaker error", e);
        }
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        AppLogger.d("Service destroyed");
        
        try {
            unregisterReceiver(answerReceiver);
        } catch (Exception e) {
            AppLogger.e("Error unregistering receiver", e);
        }
        
        if (telephonyManager != null && phoneStateListener != null && isListeningForCalls) {
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception e) {
                AppLogger.e("Error stopping listener", e);
            }
            isListeningForCalls = false;
        }
        
        super.onDestroy();
    }
}
