package com.example.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private WebView webView;
    private static volatile String cachedAppsJson = null;
    private static ZipFile externalAssetsZip = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TvInputHelper.ensureGlobalKeyWatcherStarted(this);

        if (cachedAppsJson == null) {
            new Thread(this::queryInstalledAppsJson).start();
        }

        initWebView();
    }

    private static synchronized ZipFile getExternalAssetsZip() {
        if (externalAssetsZip != null) return externalAssetsZip;
        try {
            File f = new File("/data/local/tmp/assets_pack.zip");
            if (f.exists() && f.canRead()) {
                externalAssetsZip = new ZipFile(f);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not open assets_pack.zip", t);
        }
        return externalAssetsZip;
    }

    private static String guessMimeType(String path) {
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    private void openAndroidSettings() {
        runOnUiThread(() -> {
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
        });
    }

    private void initWebView() {
        webView = findViewById(R.id.cleanframe_webview);
        webView.setBackgroundColor(0xFF000000);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri uri = request.getUrl();
                    if (uri != null) {
                        String url = uri.toString();
                        String prefix = "file:///android_asset/";
                        if (url.startsWith(prefix)) {
                            String relPath = url.substring(prefix.length());
                            int q = relPath.indexOf('?');
                            if (q >= 0) relPath = relPath.substring(0, q);
                            File overrideFile = new File("/data/local/tmp/cleanframe_override/" + relPath);
                            if (overrideFile.exists() && overrideFile.canRead()) {
                                return new WebResourceResponse(guessMimeType(relPath), "UTF-8", new java.io.FileInputStream(overrideFile));
                            }
                            try {
                                InputStream is = getAssets().open(relPath);
                                return new WebResourceResponse(guessMimeType(relPath), "UTF-8", is);
                            } catch (Throwable notInApk) {
                                ZipFile zf = getExternalAssetsZip();
                                if (zf != null) {
                                    ZipEntry entry = zf.getEntry("assets/" + relPath);
                                    if (entry != null) {
                                        return new WebResourceResponse(guessMimeType(relPath), null, zf.getInputStream(entry));
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (webView != null) {
                    webView.requestFocus();
                }
            }
        });

        webView.loadUrl("file:///android_asset/cleanframe/index.html");
    }

    public class AndroidBridge {
        private final Context context;

        public AndroidBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void launch(String target) {
            runOnUiThread(() -> handleLaunch(target));
        }

        @JavascriptInterface
        public void launchApp(String target) {
            runOnUiThread(() -> handleLaunch(target));
        }

        @JavascriptInterface
        public void launchProjectivy() {
            runOnUiThread(() -> handleLaunch("com.spocky.projengmenu"));
        }

        @JavascriptInterface
        public boolean isDefaultLauncher() {
            try {
                ComponentName alias = new ComponentName(MainActivity.this, "com.example.tv.HomeLauncherAlias");
                int state = getPackageManager().getComponentEnabledSetting(alias);
                return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            } catch (Throwable t) {
                return true;
            }
        }

        @JavascriptInterface
        public void setDefaultLauncher(String which) {
            runOnUiThread(() -> {
                try {
                    PackageManager pm = getPackageManager();
                    ComponentName alias = new ComponentName(MainActivity.this, "com.example.tv.HomeLauncherAlias");
                    if ("projectivy".equalsIgnoreCase(which)) {
                        pm.setComponentEnabledSetting(
                                alias,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP
                        );
                        Toast.makeText(MainActivity.this, "Projectivy ist jetzt Standard-Launcher", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(MainActivity.this, "CleanScreen ist jetzt Standard-Launcher", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to setDefaultLauncher", t);
                }
            });
        }

        @JavascriptInterface
        public void openHomeSettings() {
            setDefaultLauncher(isDefaultLauncher() ? "projectivy" : "clean");
        }

        @JavascriptInterface
        public void openHdmi(String port) {
            runOnUiThread(() -> {
                TvInputHelper.switchToInput(MainActivity.this, TvInputHelper.INPUT_HDMI1, "PlayStation 5");
            });
        }

        @JavascriptInterface
        public void openSettings() {
            openAndroidSettings();
        }

        @JavascriptInterface
        public void openNetworkSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Throwable t1) {
                    openAndroidSettings();
                }
            });
        }

        @JavascriptInterface
        public String getNetworkType() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    NetworkInfo active = cm.getActiveNetworkInfo();
                    if (active != null && active.isConnected()) {
                        if (active.getType() == ConnectivityManager.TYPE_ETHERNET) {
                            return "ethernet";
                        } else if (active.getType() == ConnectivityManager.TYPE_WIFI) {
                            return "wifi";
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return "wifi";
        }

        @JavascriptInterface
        public void openScreensaver() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setClassName("com.google.android.backdrop", "com.google.android.backdrop.Backdrop");
                    startActivity(intent);
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean keepOn) {
            runOnUiThread(() -> {
                try {
                    if (keepOn) {
                        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public String getRamStatus() {
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

        @JavascriptInterface
        public String clearRamAndKillApps() {
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

                int killedCount = 0;
                if (am != null && pm != null) {
                    List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                    for (android.content.pm.ApplicationInfo ai : apps) {
                        if (ai == null || ai.packageName == null || keepPkgs.contains(ai.packageName)) {
                            continue;
                        }
                        try {
                            am.killBackgroundProcesses(ai.packageName);
                            killedCount++;
                        } catch (Throwable ignored) {}
                    }
                }

                System.gc();
                try {
                    Thread.sleep(150);
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
                });

                return "Alle Apps beenden · " + freeMb + " / " + totalMb + " MB frei";
            } catch (Throwable t) {
                Log.e(TAG, "Failed to clear RAM", t);
                return "Alle Hintergrund-Apps beenden";
            }
        }

        @JavascriptInterface
        public int getScreenWidth() {
            return getResources().getDisplayMetrics().widthPixels;
        }

        @JavascriptInterface
        public int getScreenHeight() {
            return getResources().getDisplayMetrics().heightPixels;
        }

        @JavascriptInterface
        public String getInstalledAppsJson() {
            if (cachedAppsJson != null) {
                return cachedAppsJson;
            }
            return queryInstalledAppsJson();
        }
    }

    private static final Map<String, String> BRAND_TO_PACKAGE = new HashMap<>();
    static {
        BRAND_TO_PACKAGE.put("netflix", "com.netflix.ninja");
        BRAND_TO_PACKAGE.put("youtube", "com.google.android.youtube.tv");
        BRAND_TO_PACKAGE.put("spotify", "com.spotify.tv.android");
        BRAND_TO_PACKAGE.put("disneyplus", "com.disney.disneyplus");
        BRAND_TO_PACKAGE.put("disney", "com.disney.disneyplus");
        BRAND_TO_PACKAGE.put("hotstar", "com.disney.disneyplus");
        BRAND_TO_PACKAGE.put("in.startv.hotstar", "com.disney.disneyplus");
        BRAND_TO_PACKAGE.put("stremio", "com.stremio.one");
        BRAND_TO_PACKAGE.put("primevideo", "com.amazon.amazonvideo.livingroom");
    }

    private void handleLaunch(String target) {
        if ("input_hdmi1".equalsIgnoreCase(target) || "playstation".equalsIgnoreCase(target)) {
            TvInputHelper.switchToInput(this, TvInputHelper.INPUT_HDMI1, "PlayStation 5");
            return;
        }

        String pkg = target;
        if (target != null && BRAND_TO_PACKAGE.containsKey(target.toLowerCase())) {
            pkg = BRAND_TO_PACKAGE.get(target.toLowerCase());
        }

        if ("in.startv.hotstar".equals(pkg)) {
            pkg = "com.disney.disneyplus";
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
            try {
                Intent marketIntent = new Intent(Intent.ACTION_VIEW);
                marketIntent.setData(Uri.parse("market://details?id=" + packageName));
                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(marketIntent);
            } catch (Throwable t2) {
                Toast.makeText(this, "Nicht verfügbar: " + packageName, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private synchronized String queryInstalledAppsJson() {
        if (cachedAppsJson != null) {
            return cachedAppsJson;
        }
        try {
            PackageManager pm = getPackageManager();
            Intent leanbackIntent = new Intent(Intent.ACTION_MAIN, null);
            leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> leanbackApps = pm.queryIntentActivities(leanbackIntent, 0);

            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> standardApps = pm.queryIntentActivities(launcherIntent, 0);

            Map<String, ResolveInfo> allAppsMap = new LinkedHashMap<>();
            for (ResolveInfo ri : leanbackApps) {
                if (ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) {
                    allAppsMap.put(ri.activityInfo.packageName, ri);
                }
            }
            for (ResolveInfo ri : standardApps) {
                if (ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) {
                    if (!allAppsMap.containsKey(ri.activityInfo.packageName)) {
                        allAppsMap.put(ri.activityInfo.packageName, ri);
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

            File iconDir = new File(getCacheDir(), "icons");
            if (!iconDir.exists()) {
                iconDir.mkdirs();
            }

            JSONArray array = new JSONArray();
            for (ResolveInfo ri : allAppsMap.values()) {
                if (ri.activityInfo == null || blacklist.contains(ri.activityInfo.packageName)) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                JSONObject obj = new JSONObject();
                obj.put("packageName", pkg);
                obj.put("appName", ri.loadLabel(pm).toString());

                try {
                    File iconFile = new File(iconDir, pkg + ".png");
                    if (!iconFile.exists() || iconFile.length() == 0) {
                        Drawable d = ri.loadIcon(pm);
                        if (d != null) {
                            int size = 96;
                            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(bmp);
                            d.setBounds(0, 0, size, size);
                            d.draw(canvas);
                            try (FileOutputStream fos = new FileOutputStream(iconFile)) {
                                bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
                            }
                            bmp.recycle();
                        }
                    }
                    if (iconFile.exists() && iconFile.length() > 0) {
                        obj.put("iconUri", "file://" + iconFile.getAbsolutePath());
                    }
                } catch (Throwable ignored) {}

                array.put(obj);
            }
            cachedAppsJson = array.toString();
            return cachedAppsJson;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to query installed apps", t);
            return "[]";
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
                runOnUiThread(() -> TvInputHelper.showInputPicker(MainActivity.this));
                return true;
            }
            // Netflix Button
            if (keyCode == 191 || keyCode == 288 || keyCode == 196 || keyCode == 188) {
                runOnUiThread(() -> handleLaunch("com.netflix.ninja"));
                return true;
            }
            // Prime Video Button
            if (keyCode == 192 || keyCode == 289 || keyCode == 197 || keyCode == 189) {
                runOnUiThread(() -> handleLaunch("com.amazon.amazonvideo.livingroom"));
                return true;
            }

            String jsKey = null;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) jsKey = "left";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) jsKey = "right";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) jsKey = "up";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) jsKey = "down";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) jsKey = "ok";
            else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) jsKey = "back";

            if (jsKey != null) {
                final String k = jsKey;
                if (webView != null) {
                    webView.evaluateJavascript("if (window.QA && window.QA.nav) { window.QA.nav('" + k + "'); }", null);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("if (window.QA && window.QA.nav) { window.QA.nav('back'); }", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Automatically re-enable CleanScreen as default launcher if user opened it while Projectivy was default,
        // or refresh the UI state
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            webView.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
