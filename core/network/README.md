# :core:network

Every HTTP call. OkHttp + kotlinx.serialization; no Android UI, no database.

- **Navidrome/Subsonic:** `SubsonicClient` (token + salt auth) with `Models.kt`; `NavidromeNativeApi` for what Subsonic cannot do (playlist photos, users).
- **Audiobookshelf:** `AbsClient`, `AbsModels`, `AbsAuth` (tokens, JWT, server clock skew), `AbsUploads` (create a podcast, upload a book, `PodcastPaths`).
- **Tidarr:** `TidarrClient`, `TidarrModels`, `TidarrParsing`.

`@Serializable` classes live here, so `consumer-rules.pro` holds the R8 keep rule.

Tests: `MockWebServer` for both clients.
