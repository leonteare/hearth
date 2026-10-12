# :feature:player

Everything you see while something is playing. It registers no routes: `:app`'s `NavBar` and `PlayerOverlays` draw these.

- Music: `MiniPlayer`, `NowPlayingScreen`, `NowPlayingMenu`, `PlayerControls`, `LyricsView`, `QueueScreen`, `QueueRows`.
- Spoken: `SpokenNowPlayingScreen`, `SpokenControls`, `SpokenInfo`, `SpokenSheets` (speed, chapters), `SpokenPlayerMenu`, `SpokenQueue`.

State comes from `PlayerConnection` in `:core:playback`. The visualiser is its own module, `:feature:visualiser`.

Screenshot tests: `PlayerScreenshots`.
