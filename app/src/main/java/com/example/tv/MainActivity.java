package com.example.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    public static class NativeAppEntry {
        public final String packageName;
        public final String label;
        public volatile Bitmap iconBitmap;

        public NativeAppEntry(String packageName, String label, Bitmap iconBitmap) {
            this.packageName = packageName;
            this.label = label;
            this.iconBitmap = iconBitmap;
        }
    }

    private static final Map<String, NativeAppEntry> appMap = new ConcurrentHashMap<>();
    private static volatile List<NativeAppEntry> allAppsSorted = new ArrayList<>();
    private static volatile boolean appsLoaded = false;

    private CleanConfig config;
    private NativeHomeView homeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TvInputHelper.ensureGlobalKeyWatcherStarted(this);

        config = CleanConfig.load(this);
        ensureDefaultFallbackEntries();

        homeView = new NativeHomeView(this, config);
        setContentView(homeView);

        if (!appsLoaded) {
            new Thread(this::loadInstalledAppsNative).start();
        }
    }

    private void ensureDefaultFallbackEntries() {
        putFallbackIfAbsent("com.netflix.ninja", "Netflix", R.drawable.ic_brand_netflix);
        putFallbackIfAbsent("com.google.android.youtube.tv", "YouTube", R.drawable.ic_brand_youtube);
        putFallbackIfAbsent("input_hdmi1", "PlayStation 5", R.drawable.ic_brand_ps5);
        putFallbackIfAbsent("com.spotify.tv.android", "Spotify", R.drawable.ic_brand_spotify);
        putFallbackIfAbsent("com.disney.disneyplus", "Disney+", R.drawable.ic_brand_disneyplus);
        putFallbackIfAbsent("com.stremio.one", "Stremio", 0);
        putFallbackIfAbsent("com.amazon.amazonvideo.livingroom", "Prime Video", 0);
    }

    private void putFallbackIfAbsent(String pkg, String label, int drawableRes) {
        if (!appMap.containsKey(pkg)) {
            Bitmap bmp = null;
            if (drawableRes != 0) {
                try {
                    bmp = BitmapFactory.decodeResource(getResources(), drawableRes);
                } catch (Throwable ignored) {}
            }
            appMap.put(pkg, new NativeAppEntry(pkg, label, bmp));
        }
    }

    private void loadInstalledAppsNative() {
        try {
            PackageManager pm = getPackageManager();
            Intent leanbackIntent = new Intent(Intent.ACTION_MAIN, null);
            leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> leanbackApps = pm.queryIntentActivities(leanbackIntent, 0);

            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> standardApps = pm.queryIntentActivities(launcherIntent, 0);

            Map<String, ResolveInfo> merged = new LinkedHashMap<>();
            if (leanbackApps != null) {
                for (ResolveInfo ri : leanbackApps) {
                    if (ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) {
                        merged.put(ri.activityInfo.packageName, ri);
                    }
                }
            }
            if (standardApps != null) {
                for (ResolveInfo ri : standardApps) {
                    if (ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) {
                        if (!merged.containsKey(ri.activityInfo.packageName)) {
                            merged.put(ri.activityInfo.packageName, ri);
                        }
                    }
                }
            }

            Set<String> blacklist = new HashSet<>(Arrays.asList(
                    "com.mstar.netflixobserver",
                    "com.mediatek.androidbox",
                    "mediatek.factorymenu.ui",
                    "com.mediatek.wwtv.setupwizard",
                    "com.mediatek.tv.factory",
                    "com.mediatek.hotkey.dispatcher",
                    "com.mediatek.tv.agent",
                    "com.android.cts.ctsshim",
                    "com.android.cts.priv.ctsshim",
                    "com.google.android.katniss",
                    "com.google.android.tungsten.setupwraith"
            ));

            List<NativeAppEntry> loadedList = new ArrayList<>();
            // Always include HDMI 1 / PlayStation 5 shortcut
            NativeAppEntry ps5 = appMap.get("input_hdmi1");
            if (ps5 != null) {
                loadedList.add(ps5);
            }

            for (ResolveInfo ri : merged.values()) {
                if (ri.activityInfo == null || blacklist.contains(ri.activityInfo.packageName)) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm).toString();
                Bitmap iconBmp = null;

                NativeAppEntry existing = appMap.get(pkg);
                if (existing != null && existing.iconBitmap != null) {
                    iconBmp = existing.iconBitmap;
                } else {
                    try {
                        Drawable d = ri.loadIcon(pm);
                        if (d != null) {
                            int size = 112;
                            iconBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                            Canvas c = new Canvas(iconBmp);
                            d.setBounds(0, 0, size, size);
                            d.draw(c);
                        }
                    } catch (Throwable ignored) {}
                }

                NativeAppEntry entry = new NativeAppEntry(pkg, label, iconBmp);
                appMap.put(pkg, entry);
                loadedList.add(entry);
            }

            Collections.sort(loadedList, (a, b) -> a.label.compareToIgnoreCase(b.label));
            allAppsSorted = loadedList;
            appsLoaded = true;

            if (homeView != null) {
                homeView.onAppsLoaded();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error loading native apps", t);
        }
    }

    public NativeAppEntry getAppEntry(String pkg) {
        return appMap.get(pkg);
    }

    public List<NativeAppEntry> getAllAppsList() {
        return allAppsSorted;
    }

    public void setKeepScreenOnFlag(boolean keepOn) {
        runOnUiThread(() -> {
            try {
                if (keepOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            } catch (Throwable ignored) {}
        });
    }

    public boolean isDefaultLauncher() {
        try {
            ComponentName alias = new ComponentName(this, "com.example.tv.HomeLauncherAlias");
            int state = getPackageManager().getComponentEnabledSetting(alias);
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    public void setDefaultLauncher(String which) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, "com.example.tv.HomeLauncherAlias");
            if ("projectivy".equalsIgnoreCase(which)) {
                pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
                Toast.makeText(this, "Projectivy ist jetzt Standard-Launcher", Toast.LENGTH_SHORT).show();
                handleLaunch("com.spocky.projengmenu");
                finish();
            } else {
                pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                );
                try {
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.killBackgroundProcesses("com.spocky.projengmenu");
                    }
                } catch (Throwable ignored) {}
                Toast.makeText(this, "CleanScreen ist jetzt Standard-Launcher", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to setDefaultLauncher", t);
        }
    }

    public String getRamStatusText() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long freeMb = mi.availMem / (1024L * 1024L);
                long totalMb = mi.totalMem / (1024L * 1024L);
                return "Alle Apps beenden · " + freeMb + " / " + totalMb + " MB frei";
            }
        } catch (Throwable ignored) {}
        return "Alle Hintergrund-Apps beenden";
    }

    public void clearRamAndKillApps() {
        new Thread(() -> {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                PackageManager pm = getPackageManager();
                long beforeMem = 0L;
                if (am != null) {
                    ActivityManager.MemoryInfo miBefore = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(miBefore);
                    beforeMem = miBefore.availMem;
                }

                Set<String> keepPkgs = new HashSet<>(Arrays.asList(
                        getPackageName(),
                        "android",
                        "com.android.systemui",
                        "com.xiaomi.android.tvsetup.partnercustomizer",
                        "com.xiaomi.floatingframe",
                        "com.mediatek.tvinput",
                        "com.google.android.backdrop",
                        "com.android.tv.settings"
                ));

                if (am != null && pm != null) {
                    List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : apps) {
                        if (ai == null || ai.packageName == null || keepPkgs.contains(ai.packageName)) {
                            continue;
                        }
                        try {
                            am.killBackgroundProcesses(ai.packageName);
                        } catch (Throwable ignored) {}
                    }
                }

                System.gc();
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {}

                long afterMem = beforeMem;
                long totalMem = 0L;
                if (am != null) {
                    ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(miAfter);
                    afterMem = miAfter.availMem;
                    totalMem = miAfter.totalMem;
                }

                final long freeMb = afterMem / (1024L * 1024L);
                final long totalMb = totalMem / (1024L * 1024L);
                final long diffMb = Math.max(0L, (afterMem - beforeMem) / (1024L * 1024L));

                runOnUiThread(() -> {
                    String msg = diffMb > 0
                            ? "Hintergrund-Apps beendet (+" + diffMb + " MB frei · Jetzt " + freeMb + " MB frei)"
                            : "Hintergrund-Apps beendet (" + freeMb + " / " + totalMb + " MB frei)";
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    if (homeView != null) {
                        homeView.invalidate();
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Failed to clear RAM", t);
            }
        }).start();
    }

    public void openAndroidSettings() {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage("com.android.tv.settings");
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                }
            } catch (Throwable ignored) {}
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable ignored) {}
        }
    }

    private static final Map<String, String> BRAND_TO_PACKAGE = new HashMap<>();
    static {
        BRAND_TO_PACKAGE.put("netflix", "com.netflix.ninja");
        BRAND_TO_PACKAGE.put("youtube", "com.google.android.youtube.tv");
        BRAND_TO_PACKAGE.put("spotify", "com.spotify.tv.android");
        BRAND_TO_PACKAGE.put("disneyplus", "com.disney.disneyplus");
        BRAND_TO_PACKAGE.put("disney", "com.disney.disneyplus");
        BRAND_TO_PACKAGE.put("stremio", "com.stremio.one");
        BRAND_TO_PACKAGE.put("primevideo", "com.amazon.amazonvideo.livingroom");
    }

    public void handleLaunch(String target) {
        if ("input_hdmi1".equalsIgnoreCase(target) || "playstation".equalsIgnoreCase(target)) {
            TvInputHelper.switchToInput(this, TvInputHelper.INPUT_HDMI1, "PlayStation 5");
            return;
        }

        String pkg = target;
        if (target != null && BRAND_TO_PACKAGE.containsKey(target.toLowerCase())) {
            pkg = BRAND_TO_PACKAGE.get(target.toLowerCase());
        }

        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(pkg);
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            redirectToPlayStore(pkg);
        }
    }

    private void redirectToPlayStore(String packageName) {
        try {
            Intent marketIntent = new Intent(Intent.ACTION_VIEW);
            marketIntent.setData(Uri.parse("market://details?id=" + packageName));
            marketIntent.setPackage("com.android.vending");
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(marketIntent);
        } catch (Throwable t1) {
            Toast.makeText(this, "Nicht installiert: " + packageName, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            // Settings Button on Remote
            if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU || keyCode == 209 || keyCode == 176 || keyCode == 82 || keyCode == 165 || keyCode == 259) {
                openAndroidSettings();
                return true;
            }
            // TV Input / Source Button
            if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == 178 || keyCode == 182 || keyCode == 170) {
                TvInputHelper.showInputPicker(this);
                return true;
            }
            // Netflix Button
            if (keyCode == 191 || keyCode == 288 || keyCode == 196 || keyCode == 188) {
                handleLaunch("com.netflix.ninja");
                return true;
            }
            // Prime Video Button
            if (keyCode == 192 || keyCode == 289 || keyCode == 197 || keyCode == 189) {
                handleLaunch("com.amazon.amazonvideo.livingroom");
                return true;
            }
        }

        if (homeView != null && homeView.handleKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (homeView != null) {
            homeView.requestFocus();
            homeView.resetIdleTimer();
            homeView.invalidate();
        }
    }
}
