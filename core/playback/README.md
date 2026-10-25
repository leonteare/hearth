# :core:playback

Media3 playback, and the container the UI reads.

- `PlaybackService` plus its extension files: `PlayerSetup`, `PlayerListener`, `SessionCallback`, `SessionExtras`, `QueueEdits`, `QueuePersistence`, `PlaybackRecovery`, `StreamCaching`, `SpokenMode`, `SleepTimer`, `VolumeLevelling`, `SpokenAwarePlayer`, `OutputAwarePlayer`.
- `PlayerConnection` (`PlayerUiState`) — what screens observe. `QueueStore` saves the queue.
- `SongDataSource` — download, then cache, then stream, in that order. `Scrobbler`, `PlaybackErrors`/`PlaybackPolicy`, `CoverArt`, `SpokenPlayback`, `PreviewPlayer` (30 s previews).
- Sonos output: `SonosOutput` (the chosen speaker, handing the queue over, the phone as its remote control),
  `SonosHandover` (`AudioOutput`, `SonosTrack`, `SonosPlan`, `RemoteOutput`), `SonosPolicy` (`SonosPolling`,
  `SonosFailures`), `SonosSession` (the requests one hand-over is made of), `SonosFinder` (the speaker search)
  and `SonosTracks` (a queue entry as a speaker needs it). The URLs themselves belong to `:domain:music` and `:domain:spoken`.
  While a speaker has the sound, `OutputAwarePlayer` (`RemoteSession`, `RemoteVolume`) is what the media session
  publishes: it reports the speaker's state, position, song and volume instead of the phone's stopped player, and
  sends play, pause, seek and skip to the speaker. That is what makes Android send the volume keys with the phone
  locked, show its own slider for the speaker, and point the notification, lock screen and car buttons at it.
- Visualiser audio tap: `AudioAnalyzer`, `AudioAnalysis`, `BeatTracker`, `TimedRing`. The drawing lives in `:feature:visualiser`.
- `AppContainer` (DomainContainer plus the player and the chosen output) and `Context.container`.

Decisions sit in pure objects so they are JVM-testable (`QueueEditMath`, `PlaybackPolicy`, `SleepTimerPolicy`, `SonosPlan`, `SonosPolling`, `SonosFailures`, `RemoteSession`, `RemoteVolume`). Only two tests use Robolectric: `QueueEditsOnPlayerTest` and `SongSourceChainTest` — no audio, no network.
