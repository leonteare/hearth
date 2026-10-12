package im.flume.hearth.ui

// Every page's route and how pages animate in and out (slide when drilling in, crossfade when switching tabs).
// Pages register in their feature module's <Feature>Routes.kt; this file just calls each one.

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

/** How long a page (or the full-screen player) takes to slide in or out. */
internal const val SLIDE_MS = 280
private const val TAB_FADE_MS = 150

/**
 * A forward navigation is a tab switch (crossfade) rather than drilling into a page (slide) when it
 * lands on a tab page, or when the page we left was popped off the back stack by the switch (switching
 * tabs saves and pops the old tab's pages; restoring a tab can land straight on one of its detail pages).
 */
private fun isTabSwitch(nav: NavHostController, initial: NavBackStackEntry, target: NavBackStackEntry): Boolean =
    target.destination.route in TAB_ROUTES || nav.currentBackStack.value.none { it.id == initial.id }

@Composable
internal fun AppNavHost(nav: NavHostController, modifier: Modifier) {
    val ease = FastOutSlowInEasing
    NavHost(
        nav,
        startDestination = "home",
        modifier = modifier,
        enterTransition = {
            if (isTabSwitch(nav, initialState, targetState)) fadeIn(tween(TAB_FADE_MS))
            else slideInHorizontally(tween(SLIDE_MS, easing = ease)) { it }
        },
        exitTransition = {
            if (isTabSwitch(nav, initialState, targetState)) fadeOut(tween(TAB_FADE_MS))
            else slideOutHorizontally(tween(SLIDE_MS, easing = ease)) { -it / 4 }
        },
        popEnterTransition = { slideInHorizontally(tween(SLIDE_MS, easing = ease)) { -it / 4 } },
        popExitTransition = { slideOutHorizontally(tween(SLIDE_MS, easing = ease)) { it } },
    ) {
        homeRoutes()
        searchRoutes()
        libraryRoutes()
        spokenRoutes()
        settingsRoutes()
    }
}
