/* CleanFrame Android TV Bridge. Replaces Tizen APIs with Android Native Bridge. */
(function () {
  'use strict';

  var BRAND_TO_PKG = {
    netflix: 'com.netflix.ninja',
    youtube: 'com.google.android.youtube.tv',
    spotify: 'com.spotify.tv.android',
    disneyplus: 'com.disney.disneyplus',
    playstation: 'input_hdmi1',
    stremio: 'com.stremio.one',
    primevideo: 'com.amazon.amazonvideo.livingroom'
  };

  var PKG_TO_BRAND = {
    'com.netflix.ninja': 'netflix',
    'com.google.android.youtube.tv': 'youtube',
    'com.spotify.tv.android': 'spotify',
    'com.disney.disneyplus': 'disneyplus',
    'input_hdmi1': 'playstation',
    'com.stremio.one': 'stremio',
    'com.amazon.amazonvideo.livingroom': 'primevideo'
  };

  function hasBridge() {
    return typeof window.AndroidBridge !== 'undefined';
  }

  function launchApp(id, done) {
    if (!done) done = function () {};
    if (!hasBridge()) {
      done({ message: 'Preview mode', detail: 'Android Bridge not detected.' });
      return;
    }
    try {
      if (id === 'input_hdmi1' || id === 'playstation') {
        window.AndroidBridge.openHdmi('HDMI1');
      } else {
        var targetPkg = BRAND_TO_PKG[id] || id;
        window.AndroidBridge.launch(targetPkg);
      }
      done(null);
    } catch (err) {
      done({ message: 'Failed to launch app', detail: String(err) });
    }
  }

  function launch(brand, done) {
    var target = BRAND_TO_PKG[brand] || brand;
    launchApp(target, done);
  }

  function art(done) {
    if (!done) done = function () {};
    if (typeof window.enterArtMode === 'function') {
      try { window.enterArtMode(); } catch (e) {}
    } else if (typeof openWallpaper === 'function') {
      try { openWallpaper(); } catch (e) {}
    } else if (hasBridge() && window.AndroidBridge.openScreensaver) {
      window.AndroidBridge.openScreensaver();
    }
    done(null);
  }

  function home(done) {
    if (!done) done = function () {};
    if (window.QA && window.QA.scene) {
      try { window.QA.scene('home'); } catch (e) {}
    }
    done(null);
  }

  function settings(done) {
    if (!done) done = function () {};
    if (hasBridge() && window.AndroidBridge.openSettings) {
      try {
        window.AndroidBridge.openSettings();
        done(null);
      } catch (err) {
        done({ message: 'Cannot open settings', detail: String(err) });
      }
    } else {
      done(null);
    }
  }

  function network(done) {
    if (!done) done = function () {};
    if (hasBridge() && window.AndroidBridge.openNetworkSettings) {
      try {
        window.AndroidBridge.openNetworkSettings();
        done(null);
      } catch (err) {
        done({ message: 'Cannot open Network settings', detail: String(err) });
      }
    } else {
      done(null);
    }
  }

  function getNetworkType() {
    if (hasBridge() && window.AndroidBridge.getNetworkType) {
      try {
        return window.AndroidBridge.getNetworkType();
      } catch (e) {}
    }
    return 'wifi';
  }

  function apps(done) {
    if (!done) done = function () {};
    if (!hasBridge() || !window.AndroidBridge.getInstalledAppsJson) {
      // Fallback minimal list if bridge isn't ready
      var fallback = [
        { appId: 'com.google.android.youtube.tv', name: 'YouTube', brand: 'youtube', rank: 1 },
        { appId: 'com.netflix.ninja', name: 'Netflix', brand: 'netflix', rank: 2 },
        { appId: 'input_hdmi1', name: 'PlayStation 5', brand: 'playstation', rank: 3 },
        { appId: 'com.spotify.tv.android', name: 'Spotify', brand: 'spotify', rank: 4 },
        { appId: 'com.disney.disneyplus', name: 'Disney+', brand: 'disneyplus', rank: 5 },
        { appId: 'com.stremio.one', name: 'Stremio', brand: 'stremio', rank: 6 },
        { appId: 'com.amazon.amazonvideo.livingroom', name: 'Prime Video', brand: 'primevideo', rank: 7 }
      ];
      done(null, fallback);
      return;
    }

    try {
      var raw = window.AndroidBridge.getInstalledAppsJson();
      var parsed = JSON.parse(raw);
      var result = [];

      // Always prepend PlayStation 5 if not in android package manager
      result.push({
        appId: 'input_hdmi1',
        name: 'PlayStation 5',
        brand: 'playstation',
        icon: '',
        rank: 1
      });

      if (Array.isArray(parsed)) {
        parsed.forEach(function (app, idx) {
          var brand = PKG_TO_BRAND[app.packageName] || '';
          result.push({
            appId: app.packageName,
            name: app.appName || app.packageName,
            brand: brand,
            icon: app.iconUri || '',
            rank: brand ? (10 + idx) : 50
          });
        });
      }

      result.sort(function (a, b) {
        return (a.rank || 99) - (b.rank || 99) || a.name.localeCompare(b.name);
      });

      done(null, result);
    } catch (err) {
      done({ message: 'Error querying apps', detail: String(err) });
    }
  }

  window.FrameDevice = {
    launch: launch,
    launchApp: launchApp,
    art: art,
    home: home,
    settings: settings,
    network: network,
    getNetworkType: getNetworkType,
    apps: apps
  };
}());
