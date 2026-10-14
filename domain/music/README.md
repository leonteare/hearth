# :domain:music

Music logic: the library, sync, downloads, playlists, mixes, lyrics, stats and queue planning. Depends on `:core:data` and below.

- `LibraryRepository` (queries and playlist edits), `LikeOutbox`/`LikeOutboxPlan` (likes made with no connection, queued and pushed when the server answers), `PlaySource`, `LibraryOrder` (plus `SongOrder`, `YourLibrary`), `Stats`, `Usage`, `Lyrics`/`LyricsRepository`, `Mixes` (on-phone), `ServerMixes` (the server job's contract — keep names and markers in step with the homelab script), `PlaylistSharing`, `PlaylistEdits`, `PhotoPrep`, `Availability`, `RequestMatching` (is this Tidal music already on the server).
- `sync/LibrarySync` — holds the empty or shrunken library guard. **Never remove it.** Sync-only columns mean bumping `SCHEMA` in its companion to force a resync.
- `download/` — `DownloadRepository`, `DownloadPins` (removal plans, missing songs), `DownloadScheduling` (worker start and stop, Wi-Fi and app-start wake), `DownloadWorker`, `DownloadPolicy`, `CoverStore` (album art kept in the app's files, not its cache, so downloads keep their picture offline).
- `playback/` — `QueuePlanner`, `SavedQueue`.
- `MusicContainer` — this area's slice of the container.

Decisions live in pure objects taking plain values and an injectable `Random` or clock, so they are JVM-testable.
