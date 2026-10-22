package com.example.tv;

import android.content.Intent;
import android.graphics.drawable.Drawable;

public class AppInfo {
    public String packageName;
    public String activityName;
    public String label;
    public Drawable banner;
    public Drawable icon;
    public Intent launchIntent;
    public int sampledColor = 0xFF3B82F6; // default subtle blue

    public AppInfo(String packageName, String activityName, String label, Drawable banner, Drawable icon, Intent launchIntent) {
        this.packageName = packageName;
        this.activityName = activityName;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.launchIntent = launchIntent;
    }
}
