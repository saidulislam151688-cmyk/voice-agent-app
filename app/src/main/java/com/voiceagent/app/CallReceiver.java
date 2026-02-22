package com.voiceagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.TelephonyManager;

public class CallReceiver extends BroadcastReceiver {

    public static CallListener listener;

    public interface CallListener {
        void onIncomingCall(String number);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String state = intent.getStringExtra(TelephonyManager.EXTRA_CALL_STATE);
        
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

            if (listener != null) {
                listener.onIncomingCall(number != null ? number : "Unknown");
            }
        }
    }

    public static void setListener(CallListener l) {
        listener = l;
    }
}
