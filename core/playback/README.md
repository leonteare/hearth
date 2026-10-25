# :core:playback

Media3 playback, and the container the UI reads.

- `PlaybackService` plus its extension files: `PlayerSetup`, `PlayerListener`, `SessionCallback`, `SessionExtras`, `QueueEdits`, `QueuePersistence`, `PlaybackRecovery`, `StreamCaching`, `SpokenMode`, `SleepTimer`, `VolumeLevelling`, `SpokenAwarePlayer`, `RemoteVolumePlayer`.
- `PlayerConnection` (`PlayerUiState`) — what screens observe. `QueueStore` saves the queue.
- `SongDataSource` — download, then cache, then stream, in that order. `Scrobbler`, `PlaybackErrors`/`PlaybackPolicy`, `CoverArt`, `SpokenPlayback`, `PreviewPlayer` (30 s previews).
- Sonos output: `SonosOutput` (the chosen speaker, handing the queue over, the phone as its remote control),
  `SonosHandover` (`AudioOutput`, `SonosTrack`, `SonosPlan`, `RemoteOutput`), `SonosPolicy` (`SonosPolling`,
  `SonosFailures`), `SonosSession` (the requests one hand-over is made of), `SonosFinder` (the speaker search)
  and `SonosTracks` (a queue entry as a speaker needs it). The URLs themselves belong to `:domain:music` and `:domain:spoken`.
  While a speaker has the sound, `RemoteVolumePlayer` (`RemoteVolume`) has the media session report its volume as
  remote, so Android sends the volume keys to it with the phone locked and shows its own slider for the speaker.
- Visualiser audio tap: `AudioAnalyzer`, `AudioAnalysis`, `BeatTracker`, `TimedRing`. The drawing lives in `:feature:visualiser`.
- `AppContainer` (DomainContainer plus the player and the chosen output) and `Context.container`.

Decisions sit in pure objects so they are JVM-testable (`QueueEditMath`, `PlaybackPolicy`, `SleepTimerPolicy`, `SonosPlan`, `SonosPolling`, `SonosFailures`, `RemoteVolume`). Only two tests use Robolectric: `QueueEditsOnPlayerTest` and `SongSourceChainTest` — no audio, no network.
