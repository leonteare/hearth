# Hearth

A personal Android music player for a Navidrome server, built to feel like Spotify.

- **Instant shuffle.** The whole library index is synced to the phone, so "Shuffle all", artist, album, genre and playlist shuffle start immediately. The player only holds about 50 upcoming songs and tops itself up as you listen.
- **Home feed.** Jump back in, Recently added, Most played, playlists, genre shuffles and Rediscover.
- **Offline.** Tap the download icon on an album or playlist. Recently streamed songs are also cached. With no connection (Tailscale off, no signal), shuffle only uses downloaded songs.
- **Car and Bluetooth.** Play, pause and skip from the car or headphones. Pressing play after the app has been closed picks up where you left off.
- Plays are reported to Navidrome ("scrobbled"), so play counts and Most played stay accurate. Each person signs in with their own Navidrome account.

## Installing on a phone

1. Download `Hearth-x.y.z.apk` from the [latest release](https://github.com/leonteare/hearth/releases/latest) on the phone.
2. Open it and allow "Install unknown apps" for the browser or file manager you opened it with.
3. Sign in with your Navidrome address (e.g. `https://music.example` or `http://100.x.y.z:4533` over Tailscale) and your Navidrome username and password.

If your server uses its own certificate authority (e.g. Caddy's `tls internal`), install that root certificate on the phone first: Settings → Security → Install from device storage → CA certificate.

## Updates

Hearth checks GitHub for a newer release when it opens (at most every few hours) and shows an **Update available** banner on Home. You can also check from Settings → App. Tap **Install**, then confirm. Sign-in, downloads and settings are kept.

## Releasing (from the PC)

```
./release.sh "What changed"          # 1.1.0 -> 1.1.1
./release.sh "What changed" minor    # 1.1.0 -> 1.2.0
```

This bumps `version.properties`, runs the tests, builds the signed APK, tags and pushes, and creates a GitHub release with the APK attached. It needs Android Studio's JDK, the GitHub CLI (`gh`) logged in, and the signing key.

### Signing key: back it up

Release builds are signed with the keystore named in `keystore.properties` (git-ignored, kept outside the repo along with its passwords). **Back it up.** Android only installs an update over the top if it is signed with the same key. If the key is lost, you'd have to uninstall the app (losing downloads) before installing a new build.

## Building

You need Android Studio, which provides the JDK and Android SDK.

```
./gradlew assembleRelease      # signed APK in app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # unit tests
```

## Layout

| Folder | What it does |
|---|---|
| `api/` | Subsonic API client (token auth, no password stored) |
| `data/` | Room database (local library index, downloads, play history), settings, online/offline status |
| `sync/` | Full library sync (empty `search3` paging) |
| `playback/` | Media3 playback service, queue window logic, song data source (download → cache → stream), scrobbling |
| `download/` | WorkManager download worker |
| `update/` | Self-update from GitHub Releases |
| `ui/` | Jetpack Compose screens |
