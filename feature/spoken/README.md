# :feature:spoken

Podcast and audiobook pages. The player side lives in `:feature:player`.

- `ShowPage` with `ShowEpisodeRows` — a podcast's episode list, filtering and downloads.
- `BookScreen` — an Audiobookshelf book.
- `LocalBookScreen` — a book on the phone, including its chapters.
- `FindDetailsSheet` — look up metadata for a book that has none.
- `SpokenRoutes.kt`.

Screenshot tests: `SpokenPagesScreenshots`. These pages read the database, so wait for rows to settle rather than re-recording.
