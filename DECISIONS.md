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
- **Modules by layer, then by feature** (2.20.0). `core:common ← network ← data ← domain ← playback ← ui ←
  feature:* ← app`; features never depend on each other and reach each other's pages by route. Why: parallel agents
  can each own one module without merge fights, builds recompile less, and the boundaries are enforced by Gradle
  rather than by convention. Packages stayed as they were so the move renamed nothing.
- **Container interfaces.** `:app` builds the one `HearthContainer`; lower modules see it as `DomainContainer`
  (`:core:domain`) or `AppContainer` (`:core:playback`, adds the player). Tests swap in `TestContainer`.
- **Design tokens, ready for skins.** Colours, spacing, shapes and icons are named by purpose in `Tokens.kt` and read
  through `HearthTheme`, so a skin can swap them in one place. The old globals in `Theme.kt` are deprecated, kept
  only until every screen reads the tokens.
- **Screenshot tests run on Robolectric SDK 34.** SDK 35+ fails when the home folder path has a space (the owner's
  does). Fixed phone size (411x891dp, xxhdpi) and fixed sample dates so images only change when the look does.
