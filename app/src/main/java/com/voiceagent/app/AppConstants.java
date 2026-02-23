package com.voiceagent.app;

public class AppConstants {
    
    // API Configuration
    public static final String GROQ_API_BASE_URL = "https://api.groq.com/openai/v1/chat/completions";
    public static final String GROQ_MODEL = "llama-3.1-8b-instant";
    
    // API Timeouts (milliseconds)
    public static final int API_CONNECT_TIMEOUT = 15000;
    public static final int API_READ_TIMEOUT = 20000;
    
    // Retry Configuration
    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final long RETRY_DELAY_MS = 1000;
    public static final long RETRY_DELAY_MULTIPLIER = 2;
    
    // Call Configuration
    public static final int MAX_CALL_DURATION_MINUTES = 10;
    public static final int CALL_DURATION_WARNING_MINUTES = 8;
    
    // Speech Recognition
    public static final int SPEECH_TIMEOUT_MS = 6000;
    public static final int MIN_SPEECH_LENGTH_MS = 1500;
    public static final int SILENCE_THRESHOLD_MS = 3000;
    public static final int MAX_SPEECH_RESULTS = 3;
    
    // UI Update Delays
    public static final int TTS_START_DELAY_MS = 800;
    public static final int LISTEN_START_DELAY_MS = 500;
    public static final int CALL_TRANSFER_DELAY_MS = 1000;
    public static final int GREETING_DELAY_MS = 1500;
    public static final int FIRST_LISTEN_DELAY_MS = 4000;
    
    // Audio Configuration
    public static final float DEFAULT_SPEECH_RATE = 1.0f;
    public static final float DEFAULT_PITCH = 1.0f;
    public static final int VOLUME_MAX_PERCENTAGE = 100;
    
    // Notification IDs
    public static final int NOTIFICATION_ID_INCOMING_CALL = 1001;
    public static final int NOTIFICATION_ID_FOREGROUND = 1002;
    public static final int NOTIFICATION_ID_ERROR = 1003;
    
    // Notification Channel
    public static final String CHANNEL_ID_CALLS = "voice_agent_call_channel";
    public static final String CHANNEL_ID_ERRORS = "voice_agent_error_channel";
    
    // Permission Codes
    public static final int PERMISSION_CODE = 100;
    public static final int PERMISSION_REQUEST_CODE = 101;
    
    // Intent Actions
    public static final String ACTION_ANSWER = "com.voiceagent.app.ACTION_ANSWER";
    public static final String ACTION_REJECT = "com.voiceagent.app.ACTION_REJECT";
    public static final String ACTION_TRANSFER = "com.voiceagent.app.TRANSFER";
    public static final String ACTION_STOP = "com.voiceagent.app.STOP";
    public static final String ACTION_START = "com.voiceagent.app.START";
    
    // Intent Extras
    public static final String EXTRA_PHONE_NUMBER = "phone_number";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";
    
    // Preferences
    public static final String PREF_NAME = "voice_agent_prefs";
    public static final String PREF_LANGUAGE = "preferred_language";
    public static final String PREF_AUTO_ANSWER = "auto_answer";
    public static final String PREF_API_KEY_CONFIGURED = "api_key_configured";
    public static final String PREF_FIRST_RUN = "first_run";
    
    // Languages
    public static final String LANGUAGE_ENGLISH = "en";
    public static final String LANGUAGE_BENGALI = "bn";
    public static final String LANGUAGE_BENGALI_BD = "bn-BD";
    
    // TTS
    public static final String TTS_UTTERANCE_ID = "voice_agent_utterance";
}
