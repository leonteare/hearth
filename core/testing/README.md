# :core:testing

Test-only helpers, pulled in through `testImplementation` by the `hearth.screenshots` convention plugin.

- `Samples` — fixed sample data. Use fixed dates, never "now", or screenshots drift.
- `HearthTestTheme`, `FakeActions`, `liveRows()` — render any screen without a running app.
- `TestContainer` / `TestApplication` — the fake `AppContainer`. A new repository gets a property here as well as in `:app`'s `HearthContainer`.
- `ScreenshotTest` — base class: `snap("name") { ... }` for a cropped composable, `snapScreen` for the whole phone (sheets, dialogs).
