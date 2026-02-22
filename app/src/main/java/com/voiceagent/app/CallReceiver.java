package com.voiceagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.TelecomManager;

public class CallReceiver extends BroadcastReceiver {

    public static CallListener listener;

    public interface CallListener {
        void onIncomingCall(String number);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String state = intent.getStringExtra(TelecomManager.EXTRA_CALL_STATE);

        if (TelecomManager.EXTRA_CALL_STATE_RINGING.equals(state)) {
            String number = intent.getStringExtra(TelecomManager.EXTRA_INCOMING_NUMBER);

            if (listener != null) {
                listener.onIncomingCall(number != null ? number : "Unknown");
            }
        }
    }

    public static void setListener(CallListener l) {
        listener = l;
    }
}
