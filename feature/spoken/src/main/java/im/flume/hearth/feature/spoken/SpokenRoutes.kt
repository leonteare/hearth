package im.flume.hearth.feature.spoken

// The spoken feature's pages. Lives in the feature's module; :app's Routes.kt calls spokenRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.core.ui.components.arg
import im.flume.hearth.core.ui.components.screen

fun NavGraphBuilder.spokenRoutes() {
    screen("podcast/{id}") { PodcastScreen(it.arg("id")) }
    screen("podcast-feed/{url}") { PodcastScreen(null, feedUrl = it.arg("url")) }
    screen("book/{id}") { BookScreen(it.arg("id")) }
}
