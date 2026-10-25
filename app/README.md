# :app

The only module that sees everything: it wires the app together and owns the phone-facing names.

- `HearthApp.kt` — the `Application`, `HearthContainer` (the one real container; every repository is built here), and WorkManager's configuration.
- `ui/MainActivity` — the launcher activity. `ui/AppRoot` — the scaffold. `ui/Routes` — one call per feature's `<Feature>Routes()`. `ui/AppActions` — implements the `Actions` interfaces. `ui/NavBar` and `ui/PlayerOverlays` — the bottom bar, mini player, Now Playing and visualiser overlays.
- Kiosk mode, for a wall-mounted player the kids use. `ui/Kiosk` applies the policies when a phone makes Hearth its device owner (`adb shell dpm set-device-owner im.flume.hearth/.ui.KioskAdminReceiver`, via `ui/KioskAdminReceiver`): Hearth becomes the home screen (the `.ui.KioskHome` alias), pins itself, skips the lock screen and forces `Settings.kidsMode` on. `ui/KioskProfiles` keeps the family's logins and `ui/ProfilesScreen` is the "Who's listening" tab (and `KioskLogin`, the kiosk's sign-in page); `ui/KioskAccess` holds the PIN and `ui/KioskGate` is the dialog in front of Settings that can also step out to the phone's settings. Other phones are untouched.
- The manifest, launcher resources, signing config and versioning.

`im.flume.hearth` and `im.flume.hearth.ui.MainActivity` are installed on phones — do not rename them. `version.properties` belongs to `release.sh` alone.
