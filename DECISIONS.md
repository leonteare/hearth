# Decisions

Settled choices, so agents don't re-derive or undo them. Add a line when you settle a new one.

- **No DI framework, no ViewModels.** `HearthContainer` in `HearthApp.kt` builds every singleton by hand; composables
  reach it via `LocalContext.current.container`. Small app, one maintainer: wiring stays visible and greppable.
  Modules below `:app` see it through interfaces: `DomainContainer` (`:core:domain`, for workers and receivers) and
  `AppContainer` (`:core:playback`, adds the player; what `context.container` returns).
- **Subsonic token auth.** Requests carry `t`/`s` (md5 token + salt), never the password. The password is kept only
  in `PasswordVault` (Android Keystore), and only because Navidrome's native API (playlist photos, user list) needs a
  real login. Changing what's stored forces everyone to sign in again: ask first.
- **Library shrink guard.** `LibrarySync` refuses to replace the local index with an empty or suspiciously small
  server answer. A flaky server must never wipe the library, downloads or history. Never remove it.
- **No destructive migrations.** A missing Room migration should crash loudly, not wipe downloads and history.
- **Download policy.** The user's choice for a single song (downloaded / removed) beats any downloaded album,
  playlist or Liked Songs holding it; re-downloading a collection clears "removed". See `DownloadPolicy.shouldKeep`.
- **Likes fetch missing music.** Music not on the server shows dimmed in normal rows. The ♡ on it asks Tidarr to get
  it onto the server and likes it when it arrives; Download also puts it on this phone (`requests/`).
- **Spoken word lives in Audiobookshelf**, not Navidrome. Following a podcast is a per-user tag on the server item
  (`hearth-follower:<user>`); the last unfollow writes `hearth-unfollowed:<ms>`, and the server's
  `podcast-cleanup.sh` deletes episodes nobody follows.
- **Spoken progress: newest wins.** Phone and server positions merge by `lastUpdate` on the server's clock (skew
  corrected), for position and played/unplayed alike. Offline updates queue in an outbox (`ProgressMerge`, `OutboxPlan`).
- **Mixes are built on the server.** `/opt/homelab/monitoring/hearth-mixes.py` makes "Daily Mix" / "Discover Weekly"
  playlists marked `[hearth-mix]`; songs are deleted when the mix ends unless saved. Downloading a mix song adds it to
  the hidden "Hearth · On my phone" playlist (`[hearth-kept]`), which counts as saved. Contract lives in `ServerMixes`.
- **Releases.** Tested changes are published with `./release.sh` straight away (it bumps `version.properties`, runs
  `verify.sh`, signs, tags, pushes, creates the GitHub release). Phones self-update from GitHub Releases.
- **Visualisers never use the microphone.** No `RECORD_AUDIO`, ever. Audio comes from tapping the player's own output
  with a `ForwardingAudioSink` (`AudioAnalyzer`).
- **Tests are JVM-only** (JUnit 4, no instrumented tests): logic goes in pure objects/functions. The one exception is
  screenshot tests (Roborazzi + Robolectric, still on the JVM, no device), approved to prove refactors leave the look
  unchanged. Plain test runs verify against committed PNGs; recording is opt-in (`-Proborazzi.test.record=true`).
  `:core:playback` also has two small Robolectric integration tests (queue edits on a real ExoPlayer, the song data
  source chain over a real cache), since the playback service can't be covered by pure tests alone. Nothing plays
  audio or touches the network; playback logic still goes into pure objects first.
- **Modules by layer, then by feature** (2.20.0). `core:common ← network ← data ← domain ← playback ← ui ←
  feature:* ← app`; features never depend on each other and reach each other's pages by route. Why: parallel agents
  can each own one module without merge fights, builds recompile less, and the boundaries are enforced by Gradle
  rather than by convention.
- **Domain split by area** (after 2.20.0). `:domain:music`, `:domain:spoken`, `:domain:requests` (→ music: arrivals
  are matched, liked and downloaded through the library) and `:domain:update`, each on `:core:data`; `:core:domain` is
  only the aggregator (`DomainContainer`, `SearchHits`). Each area declares its own container slice
  (`MusicContainer`, ...) so its workers never need the aggregator. Text matching both music and spoken use
  (`SearchIndex.kt`) sits in `:core:data`, so spoken doesn't depend on music.
- **One root package per module** (after 2.20.1): `im.flume.hearth.<module path>`, so an import names its module.
  `:app` keeps `HearthApp`/`ui.MainActivity` (launcher and manifest names on phones). Jobs WorkManager saved under
  the old worker names still run through `LegacyWorkers` (`:core:domain`, set on `HearthApp`).
- **Container interfaces.** `:app` builds the one `HearthContainer`; lower modules see it as `DomainContainer`
  (`:core:domain`) or `AppContainer` (`:core:playback`, adds the player). Tests swap in `TestContainer`.
- **Design tokens, ready for skins.** Colours, spacing, shapes and icons are named by purpose in `Tokens.kt` and read
  through `HearthTheme`, so a skin can swap them in one place. The old globals in `Theme.kt` were removed (2.20.3)
  once every screen read the tokens.
- **Screenshot tests run on Robolectric SDK 34.** SDK 35+ fails when the home folder path has a space (the owner's
  does). Fixed phone size (411x891dp, xxhdpi) and fixed sample dates so images only change when the look does.
- **The structure rules are checked, not remembered.** `scripts/check-structure.sh` enforces the module dependency
  rule, a 400-line ceiling per Kotlin file, a `//` header line, no inline fully-qualified names, no `android.util.Log`,
  a `README.md` per module, and that CLAUDE.md's module table and file names still match the repo. It is plain bash,
  runs in about a second without Gradle, and `verify.sh` runs it before anything else. Rules that would have meant
  rewriting 150 files are grandfathered in `scripts/no-header-files.txt` and `scripts/large-files.txt`: a missing path
  on either list is not an error, so the lists only shrink. A fully-qualified name that is data rather than a reference
  gets `// fqn-ok`.
- **Android Lint runs against committed baselines** (one `lint-baseline.xml` per module, `abortOnError`,
  `checkDependencies = false`), so a new error fails while the existing 174 wait. It stays out of `verify.sh`: a warm
  `lintDebug` after a one-line change costs about 2 m 20 s against verify's 4 m 42 s.
- **CI runs `verify.sh` on Linux** (`.github/workflows/verify.yml`, every push to main and every pull request), with
  `org.gradle.java.home` overridden in the runner's `~/.gradle/gradle.properties` so the checked-in Windows path is
  ignored. This answers the old "can it build anywhere else" question: yes. Linux draws text a shade differently, so
  screenshot comparison allows a hundredth of a percent of the pixels to differ (CI saw 4-12 of about 700,000).
- **Each module documents itself** in its own `README.md` (2.21.0). CLAUDE.md keeps the map, the rules and the ledger;
  the file-by-file detail lives next to the code, so an agent reads one README instead of the whole map.
- **The visualiser is its own module** `:feature:visualiser` (2.21.0), not a package inside `:feature:player`. The two
  never depend on each other: the player hands out an "open the visualiser" callback and `:app` draws the overlay. The
  audio tap stays in `:core:playback`.
- **Kiosk and kids mode are keyed off device-owner state** (2.26.0). A phone that makes Hearth its device owner
  (`:app` `Kiosk`) is the kids' wall player: Hearth is its home screen and stays pinned, `Settings.kidsMode` is forced
  on (no podcasts, no music that isn't on the server, Settings behind a PIN) and a fourth tab switches between the
  family's saved Navidrome logins (`Profiles`). Switching is a sign-out and sign-in, so the new person gets a fresh
  sync of their own stars and playlists; the Audiobookshelf login kept for the profile is signed in the same way.
  Nothing in the regular app can turn any of this on.
- **What the kids can't hear is a playlist, not a tag** (2.27.0). The library has no explicit tags, so the parents
  curate: any public playlist whose comment holds `[hearth-not-for-kids]` (`KidsFilter`; the homelab made one called
  "Not for kids") is a block list, and in kids mode its songs never enter the index. Adding a song from a parent's
  phone is the whole workflow; the kiosk re-syncs when the list changes.
- **Sonos speakers pull from the server, not from the phone** (2.28). A speaker cannot be told to trust the
  homelab's own certificate authority the way a kiosk phone can, and Navidrome publishes no LAN port, so the
  homelab serves `http://sonos.home` (plain HTTP, LAN only): Navidrome at the root, Audiobookshelf under
  `/abs`. Hearth hands the speaker URLs on that host and acts as a remote control, so music carries on when
  the phone locks or leaves the house. The cost, accepted deliberately: the Subsonic token rides in those
  URLs unencrypted across the home network, and the speaker's queue holds them.
- **A Sonos needs DIDL-Lite metadata with every URI.** `SetAVTransportURI` with empty metadata is refused
  with UPnP error 714, because a Subsonic stream URL ends in `.view` and says nothing about its type. The
  metadata declares `audio/mpeg` (music, `format=mp3`) or `audio/mp4` (m4b audiobooks).
