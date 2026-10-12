# :app

The only module that sees everything: it wires the app together and owns the phone-facing names.

- `HearthApp.kt` — the `Application`, `HearthContainer` (the one real container; every repository is built here), and WorkManager's configuration.
- `ui/MainActivity` — the launcher activity. `ui/AppRoot` — the scaffold. `ui/Routes` — one call per feature's `<Feature>Routes()`. `ui/AppActions` — implements the `Actions` interfaces. `ui/NavBar` and `ui/PlayerOverlays` — the bottom bar, mini player, Now Playing and visualiser overlays.
- The manifest, launcher resources, signing config and versioning.

`im.flume.hearth` and `im.flume.hearth.ui.MainActivity` are installed on phones — do not rename them. `version.properties` belongs to `release.sh` alone.
