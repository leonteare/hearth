# CLAUDE.md

Hearth: an Android app (Kotlin, Compose, Media3, Room, WorkManager, OkHttp) that plays music from a Navidrome/Subsonic
server, plus podcasts and audiobooks from Audiobookshelf. Split into layer and feature modules, each with its
own root package. README.md has features; DECISIONS.md has settled choices (read it before re-litigating one).

## Commands

Run from the repo root. There is no CI.

| Purpose | Command |
|---|---|
| **Check a change** (compile + unit + screenshot tests, all modules) | `./verify.sh` (one PASS/FAIL line naming failing tests and changed screenshots) |
| One module's tests | `./gradlew :feature:library:testDebugUnitTest --tests 'im.flume.hearth.feature.library.LibraryTabsTest'` |
| Record screenshot references | `./gradlew :feature:library:testDebugUnitTest -Proborazzi.test.record=true` (per module; add `--tests`). Look at every changed PNG before committing |
| Debug APK (`im.flume.hearth.debug`, installs beside release) | `./gradlew assembleDebug` |
| Release APK | `./gradlew assembleRelease` (fails on purpose without `keystore.properties`) |
| Publish a release | `./release.sh "What changed" [minor]`: bumps version, runs `verify.sh`, signs, tags, pushes, GitHub release. **Release tested work straight away, without asking** (only work that passes `./verify.sh`) |
| Worktree for a parallel agent | `scripts/agent-worktree.sh add <name>` / `remove <name>` / `prune` |

- A failed screenshot leaves `<module>/build/outputs/roborazzi/*_compare.png` (reference / new / diff).
- `gradle.properties` hard-codes `org.gradle.java.home` to Android Studio's jbr on Windows. Elsewhere override it in
  `~/.gradle/gradle.properties`; don't edit the project file.
- Worktrees have no `local.properties`: `verify.sh` sets `ANDROID_HOME`; for raw Gradle `export
  ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`. Don't create `local.properties`. No JDK or SDK? Say so and stop.
- Live tests `AbsLiveTest`/`ShowFeedLiveTest` skip unless `ABS_LIVE_URL`/`_USER`/`_PASSWORD` (+ `ABS_LIVE_FEED`,
  optional `ABS_LIVE_CA`) are set. They hit a real server; never commit the values.
- Lint: nothing set up or enforced (only AGP's unused `lintDebug`).

## Modules

Dependency rule: `:core:common ← :core:network ← :core:data ← :core:domain ← :core:playback ← :core:ui ← :feature:* ← :app`.
Core modules `api` the one below; each feature depends only on `:core:ui`; **features never depend on each other**.
The domain layer is split by area: `:core:data ← :domain:{music,spoken,update}`, `:domain:music ← :domain:requests`,
and `:core:domain` (aggregator) `api`s all four. Domain modules never depend on `:core:domain` or on each other, except
requests → music. **Each module's root package is its namespace**, `im.flume.hearth.<module path>` (`:core:data` →
`im.flume.hearth.core.data`, `:feature:library` → `im.flume.hearth.feature.library`), with a few sub-packages:
`core.data.spoken`, `domain.music.{download,playback,sync}`, `core.ui.{theme,components,screens}`,
`feature.player.visualiser`. `:app` keeps `im.flume.hearth` (`HearthApp`) and `im.flume.hearth.ui` (`MainActivity`):
phones hold those names (launcher, manifest). Sources sit in `src/main/java/<package path>/`, tests mirrored in
`src/test/...`. Shared Gradle setup: `build-logic/` plugins `hearth.android.library` (namespace, SDKs, Java 17) and
`hearth.screenshots` (Roborazzi + Robolectric + `:core:testing` for tests).

| Module | Holds |
|---|---|
| `:core:common` | `ErrorLog.kt` (`logHandledError`, `crashLoggingHandler`, crash files), `Errors.kt` (`scrubSecrets`, `userMessage`, `couldNot`), `ApiExceptions`, `EXTRA_ROUTE`, `ic_notification` (`im.flume.hearth.core.common.R`) |
| `:core:network` | `SubsonicClient` + `Models` (token auth), `NavidromeNativeApi` (playlist photos, users), `AbsClient` + `AbsModels` + `AbsAuth` (tokens, JWT, server clock) + `AbsUploads` (new podcast, book upload, `PodcastPaths`), `TidarrClient` + `TidarrModels` + `TidarrParsing` |
| `:core:data` | Room (only KSP module): `Entities`, `AppDatabase`, `Converters`, `Migrations`, DAOs (`Library`, `Download`, `Request`, `Lyrics`), `spoken/*Entities` (+ DAOs), schemas in `core/data/schemas/im.flume.hearth.core.data.AppDatabase/`; `SessionStore`, `PasswordVault`, `NetworkMonitor`, `AbsAccountStore`; `SearchIndex` (`SearchMatch`, `Scored`, `SongFilter`: text matching music and spoken share) |
| `:domain:music` | Library, sync, downloads, playlists, mixes, lyrics, stats, queue planning (below); `MusicContainer` |
| `:domain:spoken` | Podcasts and audiobooks (below); `SpokenContainer` |
| `:domain:requests` | Tidarr requests (below, `RequestIntent`, `RequestTracking`); `RequestsContainer` |
| `:domain:update` | `Updater` (GitHub Releases; `UPDATE_REPO` in its BuildConfig); `UpdateContainer` |
| `:core:domain` | Aggregator: `DomainContainer` (extends the four area containers), `SearchHits` (Search's one list across areas), `LegacyWorkers` (old worker class names → current; WorkManager's factory) |
| `:core:playback` | Media3 service and player; `AppContainer` (DomainContainer + player) and `Context.container` |
| `:core:ui` | `theme/` (`Tokens.kt`; `Theme.kt`: accent helpers, corner radii, `HearthTheme` setup; `CoverSizes`, `Contrast`), `components/`, `screens/` (pieces two+ features share) |
| `:core:testing` | Test-only (via `testImplementation`): `Samples`, `HearthTestTheme` + `FakeActions` + `liveRows()`, `TestContainer`/`TestApplication`, `ScreenshotTest` |
| `:feature:home` | `HomeScreen`, `SpokenHome`, `StatsScreen`; `HomeRoutes.kt` |
| `:feature:library` | Library tabs/lists/controls, Browse, Album/Artist, Collection* (playlist/mix/genre/liked pages), Playlist{Screen,Edit,Menu,SharingUi}, `InfoSections`, `LocalBookRows`; `LibraryRoutes.kt` |
| `:feature:search` | `SearchScreen`, `SearchField`, `SearchResults`; `SearchRoutes.kt` |
| `:feature:player` | `MiniPlayer`, `NowPlaying*`, `PlayerControls`, `LyricsView`, `Queue*`, `Spoken*` player pieces, `visualiser/` (`VisualiserScreen` + `VisualiserControls`, renderers). No routes: `:app`'s `NavBar`/`PlayerOverlays` draw it |
| `:feature:spoken` | `ShowPage` (+ `ShowEpisodeRows`), `BookScreen`, `LocalBookScreen`, `FindDetailsSheet`; `SpokenRoutes.kt` |
| `:feature:settings` | `Settings*` and its pages (General, Appearance, Storage, GetMusic, PodcastsAndAudiobooks, LocalBook), `Downloads*`, `SpokenDownloadRows`, `LoginScreen`; `SettingsRoutes.kt` |
| `:app` | `HearthApp.kt` (`HearthContainer`, the one real container), `ui/`: `MainActivity`, `AppRoot`, `Routes` (calls each `<feature>Routes()`), `AppActions`, `NavBar`, `PlayerOverlays`; manifest, signing, versioning |

### Where things live (relative to each module's root package)

- **domain:music**: `LibraryRepository` (queries, playlist edits), `PlaySource`, `LibraryOrder` (+ `SongOrder`,
  `YourLibrary`), `Stats`, `Usage`, `Lyrics`
  (`LyricsRepository`), `Mixes` (on-phone), `ServerMixes` (server contract), `PlaylistSharing`, `PlaylistEdits`,
  `PhotoPrep`, `Availability`, `RequestMatching` (is Tidal music on the server; `Availability` needs it).
- **domain:music sub-packages**: `sync/LibrarySync` (shrink guard), `download/` (`DownloadRepository`, `DownloadPins`
  (removal plans, missing songs), `DownloadScheduling` (worker start/stop, Wi-Fi/app-start wake), `DownloadWorker`, `DownloadPolicy`), `playback/QueuePlanner` + `SavedQueue`.
- **domain:requests**: Tidarr: `RequestsRepository` (facade), `TidalBrowse` (search, artist/album lookups),
  `RequestActions` (request/retry/cancel/drop), `FollowIntents`, `RequestPolling` (start, poll), `RequestArrival`, worker.
- **domain:spoken**: `SpokenRepository` (facade), `SpokenSync`(+Worker), `SpokenFollows`, `SpokenAdd` (feeds, OPML),
  `SpokenMapping`, `SpokenProgress`, `SpokenLogic` (`ProgressMerge`, `OutboxPlan`, `FollowTags`), `SpokenDownloads`,
  `SpokenPresentation`, `ShowFeed`/`ShowFeeds`; phone books: `LocalBooks`, `LocalBookScanner`, `LocalBookUpload`(+Worker),
  `LocalBookChapters`, `LocalBookLogic`, `LocalBookDetails`, `BookMatch`, `EmbeddedChapters`.
- **core:playback**: `PlaybackService` + its extension files (`PlayerSetup`, `PlayerListener`, `SessionCallback`,
  `SessionExtras`, `QueueEdits`, `QueuePersistence`, `PlaybackRecovery`, `StreamCaching`, `SpokenMode`, `SleepTimer`,
  `VolumeLevelling`, `SpokenAwarePlayer`); `PlayerConnection` (`PlayerUiState`), `QueueStore`, `SongDataSource`
  (download → cache → stream), `Scrobbler`, `PlaybackErrors`/`Policy`, `CoverArt`, `SpokenPlayback`, `PreviewPlayer`
  (30 s previews); visualiser audio `AudioAnalyzer`, `AudioAnalysis`, `BeatTracker`, `TimedRing`.
- **core:ui `components/`**: `Actions` (+ `LocalActions`), `Routing` (`screen()`, `arg()`), `SongRows`, `MediaItems`,
  `MissingRows`, `SongMenu`, `Sheets`, `PageChrome`, `PlayerChrome`, `CoverArt`/`CoverColor`/`CoverPrefetcher`/
  `CoverGradientHeader`/`MixCover`, `GetMusic`(+`Sheets`)/`NotOnServer`(+`Icons`), `PreviewBar`, `PlayShuffleButtons`,
  `PlaylistPicker`, `LiveRows`/`RowContext`, `Formatters`.
- **core:ui `screens/` (shared)**: `LibraryRows`, `SpokenCommon`, `SpokenRows`, `SpokenMenus`, `FollowButton`, `LyricStyle`,
  `PlayerPosition`, `EqualWidthChips`, `OpmlImport`, `UpdateUi`, `CrashBanner`.

**Update this map when you add, move or split a file.**

## Where new code goes

- **New screen**: a file in its feature module, registered in that feature's `<Feature>Routes.kt` with
  `screen("name/{id}") { entry -> Page(entry.arg("id")) }` (from `:core:ui` `Routing.kt`). Other features reach it by
  route via `NavActions`, never by calling the composable. New feature module: add to `settings.gradle.kts`, `:app`'s
  dependencies, and `:app` `Routes.kt`.
- **Something two features need**: UI → `:core:ui`; logic → its area's `:domain:*` module (`:core:domain` only for
  code that merges areas). Never a feature → feature dependency.
- **New repository / singleton**: class in its `:domain:*` module (or `:core:playback` if it needs the player), a
  property on that area's container (`MusicContainer`, ...; or `AppContainer`), built in `HearthContainer` (`:app`
  `HearthApp.kt`) and in `TestContainer`. Workers reach it via the module's internal `Context.<area>Container`.
- **New DB table / column**: entity + DAO in `:core:data`, migration (below), exported `core/data/schemas/.../N.json`,
  `MigrationTest` case. Sync-only columns: bump `SCHEMA` in `LibrarySync`'s companion (forces a resync).
- **Moving or renaming a worker**: WorkManager saves jobs by the worker's class name, so add the old name to
  `LegacyWorkers` (`:core:domain`) and point existing entries at the new one (`LegacyWorkersTest` checks targets exist).
- **New user action**: a method on the right `Actions` sub-interface (`SongActions`, `PlaylistActions`,
  `LibraryActions`, `NavActions`, `MessageActions`, `CoverActions`), implemented in `:app` `AppActions` and
  `FakeActions`. Rows and cards call `LocalActions.current`, not callbacks threaded through layers.
- **New colour / size / shape / icon**: a slot in `core/ui/.../theme/Tokens.kt` (`HearthColors`, `HearthSpacing`,
  `HearthShapeSet`, `HearthIcons`), read as `HearthTheme.colors/spacing/shapes/icons`. No hard-coded dp or colours (`Theme.kt` keeps only the
  accent helpers and the corner radii `HearthShapes` is built from).
- **New screenshot test**: in the screen's module (`id("hearth.screenshots")` in its plugins), extend `ScreenshotTest`,
  `snap("name") { Page() }` (cropped) or `snapScreen` (whole phone: sheets, dialogs), use `Samples` (fixed dates, never
  "now"), record, commit `src/test/screenshots/<Class>_<name>.png`. A test moves with its screen and its PNGs.

## Conventions

- **Pure, JVM-testable logic.** JUnit 4 on the JVM, no instrumented tests. Decisions go in an `object` or top-level
  function taking plain values and injectable `Random`/time (`QueuePlanner`, `DownloadPolicy`, `Stats`, `SearchIndex`).
  Don't unit-test logic that needs a `Context`; Robolectric is only for screenshot tests (SDK 34, 411x891dp xxhdpi)
  and `:core:playback`'s two integration tests (`QueueEditsOnPlayerTest`, `SongSourceChainTest`: no audio, no network).
- **Test names are backtick sentences**: ``fun `shuffle with a chosen song plays that song first`()``. `MockWebServer`
  for API tests, `kotlinx-coroutines-test` for suspend code. Tests live in the module of the code they test.
- **Room migrations**: bump `version` in `@Database`; write a public `MIGRATION_X_Y_SQL` list, wrap it in
  `MIGRATION_X_Y`, add it to `.addMigrations(...)`; commit the new schema JSON; add a `MigrationTest` case (it also fails
  if a migration isn't registered or `version` doesn't match the newest JSON). E.g.
  `val MIGRATION_4_5_SQL = listOf("ALTER TABLE downloads ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0")`.
- Chunk `IN (:ids)` queries at 900 (see `applyStars`). Wrap multi-step DAO writes in `@Transaction`.
- **Errors**: never `android.util.Log`. Caught errors → `logHandledError(context, where, e)`; long-lived scopes use
  `crashLoggingHandler`; UI error text → `userMessage(e)`; anything that may hold a URL (token + salt) → `scrubSecrets`.
- **Comments**: plain-language KDoc on *why* or what the user sees. New files start with a `//` line saying what they hold.
- User-facing strings: plain, non-technical English in Kotlin. Only `app_name` is in `strings.xml`.
- `@Serializable` classes stay under `im.flume.hearth`; the R8 keep rule is in the `consumer-rules.pro` of each module
  holding them (network, data, domain:music, domain:spoken, playback). A module that gains its first one copies that file.
- Imports, not inline fully-qualified names.
- Regexes run on Android's ICU engine: escape literal `]` and `}` outside classes (`RegexLiteralsTest` checks).
- Smart casts don't work on another module's public `val`s: copy to a local `val` first.
- New dependencies go in `gradle/libs.versions.toml`, referenced as `libs.*`.
- Commit subjects are short user-facing summaries (`Compact queue section headers`). `Release x.y.z` only from `release.sh`.

## Parallel agents

- Own one module (one feature per worktree and branch). Don't edit another feature's module.
- Before starting, check `git log --oneline -1` against `main`: a worktree's base may be stale.
- Shared additions to core modules go in **new files** where possible. Hot files (`HearthApp.kt`, `AppActions.kt`,
  `Actions.kt`, `DomainContainer.kt`, `AppContainer.kt`, `Migrations.kt`, `LibraryDao.kt`): small, local additions;
  don't reorder or reformat. `Tokens.kt` slots are append-only (add at the end of the class, never rename or reorder).
- Never edit `version.properties` (only `release.sh`). Parallel schema bumps collide: rebase and renumber.
- Remove your worktree when done: `scripts/agent-worktree.sh remove <name>`.

## Boundaries

### Always
- Run `./verify.sh` before calling a change done; add or extend a JVM test for new pure logic.
- Ship a Room schema bump with its migration, `core/data/schemas` JSON and `MigrationTest` case.
- Scrub URLs and credentials from anything logged, saved to `errors.log`/`last-crash.txt`, or shown to the user.
- Keep the password in `PasswordVault` only. Sign-in uses the Subsonic token, not a stored plain password.

### Ask first
- `gh release`, `git push` or tags by hand (`./release.sh` itself needs no asking; see Commands).
- Editing `gradle.properties`.
- Adding a dependency, Gradle plugin, lint tool, or DI framework.
- Changing `minSdk`/`targetSdk`, `applicationId`, signing config, `network_security_config.xml`, manifest permissions.
- Changing the Subsonic auth scheme or what `SessionStore`/`PasswordVault` persist (users would sign in again).

### Never
- Commit secrets: `keystore.properties`, `*.jks`, passwords, Navidrome credentials, tokens, real server URLs.
- Add `fallbackToDestructiveMigration()`, or edit an already-released `core/data/schemas/.../N.json`.
- Sign a release with the debug key or weaken the `requireReleaseKey` check (installed apps would stop updating).
- Remove the empty/shrunken library guard in `:domain:music` `sync/LibrarySync.kt`.
- Add a feature → feature dependency, or a core module depending upward.

## Server-side contracts (homelab, `/opt/homelab`)

- `monitoring/hearth-mixes.py` builds Navidrome playlists "Daily Mix"/"Discover Weekly" whose comment holds
  `[hearth-mix]`. Downloaded mix songs go into a hidden playlist "Hearth · On my phone" marked `[hearth-kept]`, which
  the job counts as saved. Keep names/markers in `ServerMixes` in step with the script.
- `podcast-cleanup.sh` reads Audiobookshelf podcast tags `hearth-follower:<user>` and `hearth-unfollowed:<ms>`
  (`FollowTags`) to delete episodes nobody follows.

## Open questions
- Lint: nothing is configured or enforced. Is `lintDebug` expected to pass?
- Can the build run anywhere other than the owner's Windows machine? `gradle.properties` assumes it can't.

## Mistake ledger
<!-- When a session gets something wrong here and is corrected, append a one-line rule so the mistake isn't repeated. -->
- A regex that passed JVM tests crashed the app on Android (ICU rejects an unescaped `}`); `RegexLiteralsTest` now guards it.
- Agent worktrees were created from an old commit (before the restructure); always check the base against `main` first.
- `agent-worktree.sh` once reported success while leaving folders behind (Windows long paths); confirm `.claude/worktrees` is empty after cleanup.
- Changing a systemd timer's `OnCalendar` with `Persistent=true` started a missed run immediately; check `list-timers` and stop it if unwanted.
- A screenshot test of a database-backed page can fire before its rows load; wait for the data (see `SpokenPagesScreenshots`' `Settled`), don't re-record.
