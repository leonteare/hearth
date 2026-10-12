package im.flume.hearth.feature.search

// The search feature's pages. Lives in the feature's module; :app's Routes.kt calls searchRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.core.ui.components.arg
import im.flume.hearth.core.ui.components.screen

fun NavGraphBuilder.searchRoutes() {
    screen("search") { SearchScreen() }
}
