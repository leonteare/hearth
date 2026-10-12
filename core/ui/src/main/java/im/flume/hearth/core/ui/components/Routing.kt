package im.flume.hearth.core.ui.components

// How a page registers its route: every feature module's <Feature>Routes.kt calls screen(...) for its pages.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import im.flume.hearth.core.ui.theme.HearthTheme

/** Every page gets an opaque background so pages sliding over each other don't show through. */
fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
    composable(route) { entry ->
        Box(Modifier.fillMaxSize().background(HearthTheme.colors.background)) { content(entry) }
    }
}

/** A route's text argument, e.g. the id in "album/{id}". */
fun NavBackStackEntry.arg(name: String): String = arguments!!.getString(name)!!
