> **Done in 2.20.0 — kept for history; CLAUDE.md is the live map.**

# Restructure manifest: where every file goes

This is the working map for splitting the single `:app` module into layer and feature modules. Agents follow it. When a rule below doesn't settle a file, move it to the **lowest** module that compiles.
- If two or more features use something, it goes in `:core:ui` (UI) or `:core:domain` (logic). Features must not depend on each other.
- Mention every such decision in your report.

**Packages:** each module keeps the files' current packages for now, so moves don't rename symbols (e.g. `im.flume.hearth.data`, `im.flume.hearth.ui.screens`). Packages only change where a split package would be confusing. A package can be split across modules, but every module needs its own `namespace` (see below). Repackaging into `im.flume.hearth.feature.<name>` is a later, optional, mechanical step.

**Android namespaces:** each module gets its own namespace, `im.flume.hearth.<module>`, e.g. `im.flume.hearth.core.ui`. Only the R and BuildConfig classes move to it.

**Dependency direction** (no cycles, no feature → feature):
`:core:common ← :core:network ← :core:data ← :core:domain ← :core:playback ← :core:ui ← :feature:* ← :app`

| Module | Files (paths under `app/src/main/java/im/flume/hearth/` today) |
|---|---|
| `:core:common` | `util/*`; the logging helpers from `HearthApp.kt` (`logHandledError`, `crashFile`, `errorLogFile`, `newestLogEntries`, the crash-logging `CoroutineExceptionHandler`) |
| `:core:network` | `api/*` |
| `:core:data` | `data/AppDatabase.kt`, `data/Converters.kt`, `data/Migrations.kt`, `data/*Dao.kt`, `data/Entities.kt`, `spoken/SpokenEntities.kt`, `spoken/LocalBookEntities.kt`, `data/SessionStore.kt`, `data/PasswordVault.kt`, `data/NetworkMonitor.kt`, `spoken/AbsAccountStore.kt`; Room schemas `app/schemas/**` |
| `:core:domain` | the rest of `data/*` (LibraryRepository, Lyrics, Mixes, Stats, Usage, PlaylistEdits, PlaylistSharing, RequestIntent, RequestMatching, SearchIndex, SearchHits, ServerMixes, Availability, PhotoPrep); the rest of `spoken/*`; `sync/*`; `download/*`; `requests/*`; `update/*`; NEW `AppContainer` **interface** (everything screens reach through `LocalContext.current.container`) plus the `Context.container` accessor |
| `:core:playback` | `playback/*` |
| `:core:testing` | Test-only helpers (`Samples`, `HearthTestTheme`, `TestContainer`, `ScreenshotTest`); modules get it via `testImplementation` from the `hearth.screenshots` plugin. Screenshot tests and their `src/test/screenshots/<Class>_*.png` files move with their screens |
| `:core:ui` | `ui/theme/*`; `ui/components/*`; shared screens pieces: `ui/screens/{LibraryRows, EqualWidthChips, SpokenCommon, SpokenRows, OpmlImport, UpdateUi, CrashBanner, PlayerPosition}.kt`; NEW semantic tokens; Actions interfaces split per area |
| `:feature:home` | `ui/screens/{HomeScreen, StatsScreen, SpokenHome}.kt` |
| `:feature:library` | `ui/screens/{LibraryScreen, LibraryControls, LibraryLists, LibraryTabs, BrowseScreens, AlbumScreen, ArtistScreen, ArtistLayout, MusicPageLoading, InfoSections, CollectionScreen, CollectionHeader, CollectionSongList, CollectionSelection, CollectionDownloadDialogs, PlaylistScreen, PlaylistMenu, PlaylistEditScreen, PlaylistSharingUi, LikedGenreMixScreens, LocalBookRows}.kt` |
| `:feature:search` | `ui/screens/{SearchScreen, SearchField, SearchResults}.kt` |
| `:feature:player` | `ui/screens/{MiniPlayer, NowPlayingScreen, NowPlayingMenu, PlayerControls, LyricsView, QueueScreen, QueueRows, SpokenInfo, SpokenNowPlayingScreen, SpokenControls, SpokenSheets, SpokenPlayerMenu, SpokenQueue}.kt`; `ui/screens/visualiser/*` |
| `:feature:spoken` | `ui/screens/{ShowPage, BookScreen, LocalBookScreen, FindDetailsSheet, SpokenMenus}.kt` |
| `:feature:settings` | `ui/screens/{SettingsScreen, SettingsRows, GeneralSettings, StorageSettings, AppearanceSettings, PodcastsAndAudiobooksSettings, GetMusicSettings, LocalBookSettings, DownloadsScreen, DownloadRows, SpokenDownloadRows}.kt`; `ui/LoginScreen.kt` |
| `:app` | `HearthApp.kt` (the Application + the concrete `AppContainer` implementation), `ui/{MainActivity, AppRoot, Routes, AppActions, NavBar, PlayerOverlays}.kt`, manifest, launcher resources, signing, versioning |

## Known cross-feature uses
Check these before moving, and put each in the lowest module that compiles:
- **Mini player:** `MiniPlayer` is drawn by `:app` (NavBar). It stays in `:feature:player`, and `:app` depends on every feature.
- **Phone audiobooks list:** the Library's Audiobooks tab uses `PhoneAudiobooks` (`LocalBookRows`), which is why that file sits in `:feature:library`.
- **Shared pieces, kept in `:core:ui`:** Search, Home and Library all use `ArtistRow`/`AlbumListRow`/`SortMenu`/`isWideWindow`/`gridColumns` (`LibraryRows`) and the spoken rows.
- **Routes:** each feature exposes `fun NavGraphBuilder.<feature>Routes(...)` in a `<Feature>Routes.kt` file inside its module. `:app/Routes.kt` calls each one.
- **Navigation between features** uses route strings through the Actions interface, as today. Never call another feature's composable directly.

## Rules for every agent
- **No behaviour or visual changes.** Code moves; it doesn't get rewritten.
- **Visibility:** `internal` becomes `public` only where it now crosses a module boundary. Everything else keeps its visibility.
- **Imports, not inline full names.**
- **Tests move with their code**, into each module's `src/test`.
- **Resources:** each module owns the resources it uses: notification icons, strings other than `app_name`, xml.
- **R8:** `@Serializable` keep rules go into `consumer-rules.pro` of the modules that hold such classes.
- **Check:** `./verify.sh` (all modules) must PASS before every commit.
