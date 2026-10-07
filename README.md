# Hearth

A personal Android music player for our Navidrome server, built to feel like Spotify.

- **Instant shuffle.** The whole library index is synced to the phone, so "Shuffle all", artist, album, genre and playlist shuffle start immediately. The player only holds about 50 upcoming songs and tops itself up as you listen.
- **Home feed.** Jump back in, Recently added, Most played, playlists, genre shuffles and Rediscover.
- **Offline.** Tap the download icon on an album or playlist. Recently streamed songs are also cached. With no connection (Tailscale off, no signal), shuffle only uses downloaded songs.
- **Car and Bluetooth.** Play, pause and skip from the car or headphones. Pressing play after the app has been closed picks up where you left off.
- Plays are reported to Navidrome ("scrobbled"), so play counts and Most played stay accurate. Each person signs in with their own Navidrome account.

## Installing on a phone

1. Copy `app/build/outputs/apk/release/app-release.apk` to the phone (e.g. via Google Drive or a USB cable).
2. Open it and allow "Install unknown apps" for whichever app you opened it with.
3. Sign in with the server's Tailscale address, e.g. `http://100.x.y.z:4533`, and your Navidrome username and password.

To update later, build a new APK and install it over the old one. Your sign-in and downloads are kept.

## Building

You need Android Studio, which provides the JDK and Android SDK.

```
./gradlew assembleRelease      # signed APK in app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # unit tests
```

### Signing key: back it up

Release builds are signed with `C:\Users\Leon Teare\.android-keys\hearth-release.jks`. Its passwords are in `hearth-keystore.properties` in the same folder, and in `keystore.properties` in the project root (both are git-ignored). **Back up that folder.** Android only installs an update if it is signed with the same key. If the key is lost, you'd have to uninstall the app (losing downloads) before installing a new build.

## Layout

| Folder | What it does |
|---|---|
| `api/` | Subsonic API client (token auth, no password stored) |
| `data/` | Room database (local library index, downloads, play history), settings, online/offline status |
| `sync/` | Full library sync (empty `search3` paging) |
| `playback/` | Media3 playback service, queue window logic, song data source (download → cache → stream), scrobbling |
| `download/` | WorkManager download worker |
| `ui/` | Jetpack Compose screens |
