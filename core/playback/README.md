# :core:playback

Media3 playback, and the container the UI reads.

- `PlaybackService` plus its extension files: `PlayerSetup`, `PlayerListener`, `SessionCallback`, `SessionExtras`, `QueueEdits`, `QueuePersistence`, `PlaybackRecovery`, `StreamCaching`, `SpokenMode`, `SleepTimer`, `VolumeLevelling`, `SpokenAwarePlayer`.
- `PlayerConnection` (`PlayerUiState`) — what screens observe. `QueueStore` saves the queue.
- `SongDataSource` — download, then cache, then stream, in that order. `Scrobbler`, `PlaybackErrors`/`PlaybackPolicy`, `CoverArt`, `SpokenPlayback`, `PreviewPlayer` (30 s previews).
- Visualiser audio tap: `AudioAnalyzer`, `AudioAnalysis`, `BeatTracker`, `TimedRing`. The drawing lives in `:feature:visualiser`.
- `AppContainer` (DomainContainer plus the player) and `Context.container`.

Decisions sit in pure objects so they are JVM-testable (`QueueEditMath`, `PlaybackPolicy`, `SleepTimerPolicy`). Only two tests use Robolectric: `QueueEditsOnPlayerTest` and `SongSourceChainTest` — no audio, no network.
