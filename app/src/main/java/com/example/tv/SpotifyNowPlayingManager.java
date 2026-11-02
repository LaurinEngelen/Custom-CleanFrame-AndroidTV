package com.example.tv;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class SpotifyNowPlayingManager {

    public static final String SPOTIFY_PKG = "com.spotify.tv.android";

    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile Bitmap albumArt = null;
    public static volatile boolean isPlaying = false;
    public static volatile long trackDurationMs = 0L;
    public static volatile long positionMs = 0L;
    public static volatile long positionUpdateRealtime = 0L;
    public static volatile long lastPlayedTimeMs = 0L;
    private static volatile String lastArtUri = "";

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable updateCallback = null;
    private static MediaController activeController = null;

    private static final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateFromMetadata(metadata);
            notifyUpdate();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null) {
                isPlaying = (state.getState() == PlaybackState.STATE_PLAYING);
                positionMs = state.getPosition();
                long updateTime = state.getLastPositionUpdateTime();
                positionUpdateRealtime = updateTime > 0 ? updateTime : android.os.SystemClock.elapsedRealtime();
                if (isPlaying) {
                    lastPlayedTimeMs = System.currentTimeMillis();
                }
            }
            notifyUpdate();
        }

        @Override
        public void onSessionDestroyed() {
            activeController = null;
            isPlaying = false;
            notifyUpdate();
        }
    };

    public static void setUpdateCallback(Runnable callback) {
        updateCallback = callback;
    }

    public static boolean hasActiveTrack() {
        return (title != null && !title.isEmpty());
    }

    public static boolean isVisibleOnHome() {
        if (title == null || title.trim().isEmpty()) return false;
        if (isPlaying) {
            lastPlayedTimeMs = System.currentTimeMillis();
            return true;
        }
        if (lastPlayedTimeMs <= 0L) return false;
        long diff = System.currentTimeMillis() - lastPlayedTimeMs;
        return diff >= 0 && diff <= 30000L;
    }

    public static float getProgressFraction() {
        if (trackDurationMs <= 0) return 0f;
        long current = positionMs;
        if (isPlaying && positionUpdateRealtime > 0) {
            long delta = android.os.SystemClock.elapsedRealtime() - positionUpdateRealtime;
            if (delta > 0) {
                current += delta;
            }
        }
        float frac = (float) current / (float) trackDurationMs;
        return Math.max(0f, Math.min(1f, frac));
    }

    public static void init(Context context) {
        refreshSessions(context);
    }

    public static synchronized void refreshSessions(Context context) {
        if (context == null) return;
        try {
            MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;

            ComponentName cn = new ComponentName(context, CleanMediaListenerService.class);
            List<MediaController> controllers = null;
            try {
                controllers = msm.getActiveSessions(cn);
            } catch (Throwable ignored) {}

            MediaController target = null;
            if (controllers != null) {
                // First pass: look specifically for Spotify
                for (MediaController mc : controllers) {
                    if (SPOTIFY_PKG.equals(mc.getPackageName())) {
                        target = mc;
                        break;
                    }
                }
                // Second pass: fallback to any playing media controller
                if (target == null) {
                    for (MediaController mc : controllers) {
                        PlaybackState ps = mc.getPlaybackState();
                        if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                            target = mc;
                            break;
                        }
                    }
                }
                // Third pass: fallback to any active session if available
                if (target == null && !controllers.isEmpty()) {
                    target = controllers.get(0);
                }
            }

            if (target != activeController) {
                if (activeController != null) {
                    try {
                        activeController.unregisterCallback(controllerCallback);
                    } catch (Throwable ignored) {}
                }
                activeController = target;
                if (activeController != null) {
                    activeController.registerCallback(controllerCallback, handler);
                }
            }

            if (activeController != null) {
                PlaybackState ps = activeController.getPlaybackState();
                if (ps != null) {
                    isPlaying = (ps.getState() == PlaybackState.STATE_PLAYING);
                    positionMs = ps.getPosition();
                    long updateTime = ps.getLastPositionUpdateTime();
                    positionUpdateRealtime = updateTime > 0 ? updateTime : android.os.SystemClock.elapsedRealtime();
                    if (isPlaying) {
                        lastPlayedTimeMs = System.currentTimeMillis();
                    }
                }
                updateFromMetadata(activeController.getMetadata());
            }

            notifyUpdate();
        } catch (Throwable ignored) {}
    }

    public static void onNotificationUpdated(Context context, StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (SPOTIFY_PKG.equals(pkg)) {
            Notification n = sbn.getNotification();
            if (n != null && n.extras != null) {
                Bundle extras = n.extras;
                CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence a = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (t != null && t.length() > 0) {
                    title = t.toString().trim();
                }
                if (a != null && a.length() > 0) {
                    artist = a.toString().trim();
                }
                Bitmap b = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (b != null && !b.isRecycled()) {
                    scaleAndSetAlbumArt(b);
                } else {
                    Icon icon = extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG);
                    if (icon != null && context != null) {
                        try {
                            android.graphics.drawable.Drawable d = icon.loadDrawable(context);
                            if (d != null) {
                                Bitmap drawn = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                                android.graphics.Canvas canvas = new android.graphics.Canvas(drawn);
                                d.setBounds(0, 0, 128, 128);
                                d.draw(canvas);
                                albumArt = drawn;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                notifyUpdate();
            }
        }
        refreshSessions(context);
    }

    private static void updateFromMetadata(MediaMetadata mm) {
        if (mm == null) return;
        try {
            long dur = mm.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (dur > 0) {
                trackDurationMs = dur;
            }
            String t = mm.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t != null && !t.isEmpty()) {
                title = t.trim();
            }

            String a = mm.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null || a.isEmpty()) {
                a = mm.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            if (a != null && !a.isEmpty()) {
                artist = a.trim();
            }

            Bitmap art = mm.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = mm.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (art == null) {
                art = mm.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }

            if (art != null && !art.isRecycled()) {
                scaleAndSetAlbumArt(art);
            } else {
                String uriStr = mm.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                if (uriStr == null || uriStr.isEmpty()) {
                    uriStr = mm.getString(MediaMetadata.METADATA_KEY_ART_URI);
                }
                if (uriStr != null && uriStr.startsWith("http") && !uriStr.equals(lastArtUri)) {
                    lastArtUri = uriStr;
                    fetchImageAsync(uriStr);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void scaleAndSetAlbumArt(Bitmap original) {
        if (original == null || original.isRecycled()) return;
        try {
            int w = original.getWidth();
            int h = original.getHeight();
            int maxDim = 144;
            if (w > maxDim || h > maxDim) {
                float scale = Math.min((float) maxDim / w, (float) maxDim / h);
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                albumArt = Bitmap.createScaledBitmap(original, nw, nh, true);
            } else {
                albumArt = original;
            }
        } catch (Throwable t) {
            albumArt = original;
        }
    }

    private static void fetchImageAsync(final String urlStr) {
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoInput(true);
                conn.connect();
                try (InputStream is = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (bmp != null) {
                        handler.post(() -> {
                            scaleAndSetAlbumArt(bmp);
                            notifyUpdate();
                        });
                    }
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    private static void notifyUpdate() {
        handler.post(() -> {
            if (updateCallback != null) {
                updateCallback.run();
            }
        });
    }
}
