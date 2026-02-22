package com.voiceagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";
    public static CallListener listener;

    public interface CallListener {
        void onIncomingCall(String number);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.e(TAG, "Intent is null");
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);

        if ("android.intent.action.PHONE_STATE".equals(action)) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            Log.d(TAG, "Phone state: " + state);
            
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                String phoneNumber = number != null ? number : "Unknown";
                
                Log.d(TAG, "=== INCOMING CALL ===");
                Log.d(TAG, "Number: " + phoneNumber);
                
                if (listener != null) {
                    listener.onIncomingCall(phoneNumber);
                }
            } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                Log.d(TAG, "Call answered");
            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                Log.d(TAG, "Call idle");
            }
        }
    }

    public static void setListener(CallListener l) {
        listener = l;
    }
}
