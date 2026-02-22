package com.voiceagent.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
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
import android.view.animation.AnimationUtils;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
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
    
    // Replace with your Groq API key
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
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private ExecutorService executor;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    // UI Elements
    private Button btnToggle;
    private TextView tvStatus, tvUser, tvAI, tvTitle, tvIcon;
    private View circleView, pulseView;
    private View containerView;

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
        
        CallReceiver.setListener(this::onIncomingCall);
        
        Log.d(TAG, "Voice Agent started");
    }

    private void initViews() {
        try {
            btnToggle = findViewById(R.id.btnToggle);
            tvStatus = findViewById(R.id.tvStatus);
            tvUser = findViewById(R.id.tvUser);
            tvAI = findViewById(R.id.tvAI);
            tvTitle = findViewById(R.id.tvTitle);
            tvIcon = findViewById(R.id.tvIcon);
            circleView = findViewById(R.id.circleView);
            pulseView = findViewById(R.id.pulseView);
            containerView = findViewById(R.id.containerView);
            
            if (btnToggle != null) {
                btnToggle.setOnClickListener(v -> toggleConversation());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views: " + e.getMessage());
        }
    }

    private void initAudioManager() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    private void initSpeechRecognition() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                showError("Speech recognition not available");
                return;
            }
            
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new SafeRecognitionListener());
        } catch (Exception e) {
            Log.e(TAG, "Error initializing speech recognition: " + e.getMessage());
            showError("Failed to initialize speech recognition");
        }
    }

    private void initTextToSpeech() {
        try {
            textToSpeech = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech.setLanguage(Locale.US);
                    textToSpeech.setOnUtteranceProgressListener(new SafeUtteranceListener());
                    Log.d(TAG, "TTS initialized successfully");
                } else {
                    Log.e(TAG, "TTS init failed with status: " + status);
                    showError("Failed to initialize text-to-speech");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error initializing TTS: " + e.getMessage());
        }
    }

    private void checkPermissions() {
        String[] requiredPermissions = {
            Manifest.permission.RECORD_AUDIO
        };
        
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                this, 
                permissionsToRequest.toArray(new String[0]), 
                PERMISSION_CODE
            );
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
                Toast.makeText(this, "✅ Permissions granted!", Toast.LENGTH_SHORT).show();
            } else {
                showError("Microphone permission is required!");
                btnToggle.setEnabled(false);
            }
        }
    }

    private synchronized void toggleConversation() {
        if (isConversationActive) {
            stopConversation();
        } else {
            startConversation();
        }
    }

    private synchronized void startConversation() {
        if (isConversationActive) return;
        
        try {
            isConversationActive = true;
            retryCount = 0;
            
            updateState(ConversationState.PROCESSING);
            updateUIState("starting");
            
            // Request audio focus
            requestAudioFocus();
            
            // Initial greeting
            speakWithCallback("Hello! I'm ready. What would you like to talk about?", () -> {
                if (!isDestroyed && isConversationActive) {
                    startListening();
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting conversation: " + e.getMessage());
            handleError("Failed to start conversation");
        }
    }

    private synchronized void stopConversation() {
        if (!isConversationActive) return;
        
        try {
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
            abandonAudioFocus();
            
            // Reset state
            isListening = false;
            isSpeaking = false;
            updateState(ConversationState.IDLE);
            
            // Update UI on main thread
            mainHandler.post(() -> {
                try {
                    if (btnToggle != null) {
                        btnToggle.setText("▶️ START");
                        btnToggle.setEnabled(true);
                    }
                    if (tvStatus != null) {
                        tvStatus.setText("Stopped");
                    }
                    if (tvUser != null) {
                        tvUser.setText("You: ...");
                    }
                    if (tvAI != null) {
                        tvAI.setText("AI: ...");
                    }
                    stopAnimations();
                } catch (Exception e) {
                    Log.e(TAG, "Error updating UI: " + e.getMessage());
                }
            });
            
            Log.d(TAG, "Conversation stopped");
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping conversation: " + e.getMessage());
        }
    }

    private synchronized void startListening() {
        if (isDestroyed || !isConversationActive || isSpeaking) {
            return;
        }
        
        try {
            // Check if we can start listening
            if (currentState == ConversationState.SPEAKING) {
                Log.d(TAG, "Cannot start listening - currently speaking");
                return;
            }
            
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
            
            isListening = true;
            updateState(ConversationState.LISTENING);
            startListeningAnimation();
            
            speechRecognizer.startListening(intent);
            Log.d(TAG, "Started listening");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting listening: " + e.getMessage());
            handleError("Failed to start listening");
            retryOrRecover();
        }
    }

    private synchronized void stopListening() {
        try {
            if (speechRecognizer != null && isListening) {
                speechRecognizer.stopListening();
            }
            isListening = false;
            stopAnimations();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping listening: " + e.getMessage());
        }
    }

    private void handleUserInput(String text) {
        if (text == null || text.trim().isEmpty()) return;
        
        // Check for stop command
        if (text.toLowerCase().contains("stop")) {
            stopConversation();
            return;
        }
        
        // Update UI
        mainHandler.post(() -> {
            try {
                if (tvUser != null) {
                    tvUser.setText("You: " + text);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating user text: " + e.getMessage());
            }
        });
        
        // Process with AI
        processWithAI(text);
    }

    private void processWithAI(String input) {
        updateState(ConversationState.PROCESSING);
        
        executor.execute(() -> {
            try {
                String response = getGroqResponse(input);
                
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    
                    try {
                        if (tvAI != null) {
                            tvAI.setText("AI: " + response);
                        }
                        
                        speakWithCallback(response, () -> {
                            if (!isDestroyed && isConversationActive) {
                                // Restart listening after speaking
                                mainHandler.postDelayed(() -> startListening(), 800);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing AI response: " + e.getMessage());
                        handleError("Failed to process response");
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error getting AI response: " + e.getMessage());
                mainHandler.post(() -> handleError("Network error: " + e.getMessage()));
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
                    "\"messages\":[{\"role\":\"system\",\"content\":\"You are a friendly, helpful phone assistant. Keep responses conversational and under 2 sentences.\"}," +
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

    private void speakWithCallback(String text, Runnable onComplete) {
        if (isDestroyed || textToSpeech == null) return;
        
        try {
            isSpeaking = true;
            updateState(ConversationState.SPEAKING);
            startSpeakingAnimation();
            
            Bundle params = new Bundle();
            params.putString("callback", "complete");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_" + System.currentTimeMillis());
            } else {
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error speaking: " + e.getMessage());
            isSpeaking = false;
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    private void onSpeechComplete() {
        isSpeaking = false;
        stopSpeakingAnimation();
        
        if (isConversationActive) {
            // Auto-restart listening after a short delay
            mainHandler.postDelayed(() -> {
                if (!isDestroyed && isConversationActive && !isSpeaking) {
                    startListening();
                }
            }, 500);
        }
    }

    private void requestAudioFocus() {
        try {
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                int result = audioManager.requestAudioFocus(null, 
                    AudioManager.STREAM_VOICE_CALL, 
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                
                Log.d(TAG, "Audio focus request result: " + result);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting audio focus: " + e.getMessage());
        }
    }

    private void abandonAudioFocus() {
        try {
            if (audioManager != null) {
                audioManager.abandonAudioFocus(null);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error abandoning audio focus: " + e.getMessage());
        }
    }

    private void updateState(ConversationState newState) {
        currentState = newState;
        Log.d(TAG, "State changed to: " + newState);
    }

    private void updateUIState(String state) {
        mainHandler.post(() -> {
            try {
                if (isDestroyed) return;
                
                int bgColor;
                String icon;
                String statusText;
                
                switch (state) {
                    case "listening":
                        bgColor = 0xFF4CAF50;
                        icon = "🎤";
                        statusText = "Listening...";
                        break;
                    case "speaking":
                        bgColor = 0xFF2196F3;
                        icon = "🔊";
                        statusText = "Speaking...";
                        break;
                    case "thinking":
                        bgColor = 0xFFFF9800;
                        icon = "🧠";
                        statusText = "Thinking...";
                        break;
                    case "starting":
                        bgColor = 0xFF9C27B0;
                        icon = "⚡";
                        statusText = "Starting...";
                        break;
                    default:
                        bgColor = 0xFF333333;
                        icon = "🎤";
                        statusText = "Ready";
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
                if (btnToggle != null) {
                    btnToggle.setText("🛑 STOP");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error updating UI: " + e.getMessage());
            }
        });
    }

    private void startListeningAnimation() {
        mainHandler.post(() -> {
            try {
                if (circleView == null) return;
                
                // Pulse animation
                Animation pulseAnim = new ScaleAnimation(
                    1.0f, 1.15f, 1.0f, 1.15f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                );
                pulseAnim.setDuration(600);
                pulseAnim.setRepeatCount(Animation.INFINITE);
                pulseAnim.setRepeatMode(Animation.REVERSE);
                circleView.startAnimation(pulseAnim);
                
                updateUIState("listening");
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting listening animation: " + e.getMessage());
            }
        });
    }

    private void startSpeakingAnimation() {
        mainHandler.post(() -> {
            try {
                if (circleView == null) return;
                
                // Fade animation
                Animation fadeAnim = new AlphaAnimation(1.0f, 0.6f);
                fadeAnim.setDuration(400);
                fadeAnim.setRepeatCount(Animation.INFINITE);
                fadeAnim.setRepeatMode(Animation.REVERSE);
                circleView.startAnimation(fadeAnim);
                
                updateUIState("speaking");
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting speaking animation: " + e.getMessage());
            }
        });
    }

    private void stopAnimations() {
        mainHandler.post(() -> {
            try {
                if (circleView != null) {
                    circleView.clearAnimation();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping animations: " + e.getMessage());
            }
        });
    }

    private void stopSpeakingAnimation() {
        stopAnimations();
    }

    private void retryOrRecover() {
        retryCount++;
        if (retryCount < MAX_RETRIES && isConversationActive) {
            Log.d(TAG, "Retrying... attempt " + retryCount);
            mainHandler.postDelayed(() -> startListening(), 1000);
        } else {
            handleError("Connection lost. Tap to retry.");
            retryCount = 0;
        }
    }

    private void handleError(String message) {
        Log.e(TAG, "Error: " + message);
        
        mainHandler.post(() -> {
            try {
                if (tvStatus != null) {
                    tvStatus.setText(message);
                }
                
                // Show error but don't crash
                if (isConversationActive) {
                    speakWithCallback("Sorry, I encountered an error. Let's try again.", () -> {
                        mainHandler.postDelayed(() -> startListening(), 1000);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in error handler: " + e.getMessage());
            }
        });
    }

    private void showError(String message) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e(TAG, "Error showing toast: " + e.getMessage());
            }
        });
    }

    // Safe Recognition Listener
    private class SafeRecognitionListener implements RecognitionListener {
        
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
            Log.e(TAG, "Speech recognition error: " + error);
            isListening = false;
            
            // Don't restart on no speech error
            if (error == SpeechRecognizer.ERROR_NO_MATCH || 
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                // Silent - user just didn't speak
                if (isConversationActive && !isSpeaking) {
                    mainHandler.postDelayed(() -> startListening(), 500);
                }
                return;
            }
            
            // For other errors, try to recover
            if (isConversationActive && !isSpeaking) {
                retryOrRecover();
            }
        }

        @Override
        public void onResults(Bundle results) {
            Log.d(TAG, "Speech results received");
            isListening = false;
            
            try {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    Log.d(TAG, "Recognized: " + text);
                    handleUserInput(text);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing results: " + e.getMessage());
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {}

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    // Safe Utterance Listener
    private class SafeUtteranceListener extends UtteranceProgressListener {
        
        @Override
        public void onStart(String utteranceId) {
            Log.d(TAG, "TTS started");
            isSpeaking = true;
        }

        @Override
        public void onDone(String utteranceId) {
            Log.d(TAG, "TTS done");
            isSpeaking = false;
            onSpeechComplete();
        }

        @Override
        public void onError(String utteranceId) {
            Log.e(TAG, "TTS error");
            isSpeaking = false;
            onSpeechComplete();
        }
    }

    // Handle incoming calls
    public void onIncomingCall(String number) {
        if (isConversationActive) return;
        
        mainHandler.post(() -> {
            try {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("📞 Incoming Call")
                    .setMessage("From: " + number + "\n\nTransfer to AI assistant?")
                    .setPositiveButton("✅ Transfer to AI", (d, w) -> startConversation())
                    .setNegativeButton("❌ Decline", (d, w) -> {})
                    .setCancelable(false)
                    .show();
            } catch (Exception e) {
                Log.e(TAG, "Error showing call dialog: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "App paused");
        // Optionally pause conversation
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
                try {
                    executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            abandonAudioFocus();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage());
        }
        
        super.onDestroy();
    }
}
