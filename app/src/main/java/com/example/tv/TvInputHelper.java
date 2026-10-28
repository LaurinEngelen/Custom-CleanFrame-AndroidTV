package com.example.tv;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

public class TvInputHelper {

    private static final String TAG = "TvInputHelper";
    public static final String INPUT_HDMI1 = "HDMI1";
    public static final String INPUT_HDMI2 = "HDMI2";
    public static final String INPUT_HDMI3 = "HDMI3";
    public static final String INPUT_AV = "AV";
    public static final String INPUT_TUNER = "TUNER";

    private static final AtomicBoolean watcherStarted = new AtomicBoolean(false);
    private static volatile long lastInputTriggerMs = 0L;

    public static void ensureGlobalKeyWatcherStarted(Context context) {
        if (!watcherStarted.compareAndSet(false, true)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        Thread t = new Thread(() -> {
            while (true) {
                Process proc = null;
                try {
                    long startMs = System.currentTimeMillis();
                    proc = Runtime.getRuntime().exec(new String[]{
                            "logcat", "-T", "1", "-v", "brief", "GlobalKey:V", "*:S"
                    });
                    BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        long now = System.currentTimeMillis();
                        // Ignore historical line emitted immediately on startup by -T 1
                        if (now - startMs < 450) {
                            continue;
                        }
                        if (line.contains("receive KEYCODE_TV_INPUT") || line.contains("keyCode = 178")) {
                            if (now - lastInputTriggerMs > 450) {
                                lastInputTriggerMs = now;
                                Log.i(TAG, "Intercepted physical TV_INPUT key via GlobalKey: " + line);
                                showInputPicker(appContext);
                            }
                        }
                    }
                } catch (Throwable err) {
                    Log.w(TAG, "GlobalKey log watcher error", err);
                } finally {
                    if (proc != null) {
                        try {
                            proc.destroy();
                        } catch (Throwable ignored) {}
                    }
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
            }
        }, "GlobalKeyInputWatcher");
        t.setDaemon(true);
        t.start();
    }

    public static void switchToInput(Context context, String inputId, String displayName) {
        try {
            String hwInputId = null;
            if ("HDMI1".equalsIgnoreCase(inputId) || "input_hdmi1".equalsIgnoreCase(inputId)) {
                hwInputId = "com.mediatek.tvinput/.hdmi.HDMIInputService/HW2";
            } else if ("HDMI2".equalsIgnoreCase(inputId)) {
                hwInputId = "com.mediatek.tvinput/.hdmi.HDMIInputService/HW3";
            } else if ("HDMI3".equalsIgnoreCase(inputId)) {
                hwInputId = "com.mediatek.tvinput/.hdmi.HDMIInputService/HW4";
            } else if ("AV".equalsIgnoreCase(inputId)) {
                hwInputId = "com.mediatek.tvinput/.composite.CompositeInputService/HW1";
            } else if (inputId != null && inputId.contains("/")) {
                hwInputId = inputId;
            }

            if (hwInputId != null) {
                Uri uri = TvContract.buildChannelUriForPassthroughInput(hwInputId);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }

            Intent intent = new Intent();
            intent.setClassName("com.mediatek.wwtv.tvcenter", "com.mitv.livetv.nav.TurnkeyUiMainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = context.getPackageManager().getLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return;
                }
            } catch (Throwable ignored) {}
            Toast.makeText(context, "Eingang konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show();
        }
    }

    public static void showInputPicker(Context context) {
        try {
            Intent intent = new Intent(context, InputPickerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Throwable t) {
            switchToInput(context, INPUT_TUNER, "Live TV");
        }
    }
}
