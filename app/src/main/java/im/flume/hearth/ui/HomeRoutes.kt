package im.flume.hearth.ui

// The home feature's pages. Lives in the feature's module; :app's Routes.kt calls homeRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.ui.components.arg
import im.flume.hearth.ui.components.screen
import im.flume.hearth.ui.screens.HomeScreen
import im.flume.hearth.ui.screens.StatsScreen

fun NavGraphBuilder.homeRoutes() {
    screen("home") { HomeScreen() }
    screen("stats/{key}") { StatsScreen(it.arg("key")) }
}
