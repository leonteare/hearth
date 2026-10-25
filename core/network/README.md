# :core:network

Every HTTP call. OkHttp + kotlinx.serialization; no Android UI, no database.

- **Navidrome/Subsonic:** `SubsonicClient` (token + salt auth) with `Models.kt`; `NavidromeNativeApi` for what Subsonic cannot do (playlist photos, users).
- **Audiobookshelf:** `AbsClient`, `AbsModels`, `AbsAuth` (tokens, JWT, server clock skew), `AbsUploads` (create a podcast, upload a book, `PodcastPaths`).
- **Tidarr:** `TidarrClient`, `TidarrModels`, `TidarrParsing`.
- **Sonos:** `SonosDiscovery` (an SSDP search on the LAN, then each speaker's device description), `SonosClient`
  (one speaker's queue, transport, seeking and volume over UPnP), `SonosModels` (`SonosSpeaker`,
  `SonosTransportState`, `SonosPosition`, `SonosMime`) and `SonosXml` (the pure part: SOAP envelopes, the
  DIDL-Lite metadata a stream URL has to carry or the speaker answers error 714, `H:MM:SS` times, UPnP faults).

`@Serializable` classes live here, so `consumer-rules.pro` holds the R8 keep rule.

Tests: `MockWebServer` for the clients, plus pure tests for Sonos escaping, times, faults, SSDP replies and a
real device description (`src/test/resources/sonos/`).
