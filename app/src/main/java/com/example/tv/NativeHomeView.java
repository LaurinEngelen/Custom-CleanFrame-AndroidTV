package com.example.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NativeHomeView extends View {

    public static final int SCREEN_HOME = 0;
    public static final int SCREEN_SETTINGS = 1;
    public static final int SCREEN_DETAIL = 2;
    public static final int SCREEN_WALLPAPER = 3;
    public static final int SCREEN_EDITOR = 4;
    public static final int SCREEN_ART = 5;

    private static final int ZONE_APPS = 0;
    private static final int ZONE_UTILS = 1;

    private MainActivity activity;
    private CleanConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int screen = SCREEN_HOME;
    private int homeZone = ZONE_APPS;
    private int appIndex = 0;
    private int utilIndex = 0;
    private boolean arrangeMode = false;

    // Settings state
    private int settingsRow = 0;
    private String currentDetailGroup = "layout";
    private int detailRow = 0;
    private int detailCol = 0;
    private int wallPickerIndex = 0;
    private int wallPickerScroll = 0;

    // Editor state
    private int editorZone = 0; // 0 = Home apps, 1 = All apps
    private int editorHomeIdx = 0;
    private int editorAllIdx = 0;
    private int editorHomeScroll = 0;
    private int editorAllScroll = 0;
    private boolean editorLifted = false;
    private boolean allAppsLaunchMode = false;

    // Art Mode state
    private int artWallIdx = 0;
    private long artCaptionUntilMs = 0L;

    // Smooth animation state (60fps spring/lerp)
    private float animScrollX = 0f;
    private float targetScrollX = 0f;
    private float animFocusX = 112f;
    private float targetFocusX = 112f;
    private float animFocusW = 240f;
    private float targetFocusW = 240f;
    private boolean focusInitialized = false;

    // Long-press detection for OK key to enter Arrange Mode
    private boolean okDown = false;
    private boolean okLongHandled = false;
    private final Runnable longPressRunnable = () -> {
        if (okDown && screen == SCREEN_HOME && homeZone == ZONE_APPS) {
            okLongHandled = true;
            arrangeMode = !arrangeMode;
            invalidate();
        }
    };

    // Idle timer for Art Mode (3 minutes)
    private final Runnable idleArtRunnable = () -> {
        if (screen == SCREEN_HOME && !"off".equals(config.artmode)) {
            enterArtMode(false);
        }
    };

    // Clock ticker
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, 15000);
        }
    };

    // Reusable paints & geometry
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final Path clipPath = new Path();
    private LinearGradient bottomScrimGradient = null;
    private LinearGradient topScrimGradient = null;

    public NativeHomeView(MainActivity activity, CleanConfig config) {
        super(activity);
        this.activity = activity;
        this.config = config;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        if ("open".equals(config.mode) && "photo".equals(config.bg)) {
            int nextIdx = (CleanConfig.getWallIndex(config.wall) + 1) % CleanConfig.WALLS.length;
            config.wall = CleanConfig.WALLS[nextIdx].id;
            config.save(activity);
        }

        // Preload current wallpaper & material on background thread
        preloadWallpaperAsync(config.wall);
        resetIdleTimer();
        handler.postDelayed(clockTicker, 15000);
    }

    private void preloadWallpaperAsync(final String wallId) {
        new Thread(() -> {
            CleanAssets.getWallpaper(activity, wallId);
            CleanAssets.getMaterial(activity, wallId, config.material);
            postInvalidate();
        }).start();
    }

    public void onAppsLoaded() {
        postInvalidate();
    }

    public void resetIdleTimer() {
        handler.removeCallbacks(idleArtRunnable);
        if (!"off".equals(config.artmode)) {
            handler.postDelayed(idleArtRunnable, 180000);
        }
    }

    public void enterArtMode(boolean manual) {
        if ("off".equals(config.artmode)) return;
        artWallIdx = CleanConfig.getWallIndex(config.wall);
        screen = SCREEN_ART;
        artCaptionUntilMs = System.currentTimeMillis() + 4500L;
        if (manual) {
            activity.setKeepScreenOnFlag(true);
        }
        invalidate();
    }

    public void exitArtMode() {
        screen = SCREEN_HOME;
        activity.setKeepScreenOnFlag(false);
        resetIdleTimer();
        invalidate();
    }

    private List<String> getUtilityItems() {
        List<String> list = new ArrayList<>(3);
        if (!"off".equals(config.artmode)) {
            list.add("art");
        }
        list.add("apps");
        list.add("settings");
        return list;
    }

    private List<MainActivity.NativeAppEntry> getHomeAppEntries() {
        List<MainActivity.NativeAppEntry> list = new ArrayList<>();
        for (String pkg : config.homeApps) {
            MainActivity.NativeAppEntry entry = activity.getAppEntry(pkg);
            if (entry != null) {
                list.add(entry);
            }
        }
        if (list.isEmpty()) {
            for (String pkg : CleanConfig.DEFAULT_HOME_APPS) {
                MainActivity.NativeAppEntry entry = activity.getAppEntry(pkg);
                if (entry != null) list.add(entry);
            }
        }
        return list;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        canvas.save();
        float sx = vw / 1920f;
        float sy = vh / 1080f;
        canvas.scale(sx, sy);

        Typeface tf = CleanAssets.getFont(activity, config.font);
        textPaint.setTypeface(tf);

        boolean isLightBg = "light".equals(config.bg);
        int inkColor = isLightBg ? Color.parseColor("#16181A") : Color.WHITE;
        int muteColor = isLightBg ? Color.parseColor("#6E737B") : Color.parseColor("#B8C0CC");

        // 1. Draw Background
        drawBackgroundLayer(canvas);

        if (screen == SCREEN_ART) {
            drawArtModeOverlay(canvas);
            canvas.restore();
            return;
        }

        // 2. Draw Top Bar (Clock, Caption, Top-Right Utility Buttons)
        drawTopBar(canvas, inkColor, muteColor);

        // 3. Draw App Carousel (Dock) or Left List
        boolean needsMoreFrames;
        if ("list".equals(config.layout)) {
            needsMoreFrames = drawListMode(canvas, inkColor, muteColor);
        } else {
            needsMoreFrames = drawDockCarousel(canvas, inkColor, muteColor);
        }

        // 4. Draw Active Modal / Side Panel (Settings, Detail, Wallpaper Picker, Apps Editor)
        if (screen == SCREEN_SETTINGS) {
            drawSettingsSheet(canvas);
        } else if (screen == SCREEN_DETAIL) {
            drawDetailSheet(canvas);
        } else if (screen == SCREEN_WALLPAPER) {
            drawWallpaperPickerSheet(canvas);
        } else if (screen == SCREEN_EDITOR) {
            drawEditorSheet(canvas);
        }

        canvas.restore();

        if (needsMoreFrames) {
            postInvalidateOnAnimation();
        }
    }

    private void drawBackgroundLayer(Canvas canvas) {
        if ("light".equals(config.bg)) {
            canvas.drawColor(Color.parseColor("#F3EFEA"));
            return;
        }
        if ("dark".equals(config.bg)) {
            canvas.drawColor(Color.parseColor("#0D0F12"));
            return;
        }

        String wallId = (screen == SCREEN_ART) ? CleanConfig.WALLS[artWallIdx].id : config.wall;
        Bitmap wallBmp = CleanAssets.getWallpaper(activity, wallId);
        if (wallBmp != null && !wallBmp.isRecycled()) {
            srcRect.set(0, 0, wallBmp.getWidth(), wallBmp.getHeight());
            dstRect.set(0, 0, 1920, 1080);
            paint.setAlpha(255);
            canvas.drawBitmap(wallBmp, srcRect, dstRect, paint);
        } else {
            canvas.drawColor(Color.parseColor("#12151A"));
            preloadWallpaperAsync(wallId);
        }

        if (screen != SCREEN_ART) {
            if (bottomScrimGradient == null) {
                bottomScrimGradient = new LinearGradient(
                        0, 520, 0, 1080,
                        new int[]{0x00000000, 0x44000000, 0x99000000},
                        new float[]{0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP
                );
            }
            if (topScrimGradient == null) {
                topScrimGradient = new LinearGradient(
                        0, 0, 0, 220,
                        new int[]{0x66000000, 0x00000000},
                        null,
                        Shader.TileMode.CLAMP
                );
            }
            paint.setShader(topScrimGradient);
            canvas.drawRect(0, 0, 1920, 220, paint);
            paint.setShader(bottomScrimGradient);
            canvas.drawRect(0, 520, 1920, 1080, paint);
            paint.setShader(null);
        }
    }

    private void drawArtModeOverlay(Canvas canvas) {
        long now = System.currentTimeMillis();
        if (now < artCaptionUntilMs) {
            CleanConfig.WallInfo w = CleanConfig.WALLS[artWallIdx];
            paint.setColor(0xCC101216);
            dstRect.set(112, 880, 920, 996);
            canvas.drawRoundRect(dstRect, 24, 24, paint);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(34f);
            canvas.drawText(w.name, 148, 932, textPaint);

            textPaint.setColor(0xFFB0B8C4);
            textPaint.setTextSize(22f);
            canvas.drawText("The Frame Art Gallery  ·  ◄ ► Kunstwerk wechseln  ·  OK zum Beenden", 148, 972, textPaint);
        }
    }

    private void drawTopBar(Canvas canvas, int inkColor, int muteColor) {
        // Top-Left Clock + Wallpaper Title
        String timeStr = clockFmt.format(new Date());
        textPaint.setColor(inkColor);
        textPaint.setTextSize(44f);
        canvas.drawText(timeStr, 112f, 106f, textPaint);

        if ("photo".equals(config.bg)) {
            float timeW = textPaint.measureText(timeStr);
            CleanConfig.WallInfo wi = CleanConfig.getWallInfo(config.wall);
            textPaint.setColor(muteColor);
            textPaint.setTextSize(26f);
            canvas.drawText("·   " + wi.name, 112f + timeW + 20f, 102f, textPaint);
        }

        // Top-Right Utility Pills
        List<String> utils = getUtilityItems();
        if (utilIndex >= utils.size()) utilIndex = Math.max(0, utils.size() - 1);

        boolean showIcons = !"text".equals(config.controls);
        boolean showText = !"icons".equals(config.controls);
        float rightEdge = 1808f;
        float topY = 64f;
        float pillH = 60f;
        float gap = 14f;

        // Measure total width from right to left
        float[] widths = new float[utils.size()];
        String[] labels = new String[utils.size()];
        textPaint.setTextSize(26f);
        for (int i = 0; i < utils.size(); i++) {
            String u = utils.get(i);
            labels[i] = "art".equals(u) ? "Art Mode" : "apps".equals(u) ? "Alle Apps" : "Einstellungen";
            if (showIcons && showText) {
                widths[i] = 36f + textPaint.measureText(labels[i]) + 52f;
            } else if (showText) {
                widths[i] = textPaint.measureText(labels[i]) + 48f;
            } else {
                widths[i] = 60f;
            }
        }

        float totalW = 0f;
        for (int i = 0; i < widths.length; i++) {
            totalW += widths[i] + (i > 0 ? gap : 0f);
        }
        float startX = rightEdge - totalW;

        // Draw subtle glass container around utilities if glass focus is enabled
        if ("glass".equals(config.focus)) {
            dstRect.set(startX - 8f, topY - 6f, rightEdge + 8f, topY + pillH + 6f);
            drawGlassPlate(canvas, dstRect, 36f, false);
        }

        float curX = startX;
        for (int i = 0; i < utils.size(); i++) {
            boolean focused = (screen == SCREEN_HOME && homeZone == ZONE_UTILS && utilIndex == i);
            float w = widths[i];
            dstRect.set(curX, topY, curX + w, topY + pillH);

            int fgColor;
            if (focused) {
                paint.setColor(isLightBg() ? 0xFF16181A : 0xFFFFFFFF);
                canvas.drawRoundRect(dstRect, 30f, 30f, paint);
                fgColor = isLightBg() ? Color.WHITE : Color.parseColor("#111316");
            } else {
                fgColor = inkColor;
            }

            strokePaint.setColor(fgColor);
            textPaint.setColor(fgColor);
            textPaint.setTextSize(26f);

            if (showIcons && !showText) {
                CleanAssets.drawUiIcon(canvas, utils.get(i), curX + w * 0.5f, topY + pillH * 0.5f, 26f, strokePaint);
                if (focused) {
                    // Draw floating label right below the focused icon
                    String lbl = labels[i];
                    float lw = textPaint.measureText(lbl) + 36f;
                    float lx = Math.min(1808f - lw, Math.max(112f, curX + w * 0.5f - lw * 0.5f));
                    RectF lblRect = new RectF(lx, topY + pillH + 14f, lx + lw, topY + pillH + 58f);
                    paint.setColor(0xD814171C);
                    canvas.drawRoundRect(lblRect, 22f, 22f, paint);
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(23f);
                    canvas.drawText(lbl, lx + 18f, topY + pillH + 44f, textPaint);
                }
            } else if (showIcons && showText) {
                CleanAssets.drawUiIcon(canvas, utils.get(i), curX + 32f, topY + pillH * 0.5f, 24f, strokePaint);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float ty = topY + pillH * 0.5f - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(labels[i], curX + 54f, ty, textPaint);
            } else {
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float ty = topY + pillH * 0.5f - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(labels[i], curX + 24f, ty, textPaint);
            }

            curX += w + gap;
        }
    }

    private boolean drawDockCarousel(Canvas canvas, int inkColor, int muteColor) {
        List<MainActivity.NativeAppEntry> apps = getHomeAppEntries();
        int n = apps.size();
        if (n == 0) return false;
        if (appIndex >= n) appIndex = n - 1;
        if (appIndex < 0) appIndex = 0;

        float scale = config.getIconScale();
        boolean iconsOnly = "icons".equals(config.apps);
        boolean textOnly = "text".equals(config.apps);

        float baseTileW = iconsOnly ? 176f : 272f;
        float baseTileH = iconsOnly ? 144f : (textOnly ? 104f : 192f);
        float tileW = Math.round(baseTileW * scale);
        float tileH = Math.round(baseTileH * scale);
        float gap = Math.round(12f * scale);
        float pitch = tileW + gap;

        float dockBottomY;
        if ("center".equals(config.dockpos)) {
            dockBottomY = 540f + tileH * 0.5f;
        } else if ("low".equals(config.dockpos)) {
            dockBottomY = 1036f;
        } else {
            dockBottomY = 984f;
        }
        float dockTopY = dockBottomY - tileH;

        float maxVisibleWidth = 1696f; // 1920 - 2 * 112
        float totalTrackW = n * pitch - gap;
        float trackStartX;
        if (totalTrackW <= maxVisibleWidth) {
            targetScrollX = 0f;
            trackStartX = 112f;
        } else {
            trackStartX = 112f;
            float selectedCenter = appIndex * pitch + tileW * 0.5f;
            float desiredScroll = selectedCenter - maxVisibleWidth * 0.5f;
            float maxScroll = Math.max(0f, totalTrackW - maxVisibleWidth);
            targetScrollX = Math.max(0f, Math.min(maxScroll, desiredScroll));
        }

        targetFocusX = trackStartX + appIndex * pitch - targetScrollX;
        targetFocusW = tileW;

        if (!focusInitialized) {
            animScrollX = targetScrollX;
            animFocusX = targetFocusX;
            animFocusW = targetFocusW;
            focusInitialized = true;
        } else {
            animScrollX += (targetScrollX - animScrollX) * 0.38f;
            animFocusX += (targetFocusX - animFocusX) * 0.38f;
            animFocusW += (targetFocusW - animFocusW) * 0.38f;
            if (Math.abs(targetScrollX - animScrollX) < 0.4f) animScrollX = targetScrollX;
            if (Math.abs(targetFocusX - animFocusX) < 0.4f) animFocusX = targetFocusX;
            if (Math.abs(targetFocusW - animFocusW) < 0.4f) animFocusW = targetFocusW;
        }

        boolean animating = (animScrollX != targetScrollX) || (animFocusX != targetFocusX);

        // Draw Dock Shelf Glass Background if focus == glass
        float shelfRight = Math.min(1808f, trackStartX + totalTrackW + 20f);
        if ("glass".equals(config.focus)) {
            dstRect.set(trackStartX - 20f, dockTopY - 16f, shelfRight, dockBottomY + 16f);
            drawGlassPlate(canvas, dstRect, 36f, false);
        }

        // Draw sliding Focus Pill for the active app tile
        boolean appZoneFocused = (screen == SCREEN_HOME && homeZone == ZONE_APPS);
        if (appZoneFocused || screen != SCREEN_HOME) {
            dstRect.set(animFocusX, dockTopY, animFocusX + animFocusW, dockBottomY);
            if ("glass".equals(config.focus)) {
                drawGlassPlate(canvas, dstRect, 28f, appZoneFocused);
            } else {
                paint.setColor(appZoneFocused ? (isLightBg() ? 0xFF16181A : 0xFFFFFFFF) : 0x33FFFFFF);
                canvas.drawRoundRect(dstRect, 28f, 28f, paint);
            }
        }

        // Clip and draw app tiles
        canvas.save();
        canvas.clipRect(trackStartX - 16f, dockTopY - 30f, 1808f + 16f, dockBottomY + 30f);

        for (int i = 0; i < n; i++) {
            float tx = trackStartX + i * pitch - animScrollX;
            if (tx + tileW < 80f || tx > 1840f) continue;

            MainActivity.NativeAppEntry app = apps.get(i);
            boolean isFocused = appZoneFocused && (i == appIndex);
            float liftY = (isFocused && arrangeMode) ? -14f : 0f;

            int itemTextColor;
            if (isFocused && !"glass".equals(config.focus)) {
                itemTextColor = isLightBg() ? Color.WHITE : Color.parseColor("#111316");
            } else {
                itemTextColor = isFocused ? Color.WHITE : (isLightBg() ? Color.parseColor("#181A1D") : 0xEEFFFFFF);
            }

            float centerX = tx + tileW * 0.5f;
            float iconBox = Math.round((iconsOnly ? 84f : 76f) * scale);
            float iconCenterY = iconsOnly
                    ? (dockTopY + tileH * 0.5f + liftY)
                    : (dockTopY + tileH * 0.38f + liftY);

            if (!textOnly) {
                drawAppIcon(canvas, app, centerX, iconCenterY, iconBox, itemTextColor);
            }

            if (!iconsOnly) {
                textPaint.setColor(itemTextColor);
                textPaint.setTextSize(Math.max(18f, Math.round((textOnly ? 30f : 24f) * scale)));
                String label = ellipsize(app.label, tileW - 28f);
                float tw = textPaint.measureText(label);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = textOnly
                        ? (dockTopY + tileH * 0.5f - (fm.ascent + fm.descent) * 0.5f + liftY)
                        : (dockBottomY - 22f * scale + liftY);
                canvas.drawText(label, centerX - tw * 0.5f, textY, textPaint);
            }
        }
        canvas.restore();

        // In icons-only mode, draw the floating app name centered right above the focused app tile!
        if (iconsOnly && appZoneFocused && appIndex < n) {
            String focusedName = apps.get(appIndex).label;
            if (arrangeMode) {
                focusedName = "◄ ► Verschieben: " + focusedName + " (OK = Platzieren)";
            }
            textPaint.setTextSize(28f);
            float nameW = textPaint.measureText(focusedName);
            float pillW = nameW + 44f;
            float focusCenterX = animFocusX + animFocusW * 0.5f;
            float pillLeft = Math.max(112f, Math.min(1808f - pillW, focusCenterX - pillW * 0.5f));
            float pillBottom = dockTopY - 22f;
            float pillTop = pillBottom - 52f;

            dstRect.set(pillLeft, pillTop, pillLeft + pillW, pillBottom);
            paint.setColor(0xCC12151B);
            canvas.drawRoundRect(dstRect, 26f, 26f, paint);

            textPaint.setColor(Color.WHITE);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float ty = (pillTop + pillBottom) * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(focusedName, pillLeft + 22f, ty, textPaint);
        } else if (arrangeMode && appZoneFocused && appIndex < n) {
            String hint = "◄ ► Position ändern  ·  OK = Speichern  ·  Runter = Vom Homescreen entfernen";
            textPaint.setTextSize(24f);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(hint, 112f, dockTopY - 28f, textPaint);
        }

        return animating;
    }

    private boolean drawListMode(Canvas canvas, int inkColor, int muteColor) {
        List<MainActivity.NativeAppEntry> apps = getHomeAppEntries();
        int n = apps.size();
        if (n == 0) return false;
        if (appIndex >= n) appIndex = n - 1;

        int maxVisible = 6;
        int first = Math.max(0, Math.min(appIndex - 2, n - maxVisible));
        float startY = 220f;
        float rowH = 112f;
        float pitch = 124f;
        boolean appZoneFocused = (screen == SCREEN_HOME && homeZone == ZONE_APPS);

        for (int s = 0; s < Math.min(n, maxVisible); s++) {
            int i = first + s;
            MainActivity.NativeAppEntry app = apps.get(i);
            boolean isFocused = appZoneFocused && (i == appIndex);
            float y = startY + s * pitch;

            dstRect.set(112f, y, 680f, y + rowH);
            if (isFocused) {
                if ("glass".equals(config.focus)) {
                    drawGlassPlate(canvas, dstRect, 28f, true);
                } else {
                    paint.setColor(isLightBg() ? 0xFF16181A : 0xFFFFFFFF);
                    canvas.drawRoundRect(dstRect, 28f, 28f, paint);
                }
            }

            int fg = (isFocused && !"glass".equals(config.focus))
                    ? (isLightBg() ? Color.WHITE : Color.parseColor("#111316"))
                    : inkColor;

            float xOffset = 140f;
            if (!"text".equals(config.apps)) {
                drawAppIcon(canvas, app, xOffset + 36f, y + rowH * 0.5f, 64f, fg);
                xOffset += 92f;
            }
            if (!"icons".equals(config.apps)) {
                textPaint.setColor(fg);
                textPaint.setTextSize(36f);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                canvas.drawText(ellipsize(app.label, 420f), xOffset, y + rowH * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);
            }
        }
        return false;
    }

    private void drawAppIcon(Canvas canvas, MainActivity.NativeAppEntry app, float cx, float cy, float size, int tintColor) {
        if ("clean".equals(config.iconstyle)) {
            CleanAssets.drawCleanBrandMark(canvas, app.packageName, app.label, cx, cy, size, tintColor, textPaint);
            return;
        }
        Bitmap iconBmp = app.iconBitmap;
        if (iconBmp != null && !iconBmp.isRecycled()) {
            float half = size * 0.5f;
            srcRect.set(0, 0, iconBmp.getWidth(), iconBmp.getHeight());
            dstRect.set(cx - half, cy - half, cx + half, cy + half);
            canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(dstRect, size * 0.22f, size * 0.22f, Path.Direction.CW);
            canvas.clipPath(clipPath);
            paint.setAlpha(255);
            canvas.drawBitmap(iconBmp, srcRect, dstRect, paint);
            canvas.restore();
        } else {
            CleanAssets.drawCleanBrandMark(canvas, app.packageName, app.label, cx, cy, size, tintColor, textPaint);
        }
    }

    private void drawGlassPlate(Canvas canvas, RectF rect, float radius, boolean isFocusedHighlight) {
        if ("photo".equals(config.bg)) {
            Bitmap matBmp = CleanAssets.getMaterial(activity, config.wall, config.material);
            if (matBmp != null && !matBmp.isRecycled()) {
                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
                canvas.clipPath(clipPath);

                srcRect.set(0, 0, matBmp.getWidth(), matBmp.getHeight());
                dstRect.set(0, 0, 1920, 1080);
                paint.setAlpha(255);
                canvas.drawBitmap(matBmp, srcRect, dstRect, paint);

                // Subtle frost / highlight veil
                paint.setColor(isFocusedHighlight ? 0x44FFFFFF : 0x22000000);
                canvas.drawRect(rect, paint);
                canvas.restore();

                // Crisp 1.5px glass border
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(isFocusedHighlight ? 2.5f : 1.5f);
                strokePaint.setColor(isFocusedHighlight ? 0xCCFFFFFF : 0x44FFFFFF);
                canvas.drawRoundRect(rect, radius, radius, strokePaint);
                return;
            }
        }

        paint.setColor(isFocusedHighlight ? 0x48FFFFFF : 0x6612151C);
        canvas.drawRoundRect(rect, radius, radius, paint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(isFocusedHighlight ? 2.5f : 1.5f);
        strokePaint.setColor(isFocusedHighlight ? 0xCCFFFFFF : 0x38FFFFFF);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    // ---------------- SETTINGS SHEETS ----------------

    private String[][] getMainSettingsRows() {
        String wallLabel = "photo".equals(config.bg)
                ? CleanConfig.getWallInfo(config.wall).name
                : ("light".equals(config.bg) ? "Hell" : "Dunkel");
        String layoutLabel = ("dock".equals(config.layout) ? "Dock" : "Liste")
                + " · " + ("small".equals(config.iconsize) ? "Klein" : "compact".equals(config.iconsize) ? "Kompakt" : "Normal");
        String fontLabel = "geist".equals(config.font) ? "Geist" : "newsreader".equals(config.font) ? "Newsreader" : "Manrope";
        String focusLabel = "glass".equals(config.focus) ? ("Glass, " + config.material) : "Type";
        String appsCount = config.homeApps.size() + " Apps";
        String ramStatus = activity.getRamStatusText();

        return new String[][]{
                {"background", "Hintergrund", wallLabel},
                {"layout", "Layout & Art Mode", layoutLabel},
                {"typography", "Schriftart", fontLabel},
                {"focus", "Fokus & Glas-Stil", focusLabel},
                {"editor", "Apps auf dem Homescreen", appsCount},
                {"samsung", "Launcher & System", "Projectivy & Standard-App"},
                {"ram", "Arbeitsspeicher leeren", ramStatus}
        };
    }

    private void drawSheetBackdrop(Canvas canvas, String title, String subtitle) {
        dstRect.set(980f, 0f, 1920f, 1080f);
        paint.setColor(0xEE101318);
        canvas.drawRect(dstRect, paint);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.5f);
        strokePaint.setColor(0x33FFFFFF);
        canvas.drawLine(980f, 0f, 980f, 1080f, strokePaint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(46f);
        canvas.drawText(title, 1044f, 108f, textPaint);

        if (subtitle != null && !subtitle.isEmpty()) {
            textPaint.setColor(0xFF9AA4B2);
            textPaint.setTextSize(24f);
            canvas.drawText(subtitle, 1044f, 148f, textPaint);
        }
    }

    private void drawSettingsSheet(Canvas canvas) {
        drawSheetBackdrop(canvas, "Einstellungen", "Änderungen werden sofort live übernommen");
        String[][] rows = getMainSettingsRows();
        float startY = 190f;
        float rowH = 104f;
        float gap = 12f;

        for (int i = 0; i < rows.length; i++) {
            boolean focused = (i == settingsRow);
            float y = startY + i * (rowH + gap);
            dstRect.set(1044f, y, 1856f, y + rowH);

            paint.setColor(focused ? Color.WHITE : 0x1AFFFFFF);
            canvas.drawRoundRect(dstRect, 22f, 22f, paint);

            textPaint.setColor(focused ? Color.parseColor("#111316") : Color.WHITE);
            textPaint.setTextSize(32f);
            canvas.drawText(rows[i][1], 1076f, y + 44f, textPaint);

            textPaint.setColor(focused ? Color.parseColor("#4B525C") : 0xFF9AA4B2);
            textPaint.setTextSize(23f);
            canvas.drawText(rows[i][2], 1076f, y + 80f, textPaint);

            strokePaint.setColor(focused ? Color.parseColor("#111316") : 0xFF9AA4B2);
            CleanAssets.drawUiIcon(canvas, "right", 1816f, y + rowH * 0.5f, 24f, strokePaint);
        }
    }

    private static class DetailRowSpec {
        final String title;
        final String axis;
        final String[] values;
        final String[] labels;

        DetailRowSpec(String title, String axis, String[] values, String[] labels) {
            this.title = title;
            this.axis = axis;
            this.values = values;
            this.labels = labels;
        }
    }

    private List<DetailRowSpec> getDetailSpecs(String group) {
        List<DetailRowSpec> list = new ArrayList<>();
        if ("background".equals(group)) {
            list.add(new DetailRowSpec("Hintergrund-Typ", "bg",
                    new String[]{"photo", "dark", "light"},
                    new String[]{"Foto", "Dunkel", "Hell"}));
            list.add(new DetailRowSpec("Kunstwerk / Wallpaper", "action.wallpaper",
                    new String[]{"open"},
                    new String[]{"Wallpaper auswählen (" + CleanConfig.getWallInfo(config.wall).name + ")"}));
            list.add(new DetailRowSpec("Wallpaper-Wechsel", "mode",
                    new String[]{"fixed", "open", "5", "15", "60"},
                    new String[]{"Fest", "Bei Start", "5 Min", "15 Min", "60 Min"}));
        } else if ("layout".equals(group)) {
            list.add(new DetailRowSpec("Ansicht", "layout",
                    new String[]{"dock", "list"},
                    new String[]{"Dock (Karussell)", "Liste (Links)"}));
            list.add(new DetailRowSpec("Icon-Größe", "iconsize",
                    new String[]{"normal", "compact", "small"},
                    new String[]{"Normal", "Kompakt (80%)", "Klein (65%)"}));
            list.add(new DetailRowSpec("Dock-Position", "dockpos",
                    new String[]{"bottom", "center", "low"},
                    new String[]{"Unten", "Mitte", "Ganz unten"}));
            list.add(new DetailRowSpec("Icon-Stil", "iconstyle",
                    new String[]{"original", "clean"},
                    new String[]{"Original (Bunt)", "CleanFrame"}));
            list.add(new DetailRowSpec("Art Mode (Nur CleanScreen)", "artmode",
                    new String[]{"on", "off"},
                    new String[]{"Aktiv", "Aus"}));
            list.add(new DetailRowSpec("App-Beschriftung", "apps",
                    new String[]{"icons", "both", "text"},
                    new String[]{"Nur Icons", "Icons & Text", "Nur Text"}));
            list.add(new DetailRowSpec("Menüpunkte oben rechts", "controls",
                    new String[]{"icons", "both", "text"},
                    new String[]{"Nur Icons", "Icons & Text", "Nur Text"}));
        } else if ("typography".equals(group)) {
            list.add(new DetailRowSpec("Schriftart", "font",
                    new String[]{"manrope", "geist", "newsreader"},
                    new String[]{"Manrope / Outfit", "Geist Sans", "Newsreader Serif"}));
        } else if ("focus".equals(group)) {
            list.add(new DetailRowSpec("Fokus-Stil", "focus",
                    new String[]{"glass", "type"},
                    new String[]{"Glas (Frosted)", "Minimal (Type)"}));
            list.add(new DetailRowSpec("Glas-Material", "material",
                    new String[]{"frost", "soft", "tint"},
                    new String[]{"Frost", "Weichzeichner", "Klares Glas"}));
        } else if ("samsung".equals(group)) {
            boolean isCleanDef = activity.isDefaultLauncher();
            list.add(new DetailRowSpec("Zweiter Launcher", "action.projectivy",
                    new String[]{"launch"},
                    new String[]{"Projectivy Launcher jetzt starten"}));
            list.add(new DetailRowSpec("Dauerhafter Standard-Launcher (Home-Taste)", "action.default",
                    new String[]{"clean", "projectivy"},
                    new String[]{
                            "CleanScreen" + (isCleanDef ? " (Aktiv)" : ""),
                            "Projectivy" + (!isCleanDef ? " (Aktiv)" : "")
                    }));
            list.add(new DetailRowSpec("Arbeitsspeicher", "action.ram",
                    new String[]{"clear"},
                    new String[]{activity.getRamStatusText()}));
            list.add(new DetailRowSpec("System", "action.tvsettings",
                    new String[]{"open"},
                    new String[]{"Android TV Systemeinstellungen öffnen"}));
        }
        return list;
    }

    private String getConfigAxisValue(String axis) {
        if ("bg".equals(axis)) return config.bg;
        if ("mode".equals(axis)) return config.mode;
        if ("layout".equals(axis)) return config.layout;
        if ("iconsize".equals(axis)) return config.iconsize;
        if ("dockpos".equals(axis)) return config.dockpos;
        if ("iconstyle".equals(axis)) return config.iconstyle;
        if ("artmode".equals(axis)) return config.artmode;
        if ("apps".equals(axis)) return config.apps;
        if ("controls".equals(axis)) return config.controls;
        if ("font".equals(axis)) return config.font;
        if ("focus".equals(axis)) return config.focus;
        if ("material".equals(axis)) return config.material;
        if ("action.default".equals(axis)) return activity.isDefaultLauncher() ? "clean" : "projectivy";
        return "";
    }

    private void setConfigAxisValue(String axis, String val) {
        if ("bg".equals(axis)) config.bg = val;
        else if ("mode".equals(axis)) config.mode = val;
        else if ("layout".equals(axis)) config.layout = val;
        else if ("iconsize".equals(axis)) config.iconsize = val;
        else if ("dockpos".equals(axis)) config.dockpos = val;
        else if ("iconstyle".equals(axis)) config.iconstyle = val;
        else if ("artmode".equals(axis)) {
            config.artmode = val;
            resetIdleTimer();
        }
        else if ("apps".equals(axis)) config.apps = val;
        else if ("controls".equals(axis)) config.controls = val;
        else if ("font".equals(axis)) config.font = val;
        else if ("focus".equals(axis)) config.focus = val;
        else if ("material".equals(axis)) {
            config.material = val;
            preloadWallpaperAsync(config.wall);
        }
        config.save(activity);
    }

    private void drawDetailSheet(Canvas canvas) {
        String title = "layout".equals(currentDetailGroup) ? "Layout & Art Mode"
                : "background".equals(currentDetailGroup) ? "Hintergrund"
                : "typography".equals(currentDetailGroup) ? "Schriftart"
                : "focus".equals(currentDetailGroup) ? "Fokus & Glas" : "Launcher & System";
        drawSheetBackdrop(canvas, title, "◄ ► Option wählen  ·  OK = Übernehmen  ·  Zurück = Hauptmenü");

        List<DetailRowSpec> specs = getDetailSpecs(currentDetailGroup);
        if (detailRow >= specs.size()) detailRow = specs.size() - 1;
        if (detailRow < 0) detailRow = 0;

        int maxVisibleRows = 6;
        int firstRow = Math.max(0, Math.min(detailRow - 3, specs.size() - maxVisibleRows));
        float startY = 188f;
        float sectionH = 132f;

        for (int s = 0; s < Math.min(specs.size(), maxVisibleRows); s++) {
            int r = firstRow + s;
            DetailRowSpec spec = specs.get(r);
            float y = startY + s * sectionH;

            textPaint.setColor(0xFF9AA4B2);
            textPaint.setTextSize(22f);
            canvas.drawText(spec.title, 1044f, y + 26f, textPaint);

            int count = spec.values.length;
            float totalW = 812f;
            float gap = 12f;
            float btnW = (totalW - gap * (count - 1)) / count;
            float btnY = y + 40f;
            float btnH = 72f;
            String currentVal = getConfigAxisValue(spec.axis);

            for (int c = 0; c < count; c++) {
                boolean isFocused = (r == detailRow && c == Math.min(detailCol, count - 1));
                boolean isApplied = spec.values[c].equals(currentVal);
                float bx = 1044f + c * (btnW + gap);
                dstRect.set(bx, btnY, bx + btnW, btnY + btnH);

                if (isFocused) {
                    paint.setColor(Color.WHITE);
                } else if (isApplied) {
                    paint.setColor(0x38FFFFFF);
                } else {
                    paint.setColor(0x16FFFFFF);
                }
                canvas.drawRoundRect(dstRect, 18f, 18f, paint);

                if (isApplied && !isFocused) {
                    strokePaint.setStyle(Paint.Style.STROKE);
                    strokePaint.setStrokeWidth(2f);
                    strokePaint.setColor(0xAAFFFFFF);
                    canvas.drawRoundRect(dstRect, 18f, 18f, strokePaint);
                }

                textPaint.setColor(isFocused ? Color.parseColor("#111316") : Color.WHITE);
                textPaint.setTextSize(count > 3 ? 22f : 25f);
                String lbl = ellipsize(spec.labels[c], btnW - 24f);
                float tw = textPaint.measureText(lbl);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                canvas.drawText(lbl, bx + (btnW - tw) * 0.5f, btnY + btnH * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);
            }
        }
    }

    private void drawWallpaperPickerSheet(Canvas canvas) {
        drawSheetBackdrop(canvas, "Kunstwerk wählen", (wallPickerIndex + 1) + " von " + CleanConfig.WALLS.length + "  ·  OK = Auswählen");

        int maxVis = 7;
        wallPickerScroll = Math.max(0, Math.min(wallPickerIndex - 3, CleanConfig.WALLS.length - maxVis));
        float startY = 190f;
        float rowH = 104f;
        float gap = 12f;

        for (int s = 0; s < Math.min(CleanConfig.WALLS.length, maxVis); s++) {
            int i = wallPickerScroll + s;
            CleanConfig.WallInfo w = CleanConfig.WALLS[i];
            boolean focused = (i == wallPickerIndex);
            boolean applied = w.id.equals(config.wall);
            float y = startY + s * (rowH + gap);

            dstRect.set(1044f, y, 1856f, y + rowH);
            paint.setColor(focused ? Color.WHITE : (applied ? 0x33FFFFFF : 0x16FFFFFF));
            canvas.drawRoundRect(dstRect, 20f, 20f, paint);

            Bitmap thumb = CleanAssets.getThumb(activity, w.id);
            if (thumb != null && !thumb.isRecycled()) {
                srcRect.set(0, 0, thumb.getWidth(), thumb.getHeight());
                dstRect.set(1064f, y + 14f, 1196f, y + rowH - 14f);
                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(dstRect, 12f, 12f, Path.Direction.CW);
                canvas.clipPath(clipPath);
                canvas.drawBitmap(thumb, srcRect, dstRect, paint);
                canvas.restore();
            } else {
                final String wid = w.id;
                new Thread(() -> {
                    CleanAssets.getThumb(activity, wid);
                    postInvalidate();
                }).start();
            }

            textPaint.setColor(focused ? Color.parseColor("#111316") : Color.WHITE);
            textPaint.setTextSize(30f);
            canvas.drawText(w.name, 1224f, y + 62f, textPaint);

            if (applied) {
                strokePaint.setColor(focused ? Color.parseColor("#111316") : Color.WHITE);
                CleanAssets.drawUiIcon(canvas, "check", 1810f, y + rowH * 0.5f, 28f, strokePaint);
            }
        }
    }

    private void drawEditorSheet(Canvas canvas) {
        // Full-screen two-column native editor
        paint.setColor(0xF20E1116);
        canvas.drawRect(0, 0, 1920, 1080, paint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(46f);
        canvas.drawText("Apps verwalten & Alle Apps", 112f, 104f, textPaint);

        textPaint.setColor(0xFF9AA4B2);
        textPaint.setTextSize(24f);
        String hint = editorZone == 0
                ? (editorLifted ? "▲ ▼ Position verschieben  ·  OK = Ablegen" : "OK = Position verschieben  ·  Rechts = Aus Home entfernen / Alle Apps")
                : "OK = App öffnen  ·  Links = Auf dem Homescreen ein-/ausblenden";
        canvas.drawText(hint, 112f, 148f, textPaint);

        // Left Column: Home Apps
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30f);
        canvas.drawText("Auf dem Homescreen (" + config.homeApps.size() + ")", 112f, 212f, textPaint);

        List<MainActivity.NativeAppEntry> homeList = getHomeAppEntries();
        if (editorHomeIdx >= homeList.size()) editorHomeIdx = Math.max(0, homeList.size() - 1);
        int maxVis = 8;
        editorHomeScroll = Math.max(0, Math.min(editorHomeIdx - 3, Math.max(0, homeList.size() - maxVis)));

        float startY = 236f;
        float rowH = 88f;
        float gap = 10f;

        for (int s = 0; s < Math.min(homeList.size(), maxVis); s++) {
            int i = editorHomeScroll + s;
            MainActivity.NativeAppEntry app = homeList.get(i);
            boolean focused = (editorZone == 0 && i == editorHomeIdx);
            float y = startY + s * (rowH + gap);

            dstRect.set(112f, y, 900f, y + rowH);
            paint.setColor(focused ? (editorLifted ? 0xFF4E8CFF : Color.WHITE) : 0x18FFFFFF);
            canvas.drawRoundRect(dstRect, 20f, 20f, paint);

            int fg = focused ? (editorLifted ? Color.WHITE : Color.parseColor("#111316")) : Color.WHITE;
            drawAppIcon(canvas, app, 160f, y + rowH * 0.5f, 54f, fg);

            textPaint.setColor(fg);
            textPaint.setTextSize(28f);
            canvas.drawText(ellipsize(app.label, 520f), 206f, y + 54f, textPaint);

            textPaint.setColor(focused ? fg : 0xFF9AA4B2);
            textPaint.setTextSize(22f);
            canvas.drawText(editorLifted && focused ? "▲ ▼" : "#" + (i + 1), 830f, y + 52f, textPaint);
        }

        // Right Column: All Installed Apps (click OK to toggle on Home, or Long-OK to launch)
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30f);
        List<MainActivity.NativeAppEntry> allApps = activity.getAllAppsList();
        canvas.drawText("Alle Apps (" + allApps.size() + "  ·  OK = Auf Home +/-)", 980f, 212f, textPaint);

        if (editorAllIdx >= allApps.size()) editorAllIdx = Math.max(0, allApps.size() - 1);
        editorAllScroll = Math.max(0, Math.min(editorAllIdx - 3, Math.max(0, allApps.size() - maxVis)));

        for (int s = 0; s < Math.min(allApps.size(), maxVis); s++) {
            int i = editorAllScroll + s;
            MainActivity.NativeAppEntry app = allApps.get(i);
            boolean focused = (editorZone == 1 && i == editorAllIdx);
            boolean onHome = config.homeApps.contains(app.packageName);
            float y = startY + s * (rowH + gap);

            dstRect.set(980f, y, 1808f, y + rowH);
            paint.setColor(focused ? Color.WHITE : (onHome ? 0x26FFFFFF : 0x14FFFFFF));
            canvas.drawRoundRect(dstRect, 20f, 20f, paint);

            int fg = focused ? Color.parseColor("#111316") : Color.WHITE;
            drawAppIcon(canvas, app, 1028f, y + rowH * 0.5f, 54f, fg);

            textPaint.setColor(fg);
            textPaint.setTextSize(28f);
            canvas.drawText(ellipsize(app.label, 520f), 1074f, y + 54f, textPaint);

            textPaint.setColor(focused ? Color.parseColor("#111316") : (onHome ? 0xFF68E08F : 0xFF9AA4B2));
            textPaint.setTextSize(22f);
            canvas.drawText(onHome ? "✓ Auf Home" : "+ Hinzufügen", 1640f, y + 52f, textPaint);
        }
    }

    // ---------------- INPUT HANDLING (ZERO LATENCY) ----------------

    public boolean handleKeyEvent(KeyEvent event) {
        resetIdleTimer();
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                handler.removeCallbacks(longPressRunnable);
                if (okDown && !okLongHandled) {
                    okDown = false;
                    onOkPressed();
                }
                okDown = false;
                okLongHandled = false;
                return true;
            }
            return false;
        }

        if (action != KeyEvent.ACTION_DOWN) return false;

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (screen == SCREEN_HOME && homeZone == ZONE_APPS) {
                if (event.getRepeatCount() == 0) {
                    okDown = true;
                    okLongHandled = false;
                    handler.postDelayed(longPressRunnable, 450);
                }
                return true;
            } else {
                onOkPressed();
                return true;
            }
        }

        if (screen == SCREEN_ART) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                artWallIdx = (artWallIdx - 1 + CleanConfig.WALLS.length) % CleanConfig.WALLS.length;
                artCaptionUntilMs = System.currentTimeMillis() + 4500L;
                preloadWallpaperAsync(CleanConfig.WALLS[artWallIdx].id);
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                artWallIdx = (artWallIdx + 1) % CleanConfig.WALLS.length;
                artCaptionUntilMs = System.currentTimeMillis() + 4500L;
                preloadWallpaperAsync(CleanConfig.WALLS[artWallIdx].id);
                invalidate();
            } else {
                exitArtMode();
            }
            return true;
        }

        if (screen == SCREEN_HOME) {
            return handleHomeNav(keyCode);
        } else if (screen == SCREEN_SETTINGS) {
            return handleSettingsNav(keyCode);
        } else if (screen == SCREEN_DETAIL) {
            return handleDetailNav(keyCode);
        } else if (screen == SCREEN_WALLPAPER) {
            return handleWallpaperNav(keyCode);
        } else if (screen == SCREEN_EDITOR) {
            return handleEditorNav(keyCode);
        }
        return false;
    }

    private boolean handleHomeNav(int keyCode) {
        List<MainActivity.NativeAppEntry> apps = getHomeAppEntries();
        List<String> utils = getUtilityItems();
        boolean isList = "list".equals(config.layout);

        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (arrangeMode) {
                arrangeMode = false;
                invalidate();
                return true;
            }
            if (homeZone == ZONE_UTILS) {
                homeZone = ZONE_APPS;
                invalidate();
                return true;
            }
            return true;
        }

        if (homeZone == ZONE_APPS) {
            if (!isList) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (appIndex > 0) {
                        if (arrangeMode && appIndex < config.homeApps.size()) {
                            String moved = config.homeApps.remove(appIndex);
                            config.homeApps.add(appIndex - 1, moved);
                            config.save(activity);
                        }
                        appIndex--;
                        invalidate();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (appIndex < apps.size() - 1) {
                        if (arrangeMode && appIndex < config.homeApps.size() - 1) {
                            String moved = config.homeApps.remove(appIndex);
                            config.homeApps.add(appIndex + 1, moved);
                            config.save(activity);
                        }
                        appIndex++;
                        invalidate();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (arrangeMode) {
                        arrangeMode = false;
                    } else {
                        homeZone = ZONE_UTILS;
                    }
                    invalidate();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (arrangeMode && config.homeApps.size() > 1 && appIndex < config.homeApps.size()) {
                        config.homeApps.remove(appIndex);
                        if (appIndex >= config.homeApps.size()) appIndex = config.homeApps.size() - 1;
                        config.save(activity);
                        invalidate();
                    }
                    return true;
                }
            } else {
                // List mode navigation
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (appIndex > 0) appIndex--;
                    else homeZone = ZONE_UTILS;
                    invalidate();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (appIndex < apps.size() - 1) appIndex++;
                    invalidate();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    homeZone = ZONE_UTILS;
                    invalidate();
                    return true;
                }
            }
        } else {
            // ZONE_UTILS
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (utilIndex > 0) utilIndex--;
                else if (isList) homeZone = ZONE_APPS;
                invalidate();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (utilIndex < utils.size() - 1) utilIndex++;
                invalidate();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                homeZone = ZONE_APPS;
                invalidate();
                return true;
            }
        }
        return false;
    }

    private boolean handleSettingsNav(int keyCode) {
        String[][] rows = getMainSettingsRows();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            screen = SCREEN_HOME;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && settingsRow > 0) {
            settingsRow--;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && settingsRow < rows.length - 1) {
            settingsRow++;
            invalidate();
            return true;
        }
        return true;
    }

    private boolean handleDetailNav(int keyCode) {
        List<DetailRowSpec> specs = getDetailSpecs(currentDetailGroup);
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            screen = SCREEN_SETTINGS;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (detailRow > 0) {
                detailRow--;
                int maxCol = specs.get(detailRow).values.length - 1;
                if (detailCol > maxCol) detailCol = maxCol;
                invalidate();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (detailRow < specs.size() - 1) {
                detailRow++;
                int maxCol = specs.get(detailRow).values.length - 1;
                if (detailCol > maxCol) detailCol = maxCol;
                invalidate();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (detailCol > 0) {
                detailCol--;
                invalidate();
            } else {
                screen = SCREEN_SETTINGS;
                invalidate();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int maxCol = specs.get(detailRow).values.length - 1;
            if (detailCol < maxCol) {
                detailCol++;
                invalidate();
            }
            return true;
        }
        return true;
    }

    private boolean handleWallpaperNav(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            screen = SCREEN_DETAIL;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && wallPickerIndex > 0) {
            wallPickerIndex--;
            config.wall = CleanConfig.WALLS[wallPickerIndex].id;
            preloadWallpaperAsync(config.wall);
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && wallPickerIndex < CleanConfig.WALLS.length - 1) {
            wallPickerIndex++;
            config.wall = CleanConfig.WALLS[wallPickerIndex].id;
            preloadWallpaperAsync(config.wall);
            invalidate();
            return true;
        }
        return true;
    }

    private boolean handleEditorNav(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (editorLifted) {
                editorLifted = false;
            } else {
                screen = SCREEN_HOME;
            }
            invalidate();
            return true;
        }

        List<MainActivity.NativeAppEntry> allApps = activity.getAllAppsList();
        if (editorZone == 0) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && editorHomeIdx > 0) {
                if (editorLifted) {
                    String pkg = config.homeApps.remove(editorHomeIdx);
                    config.homeApps.add(editorHomeIdx - 1, pkg);
                    config.save(activity);
                }
                editorHomeIdx--;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && editorHomeIdx < config.homeApps.size() - 1) {
                if (editorLifted) {
                    String pkg = config.homeApps.remove(editorHomeIdx);
                    config.homeApps.add(editorHomeIdx + 1, pkg);
                    config.save(activity);
                }
                editorHomeIdx++;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                editorLifted = false;
                editorZone = 1;
                invalidate();
            }
        } else {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && editorAllIdx > 0) {
                editorAllIdx--;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && editorAllIdx < allApps.size() - 1) {
                editorAllIdx++;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                editorZone = 0;
                invalidate();
            }
        }
        return true;
    }

    private void onOkPressed() {
        if (screen == SCREEN_HOME) {
            if (homeZone == ZONE_APPS) {
                if (arrangeMode) {
                    arrangeMode = false;
                    invalidate();
                    return;
                }
                List<MainActivity.NativeAppEntry> apps = getHomeAppEntries();
                if (appIndex >= 0 && appIndex < apps.size()) {
                    activity.handleLaunch(apps.get(appIndex).packageName);
                }
            } else {
                List<String> utils = getUtilityItems();
                if (utilIndex >= 0 && utilIndex < utils.size()) {
                    String u = utils.get(utilIndex);
                    if ("art".equals(u)) {
                        enterArtMode(true);
                    } else if ("apps".equals(u)) {
                        editorZone = 1;
                        editorLifted = false;
                        allAppsLaunchMode = true;
                        screen = SCREEN_EDITOR;
                        invalidate();
                    } else if ("settings".equals(u)) {
                        screen = SCREEN_SETTINGS;
                        invalidate();
                    }
                }
            }
        } else if (screen == SCREEN_SETTINGS) {
            String[][] rows = getMainSettingsRows();
            if (settingsRow >= 0 && settingsRow < rows.length) {
                String id = rows[settingsRow][0];
                if ("editor".equals(id)) {
                    editorZone = 0;
                    editorLifted = false;
                    allAppsLaunchMode = false;
                    screen = SCREEN_EDITOR;
                } else if ("ram".equals(id)) {
                    activity.clearRamAndKillApps();
                } else {
                    currentDetailGroup = id;
                    detailRow = 0;
                    detailCol = 0;
                    screen = SCREEN_DETAIL;
                }
                invalidate();
            }
        } else if (screen == SCREEN_DETAIL) {
            List<DetailRowSpec> specs = getDetailSpecs(currentDetailGroup);
            if (detailRow >= 0 && detailRow < specs.size()) {
                DetailRowSpec spec = specs.get(detailRow);
                int c = Math.min(detailCol, spec.values.length - 1);
                String val = spec.values[c];

                if ("action.wallpaper".equals(spec.axis)) {
                    wallPickerIndex = CleanConfig.getWallIndex(config.wall);
                    screen = SCREEN_WALLPAPER;
                } else if ("action.projectivy".equals(spec.axis)) {
                    activity.handleLaunch("com.spocky.projengmenu");
                } else if ("action.default".equals(spec.axis)) {
                    activity.setDefaultLauncher(val);
                } else if ("action.ram".equals(spec.axis)) {
                    activity.clearRamAndKillApps();
                } else if ("action.tvsettings".equals(spec.axis)) {
                    activity.openAndroidSettings();
                } else {
                    setConfigAxisValue(spec.axis, val);
                }
                invalidate();
            }
        } else if (screen == SCREEN_WALLPAPER) {
            config.wall = CleanConfig.WALLS[wallPickerIndex].id;
            config.bg = "photo";
            config.save(activity);
            preloadWallpaperAsync(config.wall);
            screen = SCREEN_DETAIL;
            invalidate();
        } else if (screen == SCREEN_EDITOR) {
            if (editorZone == 0) {
                if (allAppsLaunchMode) {
                    List<MainActivity.NativeAppEntry> homeList = getHomeAppEntries();
                    if (editorHomeIdx >= 0 && editorHomeIdx < homeList.size()) {
                        screen = SCREEN_HOME;
                        activity.handleLaunch(homeList.get(editorHomeIdx).packageName);
                    }
                } else {
                    editorLifted = !editorLifted;
                    invalidate();
                }
            } else {
                List<MainActivity.NativeAppEntry> allApps = activity.getAllAppsList();
                if (editorAllIdx >= 0 && editorAllIdx < allApps.size()) {
                    String pkg = allApps.get(editorAllIdx).packageName;
                    if (allAppsLaunchMode) {
                        screen = SCREEN_HOME;
                        activity.handleLaunch(pkg);
                    } else {
                        if (config.homeApps.contains(pkg)) {
                            if (config.homeApps.size() > 1) {
                                config.homeApps.remove(pkg);
                            }
                        } else {
                            config.homeApps.add(pkg);
                        }
                        config.save(activity);
                        invalidate();
                    }
                }
            }
        }
    }

    private boolean isLightBg() {
        return "light".equals(config.bg);
    }

    private String ellipsize(String text, float maxWidth) {
        if (text == null) return "";
        if (textPaint.measureText(text) <= maxWidth) return text;
        String dots = "…";
        int len = text.length();
        while (len > 1 && textPaint.measureText(text.substring(0, len) + dots) > maxWidth) {
            len--;
        }
        return text.substring(0, Math.max(1, len)) + dots;
    }
}
