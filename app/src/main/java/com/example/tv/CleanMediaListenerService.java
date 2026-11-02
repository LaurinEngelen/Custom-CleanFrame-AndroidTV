package com.example.tv;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class CleanMediaListenerService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        SpotifyNowPlayingManager.init(this);
        SpotifyNowPlayingManager.refreshSessions(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        SpotifyNowPlayingManager.onNotificationUpdated(this, sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        SpotifyNowPlayingManager.refreshSessions(this);
    }
}
