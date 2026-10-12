# :domain:spoken

Podcasts and audiobooks, both the Audiobookshelf ones and books on the phone.

- `SpokenRepository` (facade), `SpokenSync` and `SpokenSyncWorker`, `SpokenFollows`, `SpokenAdd` (feeds, OPML), `SpokenMapping`, `SpokenProgress`, `SpokenDownloads`, `SpokenPresentation`, `ShowFeed`/`ShowFeeds`.
- `SpokenLogic` — the pure parts: `ProgressMerge`, `OutboxPlan`, `FollowTags` (the `hearth-follower:<user>` and `hearth-unfollowed:<ms>` tags the homelab's `podcast-cleanup.sh` reads).
- Phone books: `LocalBooks`, `LocalBookScanner`, `LocalBookUpload` and its worker, `LocalBookChapters`, `LocalBookLogic`, `LocalBookDetails`, `BookMatch`, `EmbeddedChapters`.
- `SpokenContainer`.

`AbsLiveTest` and `ShowFeedLiveTest` hit a real server and skip unless `ABS_LIVE_URL`/`_USER`/`_PASSWORD` are set. Never commit those values.
