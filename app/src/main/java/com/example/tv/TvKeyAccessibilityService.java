package com.example.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

public class TvKeyAccessibilityService extends AccessibilityService {

    private static final String SPOTIFY_PKG = "com.spotify.tv.android";
    private static final String BACKDROP_PKG = "com.google.android.backdrop";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View keepAwakeOverlay;
    private AudioManager audioManager;
    private String currentForegroundPackage = "";
    private boolean isKeepAwakeActive = false;

    private final Runnable musicPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (SPOTIFY_PKG.equals(currentForegroundPackage)) {
                evaluateKeepAwake();
                handler.postDelayed(this, 2500);
            } else {
                applyKeepAwake(false);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        TvInputHelper.ensureGlobalKeyWatcherStarted(this);
        SpotifyKeepAwakeManager.init(this);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        TvInputHelper.ensureGlobalKeyWatcherStarted(this);
        SpotifyKeepAwakeManager.init(this);
        checkAndLaunchHomeOnBoot();

        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null) {
                audioManager.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                        super.onPlaybackConfigChanged(configs);
                        evaluateKeepAwake();
                    }
                }, handler);
            }
        } catch (Throwable ignored) {}
    }

    private void checkAndLaunchHomeOnBoot() {
        handler.postDelayed(() -> {
            try {
                CleanConfig config = CleanConfig.load(this);
                if (!"off".equals(config.autostart)) {
                    Intent home = new Intent(this, MainActivity.class);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(home);
                }
            } catch (Throwable ignored) {}
        }, 1200);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null) {
                String newPkg = pkg.toString();

                // If Google Screensaver tries to open while Spotify is actively playing
                if (BACKDROP_PKG.equals(newPkg) && SPOTIFY_PKG.equals(currentForegroundPackage)) {
                    CleanConfig config = CleanConfig.load(this);
                    if ("on".equals(config.spotifyscreensaver) && isSpotifyMusicActive()) {
                        wakeUpAndRestoreSpotify();
                        applyKeepAwake(true);
                        return;
                    }
                }

                currentForegroundPackage = newPkg;
                if (SPOTIFY_PKG.equals(currentForegroundPackage)) {
                    handler.removeCallbacks(musicPollRunnable);
                    handler.post(musicPollRunnable);
                } else {
                    handler.removeCallbacks(musicPollRunnable);
                    applyKeepAwake(false);
                }
            }
        }
    }

    private boolean isSpotifyMusicActive() {
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            return audioManager != null && audioManager.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    private void evaluateKeepAwake() {
        try {
            CleanConfig config = CleanConfig.load(this);
            if (!"on".equals(config.spotifyscreensaver)) {
                applyKeepAwake(false);
                return;
            }

            boolean isSpotify = SPOTIFY_PKG.equals(currentForegroundPackage);
            boolean isPlaying = isSpotifyMusicActive();

            applyKeepAwake(isSpotify && isPlaying);
        } catch (Throwable ignored) {}
    }

    private void applyKeepAwake(boolean enable) {
        if (enable == isKeepAwakeActive) return;
        isKeepAwakeActive = enable;

        handler.post(() -> {
            try {
                if (windowManager == null) {
                    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                }
                if (windowManager == null) return;

                if (enable) {
                    if (keepAwakeOverlay == null) {
                        keepAwakeOverlay = new View(this);
                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                1, 1,
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                                PixelFormat.TRANSLUCENT
                        );
                        params.gravity = Gravity.TOP | Gravity.START;
                        params.x = 0;
                        params.y = 0;
                        windowManager.addView(keepAwakeOverlay, params);
                    }
                } else {
                    if (keepAwakeOverlay != null) {
                        windowManager.removeView(keepAwakeOverlay);
                        keepAwakeOverlay = null;
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    private void wakeUpAndRestoreSpotify() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(SPOTIFY_PKG);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        applyKeepAwake(false);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // Home Button (KEYCODE_HOME = 3)
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == 3) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                CleanConfig config = CleanConfig.load(this);
                if (!"off".equals(config.autostart)) {
                    Intent home = new Intent(this, MainActivity.class);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(home);
                    return true;
                }
            }
        }

        // TV Input / Source Button (178 = KEYCODE_TV_INPUT)
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == 178 || keyCode == 182 || keyCode == 170) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                TvInputHelper.showInputPicker(this);
            }
            return true;
        }

        // Settings Button on Remote (176 = KEYCODE_SETTINGS)
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == 176) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                openTvSettings();
            }
            return true;
        }

        return super.onKeyEvent(event);
    }

    private void openTvSettings() {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable ignored) {}
        }
    }
}
