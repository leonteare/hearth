package im.flume.hearth.ui

// The settings feature's pages. Lives in the feature's module; :app's Routes.kt calls settingsRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.core.ui.components.arg
import im.flume.hearth.core.ui.components.screen
import im.flume.hearth.ui.screens.AppearanceSettings
import im.flume.hearth.ui.screens.DownloadsScreen
import im.flume.hearth.ui.screens.GeneralSettings
import im.flume.hearth.ui.screens.SettingsScreen
import im.flume.hearth.ui.screens.StorageSettings

fun NavGraphBuilder.settingsRoutes() {
    screen("downloads") { DownloadsScreen() }
    screen("settings") { SettingsScreen() }
    screen("settings/general") { GeneralSettings() }
    // Straight to the podcasts & audiobooks sign-in, from the prompts on those tabs.
    screen("settings/general/podcasts") { GeneralSettings(showSpokenAccount = true) }
    screen("settings/storage") { StorageSettings() }
    screen("settings/appearance") { AppearanceSettings() }
}
