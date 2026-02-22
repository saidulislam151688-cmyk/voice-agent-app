package com.voiceagent.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telecom.TelecomManager;
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
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_CODE = 100;
    // TODO: Replace with your Groq API key or set via BuildConfig
    private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY_HERE";

    // Speech Recognition
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private ExecutorService executor;

    // State
    private boolean isConversationActive = false;
    private boolean isListening = false;
    private boolean isSpeaking = false;

    // Queue for 2-way conversation
    private final CopyOnWriteArrayList<String> messageQueue = new CopyOnWriteArrayList<>();

    // UI Elements
    private android.widget.Button btnToggle;
    private android.widget.TextView tvStatus, tvUser, tvAI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initSpeechRecognition();
        initTextToSpeech();
        checkPermissions();

        executor = Executors.newSingleThreadExecutor();

        // Register call receiver
        CallReceiver.setListener(this::onIncomingCall);
    }

    private void initViews() {
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        tvUser = findViewById(R.id.tvUser);
        tvAI = findViewById(R.id.tvAI);

        btnToggle.setOnClickListener(v -> toggleConversation());
    }

    private void initSpeechRecognition() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                updateUI("listening");
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
            }

            @Override
            public void onError(int error) {
                isListening = false;
                // Auto-restart if active
                if (isConversationActive && error != 7 && !isSpeaking) {
                    btnToggle.postDelayed(this::startListening, 500);
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    handleUserInput(text);
                }
                // Restart listening
                if (isConversationActive && !isSpeaking) {
                    btnToggle.postDelayed(this::startListening, 300);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}

            private void startListening() {
                if (isConversationActive && !isSpeaking) {
                    startListening();
                }
            }
        });
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        runOnUiThread(() -> {
                            isSpeaking = true;
                            updateUI("speaking");
                        });
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        runOnUiThread(() -> {
                            isSpeaking = false;
                            // Continue listening after speaking
                            if (isConversationActive) {
                                btnToggle.postDelayed(() -> {
                                    if (isConversationActive && !isListening) {
                                        startListening();
                                    }
                                }, 500);
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        runOnUiThread(() -> {
                            isSpeaking = false;
                            if (isConversationActive) startListening();
                        });
                    }
                });
            }
        });
    }

    private void checkPermissions() {
        String[] permissions = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.POST_NOTIFICATIONS
        };

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_CODE);
                return;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show();
    }

    private void toggleConversation() {
        if (!isConversationActive) {
            startConversation();
        } else {
            stopConversation();
        }
    }

    private void startConversation() {
        isConversationActive = true;
        btnToggle.setText("🛑 STOP");
        
        speak("Hello! I am ready. Say something.");
        
        btnToggle.postDelayed(this::startListening, 2500);
    }

    private void stopConversation() {
        isConversationActive = false;
        
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        textToSpeech.stop();
        
        isListening = false;
        isSpeaking = false;
        messageQueue.clear();
        
        btnToggle.setText("▶️ START");
        tvStatus.setText("Stopped");
        tvUser.setText("You: ...");
        tvAI.setText("AI: ...");
        updateUI("idle");
        
        speak("Goodbye!");
    }

    private void startListening() {
        if (!isConversationActive || isSpeaking) return;
        
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        
        isListening = true;
        speechRecognizer.startListening(intent);
    }

    private void handleUserInput(String text) {
        // Check for stop command
        if (text.toLowerCase().contains("stop")) {
            stopConversation();
            return;
        }
        
        tvUser.setText("You: " + text);
        
        // Process with AI
        processWithAI(text);
    }

    private void processWithAI(String input) {
        tvStatus.setText("Thinking...");
        updateUI("thinking");
        
        executor.execute(() -> {
            try {
                String response = getGroqResponse(input);
                
                runOnUiThread(() -> {
                    tvAI.setText("AI: " + response);
                    speak(response);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvAI.setText("AI: Error");
                    speak("Sorry, error occurred");
                });
            }
        });
    }

    private String getGroqResponse(String input) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        
        String jsonBody = "{\"model\":\"llama-3.1-8b-instant\"," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"You are a friendly person on phone. Keep responses short and natural.\"}," +
                "{\"role\":\"user\",\"content\":\"" + input + "\"}]," +
                "\"temperature\":0.8,\"max_tokens\":60}";
        
        conn.getOutputStream().write(jsonBody.getBytes());
        
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
    }

    private void speak(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance");
        } else {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    private void updateUI(String state) {
        int bgColor;
        String icon;
        
        switch (state) {
            case "listening":
                bgColor = 0xFF4CAF50;
                icon = "🎤";
                tvStatus.setText("Listening...");
                break;
            case "speaking":
                bgColor = 0xFF2196F3;
                icon = "🔊";
                tvStatus.setText("Speaking...");
                break;
            case "thinking":
                bgColor = 0xFFFF9800;
                icon = "🧠";
                tvStatus.setText("Thinking...");
                break;
            default:
                bgColor = 0xFF333333;
                icon = "🎤";
                tvStatus.setText("Ready");
        }
        
        findViewById(R.id.circleView).setBackgroundColor(bgColor);
        ((android.widget.TextView)findViewById(R.id.tvIcon)).setText(icon);
    }

    // Handle incoming calls
    public void onIncomingCall(String number) {
        if (isConversationActive) return;
        
        new AlertDialog.Builder(this)
            .setTitle("📞 Incoming Call")
            .setMessage("From: " + number)
            .setPositiveButton("✅ Transfer to AI", (d, w) -> startConversation())
            .setNegativeButton("❌ Decline", (d, w) -> {})
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (textToSpeech != null) textToSpeech.shutdown();
        executor.shutdown();
    }
}
