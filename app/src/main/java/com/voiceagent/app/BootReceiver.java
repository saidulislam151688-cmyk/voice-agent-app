package com.voiceagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        Log.d(TAG, "Boot action: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.d(TAG, "Starting Voice Agent service after boot");
            
            Intent serviceIntent = new Intent(context, CallMonitorService.class);
            context.startService(serviceIntent);
        }
    }
}
