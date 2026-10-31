package com.example.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;

public class SpotifyKeepAwakeManager {

    private static final String SPOTIFY_PKG = "com.spotify.tv.android";
    private static SpotifyKeepAwakeManager instance;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final ActivityManager activityManager;
    private PowerManager.WakeLock wakeLock;
    private boolean isMonitoring = false;

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            evaluateState();
            if (isMonitoring) {
                handler.postDelayed(this, 3000);
            }
        }
    };

    public static synchronized void init(Context context) {
        if (instance == null && context != null) {
            instance = new SpotifyKeepAwakeManager(context.getApplicationContext());
            instance.startMonitoring();
        }
    }

    private SpotifyKeepAwakeManager(Context context) {
        this.appContext = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // SCREEN_BRIGHT_WAKE_LOCK keeps the TV screen active and inhibits DreamService
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                        "CleanScreen:SpotifyKeepAwake"
                );
                wakeLock.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null) {
            try {
                audioManager.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                        super.onPlaybackConfigChanged(configs);
                        evaluateState();
                    }
                }, handler);
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void startMonitoring() {
        if (isMonitoring) return;
        isMonitoring = true;
        handler.removeCallbacks(checkRunnable);
        handler.post(checkRunnable);
    }

    public synchronized void stopMonitoring() {
        isMonitoring = false;
        handler.removeCallbacks(checkRunnable);
        releaseWakeLock();
    }

    public void evaluateState() {
        try {
            CleanConfig config = CleanConfig.load(appContext);
            if (!"on".equals(config.spotifyscreensaver)) {
                releaseWakeLock();
                return;
            }

            boolean isSpotifyTop = isSpotifyForeground();
            boolean isPlaying = audioManager != null && audioManager.isMusicActive();

            if (isSpotifyTop && isPlaying) {
                acquireWakeLock();
            } else {
                releaseWakeLock();
            }
        } catch (Throwable ignored) {}
    }

    private boolean isSpotifyForeground() {
        try {
            if (activityManager != null) {
                List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo proc : processes) {
                        if (SPOTIFY_PKG.equals(proc.processName)
                                && proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private synchronized void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }
}
