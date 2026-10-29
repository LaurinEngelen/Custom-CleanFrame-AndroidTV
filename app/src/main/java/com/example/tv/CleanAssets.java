package com.example.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CleanAssets {

    private static ZipFile externalZip = null;
    private static final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(56 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private static final Map<String, BitmapShader> shaderCache = new ConcurrentHashMap<>();
    private static final Map<String, Typeface> fontCache = new ConcurrentHashMap<>();
    private static final Map<Integer, Drawable> vectorDrawableCache = new ConcurrentHashMap<>();
    private static final Set<String> pendingLoads = Collections.synchronizedSet(new HashSet<>());

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

    /**
     * Non-blocking getter for UI thread onDraw: returns cached Bitmap immediately or triggers background load.
     */
    public static Bitmap getWallpaperCached(final Context ctx, final String wallId, final Runnable onLoaded) {
        final String key = "wall:" + wallId;
        Bitmap cached = bitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        if (pendingLoads.add(key)) {
            new Thread(() -> {
                try {
                    loadWallpaperSync(ctx, wallId);
                } finally {
                    pendingLoads.remove(key);
                }
                if (onLoaded != null) onLoaded.run();
            }).start();
        }
        return null;
    }

    public static Bitmap loadWallpaperSync(Context ctx, String wallId) {
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

    /**
     * Non-blocking getter for material BitmapShader scaled to 1920x1080 stage coordinates.
     * Avoids canvas.clipPath() so rounded corners are 100% smooth and anti-aliased on GPU!
     */
    public static BitmapShader getMaterialShaderCached(final Context ctx, final String wallId, final String materialMode, final Runnable onLoaded) {
        String suffix = "soft".equals(materialMode)
                ? "-study-soft.jpg"
                : "tint".equals(materialMode) ? "-field-clear.jpg" : "-field-frost.jpg";
        final String key = "mat:" + wallId + ":" + suffix;
        BitmapShader shader = shaderCache.get(key);
        if (shader != null) return shader;

        if (pendingLoads.add(key)) {
            new Thread(() -> {
                try {
                    loadMaterialShaderSync(ctx, wallId, materialMode);
                } finally {
                    pendingLoads.remove(key);
                }
                if (onLoaded != null) onLoaded.run();
            }).start();
        }
        return null;
    }

    public static BitmapShader loadMaterialShaderSync(Context ctx, String wallId, String materialMode) {
        String suffix = "soft".equals(materialMode)
                ? "-study-soft.jpg"
                : "tint".equals(materialMode) ? "-field-clear.jpg" : "-field-frost.jpg";
        String key = "mat:" + wallId + ":" + suffix;
        BitmapShader existing = shaderCache.get(key);
        if (existing != null) return existing;

        String path = "cleanframe/assets/materials/" + wallId + suffix;
        try (InputStream is = openAsset(ctx, path)) {
            if (is != null) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                if (bmp != null) {
                    bitmapCache.put(key, bmp);
                    BitmapShader bs = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    Matrix m = new Matrix();
                    m.setScale(1920f / bmp.getWidth(), 1080f / bmp.getHeight());
                    bs.setLocalMatrix(m);
                    shaderCache.put(key, bs);
                    return bs;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final Matrix sharedShaderMatrix = new Matrix();

    public static void applyMaterialLensMatrix(BitmapShader bs, String wallId, String materialMode, boolean curvedLens, float pivotX, float pivotY) {
        if (bs == null) return;
        String suffix = "soft".equals(materialMode)
                ? "-study-soft.jpg"
                : "tint".equals(materialMode) ? "-field-clear.jpg" : "-field-frost.jpg";
        String key = "mat:" + wallId + ":" + suffix;
        Bitmap bmp = bitmapCache.get(key);
        float bw = (bmp != null && !bmp.isRecycled()) ? bmp.getWidth() : 1920f;
        float bh = (bmp != null && !bmp.isRecycled()) ? bmp.getHeight() : 1080f;
        sharedShaderMatrix.reset();
        sharedShaderMatrix.setScale(1920f / bw, 1080f / bh);
        if (curvedLens) {
            // Subtle optical convex lens refraction around the dock center (Apple Liquid Glass effect)
            sharedShaderMatrix.postScale(1.045f, 1.12f, pivotX, pivotY);
        }
        bs.setLocalMatrix(sharedShaderMatrix);
    }

    public static Bitmap getThumbCached(final Context ctx, final String wallId, final Runnable onLoaded) {
        final String key = "thumb:" + wallId;
        Bitmap cached = bitmapCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        if (pendingLoads.add(key)) {
            new Thread(() -> {
                try {
                    String path = "cleanframe/assets/wallpapers/" + wallId + "-thumb.jpg";
                    try (InputStream is = openAsset(ctx, path)) {
                        if (is != null) {
                            Bitmap bmp = BitmapFactory.decodeStream(is);
                            if (bmp != null) {
                                bitmapCache.put(key, bmp);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    pendingLoads.remove(key);
                }
                if (onLoaded != null) onLoaded.run();
            }).start();
        }
        return null;
    }

    public static Typeface getFont(Context ctx, String fontId) {
        if (fontId == null) fontId = "manrope";
        if ("mono".equals(fontId)) return Typeface.MONOSPACE;
        if ("thin".equals(fontId)) {
            Typeface t = fontCache.get("thin");
            if (t == null) {
                t = Typeface.create("sans-serif-thin", Typeface.NORMAL);
                if (t == null) t = Typeface.DEFAULT;
                fontCache.put("thin", t);
            }
            return t;
        }
        Typeface existing = fontCache.get(fontId);
        if (existing != null) return existing;

        // Preload on background if not loaded yet, return default immediately to never block UI thread
        final String fid = fontId;
        if (pendingLoads.add("font:" + fid)) {
            new Thread(() -> {
                try {
                    loadFontSync(ctx, fid);
                } finally {
                    pendingLoads.remove("font:" + fid);
                }
            }).start();
        }
        return Typeface.DEFAULT;
    }

    public static Typeface loadFontSync(Context ctx, String fontId) {
        if (fontId == null) fontId = "manrope";
        if ("mono".equals(fontId)) return Typeface.MONOSPACE;
        if ("thin".equals(fontId)) return getFont(ctx, "thin");
        if (fontCache.containsKey(fontId)) return fontCache.get(fontId);

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

    private static Drawable getVectorDrawable(Context ctx, int resId) {
        Drawable d = vectorDrawableCache.get(resId);
        if (d == null) {
            try {
                d = ctx.getResources().getDrawable(resId, null).mutate();
                vectorDrawableCache.put(resId, d);
            } catch (Throwable ignored) {}
        }
        return d;
    }

    public static void drawUiIcon(Context ctx, Canvas c, String iconName, float cx, float cy, float size, int color, Paint strokePaint) {
        int resId = 0;
        if ("art".equals(iconName)) resId = R.drawable.ic_clean_art;
        else if ("apps".equals(iconName)) resId = R.drawable.ic_clean_apps;
        else if ("settings".equals(iconName)) resId = R.drawable.ic_clean_settings;

        if (resId != 0) {
            Drawable d = getVectorDrawable(ctx, resId);
            if (d != null) {
                int half = Math.round(size * 0.5f);
                int icx = Math.round(cx);
                int icy = Math.round(cy);
                d.setTint(color);
                d.setBounds(icx - half, icy - half, icx + half, icy + half);
                d.draw(c);
                return;
            }
        }

        float half = size * 0.5f;
        float l = cx - half;
        float t = cy - half;
        float s = size / 24f;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(Math.max(2f, 2.0f * s));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        if ("check".equals(iconName)) {
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
    }

    public static int getCleanBrandVectorRes(String pkg) {
        if (pkg == null) return 0;
        switch (pkg) {
            case "com.netflix.ninja":
                return R.drawable.ic_clean_netflix;
            case "com.google.android.youtube.tv":
            case "com.google.android.youtube.tvkids":
                return R.drawable.ic_clean_youtube;
            case "com.disney.disneyplus":
            case "in.startv.hotstar":
                return R.drawable.ic_clean_disneyplus;
            case "com.spotify.tv.android":
                return R.drawable.ic_clean_spotify;
            case "com.amazon.amazonvideo.livingroom":
                return R.drawable.ic_clean_primevideo;
            case "com.wbd.stream":
            case "com.hbo.hbonow":
                return R.drawable.ic_clean_hbomax;
            case "com.apple.atve.androidtv.appletv":
                return R.drawable.ic_brand_appletv;
            default:
                return 0;
        }
    }

    public static void drawCleanBrandMark(Context ctx, Canvas c, String pkg, String appName, float cx, float cy, float boxSize, int color, Paint textPaint, Paint strokePaint) {
        int vectorRes = getCleanBrandVectorRes(pkg);
        if (vectorRes != 0) {
            Drawable d = getVectorDrawable(ctx, vectorRes);
            if (d != null) {
                // Apply optical scaling matching CleanScreen metrics.js
                float scale = 0.78f;
                if ("com.disney.disneyplus".equals(pkg)) scale = 0.92f;
                else if ("com.amazon.amazonvideo.livingroom".equals(pkg)) scale = 0.95f;
                else if ("com.netflix.ninja".equals(pkg)) scale = 0.72f;
                else if ("com.google.android.youtube.tv".equals(pkg)) scale = 0.76f;
                else if ("com.spotify.tv.android".equals(pkg)) scale = 0.74f;

                int half = Math.round(boxSize * scale * 0.5f);
                int icx = Math.round(cx);
                int icy = Math.round(cy);
                d.setTint(color);
                d.setBounds(icx - half, icy - half, icx + half, icy + half);
                d.draw(c);
                return;
            }
        }

        // Clean monogram fallback with subtle rounded outline (.monogram span in style.css)
        String cleanName = appName != null ? appName.trim() : "?";
        String[] parts = cleanName.split("\\s+");
        String mono = parts[0].length() > 0 ? parts[0].substring(0, 1).toUpperCase() : "?";
        if (parts.length > 1 && parts[1].length() > 0) {
            mono += parts[1].substring(0, 1).toUpperCase();
        } else if (parts[0].length() > 1) {
            mono += parts[0].substring(1, 2).toUpperCase();
        }

        float boxR = boxSize * 0.42f;
        RectF monoRect = new RectF(cx - boxR, cy - boxR, cx + boxR, cy + boxR);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.2f);
        strokePaint.setColor((color & 0x00FFFFFF) | 0x60000000);
        c.drawRoundRect(monoRect, boxR * 0.42f, boxR * 0.42f, strokePaint);

        float oldSize = textPaint.getTextSize();
        int oldCol = textPaint.getColor();
        textPaint.setColor(color);
        textPaint.setTextSize(boxSize * 0.38f);
        float tw = textPaint.measureText(mono);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        c.drawText(mono, cx - tw * 0.5f, cy - (fm.ascent + fm.descent) * 0.5f, textPaint);
        textPaint.setTextSize(oldSize);
        textPaint.setColor(oldCol);
    }
}
