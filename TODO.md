# CleanFrame Android TV – Detaillierter Umsetzungsplan & TODOs (für Gemini 3.8 Flash)

> **Kontext & Umgebung für den ausführenden Agenten:**
> - **Projektpfad:** `C:\Users\lauri\.gemini\antigravity\brain\969d19c8-efb2-41d0-9f2b-85fe05ab945f\scratch\CleanFrame-AndroidTV`
> - **ADB-Pfad:** `C:\Users\lauri\AppData\Local\Android\Sdk\platform-tools\adb.exe`
> - **TV-IP:** `192.168.178.22:5555` (Vor jedem ADB-Befehl `& $adb connect 192.168.178.22:5555` ausführen!)
> - **Schnelles Deployment:** Nach `.\gradlew assembleDebug --console=plain` immer `& $adb -s 192.168.178.22:5555 install --fastdeploy -r app\build\outputs\apk\debug\app-debug.apk` nutzen (dauert nur ~10 Sekunden statt 6 Minuten, da nur das `.dex`-Delta übertragen wird).

---

## TODO 1: Spotify Bildschirmschoner-Sperre reparieren

### 1.1 Warum es aktuell fehlschlägt (Root-Cause)
1. **Bug in `SpotifyKeepAwakeManager.java` (Zeile 105–120):**
   `activityManager.getRunningAppProcesses()` gibt ab Android 5.1+ aus Sicherheitsgründen **nur den eigenen App-Prozess (`com.example.tv`)** zurück, niemals fremde Prozesse wie `com.spotify.tv.android`. Dadurch gibt `isSpotifyForeground()` **immer `false`** zurück und der `SCREEN_BRIGHT_WAKE_LOCK` wird **nie** aktiviert!
2. **Self-Canceling-Bug in `TvKeyAccessibilityService.java` (Zeile 93–116):**
   Sobald `applyKeepAwake(true)` das 1×1-Overlay (`keepAwakeOverlay` mit `FLAG_KEEP_SCREEN_ON`) zum `WindowManager` hinzufügt, feuert Android sofort ein `TYPE_WINDOW_STATE_CHANGED`-Event mit `packageName = "com.example.tv"` (oder bei Lautstärkeänderung `"com.android.systemui"`).
   In Zeile 108 überschreibt `currentForegroundPackage = newPkg` den Wert `"com.spotify.tv.android"` mit `"com.example.tv"` und ruft im `else`-Zweig (Zeile 114) sofort `applyKeepAwake(false)` auf! Das Overlay löscht sich also nach wenigen Millisekunden selbst!
3. **Anforderung des Nutzers:**
   Der Bildschirmschoner (`com.google.android.backdrop`) darf **gar nicht** kommen, sobald Spotify im Vordergrund geöffnet ist (egal ob gerade ein Song läuft oder man in Spotify browst) ODER solange Musik über Spotify läuft.

### 1.2 Exakte Umsetzungsschritte (Schritt-für-Schritt)

#### Schritt A: `TvKeyAccessibilityService.java` anpassen
1. Füge ein statisches Feld hinzu, damit auch `SpotifyKeepAwakeManager` die echte Vordergrund-App kennt:
   ```java
   public static volatile String activeForegroundPkg = "";
   ```
2. Ignoriere in `onAccessibilityEvent` (`TYPE_WINDOW_STATE_CHANGED`) zwingend System-Overlays und das eigene Paket, damit `currentForegroundPackage` nicht durch das eigene `keepAwakeOverlay` oder die Lautstärkeleiste überschrieben wird:
   ```java
   if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
       CharSequence pkgCs = event.getPackageName();
       if (pkgCs == null) return;
       String newPkg = pkgCs.toString();

       // Ignoriere eigenes Overlay, SystemUI (Lautstärke/Toast), InputMethod und Android-Rahmen
       if ("com.example.tv".equals(newPkg)
               || "com.android.systemui".equals(newPkg)
               || "android".equals(newPkg)
               || newPkg.startsWith("com.google.android.inputmethod")) {
           return;
       }

       // Falls Google Screensaver (Backdrop) startet, während Spotify im Vordergrund war
       if (BACKDROP_PKG.equals(newPkg) && SPOTIFY_PKG.equals(currentForegroundPackage)) {
           CleanConfig config = CleanConfig.load(this);
           if ("on".equals(config.spotifyscreensaver)) {
               performGlobalAction(GLOBAL_ACTION_BACK);
               wakeUpAndRestoreSpotify();
               applyKeepAwake(true);
               return;
           }
       }

       currentForegroundPackage = newPkg;
       activeForegroundPkg = newPkg;
       SpotifyKeepAwakeManager.init(this);
       SpotifyKeepAwakeManager.onForegroundPackageChanged(newPkg);

       if (SPOTIFY_PKG.equals(currentForegroundPackage)) {
           handler.removeCallbacks(musicPollRunnable);
           handler.post(musicPollRunnable);
       } else {
           handler.removeCallbacks(musicPollRunnable);
           applyKeepAwake(false);
       }
   }
   ```
3. Ändere `evaluateKeepAwake()` in `TvKeyAccessibilityService.java` so, dass `applyKeepAwake(true)` aktiv bleibt, **solange Spotify im Vordergrund offen ist** (`SPOTIFY_PKG.equals(currentForegroundPackage)`):
   ```java
   private void evaluateKeepAwake() {
       try {
           CleanConfig config = CleanConfig.load(this);
           if (!"on".equals(config.spotifyscreensaver)) {
               applyKeepAwake(false);
               return;
           }
           boolean isSpotifyOpen = SPOTIFY_PKG.equals(currentForegroundPackage);
           applyKeepAwake(isSpotifyOpen);
       } catch (Throwable ignored) {}
   }
   ```

#### Schritt B: `SpotifyKeepAwakeManager.java` anpassen
1. Füge `public static void onForegroundPackageChanged(String pkg)` hinzu, die `instance.evaluateState()` aufruft.
2. Ersetze `isSpotifyForeground()` (welches das kaputte `getRunningAppProcesses()` nutzte) durch:
   ```java
   private boolean isSpotifyForeground() {
       return SPOTIFY_PKG.equals(TvKeyAccessibilityService.activeForegroundPkg);
   }
   ```
3. In `evaluateState()`: Halte den `SCREEN_BRIGHT_WAKE_LOCK | ON_AFTER_RELEASE`, sobald `isSpotifyForeground()` **`true`** ist (auch wenn kurz pausiert wird, solange Spotify offen ist, oder wenn `isPlaying` aktiv ist).
4. **Wichtig:** Wenn der WakeLock gehalten wird, alle 30 Sekunden in `checkRunnable` einmal `wakeLock.acquire(180000L)` erneuern, damit der PowerManager-Inaktivitäts-Timer von Android TV zurückgesetzt wird.

---

## TODO 2: Spotify „Gerade läuft“ (Now Playing) Element auf dem Homescreen

### 2.1 Ziel & Funktionsumfang
Ein elegantes, im Apple-Liquid-Glass-Stil (oder klassisch/minimal) gehaltenes „Gerade läuft“-Widget direkt auf dem Homescreen (`NativeHomeView`), das Titel, Interpret und Album-Cover des aktuell in Spotify gespielten Songs anzeigt und in den Einstellungen aktiviert, verschoben (X/Y-Achse) und skaliert werden kann.

### 2.2 Exakte Umsetzungsschritte

#### Schritt A: Konfigurationsfelder in `CleanConfig.java` ergänzen
1. Felder in `CleanConfig` hinzufügen:
   ```java
   // Spotify Now Playing Widget options
   public String spotifywidget = "on";   // on, off
   public int spotPosX = 88;             // 0..100 (default 88 = rechts)
   public int spotPosY = 18;             // 5..95 (default 18 = oben unter Utilities)
   public int spotScale = 100;           // 50..160 (100 = 100%)
   public String spotglass = "apple";    // apple (Apple 3D Glas), frost (Flaches Glas), off (Ohne Box)
   ```
2. In `CleanConfig.load(Context ctx)` laden:
   ```java
   c.spotifywidget = sp.getString("spotifywidget", "on");
   c.spotPosX = clamp(sp.getInt("spotPosX", 88), 0, 100);
   c.spotPosY = clamp(sp.getInt("spotPosY", 18), 5, 95);
   c.spotScale = clamp(sp.getInt("spotScale", 100), 50, 160);
   c.spotglass = sp.getString("spotglass", "apple");
   ```
3. In `CleanConfig.save(Context ctx)` speichern:
   ```java
   ed.putString("spotifywidget", spotifywidget);
   ed.putInt("spotPosX", spotPosX);
   ed.putInt("spotPosY", spotPosY);
   ed.putInt("spotScale", spotScale);
   ed.putString("spotglass", spotglass);
   ```

#### Schritt B: `CleanMediaListenerService.java` & `SpotifyNowPlayingManager.java` erstellen
Damit die App ohne Spotify-Web-OAuth-Login direkt lokal auf dem TV Titel, Künstler und Cover-Bitmap von `com.spotify.tv.android` (und anderen Medien-Apps) auslesen kann, wird Androids `MediaSessionManager` genutzt:
1. **Neue Datei `app/src/main/java/com/example/tv/CleanMediaListenerService.java`:**
   ```java
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
   ```
2. **In `AndroidManifest.xml` innerhalb von `<application>` registrieren:**
   ```xml
   <service
       android:name=".CleanMediaListenerService"
       android:label="CleanScreen Media Bridge"
       android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
       android:exported="true">
       <intent-filter>
           <action android:name="android.service.notification.NotificationListenerService" />
       </intent-filter>
   </service>
   ```
3. **Per ADB nach der Installation einmalig Berechtigung freischalten:**
   ```powershell
   & $adb -s 192.168.178.22:5555 shell "cmd notification allow_listener com.example.tv/com.example.tv.CleanMediaListenerService"
   ```
4. **Neue Datei `app/src/main/java/com/example/tv/SpotifyNowPlayingManager.java`:**
   - Nutzt `MediaSessionManager.getActiveSessions(new ComponentName(ctx, CleanMediaListenerService.class))`.
   - Sucht bevorzugt nach einem `MediaController` mit ` "com.spotify.tv.android".equals(controller.getPackageName())` (oder fällt auf den aktiven Musik-Controller zurück).
   - Liest aus `controller.getMetadata()` (`MediaMetadata`):
     - `METADATA_KEY_TITLE` -> `currentTitle`
     - `METADATA_KEY_ARTIST` (Fallback: `METADATA_KEY_ALBUM_ARTIST`) -> `currentArtist`
     - `METADATA_KEY_ALBUM_ART` (Fallback: `METADATA_KEY_ART`, `METADATA_KEY_DISPLAY_ICON`) -> `currentAlbumArt` (`Bitmap`, skaliert auf z. B. 128×128 px).
     - Falls `currentAlbumArt == null`, prüfe `METADATA_KEY_ALBUM_ART_URI` / `METADATA_KEY_ART_URI`: falls `http(s)://`, lade die Bitmap asynchron im Hintergrund-Thread herunter und rufe `onUpdateCallback.run()` auf!
   - Liest aus `controller.getPlaybackState()`:
     - `state == PlaybackState.STATE_PLAYING` -> `isPlaying = true`
   - Speichert `public static volatile String title = "";`, `public static volatile String artist = "";`, `public static volatile Bitmap albumArt = null;`, `public static volatile boolean isPlaying = false;`.
   - Registriert einen `MediaController.Callback` auf dem Controller, sodass bei jedem Songwechsel sofort `NativeHomeView.postInvalidateOnAnimation()` getriggert wird.

#### Schritt C: Einstellungen & Live-Vorschau in `NativeHomeView.java` einbauen
1. **In `getMainSettingsRows()` (`NativeHomeView.java` ca. Zeile 1059):**
   Füge eine eigene Zeile für das Spotify-Widget hinzu (oder ergänze es):
   ```java
   String spotStatus = ("on".equals(config.spotifywidget) ? "Aktiv" : "Aus")
           + " · Größe " + config.spotScale + "% · Pos " + config.spotPosX + "%/" + config.spotPosY + "%";
   ```
   Und im Array:
   ```java
   {"background", "Hintergrund & Uhrzeit", wallLabel},
   {"focus", "Dock", dockLabel},
   {"spotify", "Spotify „Gerade läuft“", spotStatus},
   {"editor", "Apps auf dem Homescreen", appsCount},
   {"samsung", "Launcher & System", "Autostart & Standard-App"},
   {"ram", "Arbeitsspeicher leeren", ramStatus}
   ```
2. **In `drawDetailSheet` (`NativeHomeView.java` ca. Zeile 1347):**
   Erweitere `isLivePreview`, damit man das Spotify-Widget beim Verschieben/Skalieren live auf dem Homescreen sieht:
   ```java
   boolean isLivePreview = "focus".equals(currentDetailGroup)
           || "background".equals(currentDetailGroup)
           || "layout".equals(currentDetailGroup)
           || "spotify".equals(currentDetailGroup);
   ```
   Und `baseTitle`:
   ```java
   String baseTitle = "background".equals(currentDetailGroup) ? "Hintergrund & Uhrzeit"
           : "focus".equals(currentDetailGroup) ? "Dock"
           : "spotify".equals(currentDetailGroup) ? "Spotify „Gerade läuft“"
           : "Launcher & System";
   ```
3. **In `getDetailSpecs(String group)` (`NativeHomeView.java`):**
   Füge den Block `else if ("spotify".equals(group))` hinzu:
   ```java
   } else if ("spotify".equals(group)) {
       list.add(new DetailRowSpec("Spotify „Gerade läuft“ auf Homescreen", "spotifywidget",
               new String[]{"on", "off"},
               new String[]{"Aktiv (Anzeigen wenn Musik läuft)", "Aus"}));
       list.add(new DetailRowSpec("Horizontale Position (X-Achse  ·  ◄ Links / Rechts ►)", "slider.spotPosX",
               0, 100, config.spotPosX,
               config.spotPosX + "%"));
       list.add(new DetailRowSpec("Vertikale Position (Y-Achse  ·  ◄ Hoch / Runter ►)", "slider.spotPosY",
               5, 95, config.spotPosY,
               config.spotPosY + "%"));
       list.add(new DetailRowSpec("Widget-Größe (◄ Kleiner / Größer ►)", "slider.spotScale",
               50, 160, config.spotScale,
               config.spotScale == 100 ? "100% (Standard)" : config.spotScale + "%"));
       list.add(new DetailRowSpec("Hintergrund-Stil", "spotglass",
               new String[]{"apple", "frost", "off"},
               new String[]{"Apple Glas (Wölbung)", "Glas (Flach)", "Ohne Hintergrund"}));
   }
   ```
4. **In `getConfigAxisValue`, `setConfigAxisValue`, `handleDetailNav` (Slider) und `onOkPressed` (Slider-Reset):**
   - In `getConfigAxisValue`: `"spotifywidget"` -> `config.spotifywidget`, `"spotglass"` -> `config.spotglass`.
   - In `setConfigAxisValue`: `"spotifywidget"` -> `config.spotifywidget = val`, `"spotglass"` -> `config.spotglass = val`.
   - In `handleDetailNav`:
     ```java
     else if ("slider.spotPosX".equals(cur.axis)) {
         config.spotPosX = Math.max(0, Math.min(100, config.spotPosX + dir * 2));
     } else if ("slider.spotPosY".equals(cur.axis)) {
         config.spotPosY = Math.max(5, Math.min(95, config.spotPosY + dir * 2));
     } else if ("slider.spotScale".equals(cur.axis)) {
         config.spotScale = Math.max(50, Math.min(160, config.spotScale + dir * 5));
     }
     ```
   - In `onOkPressed` (Slider-Reset bei OK):
     ```java
     else if ("slider.spotPosX".equals(spec.axis)) config.spotPosX = 88;
     else if ("slider.spotPosY".equals(spec.axis)) config.spotPosY = 18;
     else if ("slider.spotScale".equals(spec.axis)) config.spotScale = 100;
     ```

#### Schritt D: `drawSpotifyWidget(Canvas canvas, int inkColor, int muteColor)` in `NativeHomeView.java` rendern
1. Rufe `drawSpotifyWidget(canvas, inkColor, muteColor);` innerhalb von `drawTopBar` (direkt nach `drawClockWidget`) auf.
2. **Logik von `drawSpotifyWidget`:**
   - Prüfe `if ("off".equals(config.spotifywidget)) return;`.
   - Prüfe, ob gerade die Einstellungs-Live-Vorschau offen ist:
     `boolean isPreview = (screen == SCREEN_DETAIL && "spotify".equals(currentDetailGroup));`
   - Wenn `!SpotifyNowPlayingManager.hasActiveTrack() && !isPreview`, zeichne nichts (`return;`).
   - Wenn `isPreview && !SpotifyNowPlayingManager.hasActiveTrack()`, verwende Platzhalter-Texte (`title = "Midnight City"`, `artist = "M83 · Gerade läuft"`), damit der Nutzer beim Verschieben und Skalieren in den Einstellungen das Widget sofort live sieht!
   - Berechne Dimensionen mit `float s = config.spotScale / 100f;`:
     - Höhe der Pille: `float boxH = 92f * s;`
     - Cover-/Icon-Größe links: `float artSize = 64f * s;`
     - Textbreite messen (`title` mit `26f * s` fett, `artist` mit `20f * s` normal, max. Textbreite `360f * s` mit `ellipsize()`).
     - Gesamtbreite: `float boxW = 16f * s + artSize + 18f * s + textMaxW + 28f * s;`
     - Position aus `config.spotPosX` (`0..100`) und `config.spotPosY` (`5..95`):
       ```java
       float minX = 64f;
       float maxX = Math.max(minX, 1856f - boxW);
       float left = minX + (maxX - minX) * (config.spotPosX / 100f);
       float minY = 56f;
       float maxY = Math.max(minY, 1024f - boxH);
       float top = minY + (maxY - minY) * ((config.spotPosY - 5f) / 90f);
       ```
     - Wenn `!"off".equals(config.spotglass)`:
       Zeichne die Glas-Box mit `drawGlassBox(canvas, widgetRect, boxH * 0.38f, "apple".equals(config.spotglass));`.
     - Zeichne links das Album-Cover (`albumArt` mit abgerundeten Ecken per `canvas.clipPath` oder Fallback: das saubere Spotify-Vektor-Icon über `CleanAssets.drawCleanBrandMark(activity, canvas, "com.spotify.tv.android", ...)`).
     - Zeichne rechts daneben den Song-Titel (`inkColor`) und darunter den Interpreten (`muteColor`).

---

## TODO 3: Google Fotos Stream (Cast) vom Google Pixel 8 reparieren (Schwarzer Bildschirm am TV)

### 3.1 Fehlerbild & Technische Ursache
- **Symptom:** Beim Streamen (Cast) aus Google Fotos von einem **Google Pixel 8** wird der Fernseher (`MiTV-MSSP3`) zwar erkannt und verbunden, zeigt aber nur einen **schwarzen Bildschirm**. Von einem **Vivo-Smartphone** aus funktioniert das Streamen dagegen sofort.
- **Warum passiert das spezifisch beim Pixel 8 vs. Vivo?**
  1. **CleanScreen-Interferenz (RAM-Cleaner & Accessibility-Overlay):**
     - In `MainActivity.clearRamAndKillApps()` (Zeile 341–350) fehlt `"com.google.android.apps.mediashell"` (Chromecast built-in Receiver) in der `keepPkgs`-Whitelist! Wenn der RAM geleert wurde, wird der Cast-Receiver im Hintergrund abgewürgt.
     - In `TvKeyAccessibilityService` legt `keepAwakeOverlay` ein `TYPE_ACCESSIBILITY_OVERLAY` über den Bildschirm. Die Cast-App (`com.google.android.apps.mediashell` / `CastShellActivity`) nutzt eine geschützte Hardware-Video-/WebGL-SurfaceView. Ein aktives Accessibility-Overlay oder ein verschlucktes Window-State-Event kann auf MediaTek-TVs (Mali-G52) das Hardware-Overlay von `CastShellActivity` schwarz schalten.
  2. **Pixel 8 Ultra-HDR (JPEG_R Gainmap) & Cast-WebGL-Shader-Cache auf dem Mi TV:**
     - Das Google Pixel 8 nimmt Fotos standardmäßig in **Google Ultra HDR (10-bit Gainmap / Display P3)** auf (das Vivo-Phone nutzt klassisches 8-bit sRGB JPEG).
     - Wenn Google Fotos vom Pixel 8 ein Ultra-HDR-Bild an den älteren `com.google.android.apps.mediashell`-Receiver des Xiaomi-TVs sendet, versucht der eingebaute Chromium/WebGL-Renderer des Cast-Receivers die große P3/Gainmap-Textur zu rendern. Ist der WebGL-Shader-Cache von `mediashell` korrupt oder eine veraltete `mediashell`-Aktualisierung installiert, bleibt der WebGL-Canvas schwarz.

### 3.2 Exakte Umsetzungsschritte (Code + ADB-Systemfix)

#### Schritt A: Schutz für Chromecast Built-in (`com.google.android.apps.mediashell`) im Code einbauen
1. **In `MainActivity.java` (Zeile 341, `clearRamAndKillApps()`):**
   Füge `"com.google.android.apps.mediashell"` und `"com.google.android.gms"` zur `keepPkgs`-Liste hinzu:
   ```java
   Set<String> keepPkgs = new HashSet<>(Arrays.asList(
           getPackageName(),
           "android",
           "com.android.systemui",
           "com.xiaomi.android.tvsetup.partnercustomizer",
           "com.xiaomi.floatingframe",
           "com.mediatek.tvinput",
           "com.google.android.backdrop",
           "com.google.android.apps.mediashell",
           "com.google.android.gms",
           "com.android.tv.settings"
   ));
   ```
2. **In `TvKeyAccessibilityService.java` (`onAccessibilityEvent`):**
   Stelle sicher, dass beim Start von `"com.google.android.apps.mediashell"` sofort jedes Overlay entfernt wird (`applyKeepAwake(false)`), damit `CastShellActivity` uneingeschränkten Hardware-Plane-Zugriff hat.

#### Schritt B: Chromecast Built-in (`mediashell`) auf dem TV per ADB bereinigen & prüfen
Führe per ADB folgende Diagnose- und Reparaturbefehle auf dem Fernseher (`192.168.178.22:5555`) aus:
1. **Version und Status von `com.google.android.apps.mediashell` prüfen:**
   ```powershell
   $adb = "C:\Users\lauri\AppData\Local\Android\Sdk\platform-tools\adb.exe"
   & $adb connect 192.168.178.22:5555
   & $adb -s 192.168.178.22:5555 shell "dumpsys package com.google.android.apps.mediashell | grep -E 'versionName|versionCode|enabled'"
   ```
2. **Korrupten WebGL-/GPU-Shader-Cache und App-Daten von `com.google.android.apps.mediashell` zurücksetzen:**
   ```powershell
   & $adb -s 192.168.178.22:5555 shell "pm clear com.google.android.apps.mediashell"
   ```
   *(Hinweis: Dadurch wird der interne Chromium/WebGL-Cache des Cast-Receivers komplett geleert und der Cast-Service startet sauber neu).*
3. **Live-Logcat-Diagnose während eines Cast-Versuchs vom Pixel 8 (falls nach `pm clear` noch nötig):**
   ```powershell
   & $adb -s 192.168.178.22:5555 shell "logcat -c"
   # (Jetzt am Pixel 8 ein Foto in Google Fotos streamen)
   & $adb -s 192.168.178.22:5555 shell "logcat -d | grep -iE 'mediashell|CastShell|chromium|WebGL|OMX|Mali|Surface'"
   ```
   - Falls im Logcat ein fehlerhaftes Play-Store-Update von `com.google.android.apps.mediashell` (`WebGL context lost` / `SurfaceTexture` Fehler) sichtbar ist:
     `& $adb -s 192.168.178.22:5555 shell "pm uninstall com.google.android.apps.mediashell"` (setzt `mediashell` auf die funktionierende Xiaomi-Werks-Systemversion zurück) und anschließend `pm clear com.google.android.apps.mediashell`.

---

## Checkliste für den Abschluss (für Gemini 3.8 Flash)
- [x] **TODO 1** in `TvKeyAccessibilityService.java` und `SpotifyKeepAwakeManager.java` umgesetzt.
- [x] **TODO 2** in `CleanConfig.java`, `CleanMediaListenerService.java`, `SpotifyNowPlayingManager.java`, `AndroidManifest.xml` und `NativeHomeView.java` umgesetzt (inkl. variabler Widget-Breite, Track-Fortschrittsbalken und 30s Auto-Hide).
- [x] **TODO 3** in `MainActivity.java` (`keepPkgs`) umgesetzt und `com.google.android.apps.mediashell` fehlerhaftes Play-Store-Update deinstalliert & Werksversion aktiviert (`pm uninstall` + `pm clear`).
- [x] APK mit `.\gradlew assembleDebug --console=plain` gebaut.
- [x] APK via ADB auf dem Fernseher installiert.
- [x] Notification-Listener-Berechtigung via `cmd notification allow_listener` gesetzt.
- [x] Änderungen auf GitHub (`git push origin main`) gepusht.
