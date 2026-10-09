# CLAUDE.md

Hearth: a single-module Android app (Kotlin, Jetpack Compose, Media3, Room, WorkManager, OkHttp) that plays music
from a Navidrome/Subsonic server. Package `im.flume.hearth`, all code in `app/`. See README.md for features.

## Commands

Run from the repo root with the Gradle wrapper. There is no CI. `release.sh` runs the same checks before it publishes.

| Purpose | Command |
|---|---|
| Unit tests (the only test suite) | `./gradlew testDebugUnitTest` |
| One test class | `./gradlew testDebugUnitTest --tests 'im.flume.hearth.playback.QueuePlannerTest'` |
| Typecheck / compile | `./gradlew compileDebugKotlin` |
| Debug APK (installs as `im.flume.hearth.debug`, alongside the release app) | `./gradlew assembleDebug` |
| Release APK | `./gradlew assembleRelease` (fails on purpose without `keystore.properties`) |
| Lint | No ktlint/detekt/spotless is set up. Only AGP's default `./gradlew lintDebug` exists, and nothing runs it. |

- `gradle.properties` hard-codes `org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr` (the owner builds
  on Windows). On other machines, set `org.gradle.java.home` in `~/.gradle/gradle.properties`. That setting wins over
  the project file. Don't edit the project file.
- The Android SDK comes from Android Studio (`local.properties`, git-ignored). If Gradle can't find a JDK or SDK, say
  so and stop. Don't claim the tests pass.
- Before calling a change done, run `testDebugUnitTest` and `compileDebugKotlin`.

## Layout: where new code goes

All paths are under `app/src/main/java/im/flume/hearth/`.

- `HearthApp.kt`: `AppContainer`, which builds every singleton by hand (no DI framework, no ViewModels). Add each new
  repository or service here. Composables reach it through `LocalContext.current.container`.
- `api/`: `SubsonicClient` (token auth), `NavidromeNativeApi` (native API, used only for playlist photos), and
  `Models.kt` (`@Serializable` responses).
- `data/`: Room (`Entities.kt`, plus `Database.kt` with the DAOs, `AppDatabase` and migrations), repositories,
  `SessionStore` (prefs and `Settings`), and `PasswordVault` (Keystore-encrypted password).
- `sync/LibrarySync.kt`: syncs the whole library. `download/Downloads.kt`: repository, `DownloadPolicy`, and the worker.
- `playback/`: `PlaybackService` (MediaSessionService), `QueuePlanner`/`QueueStore`, the data source chain
  (download → cache → stream), and the scrobbler.
- `update/`: self-update from GitHub Releases. The repo comes from `BuildConfig.UPDATE_REPO`.
- `ui/`: `AppRoot.kt` (NavHost; register new routes as `screen("name/{arg}") { ... }`), `screens/` (one file per
  area, holding many composables), `components/` (shared rows, cards, menus, `LocalActions`), and `theme/Theme.kt`
  (colours, `Dimens`, `HearthShapes`).
- `util/Errors.kt`: `scrubSecrets` / `userMessage`.
- Tests mirror the package under `app/src/test/java/im/flume/hearth/<pkg>/`.

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
  exports. Add a case to `MigrationTest` that compares the SQL with the exported schema. Real example:
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

## Boundaries

### Always
- Run `testDebugUnitTest` after changing logic, and add or extend a JVM test for any new pure logic.
- Ship a Room schema bump together with its migration, exported schema JSON and `MigrationTest` case.
- Scrub URLs and credentials from anything logged, saved to `errors.log`/`last-crash.txt`, or shown to the user.
- Keep the password in `PasswordVault` only. Sign-in uses the Subsonic token, not a stored plain password.

### Ask first
- Running `./release.sh`, `gh release`, `git push`, or creating tags. These publish an update to the owner's phones.
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
