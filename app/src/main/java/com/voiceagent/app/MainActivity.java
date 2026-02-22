package com.voiceagent.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceResultsHandler;
import android.speech.tts.UtteranceProgressListener;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
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

public class MainActivity extends AppCompatActivity implements CallReceiver.CallListener {

    private static final String TAG = "VoiceAgent";
    private static final int PERMISSION_CODE = 100;
    private static final int ANSWER_CALL_CODE = 101;
    private static final String CHANNEL_ID = "voice_agent_call_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_ANSWER = "com.voiceagent.app.ACTION_ANSWER";
    private static final String ACTION_REJECT = "com.voiceagent.app.ACTION_REJECT";
    
    // API Key - will be replaced by GitHub Actions
    private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;

    // Components
    private SpeechRecognizer speechRecognizer = null;
    private TextToSpeech textToSpeech = null;
    private boolean isTTSReady = false;
    private boolean isRecognitionReady = false;
    private AudioManager audioManager;

    // Language detection
    private String detectedLanguage = "en";
    private boolean sttBengaliBD = true;

    // UI Elements
    private FloatingActionButton btnToggle;
    private TextView tvStatus, tvUser, tvAI, tvTitle;
    private ImageView tvIcon;
    private View circleView;

    // State flags
    private boolean isConversationActive = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean isDestroyed = false;
    
    // Call handling
    private String incomingCallNumber = null;
    private boolean isCallActive = false;
    private NotificationManager notificationManager;
    private BroadcastReceiver notificationActionReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        Log.d(TAG, "=== Voice Agent Starting ===");
        
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        registerNotificationActions();
        
        CallReceiver.setListener(this);
        
        initViews();
        initAudioManager();
        initSpeechRecognition();
        initTextToSpeech();
        checkPermissions();
        
        executor = Executors.newSingleThreadExecutor();
        handleIntent(getIntent());
        
        Log.d(TAG, "Voice Agent started");
    }
    
    private void handleIntent(Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            String phoneNumber = intent.getStringExtra("phone_number");
            Log.d(TAG, "handleIntent action: " + action + ", number: " + phoneNumber);
            
            if (ACTION_ANSWER.equals(action)) {
                transferCallToAgent(phoneNumber);
            } else if (ACTION_REJECT.equals(action)) {
                dismissNotification();
            }
        }
    }
    
    private void registerNotificationActions() {
        notificationActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                String phoneNumber = intent.getStringExtra("phone_number");
                Log.d(TAG, "Notification action: " + action);
                
                if (ACTION_ANSWER.equals(action)) {
                    transferCallToAgent(phoneNumber);
                } else if (ACTION_REJECT.equals(action)) {
                    dismissNotification();
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ANSWER);
        filter.addAction(ACTION_REJECT);
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(notificationActionReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "Register receiver error: " + e.getMessage());
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
        
        btnToggle.setOnClickListener(v -> {
            Log.d(TAG, "Button clicked, isConversationActive: " + isConversationActive);
            toggleConversation();
        });
        
        updateUIState("idle");
    }

    private void initAudioManager() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private void initSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available");
            mainHandler.post(() -> Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show());
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
                    Log.e(TAG, "Speech error: " + error + " (Bengali BD: " + sttBengaliBD + ")");
                    isListening = false;
                    
                    // Try Bengali first, then English, then retry
                    if ((error == 7 || error == 8) && sttBengaliBD) { // NO_MATCH or TIMEOUT
                        sttBengaliBD = false;
                        mainHandler.postDelayed(() -> {
                            if (isConversationActive && !isSpeaking) startListeningBengaliGeneric();
                        }, 300);
                        return;
                    }
                    
                    if ((error == 7 || error == 8) && !sttBengaliBD) {
                        sttBengaliBD = true;
                        mainHandler.postDelayed(() -> {
                            if (isConversationActive && !isSpeaking) startListeningEnglish();
                        }, 300);
                        return;
                    }
                    
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
                    } else {
                        if (isConversationActive && !isSpeaking) {
                            mainHandler.postDelayed(() -> startListening(), 300);
                        }
                    }
                }

                @Override public void onPartialResults(Bundle bundle) {}
                @Override public void onEvent(int i, Bundle bundle) {}
            });
            
            isRecognitionReady = true;
            Log.d(TAG, "Speech recognition initialized");
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
                        Log.d(TAG, "TTS started");
                        isSpeaking = true;
                        mainHandler.post(() -> updateUIState("speaking"));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        Log.d(TAG, "TTS done");
                        isSpeaking = false;
                        mainHandler.post(() -> {
                            updateUIState("idle");
                            if (isConversationActive) {
                                mainHandler.postDelayed(() -> {
                                    if (isConversationActive && !isSpeaking) {
                                        startListening();
                                    }
                                }, 1000);
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "TTS error: " + utteranceId);
                        isSpeaking = false;
                        mainHandler.post(() -> {
                            updateUIState("idle");
                            if (isConversationActive) {
                                mainHandler.postDelayed(() -> {
                                    if (isConversationActive && !isSpeaking) {
                                        startListening();
                                    }
                                }, 1000);
                            }
                        });
                    }
                });
                
                Log.d(TAG, "TTS initialized");
            } else {
                Log.e(TAG, "TTS init failed: " + status);
                isTTSReady = false;
            }
        });
    }

    private void setTTSLanguage(String lang) {
        if (textToSpeech == null || !isTTSReady) return;
        
        try {
            if (lang.equals("bn")) {
                Locale bengaliLocale = new Locale("bn", "BD");
                int result = textToSpeech.setLanguage(bengaliLocale);
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

    private void checkPermissions() {
        ArrayList<String> permissionsNeeded = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ANSWER_PHONE_CALLS);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        }
        
        if (!permissionsNeeded.isEmpty()) {
            String[] permissions = permissionsNeeded.toArray(new String[0]);
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_CODE);
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
            
            mainHandler.post(() -> {
                if (allGranted) {
                    Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "⚠️ Some permissions denied", Toast.LENGTH_LONG).show();
                }
            });
        } else if (requestCode == ANSWER_CALL_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                answerCall();
            }
        }
    }

    private void toggleConversation() {
        Log.d(TAG, "toggleConversation called, active: " + isConversationActive);
        
        if (isConversationActive) {
            stopConversation();
        } else {
            startConversation();
        }
    }

    private void startConversation() {
        Log.d(TAG, "startConversation called");
        
        if (isConversationActive) {
            Log.d(TAG, "Already active, returning");
            return;
        }
        
        if (!isTTSReady) {
            Log.e(TAG, "TTS not ready");
            mainHandler.post(() -> Toast.makeText(this, "Please wait... TTS initializing", Toast.LENGTH_SHORT).show());
            return;
        }
        
        if (!isRecognitionReady) {
            Log.e(TAG, "Recognition not ready");
            mainHandler.post(() -> Toast.makeText(this, "Please wait... Speech initializing", Toast.LENGTH_SHORT).show());
            return;
        }
        
        isConversationActive = true;
        detectedLanguage = "en";
        sttBengaliBD = true;
        
        Log.d(TAG, "Setting audio mode and requesting focus");
        requestAudioFocus();
        updateUIState("starting");
        
        Log.d(TAG, "Speaking greeting");
        speak("Hello! I'm your voice assistant. How can I help you today?");
        
        // Start listening after greeting (handled by onDone callback in speak)
    }

    private void stopConversation() {
        Log.d(TAG, "stopConversation called");
        
        if (!isConversationActive) return;
        
        isConversationActive = false;
        isCallActive = false;
        
        try {
            if (speechRecognizer != null) {
                try { speechRecognizer.stopListening(); } catch (Exception e) {}
            }
            if (textToSpeech != null) {
                try { textToSpeech.stop(); } catch (Exception e) {}
            }
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
                audioManager.abandonAudioFocus(null);
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
            updateUIState("idle");
            stopAnimations();
        });
    }

    private void startListening() {
        Log.d(TAG, "startListening called, active: " + isConversationActive + ", speaking: " + isSpeaking);
        
        if (isDestroyed || !isConversationActive || isSpeaking || isListening) {
            Log.d(TAG, "Cannot start listening, returning");
            return;
        }
        
        if (speechRecognizer == null) {
            Log.e(TAG, "Speech recognizer is null");
            return;
        }
        
        sttBengaliBD = true;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            
            isListening = true;
            updateUIState("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening (Bengali BD)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting listening: " + e.getMessage());
            retryOrRecover();
        }
    }
    
    private void startListeningEnglish() {
        if (isDestroyed || !isConversationActive || isSpeaking || isListening) return;
        if (speechRecognizer == null) return;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            
            isListening = true;
            updateUIState("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening (English)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting English: " + e.getMessage());
            retryOrRecover();
        }
    }
    
    private void startListeningBengaliGeneric() {
        if (isDestroyed || !isConversationActive || isSpeaking || isListening) return;
        if (speechRecognizer == null) return;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            
            isListening = true;
            updateUIState("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening (Bengali)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting Bengali generic: " + e.getMessage());
            retryOrRecover();
        }
    }

    private void handleUserInput(String text) {
        if (text == null || text.trim().isEmpty()) { 
            sttBengaliBD = true;
            startListening(); 
            return; 
        }
        
        String lowerText = text.toLowerCase();
        if (lowerText.contains("stop") || lowerText.contains("বন্ধ") || lowerText.contains("থাম")) { 
            stopConversation(); 
            return; 
        }
        
        if (tvUser != null) {
            mainHandler.post(() -> tvUser.setText("You: " + text));
        }
        
        sttBengaliBD = true;
        processWithAI(text);
    }

    private void processWithAI(String input) {
        Log.d(TAG, "Processing AI request: " + input);
        updateUIState("thinking");
        
        executor.execute(() -> {
            try {
                String response = getGroqResponse(input, detectedLanguage);
                Log.d(TAG, "AI response: " + response);
                
                mainHandler.post(() -> {
                    if (tvAI != null) tvAI.setText("AI: " + response);
                    setTTSLanguage(detectedLanguage);
                    speak(response);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "AI error: " + e.getMessage());
                mainHandler.post(() -> {
                    String errorMsg = detectedLanguage.equals("bn") ? "দুঃখিত, সমস্যা হয়েছে। আবার চেষ্টা করুন।" : "Sorry, I encountered an error. Let's try again.";
                    if (tvAI != null) tvAI.setText("AI: " + errorMsg);
                    speak(errorMsg);
                    mainHandler.postDelayed(() -> {
                        if (isConversationActive && !isSpeaking) startListening();
                    }, 2000);
                });
            }
        });
    }

    private String getGroqResponse(String input, String lang) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        String systemPrompt;
        if (lang.equals("bn")) {
            systemPrompt = "আপনি একজন বন্ধুসুলভ ফোন সহকারী। উত্তর দিন সংক্ষেপে।";
        } else {
            systemPrompt = "You are a friendly phone assistant. Keep responses short, conversational, and helpful.";
        }
        
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
            Log.d(TAG, "API response code: " + responseCode);
            
            if (responseCode != 200) {
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
        Log.d(TAG, "speak called: " + text);
        
        if (textToSpeech == null || !isTTSReady) {
            Log.e(TAG, "TTS not ready");
            if (isConversationActive) {
                mainHandler.postDelayed(() -> startListening(), 500);
            }
            return;
        }
        
        try {
            isSpeaking = true;
            updateUIState("speaking");
            
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance");
            } else {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS error: " + e.getMessage());
            isSpeaking = false;
            updateUIState("idle");
        }
    }

    private void requestAudioFocus() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception e) { 
            Log.e(TAG, "Audio focus error: " + e.getMessage()); 
        }
    }

    private void retryOrRecover() {
        if (isConversationActive) {
            sttBengaliBD = true;
            mainHandler.postDelayed(() -> {
                if (isConversationActive && !isSpeaking) startListening();
            }, 1000);
        }
    }

    private void updateUIState(String state) {
        try {
            if (isDestroyed) return;
            
            int bgColor;
            String statusText;
            
            switch (state) {
                case "listening":
                    bgColor = 0xFF4CAF50; statusText = "Listening...";
                    startListeningAnimation();
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
                    stopAnimations();
            }
            
            if (circleView != null) circleView.setBackgroundColor(bgColor);
            if (tvStatus != null) tvStatus.setText(statusText);
            
        } catch (Exception e) { 
            Log.e(TAG, "UI error: " + e.getMessage()); 
        }
    }

    private void startListeningAnimation() {
        try {
            if (circleView != null) {
                Animation fadeIn = new AlphaAnimation(0.0f, 1.0f);
                fadeIn.setDuration(300);
                circleView.startAnimation(fadeIn);
            }
        } catch (Exception e) { 
            Log.e(TAG, "Animation error: " + e.getMessage()); 
        }
    }

    private void stopAnimations() {
        try {
            if (circleView != null) circleView.clearAnimation();
        } catch (Exception e) { 
            Log.e(TAG, "Stop animation error: " + e.getMessage()); 
        }
    }
    
    // ============== Call Notification System ==============
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Voice Agent Call",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notification for incoming calls");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private void showCallNotification(String phoneNumber) {
        Log.d(TAG, "Showing notification for: " + phoneNumber);
        incomingCallNumber = phoneNumber;
        
        Intent confirmIntent = new Intent(ACTION_ANSWER);
        confirmIntent.putExtra("phone_number", phoneNumber);
        PendingIntent confirmPendingIntent = PendingIntent.getBroadcast(
            this, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent rejectIntent = new Intent(ACTION_REJECT);
        rejectIntent.putExtra("phone_number", phoneNumber);
        PendingIntent rejectPendingIntent = PendingIntent.getBroadcast(
            this, 1, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
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
            .setContentText("Call from: " + phoneNumber)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(new long[]{0, 500, 200, 500})
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_mic_white, "✅ CONFIRM", confirmPendingIntent)
            .addAction(R.drawable.ic_mic_white, "❌ REJECT", rejectPendingIntent);
        
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
    
    private void dismissNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
    
    // Called when user clicks "CONFIRM"
    private void transferCallToAgent(String phoneNumber) {
        Log.d(TAG, "=== TRANSFER CALL TO AGENT ===");
        
        dismissNotification();
        
        isCallActive = true;
        isConversationActive = true;
        detectedLanguage = "en";
        
        // Step 1: Answer the call
        answerCall();
        
        // Step 2: Bring app to foreground
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("phone_number", phoneNumber);
        startActivity(intent);
        
        // Step 3: Enable speaker and audio mode
        enableSpeakerAndAudioMode();
        
        // Step 4: Request audio focus
        requestAudioFocus();
        updateUIState("starting");
        
        // Step 5: Greet the caller
        speak("Hello! This is an AI assistant. How can I help you?");
        
        // Step 6: Start listening (handled by onDone callback)
        
        mainHandler.post(() -> 
            Toast.makeText(this, "🤖 AI Agent connected!", Toast.LENGTH_LONG).show()
        );
    }
    
    // Auto-answer with multiple fallback methods
    private void answerCall() {
        Log.d(TAG, "Attempting to answer call...");
        
        // Method 1: TelecomManager (requires Android 6.0+ and permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null && ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) 
                        == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall();
                    Log.d(TAG, "Call answered via TelecomManager");
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "TelecomManager error: " + e.getMessage());
            }
        }
        
        // Method 2: Intent ACTION_ANSWER (deprecated but might work)
        try {
            Intent intent = new Intent(Intent.ACTION_ANSWER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "Call answered via ACTION_ANSWER");
        } catch (Exception e) {
            Log.e(TAG, "ACTION_ANSWER error: " + e.getMessage());
        }
        
        // Method 3: Key event simulation (fallback)
        try {
            Runtime.getRuntime().exec("input keyevent 5");
            Log.d(TAG, "Call answered via keyevent");
        } catch (Exception e) {
            Log.e(TAG, "keyevent error: " + e.getMessage());
        }
    }
    
    private void enableSpeakerAndAudioMode() {
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
    
    // CallReceiver.CallListener implementation
    @Override
    public void onIncomingCall(String number) {
        Log.d(TAG, "=== INCOMING CALL ===");
        Log.d(TAG, "Number: " + number);
        
        mainHandler.post(() -> showCallNotification(number));
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy called");
        isDestroyed = true;
        
        try {
            if (notificationActionReceiver != null) {
                unregisterReceiver(notificationActionReceiver);
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
        
        CallReceiver.setListener(null);
        super.onDestroy();
    }
}
