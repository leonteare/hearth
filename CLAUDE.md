# CLAUDE.md

Hearth: a single-module Android app (Kotlin, Jetpack Compose, Media3, Room, WorkManager, OkHttp) that plays music
from a Navidrome/Subsonic server, plus podcasts and audiobooks from Audiobookshelf. Package `im.flume.hearth`, all
code in `app/`. See README.md for features and DECISIONS.md for settled design choices (read before re-litigating one).

## Commands

Run from the repo root. There is no CI.

| Purpose | Command |
|---|---|
| **Check a change (compile + all unit tests)** | `./verify.sh` (one PASS/FAIL line naming failing tests; `release.sh` runs it too) |
| One test class | `./verify.sh --tests 'im.flume.hearth.playback.QueuePlannerTest'` |
| Compile only | `./gradlew compileDebugKotlin` |
| Debug APK (installs as `im.flume.hearth.debug`, alongside the release app) | `./gradlew assembleDebug` |
| Release APK | `./gradlew assembleRelease` (fails on purpose without `keystore.properties`) |
| Lint | Nothing set up or enforced (only AGP's unused `lintDebug`). |

- `gradle.properties` hard-codes `org.gradle.java.home` to Android Studio's jbr on Windows. Elsewhere, override it in
  `~/.gradle/gradle.properties`; don't edit the project file.
- Worktrees have no `local.properties` (git-ignored): `verify.sh` sets `ANDROID_HOME` itself; for raw Gradle, `export
  ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`. Don't create `local.properties`. No JDK or SDK? Say so and stop.
- Live tests `AbsLiveTest`/`ShowFeedLiveTest` skip unless `ABS_LIVE_URL`/`_USER`/`_PASSWORD` (+ `ABS_LIVE_FEED`,
  optional `ABS_LIVE_CA`) are set. They touch a real server; never commit the values.

## Layout: where new code goes

Paths under `app/src/main/java/im/flume/hearth/`. **Update this map when you add, move or split a file.**

- `HearthApp.kt`: `AppContainer` builds every singleton by hand (no DI, no ViewModels); add new repositories/services
  here. Composables use `LocalContext.current.container`. Also `logHandledError`, `crashLoggingHandler`, crash files.
- `api/` (HTTP clients; `@Serializable` models)
  - `SubsonicClient` + `Models.kt`: Navidrome via Subsonic, token+salt auth. Library, stars, playlists, scrobbles, users.
  - `NavidromeNativeApi`: Navidrome's own API (password login): playlist photos, users to share with.
  - `AbsClient` + `AbsModels`: Audiobookshelf (JWT login/refresh, `ServerClock` skew, uploads, safe file names).
  - `TidarrClient`: Tidarr/Tidal search, artist/album pages, queueing downloads, preview stream info.
- `data/`
  - Room: `Entities`, `AppDatabase` (+ `Converters`), `Migrations` (`MIGRATION_X_Y_SQL`), DAOs in `LibraryDao`,
    `DownloadDao` (+ `DownloadCounts`), `RequestDao`. Spoken DAOs live in `spoken/*Entities.kt`.
  - `LibraryRepository` (queries, `PlaySource`, `LibraryOrder`, `YourLibrary`), `SearchIndex` + `SearchHits` (ranking).
  - `SessionStore` (prefs, `Settings`), `PasswordVault` (Keystore password), `NetworkMonitor`, `Usage` (local counts).
  - `PlaylistSharing` (sharing info in the playlist comment), `PlaylistEdits`, `PhotoPrep` (playlist photo JPEGs).
  - `ServerMixes`: server-built mixes (see contract below). `Mixes`: on-phone artist mixes. `Stats`, `Lyrics`.
  - `Availability` (owned vs missing music, preview window), `RequestIntent`/`RequestMatching` (Tidarr request logic).
- `requests/`: "get music you don't have". `RequestsRepository` (state, lock), `RequestPolling` (poll loop, background
  scheduling), `RequestArrival` (found on server: like/download, notify), `RequestsWorker`.
- `sync/LibrarySync.kt`: full library sync (shrink guard).
- `download/`: `DownloadRepository` (files, queue, sync clean-up), `DownloadWorker` (parallel lanes), `DownloadPolicy`.
- `spoken/` (Audiobookshelf podcasts and audiobooks)
  - `SpokenRepository` (facade, Continue listening), `SpokenSync` (+ `SpokenSyncWorker`), `SpokenFollows`, `SpokenAdd`
    (feeds, OPML), `SpokenMapping` (API -> entities), `SpokenEntities` (Room + `SpokenDao`), `AbsAccountStore`.
  - `SpokenProgress` (resume points, outbox worker), `SpokenLogic` (`ProgressMerge`, `OutboxPlan`, `FollowTags`).
  - `SpokenDownloads` (+ worker), `SpokenPresentation` (formatting, chapters, speed, search, `FeedUrls`).
  - `ShowFeed` (RSS parse/match to server episodes) + `ShowFeeds` (fetch/cache feeds for show pages).
  - Phone audiobooks: `LocalBooks` (folders, facade), `LocalBookScanner`, `LocalBookUpload` (+ `LocalBookUploadWorker`),
    `LocalBookChapters`, `LocalBookLogic` (scan/naming), `LocalBookEntities` (+ `LocalBookDao`), `LocalBookDetails`,
    `BookMatch` (metadata search matching), `EmbeddedChapters` (MP4/ID3 chapter parsing).
- `playback/`
  - `PlaybackService` (MediaSessionService lifecycle). Its logic is split into extension files: `PlayerSetup`,
    `PlayerListener`, `SessionCallback` (connect, custom commands), `SessionExtras` (lock-screen buttons), `QueueEdits`,
    `QueuePersistence`, `PlaybackRecovery`, `StreamCaching` (prefetch, wake mode), `SpokenMode`, `SleepTimer`,
    `VolumeLevelling`, `SpokenAwarePlayer` (headset skips in spoken mode).
  - `PlayerConnection` (UI handle, `PlayerUiState`), `QueuePlanner`/`QueueStore` (window of ~50, saved queue),
    `SongDataSource` (download -> cache -> stream), `Scrobbler`, `PlaybackErrors`, `PlaybackPolicy`, `CoverArt`
    (session artwork, cache keys), `SpokenPlayback` (episode/book media items, listening tracker).
  - `PreviewPlayer`: separate 30 s preview player for music not on the server.
  - Visualiser audio: `AudioAnalyzer` (tap via `ForwardingAudioSink`), `AudioAnalysis` (FFT, bands), `BeatTracker`,
    `TimedRing`.
- `ui/`
  - `AppRoot` (state, wiring), `Routes` (NavHost; add routes as `screen("name/{arg}") { ... }`), `AppActions` (the
    `Actions` implementation), `NavBar` (bottom bar/side rail), `PlayerOverlays`, `MainActivity`, `LoginScreen`.
  - `components/`: `Actions` (`LocalActions`), `SongRows`, `MediaItems`, `MissingRows`, `SongMenu`, `Sheets`,
    `PageChrome` (headers, empty states), `CoverArt`, `CoverColor`, `CoverPrefetcher`, `GetMusic` (+ `GetMusicSheets`,
    `NotOnServer` with `LocalGetMusic`, `NotOnServerIcons`), `PreviewBar`, `PlayShuffleButtons`, `PlaylistPicker`,
    `LiveRows`/`RowContext`, `Formatters`.
  - `screens/` (one page or part per file; names say which): Home, Search{Screen,Field,Results}, Library{Screen,Tabs,
    Controls,Lists,Rows}, BrowseScreens, Album/Artist{Screen,Layout}, MusicPageLoading, Collection{Screen,Header,
    SongList,Selection,DownloadDialogs} (playlist/mix/genre/liked), Playlist{Screen,EditScreen,Menu,SharingUi},
    LikedGenreMixScreens; player: MiniPlayer, NowPlaying{Screen,Menu}, PlayerControls, PlayerPosition, LyricsView,
    Queue{Screen,Rows}; spoken: Spoken{Common,Rows,Menus,Sheets,Controls,Home,Info,Queue,NowPlayingScreen,PlayerMenu,
    DownloadRows}, ShowPage, BookScreen, LocalBook{Screen,Rows,Settings}, FindDetailsSheet, OpmlImport; Downloads
    {Screen,Rows}; Settings{Screen,Rows} + General/Appearance/Storage/GetMusic/PodcastsAndAudiobooks settings pages;
    Stats, InfoSections, EqualWidthChips, UpdateUi, CrashBanner.
  - `screens/visualiser/`: `VisualiserScreen`, `VisualiserCore` (styles, renderer base), one `*Renderer` per style,
    helpers `FramePacer`, `Retro3d`, `Ridges`, `ScopeTrace`.
  - `theme/`: `Theme` (colours, `Dimens`, `HearthShapes`), `CoverSizes`, `Contrast`.
- `update/Updater.kt`: self-update from GitHub Releases (`BuildConfig.UPDATE_REPO`). `util/Errors.kt`: `scrubSecrets`,
  `userMessage`, `couldNot`.
- Tests mirror the package under `app/src/test/java/im/flume/hearth/<pkg>/`; fixtures in `app/src/test/resources/`.

### Server-side contracts (code on the homelab, `/opt/homelab`)

- `monitoring/hearth-mixes.py` builds Navidrome playlists "Daily Mix"/"Discover Weekly" whose comment holds
  `[hearth-mix]`. Downloaded mix songs go into a hidden playlist "Hearth · On my phone" marked `[hearth-kept]`, which
  the job counts as saved. Keep the names/markers in `ServerMixes` in step with the script.
- `podcast-cleanup.sh` (server) reads Audiobookshelf podcast tags `hearth-follower:<user>` and `hearth-unfollowed:<ms>`
  (`FollowTags`) to delete episodes nobody follows.

## Conventions this code follows

- **Put logic in pure, JVM-testable units.** Tests are plain JUnit 4 on the JVM, with no Robolectric and no
  instrumented tests. Put decisions in an `object` or top-level function that takes plain values and injectable
  `Random`/time, then test that. Examples: `QueuePlanner`, `internal object DownloadPolicy`, `Stats`, `SearchIndex`.
  Don't try to unit-test code that needs a `Context`.
- **Test names are backtick sentences that describe behaviour**, e.g.
  ``fun `shuffle with a chosen song plays that song first`()``. Use `MockWebServer` for API tests and
  `kotlinx-coroutines-test` for suspend code.
- **Room schema changes:** bump `version` in `@Database`. Write the SQL as a public `MIGRATION_X_Y_SQL` list and wrap
  it in `MIGRATION_X_Y`, then add it to `.addMigrations(...)`. Commit the new `app/schemas/.../N.json` that KSP
  exports. Add a case to `MigrationTest` that compares the SQL with the exported schema. (`MigrationTest` also fails if a migration isn't
  registered or `version` doesn't match the newest JSON.) Real example:
  ```kotlin
  val MIGRATION_4_5_SQL = listOf(
      "ALTER TABLE downloads ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0",
      "CREATE TABLE IF NOT EXISTS `song_download_prefs` (`songId` TEXT NOT NULL, `wanted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
  )
  ```
- When new columns can only be filled by a full sync, bump `SCHEMA` in `LibrarySync`'s companion and update its
  comment. `syncedSchema` then forces a resync.
- Chunk `IN (:ids)` queries at 900 (see `applyStars`). Wrap multi-step DAO writes in `@Transaction` so the UI never
  sees a half-empty library.
- **Errors:** never use `android.util.Log`, which has 0 uses. Record caught errors with `logHandledError(context,
  where, e)`. Long-lived scopes use `crashLoggingHandler`. Pass any error text that reaches the UI through
  `userMessage(e)`. Stream and cover URLs carry the Subsonic token and salt, so text that might contain a URL must go
  through `scrubSecrets`.
- **UI:** use the theme tokens (`Dimens.Gutter`, `HearthShapes.Card`, `TextSecondary`, `Accent`) and existing
  components from `ui/components/`. Don't hard-code dp values or colours. Row and card actions go through
  `LocalActions.current`, not callbacks threaded through each layer.
- **Comments:** KDoc in plain language that explains *why* or what the user sees, not what the code does. Example:
  `/** No destructive fallback: a missing migration should fail loudly, not wipe downloads and history. */`
- User-facing strings are plain, non-technical English, written in Kotlin. Only the app name is in `strings.xml`.
- `@Serializable` classes must stay under `im.flume.hearth`. The R8 keep rule in `app/proguard-rules.pro` only
  covers that package, and release builds are minified.
- Commit subjects are short, user-facing summaries, e.g. `Compact queue section headers`. `Release x.y.z` commits
  come only from `release.sh`.
- Add new dependencies through `gradle/libs.versions.toml` and reference them as `libs.*`.
- Regexes run on Android's ICU engine, which rejects some patterns the JVM accepts: escape a literal `]` and `}`
  outside character classes. `RegexLiteralsTest` scans every `Regex("…")` literal for this.
- Prefer imports over inline fully-qualified names (`im.flume.hearth.spoken.Foo`) in new code.

## Parallel agents (several worktrees at once)

- One feature per worktree and branch. Commit there; don't touch other worktrees or the main checkout.
- Hot files (`HearthApp.kt`, `ui/Routes.kt`, `ui/AppActions.kt`, `data/Migrations.kt`, `data/LibraryDao.kt`) are edited by
  many agents: keep changes small and localised (add, don't reorder or reformat), so merges stay clean. Put new
  screens/logic in new files rather than growing these.
- Never edit `version.properties` (only `release.sh` does). Two schema bumps in parallel collide: rebase and renumber.
- Remove your worktree when done (`git worktree remove <path>`).

## Boundaries

### Always
- Run `./verify.sh` after changing logic, and add or extend a JVM test for any new pure logic.
- Ship a Room schema bump together with its migration, exported schema JSON and `MigrationTest` case.
- Scrub URLs and credentials from anything logged, saved to `errors.log`/`last-crash.txt`, or shown to the user.
- Keep the password in `PasswordVault` only. Sign-in uses the Subsonic token, not a stored plain password.

### Ask first
- `gh release`, `git push` or tags by hand. (`./release.sh` itself: the owner wants tested changes released straight away, without asking — only release work that passes `./verify.sh`.)
- Editing `version.properties`, which `release.sh` manages, or `gradle.properties`.
- Adding a dependency, Gradle plugin, lint tool, or DI framework.
- Changing `minSdk`/`targetSdk`, the `applicationId`, signing config, `network_security_config.xml`, or `AndroidManifest.xml` permissions.
- Changing the Subsonic auth scheme or what `SessionStore`/`PasswordVault` persist. Users would have to sign in again.

### Never
- Commit secrets: `keystore.properties`, `*.jks`, passwords, Navidrome credentials, tokens, or server URLs from a
  real install.
- Add `fallbackToDestructiveMigration()`, or edit an already-released `app/schemas/.../N.json`.
- Sign a release with the debug key or weaken the `requireReleaseKey` check. Installed apps would no longer update.
- Remove the empty or shrunken library guard in `LibrarySync`, which stops a bad server response from wiping the
  local index.

## Open questions
- Lint: nothing is configured or enforced. Is `lintDebug` expected to pass?
- Can the build run anywhere other than the owner's Windows machine? `gradle.properties` assumes it can't.

## Mistake ledger
<!-- When a session gets something wrong here and is corrected, append a one-line rule so the mistake isn't repeated. -->
