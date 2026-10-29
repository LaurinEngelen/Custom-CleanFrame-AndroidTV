package com.example.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CleanBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Received boot event: " + action);

        CleanConfig config = CleanConfig.load(context);
        if ("off".equals(config.autostart)) {
            Log.d(TAG, "Autostart is turned off in config");
            return;
        }

        try {
            Intent home = new Intent(context, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(home);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start MainActivity on boot", t);
        }
    }
}
