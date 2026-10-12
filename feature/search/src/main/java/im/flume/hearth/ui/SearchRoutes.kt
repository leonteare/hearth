package im.flume.hearth.ui

// The search feature's pages. Lives in the feature's module; :app's Routes.kt calls searchRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.core.ui.components.arg
import im.flume.hearth.core.ui.components.screen
import im.flume.hearth.ui.screens.SearchScreen

fun NavGraphBuilder.searchRoutes() {
    screen("search") { SearchScreen() }
}
