package com.voiceagent.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFocusRequest;
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
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
    
    // Replace with your Groq API key - will be replaced by GitHub Actions
    private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY";

    // State Machine
    private enum ConversationState {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING
    }
    
    private ConversationState currentState = ConversationState.IDLE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Components
    private SpeechRecognizer speechRecognizer = null;
    private TextToSpeech textToSpeech = null;
    private boolean isTTSReady = false;
    private boolean isRecognitionReady = false;
    private ExecutorService executor;
    private AudioManager audioManager;

    // UI Elements
    private Button btnToggle;
    private TextView tvStatus, tvUser, tvAI, tvTitle, tvIcon;
    private View circleView, pulseView;

    // Flags
    private boolean isConversationActive = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean isDestroyed = false;

    // Retry mechanism
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;

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
        
        Log.d(TAG, "Voice Agent started - UI initialized");
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
        
        btnToggle.setOnClickListener(v -> {
            Log.d(TAG, "Toggle button clicked");
            toggleConversation();
        });
        
        // Initial UI update
        updateUIState("idle");
    }

    private void initAudioManager() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private void initSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available");
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show();
            return;
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "Ready for speech");
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "End of speech");
                isListening = false;
            }

            @Override
            public void onError(int error) {
                Log.e(TAG, "Speech error: " + error);
                isListening = false;
                
                // Handle different errors
                if (error == SpeechRecognizer.ERROR_NO_MATCH || 
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // User didn't speak - restart listening
                    if (isConversationActive && !isSpeaking) {
                        mainHandler.postDelayed(() -> startListening(), 500);
                    }
                    return;
                }
                
                // Other errors - retry
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
                    handleUserInput(text);
                } else {
                    // No results - restart listening
                    if (isConversationActive && !isSpeaking) {
                        mainHandler.postDelayed(() -> startListening(), 300);
                    }
                }
            }

            @Override
            public void onPartialResults(Bundle bundle) {}

            @Override
            public void onEvent(int i, Bundle bundle) {}
        });
        
        isRecognitionReady = true;
        Log.d(TAG, "Speech recognition initialized");
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
                            // Restart listening after speaking
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
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "TTS Ready!", Toast.LENGTH_SHORT).show();
                });
            } else {
                Log.e(TAG, "TTS init failed: " + status);
                isTTSReady = false;
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.RECORD_AUDIO}, 
                PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Microphone permission granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "❌ Microphone permission required!", Toast.LENGTH_LONG).show();
                btnToggle.setEnabled(false);
            }
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
        
        // Check if components are ready
        if (!isTTSReady || !isRecognitionReady) {
            Toast.makeText(this, "Please wait... initializing", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Components not ready - TTS: " + isTTSReady + ", Recognition: " + isRecognitionReady);
            return;
        }
        
        isConversationActive = true;
        retryCount = 0;
        
        // Request audio focus
        requestAudioFocus();
        
        // Update UI
        updateUIState("starting");
        btnToggle.setText("🛑 STOP");
        
        // Start with greeting
        speak("Hello! I'm your voice assistant. What would you like to talk about?");
        
        // Start listening after a short delay to let TTS start
        mainHandler.postDelayed(() -> {
            if (isConversationActive && !isSpeaking) {
                startListening();
            }
        }, 1500);
    }

    private void stopConversation() {
        if (!isConversationActive) return;
        
        isConversationActive = false;
        
        // Stop listening
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recognizer: " + e.getMessage());
            }
        }
        
        // Stop speaking
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        
        // Abandon audio focus
        try {
            audioManager.abandonAudioFocus(null);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) {
            Log.e(TAG, "Error abandoning audio focus: " + e.getMessage());
        }
        
        isListening = false;
        isSpeaking = false;
        
        // Update UI
        mainHandler.post(() -> {
            btnToggle.setText("▶️ START");
            tvStatus.setText("Tap Start to begin");
            tvUser.setText("You: ...");
            tvAI.setText("AI: ...");
            updateUIState("idle");
            stopAnimations();
        });
    }

    private void startListening() {
        if (isDestroyed || !isConversationActive || isSpeaking) {
            Log.d(TAG, "Cannot start listening - destroyed: " + isDestroyed + 
                  ", active: " + isConversationActive + ", speaking: " + isSpeaking);
            return;
        }
        
        if (speechRecognizer == null) {
            Log.e(TAG, "Speech recognizer is null!");
            return;
        }
        
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            
            isListening = true;
            updateUIState("listening");
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening");
            
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
        
        // Check for stop command
        if (text.toLowerCase().contains("stop")) {
            stopConversation();
            return;
        }
        
        // Update UI with user text
        mainHandler.post(() -> tvUser.setText("You: " + text));
        
        // Process with AI
        processWithAI(text);
    }

    private void processWithAI(String input) {
        updateUIState("thinking");
        
        executor.execute(() -> {
            try {
                String response = getGroqResponse(input);
                
                mainHandler.post(() -> {
                    tvAI.setText("AI: " + response);
                    speak(response);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "AI error: " + e.getMessage());
                mainHandler.post(() -> {
                    tvAI.setText("AI: Sorry, I couldn't process that.");
                    speak("Sorry, I encountered an error. Let's try again.");
                    mainHandler.postDelayed(() -> startListening(), 2000);
                });
            }
        });
    }

    private String getGroqResponse(String input) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            
            String jsonBody = "{\"model\":\"llama-3.1-8b-instant\"," +
                    "\"messages\":[{\"role\":\"system\",\"content\":\"You are a friendly phone assistant. Keep responses short, conversational, and helpful.\"}," +
                    "{\"role\":\"user\",\"content\":\"" + input.replace("\"", "\\\"") + "\"}]," +
                    "\"temperature\":0.7,\"max_tokens\":100}";
            
            conn.getOutputStream().write(jsonBody.getBytes());
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("API error: " + responseCode);
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            JSONObject json = new JSONObject(response.toString());
            return json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
                    
        } finally {
            conn.disconnect();
        }
    }

    private void speak(String text) {
        if (textToSpeech == null || !isTTSReady) {
            Log.e(TAG, "TTS not ready!");
            // Try to restart listening anyway
            if (isConversationActive) {
                mainHandler.postDelayed(() -> startListening(), 500);
            }
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
            Log.e(TAG, "TTS speak error: " + e.getMessage());
            isSpeaking = false;
            updateUIState("idle");
        }
    }

    private void requestAudioFocus() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.requestAudioFocus(null, 
                AudioManager.STREAM_VOICE_CALL, 
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception e) {
            Log.e(TAG, "Audio focus error: " + e.getMessage());
        }
    }

    private void retryOrRecover() {
        retryCount++;
        Log.d(TAG, "Retry attempt: " + retryCount);
        
        if (retryCount < MAX_RETRIES && isConversationActive) {
            mainHandler.postDelayed(() -> startListening(), 1000);
        } else {
            retryCount = 0;
            mainHandler.post(() -> {
                tvStatus.setText("Tap to retry");
                Toast.makeText(this, "Connection issue. Tap Start to try again.", Toast.LENGTH_LONG).show();
            });
        }
    }

    private void updateUIState(String state) {
        try {
            int bgColor;
            String icon;
            String statusText;
            
            switch (state) {
                case "listening":
                    bgColor = 0xFF4CAF50; // Green
                    icon = "🎤";
                    statusText = "Listening...";
                    startListeningAnimation();
                    break;
                case "speaking":
                    bgColor = 0xFF2196F3; // Blue
                    icon = "🔊";
                    statusText = "Speaking...";
                    startSpeakingAnimation();
                    break;
                case "thinking":
                    bgColor = 0xFFFF9800; // Orange
                    icon = "🧠";
                    statusText = "Thinking...";
                    startThinkingAnimation();
                    break;
                case "starting":
                    bgColor = 0xFF9C27B0; // Purple
                    icon = "⚡";
                    statusText = "Starting...";
                    break;
                case "idle":
                default:
                    bgColor = 0xFF333333; // Dark gray
                    icon = "🎤";
                    statusText = "Tap Start to begin";
                    stopAnimations();
            }
            
            if (circleView != null) {
                circleView.setBackgroundColor(bgColor);
            }
            if (tvIcon != null) {
                tvIcon.setText(icon);
            }
            if (tvStatus != null) {
                tvStatus.setText(statusText);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "UI update error: " + e.getMessage());
        }
    }

    private void startListeningAnimation() {
        try {
            if (circleView == null) return;
            
            Animation scaleAnim = new ScaleAnimation(
                1.0f, 1.1f, 1.0f, 1.1f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            );
            scaleAnim.setDuration(600);
            scaleAnim.setRepeatCount(Animation.INFINITE);
            scaleAnim.setRepeatMode(Animation.REVERSE);
            circleView.startAnimation(scaleAnim);
            
            // Pulse effect
            if (pulseView != null) {
                Animation pulseAnim = new ScaleAnimation(
                    1.0f, 1.3f, 1.0f, 1.3f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                );
                pulseAnim.setDuration(800);
                pulseAnim.setRepeatCount(Animation.INFINITE);
                pulseAnim.setRepeatMode(Animation.REVERSE);
                pulseView.startAnimation(pulseAnim);
                pulseView.setAlpha(0.5f);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Listening animation error: " + e.getMessage());
        }
    }

    private void startSpeakingAnimation() {
        try {
            if (circleView == null) return;
            
            Animation fadeAnim = new AlphaAnimation(1.0f, 0.5f);
            fadeAnim.setDuration(400);
            fadeAnim.setRepeatCount(Animation.INFINITE);
            fadeAnim.setRepeatMode(Animation.REVERSE);
            circleView.startAnimation(fadeAnim);
            
        } catch (Exception e) {
            Log.e(TAG, "Speaking animation error: " + e.getMessage());
        }
    }

    private void startThinkingAnimation() {
        try {
            if (circleView == null) return;
            
            Animation rotateAnim = new ScaleAnimation(
                1.0f, 1.05f, 1.0f, 1.05f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            );
            rotateAnim.setDuration(300);
            rotateAnim.setRepeatCount(Animation.INFINITE);
            rotateAnim.setRepeatMode(Animation.REVERSE);
            circleView.startAnimation(rotateAnim);
            
        } catch (Exception e) {
            Log.e(TAG, "Thinking animation error: " + e.getMessage());
        }
    }

    private void stopAnimations() {
        try {
            if (circleView != null) {
                circleView.clearAnimation();
            }
            if (pulseView != null) {
                pulseView.clearAnimation();
                pulseView.setAlpha(0f);
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop animation error: " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "App paused");
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
        
        super.onDestroy();
    }
}
