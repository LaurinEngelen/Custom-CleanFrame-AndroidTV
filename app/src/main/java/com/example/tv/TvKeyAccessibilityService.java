package com.example.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class TvKeyAccessibilityService extends AccessibilityService {

    @Override
    public void onCreate() {
        super.onCreate();
        TvInputHelper.ensureGlobalKeyWatcherStarted(this);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        TvInputHelper.ensureGlobalKeyWatcherStarted(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // TV Input / Source Button (178 = KEYCODE_TV_INPUT)
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == 178 || keyCode == 182 || keyCode == 170) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                TvInputHelper.showInputPicker(this);
            }
            return true;
        }

        // Settings Button on Remote (176 = KEYCODE_SETTINGS)
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == 176) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                openTvSettings();
            }
            return true;
        }

        return super.onKeyEvent(event);
    }

    private void openTvSettings() {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable ignored) {}
        }
    }
}
