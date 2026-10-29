package com.example.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CleanAssets {

    private static ZipFile externalZip = null;
    private static final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(48 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private static final Map<String, Typeface> fontCache = new HashMap<>();

    private static synchronized ZipFile getZip() {
        if (externalZip != null) return externalZip;
        try {
            File f = new File("/data/local/tmp/assets_pack.zip");
            if (f.exists() && f.canRead()) {
                externalZip = new ZipFile(f);
            }
        } catch (Throwable ignored) {}
        return externalZip;
    }

    public static InputStream openAsset(Context ctx, String relPath) {
        try {
            return ctx.getAssets().open(relPath);
        } catch (Throwable notInApk) {
            try {
                ZipFile zf = getZip();
                if (zf != null) {
                    ZipEntry entry = zf.getEntry("assets/" + relPath);
                    if (entry != null) {
                        return zf.getInputStream(entry);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static Bitmap getWallpaper(Context ctx, String wallId) {
        String key = "wall:" + wallId;
        Bitmap cached = bitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        String path = "cleanframe/assets/wallpapers/" + wallId + ".jpg";
        try (InputStream is = openAsset(ctx, path)) {
            if (is != null) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                if (bmp != null) {
                    bitmapCache.put(key, bmp);
                    return bmp;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Bitmap getMaterial(Context ctx, String wallId, String materialMode) {
        String suffix = "soft".equals(materialMode)
                ? "-study-soft.jpg"
                : "tint".equals(materialMode) ? "-field-clear.jpg" : "-field-frost.jpg";
        String key = "mat:" + wallId + ":" + suffix;
        Bitmap cached = bitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        String path = "cleanframe/assets/materials/" + wallId + suffix;
        try (InputStream is = openAsset(ctx, path)) {
            if (is != null) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                if (bmp != null) {
                    bitmapCache.put(key, bmp);
                    return bmp;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Bitmap getThumb(Context ctx, String wallId) {
        String key = "thumb:" + wallId;
        Bitmap cached = bitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        String path = "cleanframe/assets/wallpapers/" + wallId + "-thumb.jpg";
        try (InputStream is = openAsset(ctx, path)) {
            if (is != null) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) {
                    bitmapCache.put(key, bmp);
                    return bmp;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Typeface getFont(Context ctx, String fontId) {
        if (fontId == null) fontId = "manrope";
        if (fontCache.containsKey(fontId)) {
            return fontCache.get(fontId);
        }
        String relPath;
        if ("geist".equals(fontId)) {
            relPath = "cleanframe/assets/fonts/geist/Geist-Variable.ttf";
        } else if ("newsreader".equals(fontId)) {
            relPath = "cleanframe/assets/fonts/newsreader/Newsreader-Variable.ttf";
        } else {
            relPath = "fonts/outfit.ttf";
        }

        Typeface tf = Typeface.DEFAULT;
        try {
            tf = Typeface.createFromAsset(ctx.getAssets(), relPath);
        } catch (Throwable notInAssets) {
            try {
                File out = new File(ctx.getCacheDir(), "font_" + fontId + ".ttf");
                if (!out.exists() || out.length() == 0) {
                    try (InputStream is = openAsset(ctx, relPath)) {
                        if (is != null) {
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = is.read(buf)) > 0) {
                                    fos.write(buf, 0, n);
                                }
                            }
                        }
                    }
                }
                if (out.exists() && out.length() > 0) {
                    tf = Typeface.createFromFile(out);
                }
            } catch (Throwable ignored) {}
        }
        fontCache.put(fontId, tf);
        return tf;
    }

    public static void drawUiIcon(Canvas c, String iconName, float cx, float cy, float size, Paint strokePaint) {
        float half = size * 0.5f;
        float l = cx - half;
        float t = cy - half;
        float s = size / 24f;

        Paint.Style oldStyle = strokePaint.getStyle();
        float oldStroke = strokePaint.getStrokeWidth();
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(2f, 2.0f * s));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        if ("art".equals(iconName)) {
            RectF r = new RectF(l + 3.5f * s, t + 4.5f * s, l + 20.5f * s, t + 19.5f * s);
            c.drawRoundRect(r, 2f * s, 2f * s, strokePaint);
            c.drawCircle(l + 9f * s, t + 9.8f * s, 1.5f * s, strokePaint);
            Path p = new Path();
            p.moveTo(l + 4.5f * s, t + 17.5f * s);
            p.lineTo(l + 9f * s, t + 13f * s);
            p.lineTo(l + 12f * s, t + 16f * s);
            p.lineTo(l + 14.5f * s, t + 13.5f * s);
            p.lineTo(l + 19.5f * s, t + 18.5f * s);
            c.drawPath(p, strokePaint);
        } else if ("apps".equals(iconName)) {
            RectF r1 = new RectF(l + 4f * s, t + 4f * s, l + 10.5f * s, t + 10.5f * s);
            RectF r2 = new RectF(l + 13.5f * s, t + 4f * s, l + 20f * s, t + 10.5f * s);
            RectF r3 = new RectF(l + 4f * s, t + 13.5f * s, l + 10.5f * s, t + 20f * s);
            RectF r4 = new RectF(l + 13.5f * s, t + 13.5f * s, l + 20f * s, t + 20f * s);
            c.drawRoundRect(r1, 1.6f * s, 1.6f * s, strokePaint);
            c.drawRoundRect(r2, 1.6f * s, 1.6f * s, strokePaint);
            c.drawRoundRect(r3, 1.6f * s, 1.6f * s, strokePaint);
            c.drawRoundRect(r4, 1.6f * s, 1.6f * s, strokePaint);
        } else if ("settings".equals(iconName)) {
            c.drawCircle(cx, cy, 3.4f * s, strokePaint);
            c.drawCircle(cx, cy, 8.2f * s, strokePaint);
        } else if ("check".equals(iconName)) {
            Path p = new Path();
            p.moveTo(l + 5.5f * s, t + 12.5f * s);
            p.lineTo(l + 9.5f * s, t + 16.5f * s);
            p.lineTo(l + 18.5f * s, t + 7.5f * s);
            c.drawPath(p, strokePaint);
        } else if ("left".equals(iconName)) {
            Path p = new Path();
            p.moveTo(l + 14.5f * s, t + 5f * s);
            p.lineTo(l + 7.5f * s, t + 12f * s);
            p.lineTo(l + 14.5f * s, t + 19f * s);
            c.drawPath(p, strokePaint);
        } else if ("right".equals(iconName)) {
            Path p = new Path();
            p.moveTo(l + 9.5f * s, t + 5f * s);
            p.lineTo(l + 16.5f * s, t + 12f * s);
            p.lineTo(l + 9.5f * s, t + 19f * s);
            c.drawPath(p, strokePaint);
        }

        strokePaint.setStyle(oldStyle);
        strokePaint.setStrokeWidth(oldStroke);
    }

    public static void drawCleanBrandMark(Canvas c, String pkg, String appName, float cx, float cy, float boxSize, int color, Paint textPaint) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        float s = boxSize / 24f;
        float l = cx - boxSize * 0.5f;
        float t = cy - boxSize * 0.5f;

        if ("com.netflix.ninja".equals(pkg)) {
            p.setStyle(Paint.Style.FILL);
            Path path = new Path();
            path.moveTo(l + 6f * s, t + 3f * s);
            path.lineTo(l + 9.5f * s, t + 3f * s);
            path.lineTo(l + 14.5f * s, t + 15f * s);
            path.lineTo(l + 14.5f * s, t + 3f * s);
            path.lineTo(l + 18f * s, t + 3f * s);
            path.lineTo(l + 18f * s, t + 21f * s);
            path.lineTo(l + 14.5f * s, t + 21f * s);
            path.lineTo(l + 9.5f * s, t + 9f * s);
            path.lineTo(l + 9.5f * s, t + 21f * s);
            path.lineTo(l + 6f * s, t + 21f * s);
            path.close();
            c.drawPath(path, p);
            return;
        }
        if ("com.google.android.youtube.tv".equals(pkg)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * s);
            RectF r = new RectF(l + 2.5f * s, t + 5.5f * s, l + 21.5f * s, t + 18.5f * s);
            c.drawRoundRect(r, 4f * s, 4f * s, p);
            p.setStyle(Paint.Style.FILL);
            Path tri = new Path();
            tri.moveTo(l + 10f * s, t + 9f * s);
            tri.lineTo(l + 15.5f * s, t + 12f * s);
            tri.lineTo(l + 10f * s, t + 15f * s);
            tri.close();
            c.drawPath(tri, p);
            return;
        }
        if ("com.spotify.tv.android".equals(pkg)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(2.0f * s);
            c.drawCircle(cx, cy, 9.5f * s, p);
            c.drawArc(new RectF(l + 6.5f * s, t + 8.5f * s, l + 17.5f * s, t + 14.5f * s), 200, 140, false, p);
            c.drawArc(new RectF(l + 7.5f * s, t + 11.5f * s, l + 16.5f * s, t + 16.5f * s), 200, 140, false, p);
            c.drawArc(new RectF(l + 8.5f * s, t + 14.5f * s, l + 15.5f * s, t + 18.5f * s), 200, 140, false, p);
            return;
        }
        if ("input_hdmi1".equals(pkg)) {
            String label = "PS5";
            float oldSize = textPaint.getTextSize();
            int oldCol = textPaint.getColor();
            textPaint.setColor(color);
            textPaint.setTextSize(boxSize * 0.48f);
            float tw = textPaint.measureText(label);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            c.drawText(label, cx - tw * 0.5f, cy - (fm.ascent + fm.descent) * 0.5f, textPaint);
            textPaint.setTextSize(oldSize);
            textPaint.setColor(oldCol);
            return;
        }

        // Clean monogram fallback
        String cleanName = appName != null ? appName.trim() : "?";
        String[] parts = cleanName.split("\\s+");
        String mono = parts[0].length() > 0 ? parts[0].substring(0, 1).toUpperCase() : "?";
        if (parts.length > 1 && parts[1].length() > 0) {
            mono += parts[1].substring(0, 1).toUpperCase();
        } else if (parts[0].length() > 1) {
            mono += parts[0].substring(1, 2).toUpperCase();
        }

        float oldSize = textPaint.getTextSize();
        int oldCol = textPaint.getColor();
        textPaint.setColor(color);
        textPaint.setTextSize(boxSize * 0.52f);
        float tw = textPaint.measureText(mono);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        c.drawText(mono, cx - tw * 0.5f, cy - (fm.ascent + fm.descent) * 0.5f, textPaint);
        textPaint.setTextSize(oldSize);
        textPaint.setColor(oldCol);
    }
}
