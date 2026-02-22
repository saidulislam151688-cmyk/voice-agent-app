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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceAgent";
    private static final int PERMISSION_CODE = 100;
    private static final String CHANNEL_ID = "voice_agent_call_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_ANSWER = "com.voiceagent.app.ACTION_ANSWER";
    private static final String ACTION_REJECT = "com.voiceagent.app.ACTION_REJECT";
    
    // API Key - will be replaced by GitHub Actions
    private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY";

    // State Machine
    private enum ConversationState { IDLE, LISTENING, PROCESSING, SPEAKING }
    private ConversationState currentState = ConversationState.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Components
    private SpeechRecognizer speechRecognizer = null;
    private TextToSpeech textToSpeech = null;
    private boolean isTTSReady = false;
    private boolean isRecognitionReady = false;
    private ExecutorService executor;
    private AudioManager audioManager;

    // Language detection
    private String detectedLanguage = "en";
    private String[] supportedLanguages = {"en", "bn"};

    // UI Elements
    private FloatingActionButton btnToggle;
    private TextView tvStatus, tvUser, tvAI, tvTitle;
    private ImageView tvIcon;
    private View circleView;

    // Flags
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
        
        // Initialize notification manager
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        
        // Register for notification actions
        registerNotificationActions();
        
        // Register call receiver
        CallReceiver.setListener(this);
        
        initViews();
        initAudioManager();
        initSpeechRecognition();
        initTextToSpeech();
        checkPermissions();
        
        executor = Executors.newSingleThreadExecutor();
        
        // Handle intent if app was started from notification
        handleIntent(getIntent());
        
        Log.d(TAG, "Voice Agent started successfully");
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
                Log.d(TAG, "Notification action received: " + action);
                
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notificationActionReceiver, filter);
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
        updateUIState("idle");
    }

    private void initAudioManager() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private boolean sttBengaliBD = true;
    
    private void initSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available");
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show();
            return;
        }
        
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
                
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && sttBengaliBD) {
                    if (isConversationActive && !isSpeaking) {
                        sttBengaliBD = false;
                        mainHandler.postDelayed(() -> startListeningBengaliGeneric(), 300);
                        return;
                    }
                }
                
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    if (isConversationActive && !isSpeaking) {
                        mainHandler.postDelayed(() -> startListeningEnglish(), 300);
                        return;
                    }
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
                    
                    boolean hasBengaliChars = containsBengaliCharacters(text);
                    
                    if (hasBengaliChars) {
                        detectedLanguage = "bn";
                    } else if (isLikelyEnglish(text)) {
                        detectedLanguage = "en";
                    } else {
                        detectedLanguage = "en";
                    }
                    
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
    }
    
    private boolean containsBengaliCharacters(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.matches(".*[\\u0980-\\u09FF].*");
    }
    
    private boolean isLikelyEnglish(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        String[] englishWords = {"hello", "hi", "what", "how", "are", "you", "the", "is", "can", "help", "me", "thanks", "thank", "please", "good", "bad", "yes", "no", "want", "need", "call", "phone"};
        for (String word : englishWords) {
            if (lower.contains(word)) return true;
        }
        int asciiCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= 'a' && c <= 'z') asciiCount++;
        }
        return asciiCount > text.length() * 0.5;
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                isTTSReady = true;
                textToSpeech.setLanguage(Locale.forLanguageTag("en-US"));
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
                                mainHandler.postDelayed(() -> startListening(), 800);
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "TTS error");
                        isSpeaking = false;
                        mainHandler.post(() -> {
                            updateUIState("idle");
                            if (isConversationActive) {
                                mainHandler.postDelayed(() -> startListening(), 500);
                            }
                        });
                    }
                });
                
                Log.d(TAG, "TTS initialized successfully");
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
                Log.d(TAG, "TTS Bengali set result: " + result);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.US);
                }
            } else {
                textToSpeech.setLanguage(Locale.US);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS language error: " + e.getMessage());
            try {
                textToSpeech.setLanguage(Locale.US);
            } catch (Exception ex) {}
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
            if (allGranted) {
                Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ Some permissions denied. Features may not work fully.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void toggleConversation() {
        if (isConversationActive) stopConversation();
        else startConversation();
    }

    private void startConversation() {
        if (isConversationActive) return;
        if (!isTTSReady || !isRecognitionReady) {
            Toast.makeText(this, "Please wait... initializing", Toast.LENGTH_SHORT).show();
            return;
        }
        
        isConversationActive = true;
        detectedLanguage = "en";
        sttBengaliBD = true;
        
        requestAudioFocus();
        updateUIState("starting");
        
        speak("Hello! I'm your voice assistant. What would you like to talk about?");
        
        mainHandler.postDelayed(() -> {
            if (isConversationActive && !isSpeaking) startListening();
        }, 1500);
    }

    private void stopConversation() {
        if (!isConversationActive) return;
        
        isConversationActive = false;
        isCallActive = false;
        
        try {
            if (speechRecognizer != null) speechRecognizer.stopListening();
            if (textToSpeech != null) textToSpeech.stop();
            audioManager.abandonAudioFocus(null);
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        } catch (Exception e) { Log.e(TAG, "Stop error: " + e.getMessage()); }
        
        isListening = false;
        isSpeaking = false;
        
        mainHandler.post(() -> {
            tvStatus.setText("Tap to start");
            tvUser.setText("You: ...");
            tvAI.setText("AI: ...");
            updateUIState("idle");
            stopAnimations();
        });
    }

    private void startListening() {
        if (isDestroyed || !isConversationActive || isSpeaking) return;
        if (speechRecognizer == null) return;
        
        sttBengaliBD = true;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            
            isListening = true;
            updateUIState("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening (Bengali BD mode)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting listening: " + e.getMessage());
            retryOrRecover();
        }
    }
    
    private void startListeningEnglish() {
        if (isDestroyed || !isConversationActive || isSpeaking) return;
        if (speechRecognizer == null) return;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            
            isListening = true;
            updateUIState("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening (English mode)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting English listening: " + e.getMessage());
            retryOrRecover();
        }
    }
    
    private void startListeningBengaliGeneric() {
        if (isDestroyed || !isConversationActive || isSpeaking) return;
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
            Log.d(TAG, "Started listening (Bengali generic mode)");
            
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
        
        if (text.toLowerCase().contains("stop") || text.toLowerCase().contains("বন্ধ")) { 
            stopConversation(); 
            return; 
        }
        
        mainHandler.post(() -> tvUser.setText("You: " + text));
        sttBengaliBD = true;
        processWithAI(text);
    }

    private void processWithAI(String input) {
        updateUIState("thinking");
        
        executor.execute(() -> {
            try {
                String response = getGroqResponse(input, detectedLanguage);
                
                mainHandler.post(() -> {
                    tvAI.setText("AI: " + response);
                    setTTSLanguage(detectedLanguage);
                    speak(response);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "AI error: " + e.getMessage());
                mainHandler.post(() -> {
                    String errorMsg = detectedLanguage.equals("bn") ? "দুঃখিত, আমি বুঝতে পারিনি। আবার চেষ্টা করুন।" : "Sorry, I didn't understand. Let's try again.";
                    tvAI.setText("AI: " + errorMsg);
                    speak(errorMsg);
                    mainHandler.postDelayed(() -> startListening(), 2000);
                });
            }
        });
    }

    private String getGroqResponse(String input, String lang) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        String systemPrompt;
        if (lang.equals("bn")) {
            systemPrompt = "আপনি একজন বন্ধুসুলভ ফোন সহকারী। আপনার উত্তরগুলো সংক্ষিপ্ত, স্বাভাবিক এবং বাংলায় হবে।";
        } else {
            systemPrompt = "You are a friendly phone assistant. Keep responses short, conversational, and helpful.";
        }
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
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
        if (textToSpeech == null || !isTTSReady) {
            if (isConversationActive) mainHandler.postDelayed(() -> startListening(), 500);
            return;
        }
        
        try {
            isSpeaking = true;
            updateUIState("speaking");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance");
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
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception e) { Log.e(TAG, "Audio focus error: " + e.getMessage()); }
    }

    private void retryOrRecover() {
        if (isConversationActive) {
            sttBengaliBD = true;
            mainHandler.postDelayed(() -> startListening(), 1000);
        }
    }

    private void updateUIState(String state) {
        try {
            int bgColor;
            String statusText;
            
            switch (state) {
                case "listening":
                    bgColor = 0xFF4CAF50; statusText = "Listening...";
                    startListeningAnimation();
                    break;
                case "speaking":
                    bgColor = 0xFF2196F3; statusText = "Speaking...";
                    startSpeakingAnimation();
                    break;
                case "thinking":
                    bgColor = 0xFFFF9800; statusText = "Thinking...";
                    startThinkingAnimation();
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
            
        } catch (Exception e) { Log.e(TAG, "UI error: " + e.getMessage()); }
    }

    private void startListeningAnimation() {
        try {
            if (circleView != null) {
                Animation scale = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
                circleView.startAnimation(scale);
            }
        } catch (Exception e) { Log.e(TAG, "Animation error: " + e.getMessage()); }
    }

    private void startSpeakingAnimation() {
        try {
            if (circleView != null) {
                Animation blink = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
                circleView.startAnimation(blink);
            }
        } catch (Exception e) { Log.e(TAG, "Animation error: " + e.getMessage()); }
    }

    private void startThinkingAnimation() {
        try {
            if (circleView != null) {
                Animation rotate = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
                circleView.startAnimation(rotate);
            }
        } catch (Exception e) { Log.e(TAG, "Animation error: " + e.getMessage()); }
    }

    private void stopAnimations() {
        try {
            if (circleView != null) circleView.clearAnimation();
        } catch (Exception e) { Log.e(TAG, "Stop animation error: " + e.getMessage()); }
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
        incomingCallNumber = phoneNumber;
        
        Log.d(TAG, "Showing call notification for: " + phoneNumber);
        
        // Intent for Confirm button - use Broadcast
        Intent confirmIntent = new Intent(ACTION_ANSWER);
        confirmIntent.putExtra("phone_number", phoneNumber);
        PendingIntent confirmPendingIntent = PendingIntent.getBroadcast(
            this, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Intent for Reject button
        Intent rejectIntent = new Intent(ACTION_REJECT);
        rejectIntent.putExtra("phone_number", phoneNumber);
        PendingIntent rejectPendingIntent = PendingIntent.getBroadcast(
            this, 1, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Full screen intent for when screen is locked
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setAction(ACTION_ANSWER);
        fullScreenIntent.putExtra("phone_number", phoneNumber);
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
        Log.d(TAG, "Call notification shown");
    }
    
    private void dismissNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
        Log.d(TAG, "Notification dismissed");
    }
    
    // Called when user clicks "CONFIRM" to transfer call to agent
    private void transferCallToAgent(String phoneNumber) {
        Log.d(TAG, "=== TRANSFER CALL TO AGENT ===");
        Log.d(TAG, "Phone number: " + phoneNumber);
        
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
        
        // Step 3: Enable speaker and set audio mode
        enableSpeakerAndAudioMode();
        
        // Step 4: Request audio focus
        requestAudioFocus();
        updateUIState("starting");
        
        // Step 5: Greet the caller
        String greeting = "Hello! This is an AI assistant. How can I help you today?";
        speak(greeting);
        
        // Step 6: Start listening after greeting
        mainHandler.postDelayed(() -> {
            if (isCallActive && !isSpeaking) {
                startListening();
            }
        }, 3000);
        
        Toast.makeText(this, "🤖 AI Agent connected!", Toast.LENGTH_LONG).show();
    }
    
    // Automatically answer the incoming call
    private void answerCall() {
        Log.d(TAG, "Attempting to answer call...");
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) 
                            == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.acceptRingingCall();
                        Log.d(TAG, "Call answered via TelecomManager");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "TelecomManager answer failed: " + e.getMessage());
        }
        
        // Try runtime answer
        tryRuntimeAnswer();
    }
    
    private void tryRuntimeAnswer() {
        try {
            Runtime.getRuntime().exec("input keyevent 5");
            Log.d(TAG, "Call answered via input keyevent");
        } catch (Exception e) {
            Log.e(TAG, "Runtime answer failed: " + e.getMessage());
        }
    }
    
    // Enable speaker and configure audio for 2-way conversation
    private void enableSpeakerAndAudioMode() {
        Log.d(TAG, "Enabling speaker and audio mode...");
        
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, 
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            
            Log.d(TAG, "Speaker enabled successfully");
        } catch (Exception e) {
            Log.e(TAG, "Speaker enable error: " + e.getMessage());
        }
    }
    
    // CallReceiver.CallListener implementation
    @Override
    public void onIncomingCall(String number) {
        Log.d(TAG, "=== INCOMING CALL DETECTED ===");
        Log.d(TAG, "Number: " + number);
        
        mainHandler.post(() -> {
            showCallNotification(number);
        });
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "App resumed");
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        
        try {
            if (notificationActionReceiver != null) {
                unregisterReceiver(notificationActionReceiver);
            }
        } catch (Exception e) {}
        
        try {
            stopConversation();
            if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
            if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); textToSpeech = null; }
            if (executor != null) { executor.shutdown(); executor.awaitTermination(1, TimeUnit.SECONDS); }
        } catch (Exception e) { Log.e(TAG, "Destroy error: " + e.getMessage()); }
        
        CallReceiver.setListener(null);
        super.onDestroy();
    }
}
