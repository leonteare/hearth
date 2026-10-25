# :feature:player

Everything you see while something is playing. It registers no routes: `:app`'s `NavBar` and `PlayerOverlays` draw these.

- Music: `MiniPlayer`, `NowPlayingScreen`, `NowPlayingMenu`, `PlayerControls`, `LyricsView`, `QueueScreen`, `QueueRows`.
- Spoken: `SpokenNowPlayingScreen`, `SpokenControls`, `SpokenInfo`, `SpokenSheets` (speed, chapters), `SpokenPlayerMenu`, `SpokenQueue`.

The player's bottom row holds `:core:ui`'s `SpeakerButton`, and `PlayerControls` shows `PlayingOnLine` when the
sound is going to a speaker: both take a plain `speakerName` and an `onOpenSpeakers` callback (null hides the
button), threaded in through `NowPlayingScreen`.

State comes from `PlayerConnection` in `:core:playback`. The visualiser is its own module, `:feature:visualiser`.

Screenshot tests: `PlayerScreenshots`.
