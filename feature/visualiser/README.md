# :feature:visualiser

The full-screen visualiser. It registers no routes: `:app`'s `PlayerOverlays` draws `VisualiserScreen` over the player.

- `VisualiserScreen` — picks a style, feeds it audio, handles taps, swipes and closing.
- `VisualiserCore` — what every style shares: `VisualiserStyle` (the list, and the id Settings remembers), `VisFrame`, `VisPalette`.
- `VisualiserControls` — the on-screen controls, the render layer, and keeping the screen awake.
- Renderers: `ParticlesRenderer`, `RibbonRenderer`, `SphereRenderer` (with `SphereSpin`, its turn), `UnknownPleasuresRenderer`, and the retro ones on `RetroRenderer` (`MemoryCardRenderer`, `GridRunnerRenderer`, `SoundScopeRenderer`).
- Helpers: `Retro3d` (camera, grid snapping, fog, colour maths), `Ridges`, `ScopeTrace`, `FramePacer` (frame rate, and the slow paused idle for styles that keep moving).

Audio comes from the tap in `:core:playback` (`AudioAnalyzer`, `AudioAnalysis`, `BeatTracker`, `TimedRing`). No dependency either way with `:feature:player`, which only hands out an "open the visualiser" callback.

`GridRunnerRenderer` is the one file allowed over 400 lines (`scripts/large-files.txt`).
