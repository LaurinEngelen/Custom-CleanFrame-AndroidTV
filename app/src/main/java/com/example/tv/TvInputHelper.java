package com.example.tv;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;
import android.widget.Toast;

public class TvInputHelper {

    public static final String INPUT_HDMI1 = "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5";
    public static final String INPUT_HDMI2 = "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6";
    public static final String INPUT_HDMI3 = "com.droidlogic.tvinput/.services.Hdmi3InputService/HW7";
    public static final String INPUT_AV = "com.droidlogic.tvinput/.services.AV1InputService/HW1";
    public static final String INPUT_LIVETV = "com.droidlogic.tvinput/.services.ADTVInputService/HW16";

    public static void switchToInput(Context context, String inputId, String displayName) {
        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            // Fallback for live TV or general TV app
            Intent fallback = context.getPackageManager().getLaunchIntentForPackage("com.android.tv");
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } else {
                Toast.makeText(context, "Cannot switch to " + displayName, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
