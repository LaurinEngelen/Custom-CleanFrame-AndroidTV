package com.example.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
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
    private int editorZone = 0; // 0 = Home apps, 1 = Remove button on Home app, 2 = All apps
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

    // Reusable paints & geometry (zero allocations in onDraw)
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF sliderRect = new RectF();
    private LinearGradient bottomScrimGradient = null;
    private LinearGradient topScrimGradient = null;
    private final Runnable postInvalidateRunnable = this::postInvalidateOnAnimation;

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

        // Preload current wallpaper, material shader, and font on background thread
        preloadWallpaperAsync(config.wall);
        resetIdleTimer();
        handler.postDelayed(clockTicker, 15000);
    }

    private void preloadWallpaperAsync(final String wallId) {
        new Thread(() -> {
            CleanAssets.loadFontSync(activity, config.font);
            CleanAssets.loadWallpaperSync(activity, wallId);
            CleanAssets.loadMaterialShaderSync(activity, wallId, config.material);
            postInvalidateOnAnimation();
        }).start();
    }

    public void onAppsLoaded() {
        postInvalidateOnAnimation();
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
        int muteColor = isLightBg ? Color.parseColor("#6E737B") : Color.parseColor("#C2CAD6");

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
        Bitmap wallBmp = CleanAssets.getWallpaperCached(activity, wallId, postInvalidateRunnable);
        if (wallBmp != null && !wallBmp.isRecycled()) {
            srcRect.set(0, 0, wallBmp.getWidth(), wallBmp.getHeight());
            dstRect.set(0, 0, 1920, 1080);
            paint.setAlpha(255);
            canvas.drawBitmap(wallBmp, srcRect, dstRect, paint);
        } else {
            canvas.drawColor(Color.parseColor("#12151A"));
        }

        if (screen != SCREEN_ART) {
            if (bottomScrimGradient == null) {
                bottomScrimGradient = new LinearGradient(
                        0, 560, 0, 1080,
                        new int[]{0x00000000, 0x38000000, 0x7A000000},
                        new float[]{0f, 0.6f, 1f},
                        Shader.TileMode.CLAMP
                );
            }
            if (topScrimGradient == null) {
                topScrimGradient = new LinearGradient(
                        0, 0, 0, 200,
                        new int[]{0x55000000, 0x00000000},
                        null,
                        Shader.TileMode.CLAMP
                );
            }
            paint.setShader(topScrimGradient);
            canvas.drawRect(0, 0, 1920, 200, paint);
            paint.setShader(bottomScrimGradient);
            canvas.drawRect(0, 560, 1920, 1080, paint);
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

        // Top-Right Quiet Utility Icons (matching .quiet-utils in style.css: no surrounding box!)
        List<String> utils = getUtilityItems();
        if (utilIndex >= utils.size()) utilIndex = Math.max(0, utils.size() - 1);

        boolean showIcons = !"text".equals(config.controls);
        boolean showText = !"icons".equals(config.controls);
        float rightEdge = 1808f;
        float topY = 64f;
        float pillH = 60f;
        float gap = 16f;

        float[] widths = new float[utils.size()];
        String[] labels = new String[utils.size()];
        textPaint.setTextSize(26f);
        for (int i = 0; i < utils.size(); i++) {
            String u = utils.get(i);
            labels[i] = "art".equals(u) ? "Art Mode" : "apps".equals(u) ? "Alle Apps" : "Einstellungen";
            if (showIcons && showText) {
                widths[i] = 36f + textPaint.measureText(labels[i]) + 48f;
            } else if (showText) {
                widths[i] = textPaint.measureText(labels[i]) + 40f;
            } else {
                widths[i] = 60f;
            }
        }

        float totalW = 0f;
        for (int i = 0; i < widths.length; i++) {
            totalW += widths[i] + (i > 0 ? gap : 0f);
        }
        float curX = rightEdge - totalW;

        for (int i = 0; i < utils.size(); i++) {
            boolean focused = (screen == SCREEN_HOME && homeZone == ZONE_UTILS && utilIndex == i);
            float w = widths[i];
            dstRect.set(curX, topY, curX + w, topY + pillH);

            int fgColor;
            if (focused) {
                paint.setColor(isLightBg() ? 0x22000000 : 0x33FFFFFF);
                canvas.drawRoundRect(dstRect, 30f, 30f, paint);
                fgColor = inkColor;
            } else {
                fgColor = (inkColor & 0x00FFFFFF) | 0xA8000000;
            }

            textPaint.setColor(fgColor);
            textPaint.setTextSize(26f);

            if (showIcons && !showText) {
                CleanAssets.drawUiIcon(activity, canvas, utils.get(i), curX + w * 0.5f, topY + pillH * 0.5f, focused ? 34f : 30f, fgColor, strokePaint);
                if (focused) {
                    String lbl = labels[i];
                    float lw = textPaint.measureText(lbl) + 36f;
                    float lx = Math.min(1808f - lw, Math.max(112f, curX + w * 0.5f - lw * 0.5f));
                    RectF lblRect = new RectF(lx, topY + pillH + 10f, lx + lw, topY + pillH + 52f);
                    paint.setColor(0xCC12151B);
                    canvas.drawRoundRect(lblRect, 21f, 21f, paint);
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(22f);
                    canvas.drawText(lbl, lx + 18f, topY + pillH + 39f, textPaint);
                }
            } else if (showIcons && showText) {
                CleanAssets.drawUiIcon(activity, canvas, utils.get(i), curX + 30f, topY + pillH * 0.5f, 26f, fgColor, strokePaint);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float ty = topY + pillH * 0.5f - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(labels[i], curX + 52f, ty, textPaint);
            } else {
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float ty = topY + pillH * 0.5f - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(labels[i], curX + 20f, ty, textPaint);
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

        float scale = config.getScaleFactor();
        boolean iconsOnly = "icons".equals(config.apps);
        boolean textOnly = "text".equals(config.apps);

        float baseTileW = iconsOnly ? 176f : 288f;
        float baseTileH = iconsOnly ? 144f : (textOnly ? 104f : 200f);
        float tileW = Math.round(baseTileW * scale);
        float tileH = Math.round(baseTileH * scale);
        float gap = Math.round(8f * scale);
        float pitch = tileW + gap;
        float dockPad = Math.round(16f * scale);

        // Compute horizontal & vertical position from config.dockPosX (0..100) and config.dockPosY (10..98)
        float maxShelfInnerW = 1472f;
        float totalTrackW = n * pitch - gap;
        float visibleTrackW = Math.min(maxShelfInnerW, totalTrackW);
        float totalDockW = visibleTrackW + dockPad * 2f;
        float totalDockH = tileH + dockPad * 2f;

        float minLeft = 64f;
        float maxLeft = Math.max(minLeft, 1920f - 64f - totalDockW);
        float dockLeft = minLeft + (maxLeft - minLeft) * (config.dockPosX / 100f);
        float trackStartX = dockLeft + dockPad;

        float minTop = 140f;
        float maxTop = Math.max(minTop, 1048f - totalDockH);
        float dockTop = minTop + (maxTop - minTop) * ((config.dockPosY - 10f) / 88f);
        float tileTopY = dockTop + dockPad;
        float tileBottomY = tileTopY + tileH;

        if (totalTrackW <= visibleTrackW) {
            targetScrollX = 0f;
        } else {
            float selectedCenter = appIndex * pitch + tileW * 0.5f;
            float desiredScroll = selectedCenter - visibleTrackW * 0.5f;
            float maxScroll = Math.max(0f, totalTrackW - visibleTrackW);
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
            animScrollX += (targetScrollX - animScrollX) * 0.42f;
            animFocusX += (targetFocusX - animFocusX) * 0.42f;
            animFocusW += (targetFocusW - animFocusW) * 0.42f;
            if (Math.abs(targetScrollX - animScrollX) < 0.5f) animScrollX = targetScrollX;
            if (Math.abs(targetFocusX - animFocusX) < 0.5f) animFocusX = targetFocusX;
            if (Math.abs(targetFocusW - animFocusW) < 0.5f) animFocusW = targetFocusW;
        }

        boolean animating = (animScrollX != targetScrollX) || (animFocusX != targetFocusX);

        // 1. Draw Outer Dock Container (.mat in style.css: border-radius = 48px * scale, concentric with 32px * scale inner plate!)
        float outerRadius = 48f * scale;
        float innerRadius = 32f * scale;
        dstRect.set(dockLeft, dockTop, dockLeft + totalDockW, dockTop + totalDockH);
        if ("glass".equals(config.focus)) {
            drawDockGlassContainer(canvas, dstRect, outerRadius);
        }

        // 2. Draw Inner Sliding Focus Plate (.plate in style.css: border-radius = 32px * scale)
        boolean appZoneFocused = (screen == SCREEN_HOME && homeZone == ZONE_APPS);
        dstRect.set(animFocusX, tileTopY, animFocusX + animFocusW, tileBottomY);
        if ("glass".equals(config.focus)) {
            paint.setColor(appZoneFocused ? (arrangeMode ? 0x504E8CFF : 0x36FFFFFF) : 0x1CFFFFFF);
            canvas.drawRoundRect(dstRect, innerRadius, innerRadius, paint);

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.5f);
            strokePaint.setColor(appZoneFocused ? 0x66FFFFFF : 0x28FFFFFF);
            canvas.drawRoundRect(dstRect, innerRadius, innerRadius, strokePaint);
        } else if (appZoneFocused) {
            paint.setColor(isLightBg() ? 0xFF16181A : 0xFFFFFFFF);
            canvas.drawRoundRect(dstRect, innerRadius, innerRadius, paint);
        }

        // 3. Draw App Tiles inside horizontal bounds
        canvas.save();
        canvas.clipRect(trackStartX - 4f, dockTop - 20f, trackStartX + visibleTrackW + 4f, dockTop + totalDockH + 20f);

        for (int i = 0; i < n; i++) {
            float tx = trackStartX + i * pitch - animScrollX;
            if (tx + tileW < trackStartX - 20f || tx > trackStartX + visibleTrackW + 20f) continue;

            MainActivity.NativeAppEntry app = apps.get(i);
            boolean isFocused = appZoneFocused && (i == appIndex);
            float liftY = (isFocused && arrangeMode) ? -10f : 0f;

            int itemTextColor;
            if (isFocused && !"glass".equals(config.focus)) {
                itemTextColor = isLightBg() ? Color.WHITE : Color.parseColor("#111316");
            } else {
                int baseInk = isLightBg() ? Color.parseColor("#181A1D") : Color.WHITE;
                itemTextColor = isFocused ? baseInk : ((baseInk & 0x00FFFFFF) | 0xD8000000);
            }

            float centerX = tx + tileW * 0.5f;
            float iconBox = Math.round((iconsOnly ? 76f : 72f) * scale);
            float iconCenterY = iconsOnly
                    ? (tileTopY + tileH * 0.5f + liftY)
                    : (tileTopY + tileH * 0.38f + liftY);

            if (!textOnly) {
                drawAppIcon(canvas, app, centerX, iconCenterY, iconBox, itemTextColor);
            }

            if (!iconsOnly) {
                textPaint.setColor(itemTextColor);
                textPaint.setTextSize(Math.max(16f, Math.round((textOnly ? 34f : 28f) * scale)));
                String label = ellipsize(app.label, tileW - 24f * scale);
                float tw = textPaint.measureText(label);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = textOnly
                        ? (tileTopY + tileH * 0.5f - (fm.ascent + fm.descent) * 0.5f + liftY)
                        : (tileBottomY - 24f * scale + liftY);
                canvas.drawText(label, centerX - tw * 0.5f, textY, textPaint);
            }
        }
        canvas.restore();

        // 4. Floating Label (in icons-only mode) or Arrange Mode Action Bar
        if (arrangeMode && appZoneFocused && appIndex < n) {
            String hint = "◄ ► Verschieben  ·  ▼ Runter = Entfernen (" + apps.get(appIndex).label + ")  ·  OK = Fertig";
            textPaint.setTextSize(24f);
            float hw = textPaint.measureText(hint) + 44f;
            float hx = Math.max(64f, Math.min(1856f - hw, animFocusX + animFocusW * 0.5f - hw * 0.5f));
            float pillBottom = dockTop - 16f;
            if (pillBottom < 140f) pillBottom = dockTop + totalDockH + 64f;
            dstRect.set(hx, pillBottom - 48f, hx + hw, pillBottom);
            paint.setColor(0xDD101318);
            canvas.drawRoundRect(dstRect, 24f, 24f, paint);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(hint, hx + 22f, pillBottom - 16f, textPaint);
        } else if (iconsOnly && (appZoneFocused || (screen == SCREEN_DETAIL && "layout".equals(currentDetailGroup))) && appIndex < n) {
            String focusedName = apps.get(appIndex).label;
            textPaint.setTextSize(Math.max(20f, Math.round(28f * scale)));
            float nameW = textPaint.measureText(focusedName);
            float pillW = nameW + 40f;
            float focusCenterX = animFocusX + animFocusW * 0.5f;
            float pillLeft = Math.max(64f, Math.min(1856f - pillW, focusCenterX - pillW * 0.5f));
            float pillBottom = dockTop - 14f;
            if (pillBottom < 130f) pillBottom = dockTop + totalDockH + 62f;
            float pillTop = pillBottom - 48f;

            dstRect.set(pillLeft, pillTop, pillLeft + pillW, pillBottom);
            paint.setColor(0xCC12151B);
            canvas.drawRoundRect(dstRect, 24f, 24f, paint);

            textPaint.setColor(Color.WHITE);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float ty = (pillTop + pillBottom) * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(focusedName, pillLeft + 20f, ty, textPaint);
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
                    drawDockGlassContainer(canvas, dstRect, 32f);
                } else {
                    paint.setColor(isLightBg() ? 0xFF16181A : 0xFFFFFFFF);
                    canvas.drawRoundRect(dstRect, 32f, 32f, paint);
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
            // In CleanFrame style, draw exact SVG vector icons for brand apps; for other apps draw clean monogram or icon
            if (CleanAssets.getCleanBrandVectorRes(app.packageName) != 0) {
                CleanAssets.drawCleanBrandMark(activity, canvas, app.packageName, app.label, cx, cy, size, tintColor, textPaint, strokePaint);
                return;
            }
        }

        Bitmap iconBmp = app.iconBitmap;
        if (iconBmp != null && !iconBmp.isRecycled()) {
            float half = size * 0.5f;
            srcRect.set(0, 0, iconBmp.getWidth(), iconBmp.getHeight());
            dstRect.set(cx - half, cy - half, cx + half, cy + half);
            paint.setAlpha(255);
            canvas.drawBitmap(iconBmp, srcRect, dstRect, paint);
        } else {
            CleanAssets.drawCleanBrandMark(activity, canvas, app.packageName, app.label, cx, cy, size, tintColor, textPaint, strokePaint);
        }
    }

    /**
     * Draws a smooth, anti-aliased Frosted Glass rounded container using BitmapShader (no canvas.clipPath!).
     */
    private void drawDockGlassContainer(Canvas canvas, RectF rect, float radius) {
        if ("photo".equals(config.bg)) {
            BitmapShader matShader = CleanAssets.getMaterialShaderCached(activity, config.wall, config.material, postInvalidateRunnable);
            if (matShader != null) {
                shaderPaint.setShader(matShader);
                canvas.drawRoundRect(rect, radius, radius, shaderPaint);
                shaderPaint.setShader(null);

                // Subtle frost veil matching .mat-veil in style.css
                paint.setColor(0x28080E12);
                canvas.drawRoundRect(rect, radius, radius, paint);

                // 1px hairline border matching .mat::after in style.css
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(1.5f);
                strokePaint.setColor(0x3DFFFFFF);
                canvas.drawRoundRect(rect, radius, radius, strokePaint);
                return;
            }
        }

        paint.setColor(0x7010141B);
        canvas.drawRoundRect(rect, radius, radius, paint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.5f);
        strokePaint.setColor(0x38FFFFFF);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    // ---------------- SETTINGS SHEETS ----------------

    private String[][] getMainSettingsRows() {
        String wallLabel = "photo".equals(config.bg)
                ? CleanConfig.getWallInfo(config.wall).name
                : ("light".equals(config.bg) ? "Hell" : "Dunkel");
        String layoutLabel = ("dock".equals(config.layout) ? "Dock" : "Liste")
                + " · Größe " + config.dockScale + "% · X:" + config.dockPosX + "% Y:" + config.dockPosY + "%";
        String fontLabel = "geist".equals(config.font) ? "Geist" : "newsreader".equals(config.font) ? "Newsreader" : "Manrope";
        String focusLabel = "glass".equals(config.focus) ? ("Glass, " + config.material) : "Type";
        String appsCount = config.homeApps.size() + " Apps (Hinzufügen / Entfernen)";
        String ramStatus = activity.getRamStatusText();

        return new String[][]{
                {"background", "Hintergrund", wallLabel},
                {"layout", "Layout, Position & Art Mode", layoutLabel},
                {"typography", "Schriftart", fontLabel},
                {"focus", "Fokus & Glas-Stil", focusLabel},
                {"editor", "Apps auf dem Homescreen", appsCount},
                {"samsung", "Launcher & System", "Projectivy & Standard-App"},
                {"ram", "Arbeitsspeicher leeren", ramStatus}
        };
    }

    private void drawSheetBackdrop(Canvas canvas, String title, String subtitle, boolean compactLivePreview) {
        if (compactLivePreview) {
            // Compact top-right floating card so the entire Homescreen & App Carousel are visible in Live View!
            dstRect.set(1000f, 36f, 1884f, 640f);
            paint.setColor(0xD8101318);
            canvas.drawRoundRect(dstRect, 32f, 32f, paint);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.5f);
            strokePaint.setColor(0x40FFFFFF);
            canvas.drawRoundRect(dstRect, 32f, 32f, strokePaint);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(34f);
            canvas.drawText(title, 1040f, 90f, textPaint);

            if (subtitle != null && !subtitle.isEmpty()) {
                textPaint.setColor(0xFF9AA4B2);
                textPaint.setTextSize(20f);
                canvas.drawText(subtitle, 1040f, 122f, textPaint);
            }
            return;
        }

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
        drawSheetBackdrop(canvas, "Einstellungen", "Änderungen werden sofort live übernommen", false);
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

            CleanAssets.drawUiIcon(activity, canvas, "right", 1816f, y + rowH * 0.5f, 24f,
                    focused ? Color.parseColor("#111316") : 0xFF9AA4B2, strokePaint);
        }
    }

    private static class DetailRowSpec {
        final String title;
        final String axis;
        final boolean isSlider;
        final int sliderMin;
        final int sliderMax;
        final int sliderValue;
        final String sliderText;
        final String[] values;
        final String[] labels;

        DetailRowSpec(String title, String axis, String[] values, String[] labels) {
            this.title = title;
            this.axis = axis;
            this.isSlider = false;
            this.sliderMin = 0;
            this.sliderMax = 100;
            this.sliderValue = 0;
            this.sliderText = "";
            this.values = values;
            this.labels = labels;
        }

        DetailRowSpec(String title, String axis, int min, int max, int current, String text) {
            this.title = title;
            this.axis = axis;
            this.isSlider = true;
            this.sliderMin = min;
            this.sliderMax = max;
            this.sliderValue = current;
            this.sliderText = text;
            this.values = new String[]{"slider"};
            this.labels = new String[]{text};
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
            list.add(new DetailRowSpec("Horizontale Position (X-Achse  ·  ◄ Links / Rechts ►)", "slider.dockPosX",
                    0, 100, config.dockPosX,
                    config.dockPosX == 50 ? "50% (Zentriert)" : config.dockPosX + "%"));
            list.add(new DetailRowSpec("Vertikale Position (Y-Achse  ·  ◄ Hoch / Runter ►)", "slider.dockPosY",
                    10, 98, config.dockPosY,
                    config.dockPosY == 85 ? "85% (Standard)" : config.dockPosY + "%"));
            list.add(new DetailRowSpec("Karussell- & Icon-Größe (◄ Kleiner / Größer ►)", "slider.dockScale",
                    50, 140, config.dockScale,
                    config.dockScale + "%"));
            list.add(new DetailRowSpec("Icon-Stil", "iconstyle",
                    new String[]{"clean", "original"},
                    new String[]{"CleanFrame (Original)", "Android-Icons (Bunt)"}));
            list.add(new DetailRowSpec("App-Beschriftung", "apps",
                    new String[]{"icons", "both", "text"},
                    new String[]{"Nur Icons", "Icons & Text", "Nur Text"}));
            list.add(new DetailRowSpec("Art Mode (Nur CleanScreen)", "artmode",
                    new String[]{"on", "off"},
                    new String[]{"Aktiv", "Aus"}));
            list.add(new DetailRowSpec("Menüpunkte oben rechts", "controls",
                    new String[]{"icons", "both", "text"},
                    new String[]{"Nur Icons", "Icons & Text", "Nur Text"}));
            list.add(new DetailRowSpec("Ansicht", "layout",
                    new String[]{"dock", "list"},
                    new String[]{"Dock (Karussell)", "Liste (Links)"}));
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
        else if ("iconstyle".equals(axis)) config.iconstyle = val;
        else if ("artmode".equals(axis)) {
            config.artmode = val;
            resetIdleTimer();
        }
        else if ("apps".equals(axis)) config.apps = val;
        else if ("controls".equals(axis)) config.controls = val;
        else if ("font".equals(axis)) {
            config.font = val;
            preloadWallpaperAsync(config.wall);
        }
        else if ("focus".equals(axis)) config.focus = val;
        else if ("material".equals(axis)) {
            config.material = val;
            preloadWallpaperAsync(config.wall);
        }
        config.save(activity);
    }

    private void drawDetailSheet(Canvas canvas) {
        boolean isLayoutLive = "layout".equals(currentDetailGroup);
        String title = isLayoutLive ? "Layout & Live-Position"
                : "background".equals(currentDetailGroup) ? "Hintergrund"
                : "typography".equals(currentDetailGroup) ? "Schriftart"
                : "focus".equals(currentDetailGroup) ? "Fokus & Glas" : "Launcher & System";
        String sub = isLayoutLive
                ? "◄ ► Regler live verschieben  ·  OK = Standardwert  ·  Zurück = Menü"
                : "◄ ► Option wählen  ·  OK = Übernehmen  ·  Zurück = Hauptmenü";
        drawSheetBackdrop(canvas, title, sub, isLayoutLive);

        List<DetailRowSpec> specs = getDetailSpecs(currentDetailGroup);
        if (detailRow >= specs.size()) detailRow = specs.size() - 1;
        if (detailRow < 0) detailRow = 0;

        int maxVisibleRows = isLayoutLive ? 4 : 6;
        int firstRow = Math.max(0, Math.min(detailRow - (maxVisibleRows / 2), Math.max(0, specs.size() - maxVisibleRows)));
        float startY = isLayoutLive ? 142f : 188f;
        float sectionH = isLayoutLive ? 116f : 132f;
        float leftX = isLayoutLive ? 1040f : 1044f;
        float totalW = isLayoutLive ? 804f : 812f;

        for (int s = 0; s < Math.min(specs.size(), maxVisibleRows); s++) {
            int r = firstRow + s;
            DetailRowSpec spec = specs.get(r);
            float y = startY + s * sectionH;

            textPaint.setColor(0xFFB0B8C4);
            textPaint.setTextSize(21f);
            canvas.drawText(spec.title, leftX, y + 24f, textPaint);

            float btnY = y + 36f;
            float btnH = isLayoutLive ? 64f : 72f;

            if (spec.isSlider) {
                boolean isFocused = (r == detailRow);
                dstRect.set(leftX, btnY, leftX + totalW, btnY + btnH);
                paint.setColor(isFocused ? Color.WHITE : 0x20FFFFFF);
                canvas.drawRoundRect(dstRect, 18f, 18f, paint);

                int fg = isFocused ? Color.parseColor("#111316") : Color.WHITE;
                CleanAssets.drawUiIcon(activity, canvas, "left", leftX + 28f, btnY + btnH * 0.5f, 22f, fg, strokePaint);
                CleanAssets.drawUiIcon(activity, canvas, "right", leftX + totalW - 28f, btnY + btnH * 0.5f, 22f, fg, strokePaint);

                // Draw Slider Track & Thumb
                float trackLeft = leftX + 64f;
                float trackRight = leftX + totalW - 210f;
                float trackCy = btnY + btnH * 0.5f;
                sliderRect.set(trackLeft, trackCy - 5f, trackRight, trackCy + 5f);
                paint.setColor(isFocused ? 0x33111316 : 0x38FFFFFF);
                canvas.drawRoundRect(sliderRect, 5f, 5f, paint);

                float pct = Math.max(0f, Math.min(1f, (spec.sliderValue - spec.sliderMin) / (float) (spec.sliderMax - spec.sliderMin)));
                float fillRight = trackLeft + (trackRight - trackLeft) * pct;
                sliderRect.set(trackLeft, trackCy - 5f, fillRight, trackCy + 5f);
                paint.setColor(isFocused ? Color.parseColor("#111316") : Color.WHITE);
                canvas.drawRoundRect(sliderRect, 5f, 5f, paint);

                canvas.drawCircle(fillRight, trackCy, 12f, paint);

                textPaint.setColor(fg);
                textPaint.setTextSize(23f);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                canvas.drawText(spec.sliderText, trackRight + 20f, trackCy - (fm.ascent + fm.descent) * 0.5f, textPaint);
            } else {
                int count = spec.values.length;
                float gap = 10f;
                float btnW = (totalW - gap * (count - 1)) / count;
                String currentVal = getConfigAxisValue(spec.axis);

                for (int c = 0; c < count; c++) {
                    boolean isFocused = (r == detailRow && c == Math.min(detailCol, count - 1));
                    boolean isApplied = spec.values[c].equals(currentVal);
                    float bx = leftX + c * (btnW + gap);
                    dstRect.set(bx, btnY, bx + btnW, btnY + btnH);

                    if (isFocused) {
                        paint.setColor(Color.WHITE);
                    } else if (isApplied) {
                        paint.setColor(0x38FFFFFF);
                    } else {
                        paint.setColor(0x18FFFFFF);
                    }
                    canvas.drawRoundRect(dstRect, 18f, 18f, paint);

                    if (isApplied && !isFocused) {
                        strokePaint.setStyle(Paint.Style.STROKE);
                        strokePaint.setStrokeWidth(2f);
                        strokePaint.setColor(0xAAFFFFFF);
                        canvas.drawRoundRect(dstRect, 18f, 18f, strokePaint);
                    }

                    textPaint.setColor(isFocused ? Color.parseColor("#111316") : Color.WHITE);
                    textPaint.setTextSize(count > 3 ? 21f : 24f);
                    String lbl = ellipsize(spec.labels[c], btnW - 20f);
                    float tw = textPaint.measureText(lbl);
                    Paint.FontMetrics fm = textPaint.getFontMetrics();
                    canvas.drawText(lbl, bx + (btnW - tw) * 0.5f, btnY + btnH * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);
                }
            }
        }
    }

    private void drawWallpaperPickerSheet(Canvas canvas) {
        drawSheetBackdrop(canvas, "Kunstwerk wählen", (wallPickerIndex + 1) + " von " + CleanConfig.WALLS.length + "  ·  OK = Auswählen", false);

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

            Bitmap thumb = CleanAssets.getThumbCached(activity, w.id, postInvalidateRunnable);
            if (thumb != null && !thumb.isRecycled()) {
                srcRect.set(0, 0, thumb.getWidth(), thumb.getHeight());
                dstRect.set(1064f, y + 14f, 1196f, y + rowH - 14f);
                paint.setAlpha(255);
                canvas.drawBitmap(thumb, srcRect, dstRect, paint);
            }

            textPaint.setColor(focused ? Color.parseColor("#111316") : Color.WHITE);
            textPaint.setTextSize(30f);
            canvas.drawText(w.name, 1224f, y + 62f, textPaint);

            if (applied) {
                CleanAssets.drawUiIcon(activity, canvas, "check", 1810f, y + rowH * 0.5f, 28f,
                        focused ? Color.parseColor("#111316") : Color.WHITE, strokePaint);
            }
        }
    }

    private void drawEditorSheet(Canvas canvas) {
        paint.setColor(0xF20E1116);
        canvas.drawRect(0, 0, 1920, 1080, paint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(46f);
        canvas.drawText(allAppsLaunchMode ? "Alle Apps & Homescreen" : "Apps auf dem Homescreen verwalten", 112f, 104f, textPaint);

        textPaint.setColor(0xFF9AA4B2);
        textPaint.setTextSize(24f);
        String hint;
        if (editorZone == 0) {
            hint = editorLifted
                    ? "▲ ▼ Position verschieben  ·  OK = Ablegen"
                    : "OK = Verschieben  ·  Rechts ► = 'Entfernen'-Button auswählen";
        } else if (editorZone == 1) {
            hint = "OK = App vom Homescreen entfernen  ·  ◄ Links = Zurück  ·  Rechts ► = Alle Apps";
        } else {
            hint = allAppsLaunchMode
                    ? "OK = App öffnen  ·  ◄ Links = Zum Homescreen-Editor"
                    : "OK = Auf dem Homescreen ein-/ausblenden  ·  ◄ Links = Homescreen-Liste";
        }
        canvas.drawText(hint, 112f, 148f, textPaint);

        // Left Column: Home Apps + Dedicated [✕ Entfernen] button per row!
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
            boolean rowFocused = (editorZone == 0 && i == editorHomeIdx);
            boolean rmFocused = (editorZone == 1 && i == editorHomeIdx);
            float y = startY + s * (rowH + gap);

            // Main app pill
            dstRect.set(112f, y, 716f, y + rowH);
            paint.setColor(rowFocused ? (editorLifted ? 0xFF4E8CFF : Color.WHITE) : 0x18FFFFFF);
            canvas.drawRoundRect(dstRect, 20f, 20f, paint);

            int fg = rowFocused ? (editorLifted ? Color.WHITE : Color.parseColor("#111316")) : Color.WHITE;
            drawAppIcon(canvas, app, 158f, y + rowH * 0.5f, 52f, fg);

            textPaint.setColor(fg);
            textPaint.setTextSize(27f);
            canvas.drawText(ellipsize(app.label, 420f), 202f, y + 54f, textPaint);

            textPaint.setColor(rowFocused ? fg : 0xFF9AA4B2);
            textPaint.setTextSize(21f);
            canvas.drawText(editorLifted && rowFocused ? "▲ ▼" : "#" + (i + 1), 656f, y + 52f, textPaint);

            // Dedicated [Entfernen] button right next to each Home app
            dstRect.set(728f, y, 912f, y + rowH);
            paint.setColor(rmFocused ? 0xFFFF5252 : 0x22FF5252);
            canvas.drawRoundRect(dstRect, 20f, 20f, paint);
            textPaint.setColor(rmFocused ? Color.WHITE : 0xFFFF8A80);
            textPaint.setTextSize(23f);
            String rmTxt = "Entfernen";
            float rmW = textPaint.measureText(rmTxt);
            canvas.drawText(rmTxt, 728f + (184f - rmW) * 0.5f, y + 53f, textPaint);
        }

        // Right Column: All Installed Apps
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30f);
        List<MainActivity.NativeAppEntry> allApps = activity.getAllAppsList();
        canvas.drawText("Alle installierten Apps (" + allApps.size() + ")", 980f, 212f, textPaint);

        if (editorAllIdx >= allApps.size()) editorAllIdx = Math.max(0, allApps.size() - 1);
        editorAllScroll = Math.max(0, Math.min(editorAllIdx - 3, Math.max(0, allApps.size() - maxVis)));

        for (int s = 0; s < Math.min(allApps.size(), maxVis); s++) {
            int i = editorAllScroll + s;
            MainActivity.NativeAppEntry app = allApps.get(i);
            boolean focused = (editorZone == 2 && i == editorAllIdx);
            boolean onHome = config.homeApps.contains(app.packageName);
            float y = startY + s * (rowH + gap);

            dstRect.set(980f, y, 1808f, y + rowH);
            paint.setColor(focused ? Color.WHITE : (onHome ? 0x26FFFFFF : 0x14FFFFFF));
            canvas.drawRoundRect(dstRect, 20f, 20f, paint);

            int fg = focused ? Color.parseColor("#111316") : Color.WHITE;
            drawAppIcon(canvas, app, 1028f, y + rowH * 0.5f, 52f, fg);

            textPaint.setColor(fg);
            textPaint.setTextSize(27f);
            canvas.drawText(ellipsize(app.label, 500f), 1074f, y + 54f, textPaint);

            textPaint.setColor(focused ? Color.parseColor("#111316") : (onHome ? 0xFF68E08F : 0xFF9AA4B2));
            textPaint.setTextSize(22f);
            canvas.drawText(onHome ? "✓ Auf Home" : "+ Auf Home", 1640f, y + 52f, textPaint);
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
                    handler.postDelayed(longPressRunnable, 420);
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
                    if (arrangeMode && config.homeApps.size() > 1 && appIndex < apps.size()) {
                        String pkgToRemove = apps.get(appIndex).packageName;
                        config.homeApps.remove(pkgToRemove);
                        if (appIndex >= config.homeApps.size()) appIndex = Math.max(0, config.homeApps.size() - 1);
                        config.save(activity);
                        invalidate();
                    }
                    return true;
                }
            } else {
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

        DetailRowSpec cur = specs.get(detailRow);
        if (cur.isSlider) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int dir = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ? 1 : -1;
                if ("slider.dockPosX".equals(cur.axis)) {
                    config.dockPosX = Math.max(0, Math.min(100, config.dockPosX + dir * 2));
                } else if ("slider.dockPosY".equals(cur.axis)) {
                    config.dockPosY = Math.max(10, Math.min(98, config.dockPosY + dir * 2));
                } else if ("slider.dockScale".equals(cur.axis)) {
                    config.dockScale = Math.max(50, Math.min(140, config.dockScale + dir * 5));
                }
                config.save(activity);
                focusInitialized = false;
                invalidate();
                return true;
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
            int maxCol = cur.values.length - 1;
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

        List<MainActivity.NativeAppEntry> homeList = getHomeAppEntries();
        List<MainActivity.NativeAppEntry> allApps = activity.getAllAppsList();
        if (editorZone == 0) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && editorHomeIdx > 0) {
                if (editorLifted && editorHomeIdx < config.homeApps.size()) {
                    String pkg = config.homeApps.remove(editorHomeIdx);
                    config.homeApps.add(editorHomeIdx - 1, pkg);
                    config.save(activity);
                }
                editorHomeIdx--;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && editorHomeIdx < homeList.size() - 1) {
                if (editorLifted && editorHomeIdx < config.homeApps.size() - 1) {
                    String pkg = config.homeApps.remove(editorHomeIdx);
                    config.homeApps.add(editorHomeIdx + 1, pkg);
                    config.save(activity);
                }
                editorHomeIdx++;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                editorLifted = false;
                editorZone = 1; // Move focus to the [Entfernen] button of this row
                invalidate();
            }
        } else if (editorZone == 1) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && editorHomeIdx > 0) {
                editorHomeIdx--;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && editorHomeIdx < homeList.size() - 1) {
                editorHomeIdx++;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                editorZone = 0;
                invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                editorZone = 2;
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
                editorZone = 1;
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
                        editorZone = 2;
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
                if (spec.isSlider) {
                    // Pressing OK on a slider resets it to default
                    if ("slider.dockPosX".equals(spec.axis)) config.dockPosX = 50;
                    else if ("slider.dockPosY".equals(spec.axis)) config.dockPosY = 85;
                    else if ("slider.dockScale".equals(spec.axis)) config.dockScale = 100;
                    config.save(activity);
                    focusInitialized = false;
                    invalidate();
                    return;
                }

                int c = Math.min(detailCol, spec.values.length - 1);
                String val = spec.values[c];

                if ("action.wallpaper".equals(spec.axis)) {
                    wallPickerIndex = CleanConfig.getWallIndex(config.wall);
                    screen = SCREEN_WALLPAPER;
                } else if ("action.projectivy".equals(spec.axis)) {
                    activity.handleLaunch("com.spocky.projengmenu");
                } else if ("action.default".equals(spec.axis)) {
                    activity.setDefaultLauncher(val);
                } else if ("action.tvsettings".equals(spec.axis)) {
                    activity.openAndroidSettings();
                } else {
                    setConfigAxisValue(spec.axis, val);
                    focusInitialized = false;
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
            List<MainActivity.NativeAppEntry> homeList = getHomeAppEntries();
            if (editorZone == 0) {
                editorLifted = !editorLifted;
                invalidate();
            } else if (editorZone == 1) {
                // Remove button clicked on Home app!
                if (homeList.size() > 1 && editorHomeIdx >= 0 && editorHomeIdx < homeList.size()) {
                    String pkg = homeList.get(editorHomeIdx).packageName;
                    config.homeApps.remove(pkg);
                    if (editorHomeIdx >= config.homeApps.size()) {
                        editorHomeIdx = Math.max(0, config.homeApps.size() - 1);
                    }
                    if (appIndex >= config.homeApps.size()) {
                        appIndex = Math.max(0, config.homeApps.size() - 1);
                    }
                    config.save(activity);
                    focusInitialized = false;
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
                        focusInitialized = false;
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
