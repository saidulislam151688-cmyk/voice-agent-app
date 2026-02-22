package com.voiceagent.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
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
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    private Button btnToggle;
    private TextView tvStatus, tvUser, tvAI, tvTitle, tvIcon;
    private View circleView, pulseView;

    // Flags
    private boolean isConversationActive = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean isDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initAudioManager();
        initSpeechRecognition();
        initTextToSpeech();
        checkPermissions();
        
        executor = Executors.newSingleThreadExecutor();
        Log.d(TAG, "Voice Agent started");
    }

    private void initViews() {
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        tvUser = findViewById(R.id.tvUser);
        tvAI = findViewById(R.id.tvAI);
        tvTitle = findViewById(R.id.tvTitle);
        tvIcon = findViewById(R.id.tvIcon);
        circleView = findViewById(R.id.circleView);
        pulseView = findViewById(R.id.pulseView);
        
        btnToggle.setOnClickListener(v -> toggleConversation());
        updateUIState("idle");
    }

    private void initAudioManager() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private boolean sttBengaliBD = true; // true = try bn-BD, false = try bn (generic)
    
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
                
                // If Bengali BD failed, try generic Bengali
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && sttBengaliBD) {
                    if (isConversationActive && !isSpeaking) {
                        sttBengaliBD = false;
                        mainHandler.postDelayed(() -> startListeningBengaliGeneric(), 300);
                        return;
                    }
                }
                
                // If Bengali failed, try English
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
                Log.d(TAG, "Got results (Bengali BD: " + sttBengaliBD + ")");
                isListening = false;
                
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    Log.d(TAG, "Raw recognized: " + text);
                    
                    // Check if this looks like Bengali text
                    boolean hasBengaliChars = containsBengaliCharacters(text);
                    
                    if (hasBengaliChars) {
                        // Bengali text detected correctly!
                        detectedLanguage = "bn";
                        Log.d(TAG, "Detected Bengali correctly: " + text);
                    } else {
                        // Check if it might be English
                        if (isLikelyEnglish(text)) {
                            detectedLanguage = "en";
                            Log.d(TAG, "Detected English: " + text);
                        } else {
                            // Might be Banglish - still treat as English for now
                            detectedLanguage = "en";
                            Log.d(TAG, "Detected as English (possibly Banglish): " + text);
                        }
                    }
                    
                    Log.d(TAG, "Final language: " + detectedLanguage + ", Text: " + text);
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
        // Bengali Unicode range: U+0980 to U+09FF
        return text.matches(".*[\\u0980-\\u09FF].*");
    }
    
    private boolean isLikelyEnglish(String text) {
        if (text == null || text.isEmpty()) return false;
        // Check for common English words
        String lower = text.toLowerCase();
        String[] englishWords = {"hello", "hi", "what", "how", "are", "you", "the", "is", "can", "help", "me", "thanks", "thank", "please", "good", "bad", "yes", "no", "want", "need", "call", "phone"};
        for (String word : englishWords) {
            if (lower.contains(word)) return true;
        }
        // Check if mostly ASCII letters
        int asciiCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= 'a' && c <= 'z') asciiCount++;
        }
        return asciiCount > text.length() * 0.5;
    }

    private void detectLanguage(String text) {
        // Simple detection - check for Bengali characters
        if (text.matches(".*[অ-হ].*") || text.matches(".*[া-ীেোো].*") || text.matches(".*[ক-ম].*")) {
            detectedLanguage = "bn";
            Log.d(TAG, "Language detected: Bengali");
        } else {
            detectedLanguage = "en";
            Log.d(TAG, "Language detected: English");
        }
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
                    Log.w(TAG, "Bengali TTS not supported, trying en-US");
                    textToSpeech.setLanguage(Locale.US);
                }
            } else {
                textToSpeech.setLanguage(Locale.US);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS language error: " + e.getMessage());
            try {
                textToSpeech.setLanguage(Locale.US);
            } catch (Exception ex) {
                Log.e(TAG, "Fallback TTS failed: " + ex.getMessage());
            }
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Microphone ready!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "❌ Microphone permission required!", Toast.LENGTH_LONG).show();
                btnToggle.setEnabled(false);
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
        btnToggle.setText("🛑 STOP");
        
        // Start with greeting
        speak("Hello! I'm your voice assistant. What would you like to talk about?");
        
        mainHandler.postDelayed(() -> {
            if (isConversationActive && !isSpeaking) startListening();
        }, 1500);
    }

    private void stopConversation() {
        if (!isConversationActive) return;
        
        isConversationActive = false;
        
        try {
            if (speechRecognizer != null) speechRecognizer.stopListening();
            if (textToSpeech != null) textToSpeech.stop();
            audioManager.abandonAudioFocus(null);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) { Log.e(TAG, "Stop error: " + e.getMessage()); }
        
        isListening = false;
        isSpeaking = false;
        
        mainHandler.post(() -> {
            btnToggle.setText("🎙️ START");
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
        
        // Reset to try Bengali BD first each time
        sttBengaliBD = true;
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            
            // Try Bengali Bangladesh first
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
    
    // Try generic Bengali locale
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
        
        // Reset for next turn
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
            String icon, statusText;
            
            switch (state) {
                case "listening":
                    bgColor = 0xFF4CAF50; icon = "🎤"; statusText = "Listening...";
                    startListeningAnimation();
                    break;
                case "speaking":
                    bgColor = 0xFF2196F3; icon = "🔊"; statusText = "Speaking...";
                    startSpeakingAnimation();
                    break;
                case "thinking":
                    bgColor = 0xFFFF9800; icon = "💭"; statusText = "Thinking...";
                    startThinkingAnimation();
                    break;
                case "starting":
                    bgColor = 0xFF9C27B0; icon = "⚡"; statusText = "Starting...";
                    break;
                default:
                    bgColor = 0xFF1A1A2E; icon = "🎙️"; statusText = "Tap to start";
                    stopAnimations();
            }
            
            if (circleView != null) circleView.setBackgroundColor(bgColor);
            if (tvIcon != null) tvIcon.setText(icon);
            if (tvStatus != null) tvStatus.setText(statusText);
            
        } catch (Exception e) { Log.e(TAG, "UI error: " + e.getMessage()); }
    }

    private void startListeningAnimation() {
        try {
            if (circleView != null) {
                Animation scale = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
                circleView.startAnimation(scale);
            }
            if (pulseView != null) {
                Animation pulse = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
                pulseView.startAnimation(pulse);
                pulseView.setAlpha(0.6f);
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
            if (pulseView != null) {
                pulseView.clearAnimation();
                pulseView.setAlpha(0f);
            }
        } catch (Exception e) { Log.e(TAG, "Stop animation error: " + e.getMessage()); }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        try {
            stopConversation();
            if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
            if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); textToSpeech = null; }
            if (executor != null) { executor.shutdown(); executor.awaitTermination(1, TimeUnit.SECONDS); }
        } catch (Exception e) { Log.e(TAG, "Destroy error: " + e.getMessage()); }
        super.onDestroy();
    }
}
