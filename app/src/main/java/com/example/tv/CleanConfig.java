package com.example.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CleanConfig {

    private static final String PREFS_NAME = "cleanframe_native_v2";

    public static class WallInfo {
        public final String id;
        public final String name;
        public final boolean lightTone;

        public WallInfo(String id, String name, boolean lightTone) {
            this.id = id;
            this.name = name;
            this.lightTone = lightTone;
        }
    }

    public static final WallInfo[] WALLS = new WallInfo[]{
            new WallInfo("misty", "Misty coast", true),
            new WallInfo("alpine", "Alpine lake", false),
            new WallInfo("amber", "Amber dunes", false),
            new WallInfo("bridge", "Golden Gate mist", true),
            new WallInfo("cove", "Quiet cove", false),
            new WallInfo("forest", "Redwood canopy", false),
            new WallInfo("goldcoast", "Gold Coast surf", true),
            new WallInfo("golden", "Golden hills", true),
            new WallInfo("mint", "Mint glacier", true),
            new WallInfo("night", "Night peaks", false),
            new WallInfo("peaks", "Granite peaks", false),
            new WallInfo("ridge", "Blue ridge", false),
            new WallInfo("summit", "Winter summit", true),
            new WallInfo("sydney", "Sydney harbour", false),
            new WallInfo("valley", "Yosemite valley", false)
    };

    public static final List<String> DEFAULT_HOME_APPS = Arrays.asList(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android"
    );

    public String bg = "photo";          // photo, light, dark
    public String wall = "misty";        // wallpaper id
    public String mode = "fixed";        // fixed, open, 5, 15, 60
    public String layout = "dock";       // dock, list
    public int dockPosX = 50;            // 0..100 (50 = centered)
    public int dockPosY = 85;            // 10..98 (85 = standard bottom)
    public int dockScale = 100;          // 50..140 (100 = 100%)
    public String iconstyle = "clean";   // clean (CleanFrame SVG vectors), original (Android app icons)
    public String artmode = "on";        // on, off
    public String apps = "both";         // icons, both, text
    public String labelpos = "bottom";   // bottom (Unten), top (Oben) - when apps == "icons"
    public String controls = "icons";    // icons, both, text
    public String font = "manrope";      // manrope, geist, newsreader
    public String focus = "glass";       // glass, type
    public String material = "frost";    // soft, frost, tint
    public String focuszoom = "on";      // on (slightly larger on focus), off
    public String glasscurve = "on";     // on (Apple curved liquid glass), off (flat classic glass)
    public final List<String> homeApps = new ArrayList<>();

    public static CleanConfig load(Context ctx) {
        CleanConfig c = new CleanConfig();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        c.bg = sp.getString("bg", "photo");
        c.wall = sp.getString("wall", "misty");
        c.mode = sp.getString("mode", "fixed");
        c.layout = sp.getString("layout", "dock");
        c.dockPosX = clamp(sp.getInt("dockPosX", 50), 0, 100);
        c.dockPosY = clamp(sp.getInt("dockPosY", 85), 10, 98);
        c.dockScale = clamp(sp.getInt("dockScale", 100), 50, 140);
        c.iconstyle = sp.getString("iconstyle", "clean");
        c.artmode = sp.getString("artmode", "on");
        c.apps = sp.getString("apps", "both");
        c.labelpos = sp.getString("labelpos", "bottom");
        c.controls = sp.getString("controls", "icons");
        c.font = sp.getString("font", "manrope");
        c.focus = sp.getString("focus", "glass");
        c.material = sp.getString("material", "frost");
        c.focuszoom = sp.getString("focuszoom", "on");
        c.glasscurve = sp.getString("glasscurve", "on");

        String homeJson = sp.getString("homeApps", null);
        if (homeJson != null) {
            try {
                JSONArray arr = new JSONArray(homeJson);
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i, null);
                    if (pkg != null && !pkg.isEmpty()
                            && !"input_hdmi1".equals(pkg)
                            && !"com.stremio.one".equals(pkg)
                            && !c.homeApps.contains(pkg)) {
                        c.homeApps.add(pkg);
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (c.homeApps.isEmpty()) {
            c.homeApps.addAll(DEFAULT_HOME_APPS);
        }
        return c;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putString("bg", bg);
        ed.putString("wall", wall);
        ed.putString("mode", mode);
        ed.putString("layout", layout);
        ed.putInt("dockPosX", dockPosX);
        ed.putInt("dockPosY", dockPosY);
        ed.putInt("dockScale", dockScale);
        ed.putString("iconstyle", iconstyle);
        ed.putString("artmode", artmode);
        ed.putString("apps", apps);
        ed.putString("labelpos", labelpos);
        ed.putString("controls", controls);
        ed.putString("font", font);
        ed.putString("focus", focus);
        ed.putString("material", material);
        ed.putString("focuszoom", focuszoom);
        ed.putString("glasscurve", glasscurve);

        JSONArray arr = new JSONArray();
        for (String pkg : homeApps) {
            arr.put(pkg);
        }
        ed.putString("homeApps", arr.toString());
        ed.apply();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static WallInfo getWallInfo(String id) {
        for (WallInfo w : WALLS) {
            if (w.id.equals(id)) return w;
        }
        return WALLS[0];
    }

    public static int getWallIndex(String id) {
        for (int i = 0; i < WALLS.length; i++) {
            if (WALLS[i].id.equals(id)) return i;
        }
        return 0;
    }

    public float getScaleFactor() {
        return dockScale / 100f;
    }
}
