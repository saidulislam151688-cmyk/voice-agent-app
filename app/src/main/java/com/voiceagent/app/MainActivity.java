package com.voiceagent.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telecom.TelecomManager;
import android.util.Log;
import android.os.HandlerThread;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    
    private Handler mainHandler;
    private HandlerThread handlerThread;
    private ExecutorService executor;
    
    private SpeechRecognizer speechRecognizer = null;
    private TextToSpeech textToSpeech = null;
    private boolean isTTSReady = false;
    private boolean isRecognitionReady = false;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    
    private String detectedLanguage = AppConstants.LANGUAGE_ENGLISH;
    private String preferredLanguage = AppConstants.LANGUAGE_AUTO;
    
    private FloatingActionButton btnToggle;
    private TextView tvStatus, tvUser, tvAI, tvTitle;
    private ImageView tvIcon;
    private View circleView;
    
    private boolean isConversationActive = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean isDestroyed = false;
    private boolean isApiKeyValid = true;
    
    private String incomingCallNumber = null;
    private String incomingCallName = null;
    private boolean isCallActive = false;
    private boolean serviceBound = false;
    private CallMonitorService callMonitorService = null;
    
    private long conversationStartTime = 0;
    private Handler durationHandler = null;
    private Runnable durationRunnable = null;
    
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;
    
    private AudioManager.OnAudioRoutingChangedListener audioRoutingListener;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Service connected");
            serviceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Service disconnected");
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        AppLogger.init(this);
        AppLogger.d("=== Voice Agent Starting ===");
        
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initHandler();
        
        initViews();
        checkPermissions();
        
        executor = Executors.newSingleThreadExecutor();
        handleIntent(getIntent());
        
        loadPreferences();
        checkApiKey();
        
        AppLogger.d("Voice Agent started");
    }
    
    private void initHandler() {
        handlerThread = new HandlerThread("VoiceAgentThread");
        handlerThread.start();
        mainHandler = new Handler(handlerThread.getLooper());
    }
    
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
        preferredLanguage = prefs.getString(AppConstants.PREF_LANGUAGE, AppConstants.LANGUAGE_AUTO);
        AppLogger.d("Loaded preferred language: " + preferredLanguage);
    }
    
    private void checkApiKey() {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_GROQ_API_KEY")) {
            isApiKeyValid = false;
            AppLogger.w("API key not configured");
            runOnUiThread(() -> showApiKeyDialog());
        } else {
            isApiKeyValid = true;
            AppLogger.d("API key is configured");
        }
    }
    
    private String getApiKey() {
        try {
            return BuildConfig.GROQ_API_KEY;
        } catch (Exception e) {
            AppLogger.e("Error getting API key", e);
            return "YOUR_GROQ_API_KEY";
        }
    }
    
    private void showApiKeyDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_api_key, null);
        TextInputEditText etApiKey = dialogView.findViewById(R.id.etApiKey);
        
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.api_key)
            .setMessage(R.string.error_api_key)
            .setView(dialogView)
            .setPositiveButton(R.string.save, (dialog, which) -> {
                String key = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
                if (!key.isEmpty()) {
                    showMessage("API key updated. Please rebuild the app with your key in local.properties");
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }
    
    private void showMessage(String message) {
        runOnUiThread(() -> {
            if (!isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleIntent(Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            String phoneNumber = intent.getStringExtra(AppConstants.EXTRA_PHONE_NUMBER);
            
            AppLogger.d("Action: " + action + ", Number: " + phoneNumber);
            
            if (AppConstants.ACTION_ANSWER.equals(action)) {
                AppLogger.d("ACTION_ANSWER received in handleIntent");
                incomingCallNumber = phoneNumber;
                incomingCallName = getContactName(phoneNumber);
                answerAndTransferCall(phoneNumber);
            } else if (AppConstants.ACTION_REJECT.equals(action)) {
                AppLogger.d("ACTION_REJECT received");
                dismissNotification();
            } else if (AppConstants.ACTION_TRANSFER.equals(action)) {
                incomingCallNumber = phoneNumber;
                incomingCallName = getContactName(phoneNumber);
                transferCallToAgent(phoneNumber);
            }
        }
    }

    private String getContactName(String phoneNumber) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) 
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        
        try {
            android.database.Cursor cursor = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                new String[]{"%" + phoneNumber},
                null
            );
            
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        String name = cursor.getString(nameIndex);
                        cursor.close();
                        return name;
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            AppLogger.e("Error getting contact name", e);
        }
        return null;
    }

    private void dismissNotification() {
        try {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                manager.cancel(AppConstants.NOTIFICATION_ID_INCOMING_CALL);
            }
        } catch (Exception e) {
            AppLogger.e("Error dismissing notification: " + e.getMessage());
        }
    }

    private void answerAndTransferCall(String phoneNumber) {
        AppLogger.d("=== ANSWER AND TRANSFER CALL === Number: " + phoneNumber);
        
        dismissNotification();
        
        mainHandler.postDelayed(() -> {
            answerCallDirectly(phoneNumber);
        }, AppConstants.CALL_TRANSFER_DELAY_MS);
    }
    
    private void answerCallDirectly(String phoneNumber) {
        AppLogger.d("Attempting to answer call for: " + phoneNumber);
        
        // Try TelecomManager first (Android 6.0+)
        boolean answered = tryAnswerWithTelecomManager();
        
        if (!answered) {
            // Fallback to ACTION_ANSWER
            answered = tryAnswerWithActionAnswer();
        }
        
        if (!answered) {
            // Final fallback to keyevent
            answered = tryAnswerWithKeyEvent();
        }
        
        if (answered) {
            mainHandler.postDelayed(() -> {
                transferCallToAgent(phoneNumber);
            }, AppConstants.CALL_TRANSFER_DELAY_MS * 2);
        } else {
            AppLogger.e("All answer methods failed");
            transferCallToAgent(phoneNumber); // Still try to transfer
        }
    }
    
    private boolean tryAnswerWithTelecomManager() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) 
                            == PackageManager.PERMISSION_GRANTED) {
                        if (telecomManager.isRinging()) {
                            telecomManager.acceptRingingCall();
                            AppLogger.d("Call answered via TelecomManager");
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.e("TelecomManager error: " + e.getMessage());
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
            AppLogger.e("ACTION_ANSWER error: " + e.getMessage());
        }
        return false;
    }
    
    private boolean tryAnswerWithKeyEvent() {
        try {
            Runtime.getRuntime().exec("input keyevent 5");
            AppLogger.d("Call answered via keyevent");
            return true;
        } catch (Exception e) {
            AppLogger.e("keyevent error: " + e.getMessage());
        }
        return false;
    }

    private void initViews() {
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        tvUser = findViewById(R.id.tvUser);
        tvAI = findViewById(R.id.tvAI);
        tvTitle = findViewById(R.id.tvTitle);
        tvIcon = findViewById(R.id.tvIcon);
        circleView = findViewById(R.id.circleView);
        
        btnToggle.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            toggleConversation();
        });
        
        initSpeechRecognition();
        initTextToSpeech();
        
        updateUI("idle");
    }

    private void initSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AppLogger.e("Speech recognition not available");
            showErrorDialog(getString(R.string.speech_not_available));
            return;
        }
        
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { 
                    AppLogger.d("Ready for speech"); 
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { 
                    AppLogger.d("End of speech"); 
                    isListening = false; 
                }

                @Override
                public void onError(int error) {
                    AppLogger.e("Speech error: " + error);
                    isListening = false;
                    
                    if (isConversationActive && !isSpeaking) {
                        handleSpeechError(error);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    AppLogger.d("Got results");
                    isListening = false;
                    
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        AppLogger.d("Recognized: " + text);
                        
                        detectLanguage(text);
                        handleUserInput(text);
                    } else if (isConversationActive && !isSpeaking) {
                        mainHandler.postDelayed(() -> startListening(), AppConstants.LISTEN_START_DELAY_MS);
                    }
                }

                @Override public void onPartialResults(Bundle bundle) {}
                @Override public void onEvent(int i, Bundle bundle) {}
            });
            
            isRecognitionReady = true;
            AppLogger.d("Speech recognition ready");
        } catch (Exception e) {
            AppLogger.e("Speech init error", e);
        }
    }
    
    private void handleSpeechError(int error) {
        // Handle specific speech recognition errors
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                AppLogger.d("No speech match");
                retryOrRecover();
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                AppLogger.d("Speech timeout");
                retryOrRecover();
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                AppLogger.e("Audio recording error");
                retryOrRecover();
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                AppLogger.e("Network error in speech recognition");
                showMessage(getString(R.string.error_no_internet));
                stopConversation();
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                AppLogger.e("Network timeout");
                retryOrRecover();
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                AppLogger.e("Recognizer busy");
                mainHandler.postDelayed(this::retryOrRecover, 1000);
                break;
            default:
                retryOrRecover();
        }
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                isTTSReady = true;
                
                // Set default language
                int langResult = textToSpeech.setLanguage(Locale.US);
                if (langResult == TextToSpeech.LANG_MISSING_DATA || 
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    AppLogger.w("English TTS not available");
                }
                
                textToSpeech.setSpeechRate(AppConstants.DEFAULT_SPEECH_RATE);
                textToSpeech.setPitch(AppConstants.DEFAULT_PITCH);
                
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        isSpeaking = true;
                        runOnUiThread(() -> updateUI("speaking"));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isSpeaking = false;
                        runOnUiThread(() -> {
                            updateUI("idle");
                            if (isConversationActive) {
                                mainHandler.postDelayed(() -> {
                                    if (isConversationActive && !isSpeaking && !isListening) {
                                        startListening();
                                    }
                                }, AppConstants.TTS_START_DELAY_MS);
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isSpeaking = false;
                        AppLogger.e("TTS error: " + utteranceId);
                        runOnUiThread(() -> {
                            updateUI("idle");
                            if (isConversationActive) {
                                retryOrRecover();
                            }
                        });
                    }
                });
                
                AppLogger.d("TTS ready");
            } else {
                AppLogger.e("TTS init failed: " + status);
                showErrorDialog(getString(R.string.tts_not_available));
            }
        });
    }

    private void checkPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        
        // Add contacts permission for caller ID
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CONTACTS);
        }
        
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), AppConstants.PERMISSION_CODE);
        } else {
            startCallService();
        }
    }

    private void startCallService() {
        try {
            Intent serviceIntent = new Intent(this, CallMonitorService.class);
            startService(serviceIntent);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            
            CallMonitorService.listener = new CallMonitorService.CallListener() {
                @Override
                public void onCallRinging(String number) {
                    AppLogger.d("Call ringing: " + number);
                    runOnUiThread(() -> {
                        String displayName = getContactName(number);
                        String displayText = displayName != null ? displayName : number;
                        Toast.makeText(MainActivity.this, getString(R.string.incoming_call) + ": " + displayText, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onCallAnswered() {
                    AppLogger.d("Call answered by system");
                    runOnUiThread(() -> {
                        if (isConversationActive) {
                            enableAudioForCall();
                        }
                    });
                }

                @Override
                public void onCallEnded() {
                    AppLogger.d("Call ended");
                    runOnUiThread(() -> stopConversation());
                }

                @Override
                public void onCallDisconnected() {
                    AppLogger.d("Call disconnected");
                }
            };
            
            CallMonitorService.answerListener = number -> {
                AppLogger.d("Answer listener triggered for: " + number);
                runOnUiThread(() -> {
                    incomingCallNumber = number;
                    incomingCallName = getContactName(number);
                    answerAndTransferCall(number);
                });
            };
            
            AppLogger.d("Call service started");
        } catch (Exception e) {
            AppLogger.e("Error starting service", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == AppConstants.PERMISSION_CODE) {
            boolean allGranted = true;
            List<String> deniedPermissions = new ArrayList<>();
            
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    deniedPermissions.add(permissions[i]);
                    AppLogger.w("Permission denied: " + permissions[i]);
                }
            }
            
            final boolean granted = allGranted;
            runOnUiThread(() -> {
                if (granted) {
                    Toast.makeText(this, R.string.permission_all_granted, Toast.LENGTH_SHORT).show();
                    startCallService();
                } else {
                    Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                    // Start service anyway, some features will just not work
                    startCallService();
                }
            });
        }
    }

    private void toggleConversation() {
        if (isConversationActive) {
            stopConversation();
        } else {
            startConversation();
        }
    }

    private void startConversation() {
        if (isConversationActive) return;
        if (!isTTSReady || !isRecognitionReady) {
            Toast.makeText(this, R.string.status_starting, Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!isApiKeyValid) {
            showApiKeyDialog();
            return;
        }
        
        if (!NetworkUtils.isNetworkAvailable(this)) {
            showErrorDialog(getString(R.string.error_no_internet));
            return;
        }
        
        isConversationActive = true;
        detectedLanguage = resolveLanguage();
        
        acquireWakeLock();
        enableAudioForCall();
        registerAudioRoutingListener();
        updateUI("starting");
        
        String greeting = detectedLanguage.equals(AppConstants.LANGUAGE_BENGALI) ? 
                getString(R.string.greeting_bn) : getString(R.string.greeting_en);
        
        speak(greeting);
        startDurationMonitoring();
    }
    
    private String resolveLanguage() {
        if (preferredLanguage.equals(AppConstants.LANGUAGE_AUTO)) {
            return detectedLanguage;
        }
        return preferredLanguage;
    }
    
    private void detectLanguage(String text) {
        if (preferredLanguage.equals(AppConstants.LANGUAGE_AUTO)) {
            boolean hasBengali = text.matches(".*[\\u0980-\\u09FF].*");
            detectedLanguage = hasBengali ? AppConstants.LANGUAGE_BENGALI : AppConstants.LANGUAGE_ENGLISH;
            AppLogger.d("Detected language: " + detectedLanguage);
        } else {
            detectedLanguage = preferredLanguage;
        }
    }

    private void stopConversation() {
        if (!isConversationActive) return;
        
        isConversationActive = false;
        isCallActive = false;
        
        stopDurationMonitoring();
        releaseWakeLock();
        unregisterAudioRoutingListener();
        
        try {
            if (speechRecognizer != null) speechRecognizer.cancel();
            if (textToSpeech != null) textToSpeech.stop();
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Exception e) { 
            AppLogger.e("Stop error: " + e.getMessage()); 
        }
        
        isListening = false;
        isSpeaking = false;
        retryCount = 0;
        
        runOnUiThread(() -> {
            if (tvStatus != null) tvStatus.setText(R.string.status_tap_to_start);
            if (tvUser != null) tvUser.setText(R.string.chat_user_placeholder);
            if (tvAI != null) tvAI.setText(R.string.chat_ai_placeholder);
            updateUI("idle");
        });
    }
    
    private void startDurationMonitoring() {
        conversationStartTime = System.currentTimeMillis();
        
        durationHandler = new Handler(Looper.getMainLooper());
        durationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isConversationActive) return;
                
                long durationMinutes = (System.currentTimeMillis() - conversationStartTime) / 60000;
                
                if (durationMinutes >= AppConstants.CALL_DURATION_WARNING_MINUTES) {
                    int remainingMinutes = AppConstants.MAX_CALL_DURATION_MINUTES - (int)durationMinutes;
                    if (remainingMinutes > 0) {
                        String warning = String.format(Locale.getDefault(), 
                                getString(R.string.call_warning_duration), remainingMinutes);
                        speak(warning);
                    }
                }
                
                if (durationMinutes >= AppConstants.MAX_CALL_DURATION_MINUTES) {
                    AppLogger.d("Max call duration reached");
                    speak("Maximum call duration reached. Goodbye!");
                    stopConversation();
                    return;
                }
                
                durationHandler.postDelayed(this, 60000); // Check every minute
            }
        };
        
        durationHandler.postDelayed(durationRunnable, AppConstants.CALL_DURATION_WARNING_MINUTES * 60000);
    }
    
    private void stopDurationMonitoring() {
        if (durationHandler != null && durationRunnable != null) {
            durationHandler.removeCallbacks(durationRunnable);
            durationHandler = null;
            durationRunnable = null;
        }
    }
    
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAgent:WakeLock");
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(AppConstants.MAX_CALL_DURATION_MINUTES * 60 * 1000L);
            AppLogger.d("WakeLock acquired");
        }
    }
    
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            AppLogger.d("WakeLock released");
        }
    }
    
    private void registerAudioRoutingListener() {
        if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioRoutingListener = new AudioManager.OnAudioRoutingChangedListener() {
                    @Override
                    public void onAudioRoutingChanged(AudioManager audioManager) {
                        AppLogger.d("Audio routing changed: " + audioManager.getMode());
                        handleAudioRoutingChange();
                    }
                };
                audioManager.registerAudioRoutingChangedListener(audioRoutingListener);
            } catch (Exception e) {
                AppLogger.e("Error registering audio routing listener", e);
            }
        }
    }
    
    private void unregisterAudioRoutingListener() {
        if (audioManager != null && audioRoutingListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.unregisterAudioRoutingChangedListener(audioRoutingListener);
            } catch (Exception e) {
                AppLogger.e("Error unregistering audio listener", e);
            }
        }
    }
    
    private void handleAudioRoutingChange() {
        // Audio routing changed (headphones, bluetooth, etc.)
        // Re-enable speaker for call if needed
        if (isCallActive || isConversationActive) {
            mainHandler.postDelayed(this::enableAudioForCall, 500);
        }
    }

    private void startListening() {
        if (isDestroyed || !isConversationActive || isSpeaking || isListening) return;
        if (speechRecognizer == null) return;
        
        if (!NetworkUtils.isNetworkAvailable(this)) {
            AppLogger.w("No network available for speech recognition");
            showMessage(getString(R.string.error_no_internet));
            return;
        }
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            
            // Use detected language or Bengali BD as default
            String lang = detectedLanguage.equals(AppConstants.LANGUAGE_BENGALI) ? 
                    AppConstants.LANGUAGE_BENGALI_BD : "en-US";
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, AppConstants.MAX_SPEECH_RESULTS);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, AppConstants.SILENCE_THRESHOLD_MS);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, AppConstants.MIN_SPEECH_LENGTH_MS);
            
            isListening = true;
            updateUI("listening");
            
            speechRecognizer.startListening(intent);
            AppLogger.d("Started listening");
            
            // Auto-stop listening after timeout
            mainHandler.postDelayed(() -> {
                if (isListening) {
                    try {
                        speechRecognizer.stopListening();
                    } catch (Exception e) {
                        AppLogger.e("Error stopping listening", e);
                    }
                }
            }, AppConstants.SPEECH_TIMEOUT_MS);
            
        } catch (Exception e) {
            AppLogger.e("Error starting listening", e);
            retryOrRecover();
        }
    }

    private void handleUserInput(String text) {
        if (text == null || text.trim().isEmpty()) { 
            startListening(); 
            return; 
        }
        
        String lowerText = text.toLowerCase();
        if (lowerText.contains("stop") || lowerText.contains("বন্ধ") || 
            lowerText.contains("বন্ধ কর") || lowerText.contains("exit")) { 
            stopConversation(); 
            return; 
        }
        
        if (tvUser != null) {
            runOnUiThread(() -> tvUser.setText(getString(R.string.chat_user_label) + " " + text));
        }
        
        retryCount = 0;
        processWithAI(text);
    }

    private void processWithAI(String input) {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            showErrorDialog(getString(R.string.error_no_internet));
            updateUI("idle");
            return;
        }
        
        updateUI("thinking");
        
        executor.execute(() -> {
            try {
                String response = getGroqResponseWithRetry(input, detectedLanguage);
                retryCount = 0; // Reset on success
                
                runOnUiThread(() -> {
                    if (tvAI != null) tvAI.setText(getString(R.string.chat_ai_label) + " " + response);
                    setTTSLanguage(detectedLanguage);
                    speak(response);
                });
                
            } catch (Exception e) {
                AppLogger.e("AI error: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    String errorMsg = detectedLanguage.equals(AppConstants.LANGUAGE_BENGALI) ? 
                            getString(R.string.did_not_understand_bn) : getString(R.string.did_not_understand);
                    if (tvAI != null) tvAI.setText(getString(R.string.chat_ai_label) + " " + errorMsg);
                    speak(errorMsg);
                });
            }
        });
    }
    
    private String getGroqResponseWithRetry(String input, String lang) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    AppLogger.d("Retry attempt " + attempt + " after delay");
                    Thread.sleep(AppConstants.RETRY_DELAY_MS * (long) Math.pow(AppConstants.RETRY_DELAY_MULTIPLIER, attempt - 1));
                    runOnUiThread(() -> showMessage(getString(R.string.retrying)));
                }
                
                return getGroqResponse(input, lang);
                
            } catch (Exception e) {
                lastException = e;
                AppLogger.w("Attempt " + (attempt + 1) + " failed: " + e.getMessage());
                
                // Don't retry for certain errors
                if (e.getMessage() != null && (e.getMessage().contains("401") || 
                    e.getMessage().contains("API key") ||
                    e.getMessage().contains("unauthorized"))) {
                    break; // Don't retry auth errors
                }
            }
        }
        
        throw lastException != null ? lastException : new Exception("All retries failed");
    }

    private String getGroqResponse(String input, String lang) throws Exception {
        // Check network first
        if (!NetworkUtils.isNetworkAvailable(this)) {
            throw new Exception("No network available");
        }
        
        URL url = new URL(AppConstants.GROQ_API_BASE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        String systemPrompt = lang.equals(AppConstants.LANGUAGE_BENGALI) ? 
            "আপনি বন্ধুসুলভ সহকারী। উত্তর দিন সংক্ষেপে বাংলায়।" :
            "You are a friendly phone assistant. Keep responses short.";
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(AppConstants.API_CONNECT_TIMEOUT);
            conn.setReadTimeout(AppConstants.API_READ_TIMEOUT);
            conn.setDoOutput(true);
            
            String jsonBody = String.format(Locale.US, 
                "{\"model\":\"%s\"," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"%s\"}," +
                "{\"role\":\"user\",\"content\":\"%s\"}]," +
                "\"temperature\":0.7,\"max_tokens\":150}",
                AppConstants.GROQ_MODEL,
                systemPrompt.replace("\"", "\\\""),
                input.replace("\"", "\\\"")
            );
            
            conn.getOutputStream().write(jsonBody.getBytes());
            
            int responseCode = conn.getResponseCode();
            AppLogger.d("API response code: " + responseCode);
            
            if (responseCode == 401) {
                throw new Exception("API key invalid - 401 Unauthorized");
            } else if (responseCode == 429) {
                throw new Exception("Rate limit exceeded");
            } else if (responseCode != 200) {
                throw new Exception("API error: " + responseCode);
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();
            
            JSONObject json = new JSONObject(response.toString());
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                    
        } finally {
            conn.disconnect();
        }
    }

    private void speak(String text) {
        if (textToSpeech == null || !isTTSReady) {
            AppLogger.e("TTS not ready");
            return;
        }
        
        try {
            isSpeaking = true;
            updateUI("speaking");
            
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, AppConstants.TTS_UTTERANCE_ID);
            } else {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            AppLogger.e("TTS error", e);
            isSpeaking = false;
            updateUI("idle");
        }
    }

    private void setTTSLanguage(String lang) {
        if (textToSpeech == null || !isTTSReady) return;
        
        try {
            if (lang.equals(AppConstants.LANGUAGE_BENGALI)) {
                int result = textToSpeech.setLanguage(new Locale("bn", "BD"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    AppLogger.w("Bengali TTS not available, falling back to English");
                    showMessage(getString(R.string.tts_fallback_en));
                    textToSpeech.setLanguage(Locale.US);
                }
            } else {
                textToSpeech.setLanguage(Locale.US);
            }
        } catch (Exception e) {
            AppLogger.e("TTS language error", e);
        }
    }

    private void enableAudioForCall() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            
            AppLogger.d("Audio mode enabled for call");
        } catch (Exception e) { 
            AppLogger.e("Audio mode error", e); 
        }
    }

    private void retryOrRecover() {
        if (isConversationActive && !isSpeaking) {
            mainHandler.postDelayed(this::startListening, 1000);
        }
    }
    
    private void updateUI(String state) {
        try {
            if (isDestroyed) return;
            
            int bgColor;
            String statusText;
            
            switch (state) {
                case "listening":
                    bgColor = 0xFF4CAF50;
                    statusText = getString(R.string.status_listening);
                    break;
                case "speaking":
                    bgColor = 0xFF2196F3;
                    statusText = getString(R.string.status_speaking);
                    break;
                case "thinking":
                    bgColor = 0xFFFF9800;
                    statusText = getString(R.string.status_thinking);
                    break;
                case "starting":
                    bgColor = 0xFF9C27B0;
                    statusText = getString(R.string.status_starting);
                    break;
                case "error":
                    bgColor = 0xFFF44336;
                    statusText = getString(R.string.status_error);
                    break;
                default:
                    bgColor = 0xFF1A1A2E;
                    statusText = getString(R.string.status_tap_to_start);
            }
            
            if (circleView != null) {
                circleView.setBackgroundColor(bgColor);
            }
            if (tvStatus != null) {
                tvStatus.setText(statusText);
            }
            
        } catch (Exception e) { 
            AppLogger.e("UI error", e); 
        }
    }
    
    private void showErrorDialog(String message) {
        runOnUiThread(() -> {
            if (isDestroyed) return;
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_ok, null)
                .show();
        });
    }
    
    private void transferCallToAgent(String phoneNumber) {
        AppLogger.d("=== TRANSFER CALL TO AGENT ===");
        
        isCallActive = true;
        isConversationActive = true;
        detectedLanguage = resolveLanguage();
        
        acquireWakeLock();
        enableAudioForCall();
        registerAudioRoutingListener();
        
        bringToFront();
        
        String displayName = incomingCallName != null ? incomingCallName : phoneNumber;
        
        mainHandler.postDelayed(() -> {
            String greeting = detectedLanguage.equals(AppConstants.LANGUAGE_BENGALI) ?
                    getString(R.string.greeting_call_bn) : getString(R.string.greeting_call_en);
            speak(greeting);
        }, AppConstants.GREETING_DELAY_MS);
        
        mainHandler.postDelayed(() -> {
            if (isConversationActive && !isSpeaking) {
                startListening();
            }
        }, AppConstants.FIRST_LISTEN_DELAY_MS);
        
        mainHandler.post(() -> 
            Toast.makeText(this, R.string.call_connected, Toast.LENGTH_LONG).show()
        );
        
        startDurationMonitoring();
    }
    
    private void bringToFront() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
    
    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Handle configuration changes without restarting activity
        AppLogger.d("Configuration changed");
    }

    @Override
    protected void onDestroy() {
        AppLogger.d("onDestroy");
        isDestroyed = true;
        
        stopDurationMonitoring();
        releaseWakeLock();
        unregisterAudioRoutingListener();
        
        try {
            if (serviceBound) {
                unbindService(serviceConnection);
                serviceBound = false;
            }
        } catch (Exception e) {
            AppLogger.e("Error unbinding service", e);
        }
        
        try {
            stopConversation();
            if (speechRecognizer != null) { 
                speechRecognizer.destroy(); 
                speechRecognizer = null; 
            }
            if (textToSpeech != null) { 
                textToSpeech.stop(); 
                textToSpeech.shutdown(); 
                textToSpeech = null; 
            }
            if (executor != null) { 
                executor.shutdown(); 
                try {
                    executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) { 
            AppLogger.e("Destroy error", e); 
        }
        
        try {
            if (handlerThread != null) {
                handlerThread.quitSafely();
            }
        } catch (Exception e) {
            AppLogger.e("Error quitting handler thread", e);
        }
        
        CallMonitorService.listener = null;
        CallMonitorService.answerListener = null;
        
        super.onDestroy();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        AppLogger.d("onResume");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        AppLogger.d("onPause");
    }
}
