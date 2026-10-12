# :domain:requests

Asking Tidarr for music the server does not have. The one domain module that depends on another (`:domain:music`, for `RequestMatching`).

- `RequestsRepository` (facade), `TidalBrowse` (search, artist and album lookups), `RequestActions` (request, retry, cancel, drop), `FollowIntents`, `RequestPolling` (start, poll), `RequestArrival`, `RequestsWorker`.
- `RequestIntent`, `RequestTracking` — the pure state machine, hence `RequestJourneyTest`.
- `RequestsContainer`.
