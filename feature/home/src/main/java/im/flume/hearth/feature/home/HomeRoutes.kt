package im.flume.hearth.feature.home

// The home feature's pages. Lives in the feature's module; :app's Routes.kt calls homeRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.core.ui.components.arg
import im.flume.hearth.core.ui.components.screen

fun NavGraphBuilder.homeRoutes() {
    screen("home") { HomeScreen() }
    screen("stats/{key}") { StatsScreen(it.arg("key")) }
}
