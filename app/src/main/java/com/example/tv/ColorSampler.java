package com.example.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

public class ColorSampler {

    public static int getAppSignatureColor(String packageName, Drawable drawable) {
        if (packageName != null) {
            switch (packageName) {
                case "com.google.android.youtube.tv":
                    return 0xFFFF0033; // YouTube Crimson
                case "com.netflix.ninja":
                    return 0xFFE50914; // Netflix Red
                case "com.amazon.amazonvideo.livingroom":
                    return 0xFF00A8E1; // Prime Video Cyan Blue
                case "com.android.vending":
                    return 0xFF0086F8; // Google Play Blue
                case "com.android.tv":
                    return 0xFF10B981; // Live TV Emerald
                case "com.xiaomi.mitv.smartshare":
                    return 0xFFFF6900; // Mi Orange
            }
        }
        return sampleDominantColor(drawable, 0xFF3B82F6);
    }

    public static int sampleDominantColor(Drawable drawable, int fallbackColor) {
        if (drawable == null) {
            return fallbackColor;
        }

        try {
            int width = 16;
            int height = 16;
            Bitmap bitmap;

            if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
                Bitmap orig = ((BitmapDrawable) drawable).getBitmap();
                bitmap = Bitmap.createScaledBitmap(orig, width, height, false);
            } else {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);
            }

            int bestColor = fallbackColor;
            float maxScore = -1f;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    int alpha = Color.alpha(pixel);
                    if (alpha < 128) continue;

                    float[] hsv = new float[3];
                    Color.colorToHSV(pixel, hsv);
                    float sat = hsv[1];
                    float val = hsv[2];

                    if (val > 0.25f && val < 0.95f && sat > 0.25f) {
                        float score = sat * 1.5f + val;
                        if (score > maxScore) {
                            maxScore = score;
                            bestColor = pixel;
                        }
                    }
                }
            }

            bitmap.recycle();
            return (bestColor & 0x00FFFFFF) | 0xFF000000;
        } catch (Throwable t) {
            return fallbackColor;
        }
    }
}
