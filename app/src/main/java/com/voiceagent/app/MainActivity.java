package com.voiceagent.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioManager;
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
    private static final String ACTION_ANSWER = "com.voiceagent.app.ACTION_ANSWER";
    private static final String ACTION_REJECT = "com.voiceagent.app.ACTION_REJECT";
    public static final String ACTION_TRANSFER_CALL = "com.voiceagent.app.TRANSFER_CALL";
    
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
    private CallMonitorService callService;
    private boolean serviceBound = false;

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
        
        // Bind to CallMonitorService
        Intent serviceIntent = new Intent(this, CallMonitorService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        
        // Set up call listener
        CallMonitorService.listener = new CallMonitorService.CallListener() {
            @Override
            public void onIncomingCall(String number) {
                Log.d(TAG, "Incoming call: " + number);
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
        };
        
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
            
            if (ACTION_TRANSFER_CALL.equals(action) || ACTION_ANSWER.equals(action)) {
                transferCallToAgent(phoneNumber);
            } else if (ACTION_REJECT.equals(action)) {
                // Handle reject
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
        
        btnToggle.setOnClickListener(v -> {
            Log.d(TAG, "Button clicked");
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
                    Log.e(TAG, "Speech error: " + error);
                    isListening = false;
                    
                    if ((error == 7 || error == 8) && sttBengaliBD) {
                        sttBengaliBD = false;
                        mainHandler.postDelayed(() -> {
                            if (isConversationActive && !isSpeaking) startListeningBengali();
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
                                    if (isConversationActive && !isSpeaking && !isListening) {
                                        startListening();
                                    }
                                }, 1000);
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
                                mainHandler.postDelayed(() -> {
                                    if (isConversationActive && !isSpeaking && !isListening) {
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
        } else {
            startCallService();
        }
    }

    private void startCallService() {
        Intent serviceIntent = new Intent(this, CallMonitorService.class);
        serviceIntent.setAction(CallMonitorService.ACTION_START_SERVICE);
        startService(serviceIntent);
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
                    Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show();
                    startCallService();
                } else {
                    Toast.makeText(this, "⚠️ Permissions needed for call features", Toast.LENGTH_LONG).show();
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
            mainHandler.post(() -> Toast.makeText(this, "Please wait... initializing", Toast.LENGTH_SHORT).show());
            return;
        }
        
        isConversationActive = true;
        detectedLanguage = "en";
        sttBengaliBD = true;
        
        requestAudioFocus();
        updateUIState("starting");
        
        speak("Hello! I'm your voice assistant. How can I help you today?");
    }

    private void stopConversation() {
        if (!isConversationActive) return;
        
        isConversationActive = false;
        isCallActive = false;
        
        try {
            if (speechRecognizer != null) speechRecognizer.stopListening();
            if (textToSpeech != null) textToSpeech.stop();
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
    
    private void startListeningBengali() {
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
            Log.e(TAG, "Error starting Bengali: " + e.getMessage());
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
        updateUIState("thinking");
        
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
                    String errorMsg = detectedLanguage.equals("bn") ? "দুঃখিত, সমস্যা হয়েছে।" : "Sorry, I encountered an error.";
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
                if (isConversationActive && !isSpeaking && !isListening) {
                    startListening();
                }
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
            
            if (circleView != null) circleView.setBackgroundColor(bgColor);
            if (tvStatus != null) tvStatus.setText(statusText);
            
        } catch (Exception e) { 
            Log.e(TAG, "UI error: " + e.getMessage()); 
        }
    }
    
    private void transferCallToAgent(String phoneNumber) {
        Log.d(TAG, "=== TRANSFER CALL TO AGENT ===");
        
        isCallActive = true;
        isConversationActive = true;
        detectedLanguage = "en";
        
        enableFullAudioMode();
        
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        
        speak("Hello! This is an AI assistant. How can I help you?");
        
        mainHandler.postDelayed(() -> {
            if (isConversationActive && !isSpeaking) {
                startListening();
            }
        }, 3000);
        
        mainHandler.post(() -> 
            Toast.makeText(this, "🤖 AI Agent connected!", Toast.LENGTH_LONG).show()
        );
    }
    
    private void enableFullAudioMode() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, 
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            
            Log.d(TAG, "Full audio mode enabled");
        } catch (Exception e) {
            Log.e(TAG, "Audio mode error: " + e.getMessage());
        }
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
