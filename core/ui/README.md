# :core:ui

The design system and anything two or more features draw.

- `theme/Tokens.kt` — `HearthColors`, `HearthSpacing`, `HearthShapeSet`, `HearthIcons`, read as `HearthTheme.colors/spacing/shapes/icons`. **Append-only**: add slots at the end, never rename or reorder. No hard-coded dp or colours above this module.
- `theme/` also: `Theme.kt` (accent helpers, corner radii, `HearthTheme` setup), `CoverSizes`, `Contrast`.
- `components/` — `Actions` (plus `LocalActions`), `Routing` (`screen()`, `arg()`), `SongRows`, `SongMenu`, `MediaItems`, `MissingRows`, `Sheets`, `PageChrome`, `PlayerChrome`, cover art (`CoverArt`, `CoverColor`, `CoverPrefetcher`, `CoverGradientHeader`, `MixCover`), `GetMusic` and `GetMusicSheets`, `NotOnServer` and `NotOnServerIcons`, `PreviewBar`, `PlayShuffleButtons`, `PlaylistPicker`, `LiveRows`/`RowContext`, `Formatters`.
- `screens/` — shared screen pieces: `LibraryRows`, `SpokenCommon`, `SpokenRows`, `SpokenMenus`, `FollowButton`, `LyricStyle`, `PlayerPosition`, `EqualWidthChips`, `OpmlImport`, `UpdateUi`, `CrashBanner`.

Rows and cards call `LocalActions.current` rather than taking callbacks threaded down through layers.
