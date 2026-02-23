package com.voiceagent.app;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppLogger {
    
    private static final String TAG = "VoiceAgent";
    private static final String LOG_FILE_NAME = "voice_agent_log.txt";
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB
    
    private static ExecutorService logExecutor;
    private static File logFile;
    private static boolean isInitialized = false;
    
    public static void init(Context context) {
        if (isInitialized) return;
        
        logExecutor = Executors.newSingleThreadExecutor();
        
        File logDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (logDir == null) {
            logDir = context.getFilesDir();
        }
        
        logFile = new File(logDir, LOG_FILE_NAME);
        
        // Rotate log if too large
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
            rotateLog();
        }
        
        isInitialized = true;
        log(TAG, "=== Logger Initialized ===");
    }
    
    private static void rotateLog() {
        try {
            File archivedLog = new File(logFile.getParent(), "voice_agent_log_old.txt");
            if (archivedLog.exists()) {
                archivedLog.delete();
            }
            logFile.renameTo(archivedLog);
        } catch (Exception e) {
            Log.e(TAG, "Error rotating log: " + e.getMessage());
        }
    }
    
    public static void d(String message) {
        Log.d(TAG, message);
        log(TAG, "DEBUG: " + message);
    }
    
    public static void i(String message) {
        Log.i(TAG, message);
        log(TAG, "INFO: " + message);
    }
    
    public static void w(String message) {
        Log.w(TAG, message);
        log(TAG, "WARN: " + message);
    }
    
    public static void e(String message) {
        Log.e(TAG, message);
        log(TAG, "ERROR: " + message);
    }
    
    public static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        log(TAG, "ERROR: " + message);
        logException(throwable);
    }
    
    private static void log(final String tag, final String message) {
        if (!isInitialized || logExecutor == null) return;
        
        logExecutor.execute(() -> {
            try {
                if (logFile == null) return;
                
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                String timestamp = sdf.format(new Date());
                String logLine = String.format("[%s] [%s] %s\n", timestamp, tag, message);
                
                FileWriter fw = new FileWriter(logFile, true);
                fw.append(logLine);
                fw.flush();
                fw.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write log: " + e.getMessage());
            }
        });
    }
    
    private static void logException(final Throwable throwable) {
        if (!isInitialized || logExecutor == null || logFile == null) return;
        
        logExecutor.execute(() -> {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                String timestamp = sdf.format(new Date());
                
                PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
                pw.println(String.format("[%s] [EXCEPTION]", timestamp));
                throwable.printStackTrace(pw);
                pw.println();
                pw.flush();
                pw.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write exception: " + e.getMessage());
            }
        });
    }
    
    public static File getLogFile() {
        return logFile;
    }
    
    public static String getLogContent() {
        if (logFile == null || !logFile.exists()) {
            return "No log file found";
        }
        
        try {
            int size = (int) logFile.length();
            if (size > 1024 * 1024) {
                // Return last 1MB if log is large
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r");
                raf.seek(size - 1024 * 1024);
                byte[] buffer = new byte[1024 * 1024];
                int bytesRead = raf.read(buffer);
                raf.close();
                return new String(buffer, 0, bytesRead);
            }
            
            java.io.FileInputStream fis = new java.io.FileInputStream(logFile);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(fis));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            return content.toString();
        } catch (Exception e) {
            return "Error reading log: " + e.getMessage();
        }
    }
    
    public static void clearLog() {
        if (logFile != null && logFile.exists()) {
            logFile.delete();
            log(TAG, "Log cleared");
        }
    }
}
