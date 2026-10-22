package com.example.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private WebView webView;
    private SoundPool soundPool;
    private int soundNavTickId = 0;
    private boolean soundLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initSoundPool();
        initWebView();
    }

    private void initSoundPool() {
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            soundPool = new SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(audioAttributes)
                    .build();

            soundNavTickId = soundPool.load(this, R.raw.sound_nav_tick, 1);
            soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
                if (status == 0) {
                    soundLoaded = true;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to init SoundPool", t);
        }
    }

    private void playNavSound() {
        if (soundPool != null && soundNavTickId != 0 && soundLoaded) {
            soundPool.play(soundNavTickId, 0.85f, 0.85f, 1, 0, 1.0f);
        }
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
                Log.d("CleanFrameJS", "[" + consoleMessage.messageLevel() + "] "
                        + consoleMessage.message() + " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webView.requestFocus();

                // Diagnostic check
                webView.postDelayed(() -> {
                    if (webView == null) return;
                    webView.evaluateJavascript(
                        "(function() {" +
                        "  try {" +
                        "    var qa = window.QA ? window.QA.where() : 'no QA';" +
                        "    var dock = document.getElementById('dock');" +
                        "    var dockHtml = dock ? dock.innerHTML.length : -1;" +
                        "    var dockStyle = dock ? window.getComputedStyle(dock).display + ' / ' + window.getComputedStyle(dock).visibility + ' / ' + dock.style.top + ' / ' + dock.style.left : 'none';" +
                        "    var stage = document.getElementById('stage');" +
                        "    var r = stage ? stage.getBoundingClientRect() : {};" +
                        "    var stageStyle = stage ? stage.style.transform + ' / ' + stage.style.left + ' / ' + stage.style.top : 'none';" +
                        "    return JSON.stringify({ qa: qa, r: { l: r.left, t: r.top, w: r.width, h: r.height }, dpr: window.devicePixelRatio, w: window.innerWidth, h: window.innerHeight, stageStyle: stageStyle });" +
                        "  } catch (e) { return 'ERR: ' + e.message; }" +
                        "})()",
                        res -> Log.d("CLEANFRAME_DIAG", res)
                    );
                }, 1000);
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
        public void openHdmi(String port) {
            runOnUiThread(() -> {
                TvInputHelper.switchToInput(MainActivity.this, TvInputHelper.INPUT_HDMI1, "PlayStation 5");
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "Cannot open Settings", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openScreensaver() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setClassName("com.android.dreams.basic", "com.android.dreams.basic.Colors");
                    startActivity(intent);
                } catch (Throwable ignored) {}
            });
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
        Log.d(TAG, "handleLaunch triggered with target: " + target);
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

        Log.d(TAG, "handleLaunch resolved package: " + pkg);

        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(pkg);
        }

        if (intent != null) {
            Log.d(TAG, "App is installed, launching: " + pkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Log.d(TAG, "App is not installed, redirecting to Play Store for: " + pkg);
            redirectToPlayStore(pkg);
        }
    }

    private void redirectToPlayStore(String packageName) {
        Log.i(TAG, "redirectToPlayStore: Opening Google Play Store for " + packageName);
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
                try {
                    Intent webIntent = new Intent(Intent.ACTION_VIEW);
                    webIntent.setData(Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(webIntent);
                } catch (Throwable t3) {
                    Toast.makeText(this, "Tidak dapat membuka Play Store untuk " + packageName, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private String queryInstalledAppsJson() {
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

            JSONArray array = new JSONArray();
            for (ResolveInfo ri : allAppsMap.values()) {
                JSONObject obj = new JSONObject();
                obj.put("packageName", ri.activityInfo.packageName);
                obj.put("appName", ri.loadLabel(pm).toString());
                array.put(obj);
            }
            return array.toString();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to query installed apps", t);
            return "[]";
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            String jsKey = null;

            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) jsKey = "left";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) jsKey = "right";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) jsKey = "up";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) jsKey = "down";
            else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) jsKey = "ok";
            else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) jsKey = "back";

            if (jsKey != null) {
                if ("left".equals(jsKey) || "right".equals(jsKey) || "up".equals(jsKey) || "down".equals(jsKey)) {
                    playNavSound();
                }
                final String k = jsKey;
                if (webView != null) {
                    webView.post(() -> {
                        String js = "if (window.QA && window.QA.nav) { window.QA.nav('" + k + "'); } else if (window.handleAndroidKey) { window.handleAndroidKey('" + k + "'); }";
                        webView.evaluateJavascript(js, null);
                    });
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.post(() -> {
                webView.evaluateJavascript("if (window.QA && window.QA.nav) { window.QA.nav('back'); } else if (window.handleAndroidBack) { window.handleAndroidBack(); }", null);
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        if (soundPool != null) {
            try {
                soundPool.release();
            } catch (Throwable ignored) {}
            soundPool = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
