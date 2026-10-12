package im.flume.hearth.ui

// Every page's route and how pages animate in and out (slide when drilling in, crossfade when switching tabs).
// Register new pages here as screen("name/{arg}") { ... }.

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import im.flume.hearth.ui.screens.AlbumScreen
import im.flume.hearth.ui.screens.AppearanceSettings
import im.flume.hearth.ui.screens.ArtistScreen
import im.flume.hearth.ui.screens.BookScreen
import im.flume.hearth.ui.screens.BrowseAlbumsScreen
import im.flume.hearth.ui.screens.BrowseArtistsScreen
import im.flume.hearth.ui.screens.BrowseSongsScreen
import im.flume.hearth.ui.screens.DownloadsScreen
import im.flume.hearth.ui.screens.GeneralSettings
import im.flume.hearth.ui.screens.GenreScreen
import im.flume.hearth.ui.screens.HomeScreen
import im.flume.hearth.ui.screens.LibraryScreen
import im.flume.hearth.ui.screens.LikedScreen
import im.flume.hearth.ui.screens.MixScreen
import im.flume.hearth.ui.screens.PlaylistEditScreen
import im.flume.hearth.ui.screens.PlaylistScreen
import im.flume.hearth.ui.screens.PodcastScreen
import im.flume.hearth.ui.screens.SearchScreen
import im.flume.hearth.ui.screens.SettingsScreen
import im.flume.hearth.ui.screens.StatsScreen
import im.flume.hearth.ui.screens.StorageSettings
import im.flume.hearth.ui.theme.Background

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
        screen("home") { HomeScreen() }
        screen("search") { SearchScreen() }
        screen("library") { LibraryScreen() }
        screen("browse/artists") { BrowseArtistsScreen() }
        screen("browse/albums") { BrowseAlbumsScreen() }
        screen("browse/songs") { BrowseSongsScreen() }
        screen("liked") { LikedScreen() }
        screen("downloads") { DownloadsScreen() }
        screen("settings") { SettingsScreen() }
        screen("settings/general") { GeneralSettings() }
        // Straight to the podcasts & audiobooks sign-in, from the prompts on those tabs.
        screen("settings/general/podcasts") { GeneralSettings(showSpokenAccount = true) }
        screen("settings/storage") { StorageSettings() }
        screen("settings/appearance") { AppearanceSettings() }
        screen("album/{id}") { AlbumScreen(it.arguments!!.getString("id")!!) }
        screen("artist/{id}") { ArtistScreen(it.arguments!!.getString("id")!!) }
        screen("playlist/{id}") { PlaylistScreen(it.arguments!!.getString("id")!!) }
        screen("genre/{name}") { GenreScreen(it.arguments!!.getString("name")!!) }
        screen("mix/{key}") { MixScreen(it.arguments!!.getString("key")!!) }
        screen("stats/{key}") { StatsScreen(it.arguments!!.getString("key")!!) }
        screen("playlist-edit/{id}") { PlaylistEditScreen(it.arguments!!.getString("id")!!) }
        screen("podcast/{id}") { PodcastScreen(it.arguments!!.getString("id")!!) }
        screen("podcast-feed/{url}") { PodcastScreen(null, feedUrl = it.arguments!!.getString("url")!!) }
        screen("book/{id}") { BookScreen(it.arguments!!.getString("id")!!) }
    }
}

/** Every page gets an opaque background so pages sliding over each other don't show through. */
private fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
    composable(route) { entry ->
        Box(Modifier.fillMaxSize().background(Background)) { content(entry) }
    }
}
