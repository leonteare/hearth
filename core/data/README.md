# :core:data

Room and the stores on disk. The only module with KSP.

- Room: `AppDatabase`, `Entities`, `Converters`, `Migrations`, DAOs (`LibraryDao`, `DownloadDao`, `RequestDao`, `LyricsDao`), `LikeOutbox` (likes waiting for the server: `LikeKind`, `LikeOutboxEntity`, `LikeOutboxDao`), and `spoken/` (`SpokenEntities`, `LocalBookEntities`, `LocalBookMerge`, their DAOs).
- Schemas: `schemas/im.flume.hearth.core.data.AppDatabase/N.json` — exported, committed, never edited once released.
- Stores: `SessionStore` (server, user, token), `PasswordVault` (the password, and nowhere else), `AbsAccountStore`, `NetworkMonitor`.
- `SearchIndex` — text matching (`SearchMatch`, `Scored`, `SongFilter`) that music and spoken search share.

A migration ships with its schema JSON and a `MigrationTest` case; `MigrationTest` also fails if a migration is not registered. Chunk `IN (:ids)` queries at 900 (see `applyStars`).
