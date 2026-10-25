# CLAUDE.md

Hearth: an Android app (Kotlin, Compose, Media3, Room, WorkManager, OkHttp) that plays music from a Navidrome/Subsonic
server, plus podcasts and audiobooks from Audiobookshelf. Split into layer and feature modules, each with its own root
package. README.md has features; DECISIONS.md has settled choices (read it before re-litigating one).

**Read only what you need.** This file is the map and the rules. Each module has its own `README.md` listing what it
holds — read the one for the module you are working in, not all of them.

## Commands

Run from the repo root.

| Purpose | Command |
|---|---|
| **Check a change** (structure checks, then compile + unit + screenshot tests, all modules) | `./verify.sh` (one PASS/FAIL line naming failing tests and changed screenshots) |
| One module only (compile + its unit and screenshot tests) | `./verify.sh :feature:library` (same one-line PASS/FAIL) |
| One test | `./gradlew :feature:library:testDebugUnitTest --tests 'im.flume.hearth.feature.library.LibraryTabsTest'` |
| Record screenshot references | `./gradlew :feature:library:testDebugUnitTest -Proborazzi.test.record=true` (per module; add `--tests`). Look at every changed PNG before committing |
| Debug APK (`im.flume.hearth.debug`, installs beside release) | `./gradlew assembleDebug` |
| Release APK | `./gradlew assembleRelease` (fails on purpose without `keystore.properties`) |
| Publish a release | `./release.sh "What changed" [minor]`: bumps version, runs `verify.sh`, signs, tags, pushes, GitHub release. **Release tested work straight away, without asking** |
| Worktree for a parallel agent | `scripts/agent-worktree.sh add <name>` / `remove <name>` / `prune` |

- **CI:** `.github/workflows/verify.yml` runs `./verify.sh` on every push to main and every pull request. On failure it
  uploads the test reports and any roborazzi `*_compare.png` as artifacts.
- `scripts/check-structure.sh` (bash, ~1 s; `verify.sh` runs it first) enforces the dependency rule, the 400-line file
  limit, `//` header lines, no inline fully-qualified names, no `android.util.Log`, and that the module table matches the
  repo. Old exceptions sit in `scripts/{no-header,large}-files.txt`: shrink those lists, never grow them. `// fqn-ok` on a
  line (or as a comment line for a whole file) allows a fully-qualified name that is data, not a reference.
- **Lint:** every module has a committed `lint-baseline.xml`, so `./gradlew lintDebug` fails only on a *new* error. Not in
  `verify.sh` (adds ~2 m 20 s warm).
- A failed screenshot leaves `<module>/build/outputs/roborazzi/*_compare.png` (reference / new / diff).
- `gradle.properties` hard-codes `org.gradle.java.home` to Android Studio's jbr on Windows. Elsewhere override it in
  `~/.gradle/gradle.properties` (which wins); do not edit the project file. CI does exactly this.
- Worktrees have no `local.properties`: `verify.sh` sets `ANDROID_HOME`; for raw Gradle `export
  ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`. Do not create `local.properties`. No JDK or SDK? Say so and stop.
- Live tests `AbsLiveTest`/`ShowFeedLiveTest` skip unless `ABS_LIVE_URL`/`_USER`/`_PASSWORD` (+ `ABS_LIVE_FEED`,
  optional `ABS_LIVE_CA`) are set. They hit a real server; never commit the values.

## Modules

Dependency rule: `:core:common ← :core:network ← :core:data ← :core:domain ← :core:playback ← :core:ui ← :feature:* ← :app`.
Core modules `api` the one below; each feature depends only on `:core:ui`; **features never depend on each other**.
The domain layer is split by area: `:core:data ← :domain:{music,spoken,update}`, `:domain:music ← :domain:requests`,
and `:core:domain` (aggregator) `api`s all four. Domain modules never depend on `:core:domain` or on each other, except
requests → music. **Each module's root package is its namespace**, `im.flume.hearth.<module path>` (`:core:data` →
`im.flume.hearth.core.data`, `:feature:library` → `im.flume.hearth.feature.library`), with a few sub-packages:
`core.data.spoken`, `domain.music.{download,playback,sync}`, `core.ui.{theme,components,screens}`. `:app` keeps
`im.flume.hearth` (`HearthApp`) and `im.flume.hearth.ui` (`MainActivity`): phones hold those names (launcher,
manifest). Sources sit in `src/main/java/<package path>/`, tests mirrored in `src/test/...`. Shared Gradle setup:
`build-logic/` plugins `hearth.android.library` (namespace, SDKs, Java 17, lint baseline) and `hearth.screenshots`
(Roborazzi + Robolectric + `:core:testing` for tests).

| Module | Holds |
|---|---|
| `:core:common` | Error logging, `scrubSecrets`/`userMessage`, API exceptions, `EXTRA_ROUTE` |
| `:core:network` | Subsonic, Navidrome native, Audiobookshelf, Tidarr, and Sonos (SSDP discovery, UPnP control) |
| `:core:data` | Room (the only KSP module), DAOs, schemas, `SessionStore`/`PasswordVault`, `SearchIndex`, the likes outbox |
| `:domain:music` | Library, sync, downloads, playlists, mixes, lyrics, stats, queue planning, likes made offline; `MusicContainer` |
| `:domain:spoken` | Podcasts, Audiobookshelf books and books on the phone; `SpokenContainer` |
| `:domain:requests` | Tidarr requests; `RequestsContainer` |
| `:domain:update` | `Updater` (GitHub Releases; `UPDATE_REPO` in its BuildConfig); `UpdateContainer` |
| `:core:domain` | Aggregator: `DomainContainer`, `SearchHits`, `LegacyWorkers` |
| `:core:playback` | Media3 service and player, `AppContainer` + `Context.container`, visualiser audio tap |
| `:core:ui` | `theme/` (`Tokens.kt`), `components/`, `screens/` (pieces two or more features share) |
| `:core:testing` | Test-only: `Samples`, `HearthTestTheme`, `FakeActions`, `TestContainer`, `ScreenshotTest` |
| `:feature:home` | Home, spoken home, stats |
| `:feature:library` | Your Library, browse, album and artist pages, collections, playlists |
| `:feature:search` | Search across music, podcasts and books |
| `:feature:player` | Mini player, Now Playing, lyrics, queue, spoken player. No routes: `:app` draws it |
| `:feature:visualiser` | The full-screen visualiser: styles, renderers, controls. No routes: `:app` draws it |
| `:feature:spoken` | Podcast show pages, book pages, phone-book pages |
| `:feature:settings` | Settings pages, Downloads, login |
| `:app` | `HearthApp` (`HearthContainer`), `MainActivity`, `AppRoot`, `Routes`, `AppActions`, `NavBar`, `PlayerOverlays`; kiosk mode (`Kiosk`, `ProfilesScreen`, `KioskGate`) |

Each module's own `README.md` (e.g. `feature/library/README.md`) lists its files.
**When you add, move or split a file, update that README** (and this table if a module's one-line summary changes).

## Where new code goes

- **New screen**: a file in its feature module, registered in that feature's `<Feature>Routes.kt` with
  `screen("name/{id}") { entry -> Page(entry.arg("id")) }` (from `:core:ui` `Routing.kt`). Other features reach it by
  route via `NavActions`, never by calling the composable. New feature module: add to `settings.gradle.kts`, `:app`'s
  dependencies, `:app` `Routes.kt`, the table above and a `README.md`.
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
  `HearthShapeSet`, `HearthIcons`), read as `HearthTheme.colors/spacing/shapes/icons`. No hard-coded dp or colours
  (`Theme.kt` keeps only the accent helpers and the corner radii `HearthShapes` is built from).
- **New screenshot test**: in the screen's module (`id("hearth.screenshots")` in its plugins), extend `ScreenshotTest`,
  `snap("name") { Page() }` (cropped) or `snapScreen` (whole phone: sheets, dialogs), use `Samples` (fixed dates, never
  "now"), record, commit `src/test/screenshots/<Class>_<name>.png`. A test moves with its screen and its PNGs.

## Conventions

- **Every `src/main` Kotlin file starts with a `//` line saying what it holds, and stays under 400 lines.** Both are
  enforced; split a file rather than adding to the allowlist.
- **Pure, JVM-testable logic.** JUnit 4 on the JVM, no instrumented tests. Decisions go in an `object` or top-level
  function taking plain values and injectable `Random`/time (`QueuePlanner`, `DownloadPolicy`, `Stats`, `SearchIndex`).
  Do not unit-test logic that needs a `Context`; Robolectric is only for screenshot tests (SDK 34, 411x891dp xxhdpi)
  and `:core:playback`'s two integration tests (`QueueEditsOnPlayerTest`, `SongSourceChainTest`: no audio, no network).
- **Test names are backtick sentences**: ``fun `shuffle with a chosen song plays that song first`()``. `MockWebServer`
  for API tests, `kotlinx-coroutines-test` for suspend code. Tests live in the module of the code they test.
- **Room migrations**: bump `version` in `@Database`; write a public `MIGRATION_X_Y_SQL` list, wrap it in
  `MIGRATION_X_Y`, add it to `.addMigrations(...)`; commit the new schema JSON; add a `MigrationTest` case (it also fails
  if a migration is not registered or `version` does not match the newest JSON). E.g.
  `val MIGRATION_4_5_SQL = listOf("ALTER TABLE downloads ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0")`.
- Chunk `IN (:ids)` queries at 900 (see `applyStars`). Wrap multi-step DAO writes in `@Transaction`.
- **Errors**: never `android.util.Log`. Caught errors → `logHandledError(context, where, e)`; long-lived scopes use
  `crashLoggingHandler`; UI error text → `userMessage(e)`; anything that may hold a URL (token + salt) → `scrubSecrets`.
- **Comments**: plain-language KDoc on *why* or what the user sees.
- User-facing strings: plain, non-technical English in Kotlin. Only `app_name` is in `strings.xml`.
- `@Serializable` classes stay under `im.flume.hearth`; the R8 keep rule is in the `consumer-rules.pro` of each module
  holding them (network, data, domain:music, domain:spoken, playback). A module that gains its first one copies that file.
- Imports, not inline fully-qualified names.
- Regexes run on Android's ICU engine: escape literal `]` and `}` outside classes (`RegexLiteralsTest` checks).
- Smart casts do not work on another module's public `val`s: copy to a local `val` first.
- New dependencies go in `gradle/libs.versions.toml`, referenced as `libs.*`.
- Commit subjects are short user-facing summaries (`Compact queue section headers`). `Release x.y.z` only from `release.sh`.

## How we work

- **Done means**: `./verify.sh` PASS, a JVM test for any new pure logic, the module's README updated, an emulator check
  for anything visual, and released with `./release.sh` — straight away, without asking.
- **Decide, do not ask.** Technical choices (structure, naming, which module, how to test) are yours. Ask only about
  product and UX choices, and about the "Ask first" list below. Best practice beats "not needed now".
- **Big jobs**: write a plan with clear roles first, then run agents in parallel, one module each, in worktrees from
  current `main`. The lead session merges, runs the full `verify.sh`, updates the docs once, and releases.
- **Reporting**: plain language, short — what changed, and what to try on the phone.

## Parallel agents

- Own one module (one feature per worktree and branch). Do not edit another feature's module.
- Before starting, check `git log --oneline -1` against `main`: a worktree's base may be stale.
- Agents do not edit `CLAUDE.md` or `DECISIONS.md`: report the lines that need changing, and the lead folds them in once.
- Shared additions to core modules go in **new files** where possible. Hot files (`HearthApp.kt`, `AppActions.kt`,
  `Actions.kt`, `DomainContainer.kt`, `AppContainer.kt`, `Migrations.kt`, `LibraryDao.kt`): small, local additions;
  do not reorder or reformat. `Tokens.kt` slots are append-only (add at the end of the class, never rename or reorder).
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
- Remove the empty or shrunken library guard in `:domain:music` `sync/LibrarySync.kt`.
- Add a feature → feature dependency, or a core module depending upward.

## Server-side contracts (homelab, `/opt/homelab`)

- `monitoring/hearth-mixes.py` builds Navidrome playlists "Daily Mix"/"Discover Weekly" whose comment holds
  `[hearth-mix]`. Downloaded mix songs go into a hidden playlist "Hearth · On my phone" marked `[hearth-kept]`, which
  the job counts as saved. Keep names and markers in `ServerMixes` in step with the script.
- `podcast-cleanup.sh` reads Audiobookshelf podcast tags `hearth-follower:<user>` and `hearth-unfollowed:<ms>`
  (`FollowTags`) to delete episodes nobody follows.

## Open questions
- Nothing runs lint automatically. Work the 174 baselined issues down (95 are Media3 `UnsafeOptInUsageError` in
  `:core:playback`), or narrow the check?

## Mistake ledger
- A regex that passed JVM tests crashed the app on Android (ICU rejects an unescaped `}`); `RegexLiteralsTest` now guards it.
- Agent worktrees were created from an old commit (before the restructure); always check the base against `main` first.
- `agent-worktree.sh` once reported success while leaving folders behind (Windows long paths); confirm `.claude/worktrees` is empty after cleanup.
- Changing a systemd timer's `OnCalendar` with `Persistent=true` started a missed run immediately; check `list-timers` and stop it if unwanted.
- A screenshot test of a database-backed page can fire before its rows load; wait for the data (see `SpokenPagesScreenshots`' `Settled`), do not re-record.
- The rules in this file rotted until `check-structure.sh` existed: if a rule matters, check it.
- The player's title and artist drifted apart because an accessibility change gave the artist its own 48dp touch
  target; give a tight pair one shared target rather than one each.
- A change the server refused used to be reverted on the phone, which looked like the app ignoring the tap. Keep the
  user's change and queue it (`LikeOutbox`); only drop it if the server actually rejects it.
- A Sonos refuses a stream URL sent without DIDL-Lite metadata (UPnP 714). The metadata is escaped twice:
  once inside the DIDL document, once into the SOAP body. `SonosXml` keeps that pure and tested.
- `userMessage` rewrites anything containing an HTTP status into "The server had a problem", so a typed
  refusal has to word itself without one.
- `docker compose up -d <service>` does not reload a changed config file that is only mounted into the
  container: it sees no change to the service and leaves it running. Caddy then answers an unknown host on
  port 80 with an empty `200`, which reads as success if only the status code is checked. Restart, and check
  a response body.
- Linux draws text a shade differently from Windows, so CI saw a handful of changed pixels in screenshots that are
  identical to the eye; `ScreenshotTest`'s `OPTIONS` now allows a hundredth of a percent of pixels to differ.
