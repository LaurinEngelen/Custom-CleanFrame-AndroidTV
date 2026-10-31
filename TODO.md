# CleanFrame Android TV – Entwicklungs-TODOs

## 1. Spotify Screensaver-Sperre reparieren
- **Problembeschreibung:**
  - Der Google Screensaver (`com.google.android.backdrop`) startet auf dem TV nach wie vor nach Inaktivität, selbst wenn die Spotify-App (`com.spotify.tv.android`) im Vordergrund aktiv ist und Musik abspielt.
- **Ursachenanalyse & Lösungsansatz:**
  - Standard-`PowerManager.SCREEN_BRIGHT_WAKE_LOCK` hält im Hintergrund auf Android TV oft nur die CPU aktiv, verhindert aber nicht zwingend den Daydream-Screensaver des Android TV Systems.
  - Auf Android TV / Google TV wird der Screensaver über den `DreamManager` bzw. `stay_on_while_plugged_in` / `dreams_enabled` gesteuert oder kann bei laufender Wiedergabe über einen foreground-MediaSession-Listener oder direkte Window-Flags (`FLAG_KEEP_SCREEN_ON`) blockiert werden.
  - Ergänzung: In der `TvKeyAccessibilityService` muss der Start von `com.google.android.backdrop` noch rigoroser abgefangen/unterbunden werden (z. B. durch automatisches Senden des Home-/Zurück-Intents oder temporäre Screensaver-Deaktivierung via Settings/System-Service während aktiver Spotify-Audioausgabe).

## 2. Spotify "Now Playing" ("Gerade läuft") Widget für den Homescreen
- **Anforderung:**
  - Ein interaktives oder statisches Widget auf dem Homescreen, das den aktuell laufenden Song, Künstler und optional das Album-Cover anzeigt.
- **Konfigurations-Optionen in den Einstellungen:**
  - An-/Ausschalter: `spotifywidget: on / off`
  - Position anpassen: Slider oder Achsenwahl (`spotPosX`, `spotPosY`)
  - Skalierung / Größe: Slider (`spotScale`, z. B. 50% bis 150%)
- **Technische Umsetzung:**
  - Anbindung an `MediaSessionManager` / `NotificationListenerService` zur Erkennung des Spotify-Playback-States und der Song-Metadaten (`MediaMetadata.METADATA_KEY_TITLE`, `METADATA_KEY_ARTIST`, `METADATA_KEY_ALBUM_ART`).
  - Rendering im passenden Liquid-Glass- / Frosted-Look direkt in `NativeHomeView`.
