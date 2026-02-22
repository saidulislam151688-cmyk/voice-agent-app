package com.voiceagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class CallActionReceiver extends BroadcastReceiver {

    private static final String TAG = "CallActionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        String phoneNumber = intent.getStringExtra("phone_number");
        
        Log.d(TAG, "Action received: " + action + ", number: " + phoneNumber);
        
        if (CallMonitorService.ACTION_ANSWER.equals(action)) {
            // Start the service and answer call
            Intent serviceIntent = new Intent(context, CallMonitorService.class);
            context.startService(serviceIntent);
            
            // Also start MainActivity to handle the conversation
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.setAction(MainActivity.ACTION_TRANSFER_CALL);
            mainIntent.putExtra("phone_number", phoneNumber);
            mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(mainIntent);
            
            Log.d(TAG, "Call transfer initiated");
        } 
        else if (CallMonitorService.ACTION_REJECT.equals(action)) {
            // Reject the call
            Intent serviceIntent = new Intent(context, CallMonitorService.class);
            context.startService(serviceIntent);
            Log.d(TAG, "Call rejected");
        }
    }
}
