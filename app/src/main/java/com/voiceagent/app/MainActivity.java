package com.voiceagent.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_CODE = 100;
    private static final String ACTION_ANSWER = "com.voiceagent.app.ACTION_ANSWER";
    private static final String ACTION_REJECT = "com.voiceagent.app.ACTION_REJECT";
    public static final String ACTION_TRANSFER = "com.voiceagent.app.TRANSFER";
    
    private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;

    private SpeechRecognizer speechRecognizer = null;
    private TextToSpeech textToSpeech = null;
    private boolean isTTSReady = false;
    private boolean isRecognitionReady = false;
    private AudioManager audioManager;

    private String detectedLanguage = "en";
    private boolean sttBengaliBD = true;

    private FloatingActionButton btnToggle;
    private TextView tvStatus, tvUser, tvAI, tvTitle;
    private ImageView tvIcon;
    private View circleView;

    private boolean isConversationActive = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean isDestroyed = false;
    
    private String incomingCallNumber = null;
    private boolean isCallActive = false;
    private boolean serviceBound = false;
    private CallMonitorService callMonitorService = null;

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
        
        Log.d(TAG, "=== Voice Agent Starting ===");
        
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        initViews();
        checkPermissions();
        
        executor = Executors.newSingleThreadExecutor();
        handleIntent(getIntent());
        
        Log.d(TAG, "Voice Agent started");
    }
    
    private void handleIntent(Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            String phoneNumber = intent.getStringExtra("phone_number");
            
            Log.d(TAG, "Action: " + action + ", Number: " + phoneNumber);
            
            if (ACTION_ANSWER.equals(action)) {
                Log.d(TAG, "ACTION_ANSWER received in handleIntent");
                incomingCallNumber = phoneNumber;
                answerAndTransferCall(phoneNumber);
            } else if (ACTION_REJECT.equals(action)) {
                Log.d(TAG, "ACTION_REJECT received");
                dismissNotification();
            } else if (ACTION_TRANSFER.equals(action)) {
                incomingCallNumber = phoneNumber;
                transferCallToAgent(phoneNumber);
            }
        }
    }

    private void dismissNotification() {
        try {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                manager.cancel(1001);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing notification: " + e.getMessage());
        }
    }

    private void answerAndTransferCall(String phoneNumber) {
        Log.d(TAG, "=== ANSWER AND TRANSFER CALL === Number: " + phoneNumber);
        
        dismissNotification();
        
        mainHandler.postDelayed(() -> {
            answerCallDirectly(phoneNumber);
        }, 300);
    }
    
    private void answerCallDirectly(String phoneNumber) {
        Log.d(TAG, "Attempting to answer call for: " + phoneNumber);
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) 
                            == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.acceptRingingCall();
                        Log.d(TAG, "Call answered via TelecomManager");
                        
                        mainHandler.postDelayed(() -> {
                            transferCallToAgent(phoneNumber);
                        }, 1000);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "TelecomManager error: " + e.getMessage());
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_ANSWER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "Call answered via ACTION_ANSWER");
            
            mainHandler.postDelayed(() -> {
                transferCallToAgent(phoneNumber);
            }, 2000);
        } catch (Exception e) {
            Log.e(TAG, "ACTION_ANSWER error: " + e.getMessage());
            
            try {
                Runtime.getRuntime().exec("input keyevent 5");
                Log.d(TAG, "Call answered via keyevent");
                
                mainHandler.postDelayed(() -> {
                    transferCallToAgent(phoneNumber);
                }, 1000);
            } catch (Exception ex) {
                Log.e(TAG, "keyevent error: " + ex.getMessage());
                transferCallToAgent(phoneNumber);
            }
        }
    }

    private void initViews() {
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        tvUser = findViewById(R.id.tvUser);
        tvAI = findViewById(R.id.tvAI);
        tvTitle = findViewById(R.id.tvTitle);
        tvIcon = findViewById(R.id.tvIcon);
        circleView = findViewById(R.id.circleView);
        
        btnToggle.setOnClickListener(v -> toggleConversation());
        
        initSpeechRecognition();
        initTextToSpeech();
        
        updateUI("idle");
    }

    private void initSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available");
            return;
        }
        
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { 
                    Log.d(TAG, "Ready for speech"); 
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { 
                    Log.d(TAG, "End of speech"); 
                    isListening = false; 
                }

                @Override
                public void onError(int error) {
                    Log.e(TAG, "Speech error: " + error);
                    isListening = false;
                    
                    if (isConversationActive && !isSpeaking) {
                        retryOrRecover();
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    Log.d(TAG, "Got results");
                    isListening = false;
                    
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        Log.d(TAG, "Recognized: " + text);
                        
                        boolean hasBengali = text.matches(".*[\\u0980-\\u09FF].*");
                        detectedLanguage = hasBengali ? "bn" : "en";
                        
                        handleUserInput(text);
                    } else if (isConversationActive && !isSpeaking) {
                        mainHandler.postDelayed(() -> startListening(), 500);
                    }
                }

                @Override public void onPartialResults(Bundle bundle) {}
                @Override public void onEvent(int i, Bundle bundle) {}
            });
            
            isRecognitionReady = true;
            Log.d(TAG, "Speech recognition ready");
        } catch (Exception e) {
            Log.e(TAG, "Speech init error: " + e.getMessage());
        }
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                isTTSReady = true;
                textToSpeech.setLanguage(Locale.US);
                textToSpeech.setSpeechRate(1.0f);
                textToSpeech.setPitch(1.0f);
                
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        isSpeaking = true;
                        mainHandler.post(() -> updateUI("speaking"));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isSpeaking = false;
                        mainHandler.post(() -> {
                            updateUI("idle");
                            if (isConversationActive) {
                                mainHandler.postDelayed(() -> {
                                    if (isConversationActive && !isSpeaking && !isListening) {
                                        startListening();
                                    }
                                }, 800);
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isSpeaking = false;
                        mainHandler.post(() -> {
                            updateUI("idle");
                            if (isConversationActive) {
                                retryOrRecover();
                            }
                        });
                    }
                });
                
                Log.d(TAG, "TTS ready");
            } else {
                Log.e(TAG, "TTS init failed");
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
        
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_CODE);
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
                    Log.d(TAG, "Call ringing: " + number);
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "Incoming call: " + number, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onCallAnswered() {
                    Log.d(TAG, "Call answered by system");
                    mainHandler.post(() -> {
                        if (isConversationActive) {
                            enableAudioForCall();
                        }
                    });
                }

                @Override
                public void onCallEnded() {
                    Log.d(TAG, "Call ended");
                    mainHandler.post(() -> stopConversation());
                }

                @Override
                public void onCallDisconnected() {
                    Log.d(TAG, "Call disconnected");
                }
            };
            
            CallMonitorService.answerListener = number -> {
                Log.d(TAG, "Answer listener triggered for: " + number);
                mainHandler.post(() -> answerAndTransferCall(number));
            };
            
            Log.d(TAG, "Call service started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting service: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            final boolean granted = allGranted;
            mainHandler.post(() -> {
                if (granted) {
                    Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
                    startCallService();
                } else {
                    Toast.makeText(this, "Permissions needed for call features", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show();
            return;
        }
        
        isConversationActive = true;
        detectedLanguage = "en";
        
        enableAudioForCall();
        updateUI("starting");
        
        speak("Hello! I'm your voice assistant. How can I help you?");
    }

    private void stopConversation() {
        if (!isConversationActive) return;
        
        isConversationActive = false;
        isCallActive = false;
        
        try {
            if (speechRecognizer != null) speechRecognizer.cancel();
            if (textToSpeech != null) textToSpeech.stop();
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Exception e) { 
            Log.e(TAG, "Stop error: " + e.getMessage()); 
        }
        
        isListening = false;
        isSpeaking = false;
        
        mainHandler.post(() -> {
            if (tvStatus != null) tvStatus.setText("Tap to start");
            if (tvUser != null) tvUser.setText("You: ...");
            if (tvAI != null) tvAI.setText("AI: ...");
            updateUI("idle");
        });
    }

    private void startListening() {
        if (isDestroyed || !isConversationActive || isSpeaking || isListening) return;
        if (speechRecognizer == null) return;
        
        sttBengaliBD = true;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);
            
            isListening = true;
            updateUI("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening");
            
            mainHandler.postDelayed(() -> {
                if (isListening) {
                    try {
                        speechRecognizer.stopListening();
                    } catch (Exception e) {}
                }
            }, 6000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting listening: " + e.getMessage());
            retryOrRecover();
        }
    }

    private void handleUserInput(String text) {
        if (text == null || text.trim().isEmpty()) { 
            startListening(); 
            return; 
        }
        
        String lowerText = text.toLowerCase();
        if (lowerText.contains("stop") || lowerText.contains("বন্ধ")) { 
            stopConversation(); 
            return; 
        }
        
        if (tvUser != null) {
            mainHandler.post(() -> tvUser.setText("You: " + text));
        }
        
        processWithAI(text);
    }

    private void processWithAI(String input) {
        updateUI("thinking");
        
        executor.execute(() -> {
            try {
                String response = getGroqResponse(input, detectedLanguage);
                
                mainHandler.post(() -> {
                    if (tvAI != null) tvAI.setText("AI: " + response);
                    setTTSLanguage(detectedLanguage);
                    speak(response);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "AI error: " + e.getMessage());
                mainHandler.post(() -> {
                    String errorMsg = detectedLanguage.equals("bn") ? "দুঃখিত।" : "Sorry, I didn't understand.";
                    if (tvAI != null) tvAI.setText("AI: " + errorMsg);
                    speak(errorMsg);
                });
            }
        });
    }

    private String getGroqResponse(String input, String lang) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        String systemPrompt = lang.equals("bn") ? 
            "আপনি বন্ধুসুলভ সহকারী। উত্তর দিন সংক্ষেপে বাংলায়।" :
            "You are a friendly phone assistant. Keep responses short.";
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            
            String jsonBody = "{\"model\":\"llama-3.1-8b-instant\"," +
                    "\"messages\":[{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"}," +
                    "{\"role\":\"user\",\"content\":\"" + input.replace("\"", "\\\"") + "\"}]," +
                    "\"temperature\":0.7,\"max_tokens\":150}";
            
            conn.getOutputStream().write(jsonBody.getBytes());
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) throw new Exception("API error: " + responseCode);
            
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
        if (textToSpeech == null || !isTTSReady) return;
        
        try {
            isSpeaking = true;
            updateUI("speaking");
            
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance");
            } else {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS error: " + e.getMessage());
            isSpeaking = false;
            updateUI("idle");
        }
    }

    private void setTTSLanguage(String lang) {
        if (textToSpeech == null || !isTTSReady) return;
        
        try {
            if (lang.equals("bn")) {
                int result = textToSpeech.setLanguage(new Locale("bn", "BD"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.US);
                }
            } else {
                textToSpeech.setLanguage(Locale.US);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS language error: " + e.getMessage());
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
            
            Log.d(TAG, "Audio mode enabled for call");
        } catch (Exception e) { 
            Log.e(TAG, "Audio mode error: " + e.getMessage()); 
        }
    }

    private void retryOrRecover() {
        if (isConversationActive && !isSpeaking) {
            mainHandler.postDelayed(this::startListening, 1000);
        }
    }

    private void updateUI(String state) {
        try {
            if (isDestroyed || circleView == null || tvStatus == null) return;
            
            int bgColor;
            String statusText;
            
            switch (state) {
                case "listening":
                    bgColor = 0xFF4CAF50; statusText = "Listening...";
                    break;
                case "speaking":
                    bgColor = 0xFF2196F3; statusText = "Speaking...";
                    break;
                case "thinking":
                    bgColor = 0xFFFF9800; statusText = "Thinking...";
                    break;
                case "starting":
                    bgColor = 0xFF9C27B0; statusText = "Starting...";
                    break;
                default:
                    bgColor = 0xFF1A1A2E; statusText = "Tap to start";
            }
            
            circleView.setBackgroundColor(bgColor);
            tvStatus.setText(statusText);
            
        } catch (Exception e) { 
            Log.e(TAG, "UI error: " + e.getMessage()); 
        }
    }
    
    private void transferCallToAgent(String phoneNumber) {
        Log.d(TAG, "=== TRANSFER CALL TO AGENT ===");
        
        isCallActive = true;
        isConversationActive = true;
        detectedLanguage = "en";
        
        enableAudioForCall();
        
        bringToFront();
        
        mainHandler.postDelayed(() -> {
            speak("Hello! This is an AI assistant. How can I help you?");
        }, 1500);
        
        mainHandler.postDelayed(() -> {
            if (isConversationActive && !isSpeaking) {
                startListening();
            }
        }, 4000);
        
        mainHandler.post(() -> 
            Toast.makeText(this, "AI Agent Connected!", Toast.LENGTH_LONG).show()
        );
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
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        isDestroyed = true;
        
        try {
            if (serviceBound) {
                unbindService(serviceConnection);
                serviceBound = false;
            }
        } catch (Exception e) {}
        
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
                executor.awaitTermination(1, TimeUnit.SECONDS); 
            }
        } catch (Exception e) { 
            Log.e(TAG, "Destroy error: " + e.getMessage()); 
        }
        
        CallMonitorService.listener = null;
        CallMonitorService.answerListener = null;
        super.onDestroy();
    }
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
        
        Log.d(TAG, "=== Voice Agent Starting ===");
        
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        initViews();
        checkPermissions();
        
        executor = Executors.newSingleThreadExecutor();
        handleIntent(getIntent());
        
        Log.d(TAG, "Voice Agent started");
    }
    
    private void handleIntent(Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            String phoneNumber = intent.getStringExtra("phone_number");
            
            Log.d(TAG, "Action: " + action + ", Number: " + phoneNumber);
            
            if (ACTION_ANSWER.equals(action) || ACTION_TRANSFER.equals(action)) {
                incomingCallNumber = phoneNumber;
                transferCallToAgent(phoneNumber);
            }
        }
    }

    private void initViews() {
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        tvUser = findViewById(R.id.tvUser);
        tvAI = findViewById(R.id.tvAI);
        tvTitle = findViewById(R.id.tvTitle);
        tvIcon = findViewById(R.id.tvIcon);
        circleView = findViewById(R.id.circleView);
        
        btnToggle.setOnClickListener(v -> toggleConversation());
        
        initSpeechRecognition();
        initTextToSpeech();
        
        updateUI("idle");
    }

    private void initSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available");
            return;
        }
        
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { 
                    Log.d(TAG, "Ready for speech"); 
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { 
                    Log.d(TAG, "End of speech"); 
                    isListening = false; 
                }

                @Override
                public void onError(int error) {
                    Log.e(TAG, "Speech error: " + error);
                    isListening = false;
                    
                    if (isConversationActive && !isSpeaking) {
                        retryOrRecover();
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    Log.d(TAG, "Got results");
                    isListening = false;
                    
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        Log.d(TAG, "Recognized: " + text);
                        
                        boolean hasBengali = text.matches(".*[\\u0980-\\u09FF].*");
                        detectedLanguage = hasBengali ? "bn" : "en";
                        
                        handleUserInput(text);
                    } else if (isConversationActive && !isSpeaking) {
                        mainHandler.postDelayed(() -> startListening(), 500);
                    }
                }

                @Override public void onPartialResults(Bundle bundle) {}
                @Override public void onEvent(int i, Bundle bundle) {}
            });
            
            isRecognitionReady = true;
            Log.d(TAG, "Speech recognition ready");
        } catch (Exception e) {
            Log.e(TAG, "Speech init error: " + e.getMessage());
        }
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                isTTSReady = true;
                textToSpeech.setLanguage(Locale.US);
                textToSpeech.setSpeechRate(1.0f);
                textToSpeech.setPitch(1.0f);
                
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        isSpeaking = true;
                        mainHandler.post(() -> updateUI("speaking"));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isSpeaking = false;
                        mainHandler.post(() -> {
                            updateUI("idle");
                            if (isConversationActive) {
                                mainHandler.postDelayed(() -> {
                                    if (isConversationActive && !isSpeaking && !isListening) {
                                        startListening();
                                    }
                                }, 800);
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isSpeaking = false;
                        mainHandler.post(() -> {
                            updateUI("idle");
                            if (isConversationActive) {
                                retryOrRecover();
                            }
                        });
                    }
                });
                
                Log.d(TAG, "TTS ready");
            } else {
                Log.e(TAG, "TTS init failed");
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
        
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_CODE);
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
                    Log.d(TAG, "Call ringing: " + number);
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "Incoming call: " + number, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onCallAnswered() {
                    Log.d(TAG, "Call answered");
                }

                @Override
                public void onCallEnded() {
                    Log.d(TAG, "Call ended");
                    mainHandler.post(() -> stopConversation());
                }

                @Override
                public void onCallDisconnected() {
                    Log.d(TAG, "Call disconnected");
                }
            };
            
            Log.d(TAG, "Call service started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting service: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            final boolean granted = allGranted;
            mainHandler.post(() -> {
                if (granted) {
                    Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
                    startCallService();
                } else {
                    Toast.makeText(this, "Permissions needed for call features", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show();
            return;
        }
        
        isConversationActive = true;
        detectedLanguage = "en";
        
        enableAudioMode();
        updateUI("starting");
        
        speak("Hello! I'm your voice assistant. How can I help you?");
    }

    private void stopConversation() {
        if (!isConversationActive) return;
        
        isConversationActive = false;
        isCallActive = false;
        
        try {
            if (speechRecognizer != null) speechRecognizer.cancel();
            if (textToSpeech != null) textToSpeech.stop();
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Exception e) { 
            Log.e(TAG, "Stop error: " + e.getMessage()); 
        }
        
        isListening = false;
        isSpeaking = false;
        
        mainHandler.post(() -> {
            if (tvStatus != null) tvStatus.setText("Tap to start");
            if (tvUser != null) tvUser.setText("You: ...");
            if (tvAI != null) tvAI.setText("AI: ...");
            updateUI("idle");
        });
    }

    private void startListening() {
        if (isDestroyed || !isConversationActive || isSpeaking || isListening) return;
        if (speechRecognizer == null) return;
        
        sttBengaliBD = true;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            
            isListening = true;
            updateUI("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening");
            
            // Auto-stop after 5 seconds if no result
            mainHandler.postDelayed(() -> {
                if (isListening) {
                    try {
                        speechRecognizer.stopListening();
                    } catch (Exception e) {}
                }
            }, 5000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting listening: " + e.getMessage());
            retryOrRecover();
        }
    }

    private void handleUserInput(String text) {
        if (text == null || text.trim().isEmpty()) { 
            startListening(); 
            return; 
        }
        
        String lowerText = text.toLowerCase();
        if (lowerText.contains("stop") || lowerText.contains("বন্ধ")) { 
            stopConversation(); 
            return; 
        }
        
        if (tvUser != null) {
            mainHandler.post(() -> tvUser.setText("You: " + text));
        }
        
        processWithAI(text);
    }

    private void processWithAI(String input) {
        updateUI("thinking");
        
        executor.execute(() -> {
            try {
                String response = getGroqResponse(input, detectedLanguage);
                
                mainHandler.post(() -> {
                    if (tvAI != null) tvAI.setText("AI: " + response);
                    setTTSLanguage(detectedLanguage);
                    speak(response);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "AI error: " + e.getMessage());
                mainHandler.post(() -> {
                    String errorMsg = detectedLanguage.equals("bn") ? "দুঃখিত।" : "Sorry, I didn't understand.";
                    if (tvAI != null) tvAI.setText("AI: " + errorMsg);
                    speak(errorMsg);
                });
            }
        });
    }

    private String getGroqResponse(String input, String lang) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        String systemPrompt = lang.equals("bn") ? 
            "আপনি বন্ধুসুলভ সহকারী। উত্তর দিন সংক্ষেপে বাংলায়।" :
            "You are a friendly phone assistant. Keep responses short.";
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            
            String jsonBody = "{\"model\":\"llama-3.1-8b-instant\"," +
                    "\"messages\":[{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"}," +
                    "{\"role\":\"user\",\"content\":\"" + input.replace("\"", "\\\"") + "\"}]," +
                    "\"temperature\":0.7,\"max_tokens\":150}";
            
            conn.getOutputStream().write(jsonBody.getBytes());
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) throw new Exception("API error: " + responseCode);
            
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
        if (textToSpeech == null || !isTTSReady) return;
        
        try {
            isSpeaking = true;
            updateUI("speaking");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance");
            } else {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS error: " + e.getMessage());
            isSpeaking = false;
            updateUI("idle");
        }
    }

    private void setTTSLanguage(String lang) {
        if (textToSpeech == null || !isTTSReady) return;
        
        try {
            if (lang.equals("bn")) {
                int result = textToSpeech.setLanguage(new Locale("bn", "BD"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.US);
                }
            } else {
                textToSpeech.setLanguage(Locale.US);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS language error: " + e.getMessage());
        }
    }

    private void enableAudioMode() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception e) { 
            Log.e(TAG, "Audio mode error: " + e.getMessage()); 
        }
    }

    private void retryOrRecover() {
        if (isConversationActive && !isSpeaking) {
            mainHandler.postDelayed(this::startListening, 1000);
        }
    }

    private void updateUI(String state) {
        try {
            if (isDestroyed || circleView == null || tvStatus == null) return;
            
            int bgColor;
            String statusText;
            
            switch (state) {
                case "listening":
                    bgColor = 0xFF4CAF50; statusText = "Listening...";
                    break;
                case "speaking":
                    bgColor = 0xFF2196F3; statusText = "Speaking...";
                    break;
                case "thinking":
                    bgColor = 0xFFFF9800; statusText = "Thinking...";
                    break;
                case "starting":
                    bgColor = 0xFF9C27B0; statusText = "Starting...";
                    break;
                default:
                    bgColor = 0xFF1A1A2E; statusText = "Tap to start";
            }
            
            circleView.setBackgroundColor(bgColor);
            tvStatus.setText(statusText);
            
        } catch (Exception e) { 
            Log.e(TAG, "UI error: " + e.getMessage()); 
        }
    }
    
    private void transferCallToAgent(String phoneNumber) {
        Log.d(TAG, "=== TRANSFER CALL TO AGENT ===");
        
        isCallActive = true;
        isConversationActive = true;
        detectedLanguage = "en";
        
        // Enable full audio
        enableAudioMode();
        
        // Bring to front
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        
        // Greet caller
        speak("Hello! This is an AI assistant. How can I help you?");
        
        // Start listening after greeting
        mainHandler.postDelayed(() -> {
            if (isConversationActive && !isSpeaking) {
                startListening();
            }
        }, 3000);
        
        mainHandler.post(() -> 
            Toast.makeText(this, "AI Agent Connected!", Toast.LENGTH_LONG).show()
        );
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        isDestroyed = true;
        
        try {
            if (serviceBound) {
                unbindService(serviceConnection);
                serviceBound = false;
            }
        } catch (Exception e) {}
        
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
                executor.awaitTermination(1, TimeUnit.SECONDS); 
            }
        } catch (Exception e) { 
            Log.e(TAG, "Destroy error: " + e.getMessage()); 
        }
        
        CallMonitorService.listener = null;
        super.onDestroy();
    }
}
