package im.flume.hearth.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import im.flume.hearth.AppContainer
import im.flume.hearth.container
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.ui.components.Actions
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalRowContext
import im.flume.hearth.ui.components.PlaylistPickerDialog
import im.flume.hearth.ui.components.RowContext
import im.flume.hearth.ui.screens.AlbumScreen
import im.flume.hearth.ui.screens.ArtistScreen
import im.flume.hearth.ui.screens.DownloadsScreen
import im.flume.hearth.ui.screens.GenreScreen
import im.flume.hearth.ui.screens.HomeScreen
import im.flume.hearth.ui.screens.LibraryScreen
import im.flume.hearth.ui.screens.LikedScreen
import im.flume.hearth.ui.screens.MiniPlayer
import im.flume.hearth.ui.screens.MixScreen
import im.flume.hearth.ui.screens.PlaylistEditScreen
import im.flume.hearth.ui.screens.StatsScreen
import im.flume.hearth.ui.screens.NowPlayingScreen
import im.flume.hearth.ui.screens.PlaylistScreen
import im.flume.hearth.ui.screens.QueueScreen
import im.flume.hearth.ui.screens.SearchScreen
import im.flume.hearth.ui.screens.AppearanceSettings
import im.flume.hearth.ui.screens.GeneralSettings
import im.flume.hearth.ui.screens.SettingsScreen
import im.flume.hearth.ui.screens.StorageSettings
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.Background
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private class AppActions(
    private val c: AppContainer,
    private val nav: NavHostController,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) : Actions {
    override fun play(source: PlaySource, startSongId: String?, shuffle: Boolean) {
        if (shuffle) c.usage.track(im.flume.hearth.data.Usage.SHUFFLE)
        if (!c.network.isOnline.value) {
            scope.launch { snackbar.showSnackbar("Offline: playing downloaded songs only") }
        }
        c.player.play(source, startSongId, shuffle)
    }

    override fun showUndo(message: String, undo: suspend () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) c.appScope.launch { runCatching { undo() } }
        }
    }

    override fun showMessage(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    override fun playNext(songIds: List<String>) {
        haptic(HapticFeedbackType.Confirm)
        c.player.playNext(songIds)
        showUndo(if (songIds.size == 1) "Playing next" else "${songIds.size} songs will play next") { c.player.removeQueued(songIds) }
    }

    override fun addToQueue(songIds: List<String>) {
        haptic(HapticFeedbackType.Confirm)
        c.player.addToQueue(songIds)
        showUndo(if (songIds.size == 1) "Added to the queue" else "Added ${songIds.size} songs to the queue") { c.player.removeQueued(songIds) }
    }

    override fun setStarred(songId: String, starred: Boolean) {
        haptic(if (starred) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        scope.launch {
            runCatching { c.library.setStarred(songId, starred) }
                .onSuccess { if (!starred) showUndo("Removed from Liked Songs") { c.library.setStarred(songId, true) } }
                .onFailure { snackbar.showSnackbar("Couldn't update Liked Songs (offline?)") }
        }
    }

    var haptics: HapticFeedback? = null
    private fun haptic(type: HapticFeedbackType) { haptics?.performHapticFeedback(type) }

    override fun download(songs: List<SongEntity>) {
        c.usage.track(im.flume.hearth.data.Usage.DOWNLOAD)
        c.downloads.download(songs)
        scope.launch {
            val wifi = if (c.session.settings.value.wifiOnlyDownloads && !c.network.wifi.value) " (waits for Wi-Fi)" else ""
            snackbar.showSnackbar("Downloading ${songs.size} song${if (songs.size == 1) "" else "s"}$wifi")
        }
    }

    override fun removeDownload(songIds: List<String>) {
        c.downloads.remove(songIds)
        showUndo(if (songIds.size == 1) "Download removed" else "${songIds.size} downloads removed") {
            c.downloads.download(c.library.songsByIds(songIds))
        }
    }
    override fun openAlbum(id: String) = nav.navigate("album/${Uri.encode(id)}")
    override fun openArtist(id: String) = nav.navigate("artist/${Uri.encode(id)}")
    override fun openPlaylist(id: String) = nav.navigate("playlist/${Uri.encode(id)}")
    override fun openGenre(name: String) = nav.navigate("genre/${Uri.encode(name)}")
    override fun open(route: String) {
        when {
            route.startsWith("mix/") -> c.usage.track(im.flume.hearth.data.Usage.MIX)
            route.startsWith("stats/") -> c.usage.track(im.flume.hearth.data.Usage.STATS)
            route == "queue" -> c.usage.track(im.flume.hearth.data.Usage.QUEUE)
        }
        nav.navigate(route)
    }
    override fun coverUrl(coverArt: String?, size: Int) = c.api.coverArtUrl(coverArt, size)

    /** Songs waiting for the "Add to playlist" picker, or null when it's closed. */
    val playlistPicker = mutableStateOf<List<String>?>(null)

    override fun addToPlaylist(songIds: List<String>) {
        c.usage.track(im.flume.hearth.data.Usage.PLAYLIST_ADD)
        playlistPicker.value = songIds
    }

    override fun startRadio(song: SongEntity) = radio("Radio · ${song.title}") { c.library.radio(song, null, it) }

    override fun startArtistRadio(artistId: String, name: String) = radio("Radio · $name") { c.library.radio(null, artistId, it) }

    private fun radio(label: String, ids: suspend (online: Boolean) -> List<String>) {
        c.usage.track(im.flume.hearth.data.Usage.RADIO)
        scope.launch {
            val list = ids(c.network.isOnline.value)
            if (list.isEmpty()) {
                snackbar.showSnackbar("Couldn't find anything for this radio")
            } else {
                c.player.play(PlaySource(PlaySource.Kind.SONGS, label = label, songIds = list))
            }
        }
    }

    fun confirmAddToPlaylist(playlistId: String?, name: String, songIds: List<String>) {
        playlistPicker.value = null
        c.appScope.launch {
            val result = runCatching {
                if (playlistId == null) c.library.createPlaylist(name, songIds) else c.library.addToPlaylist(playlistId, songIds)
            }
            snackbar.showSnackbar(
                if (result.isSuccess) (if (playlistId == null) "Created \"$name\"" else "Added to \"$name\"")
                else "Couldn't update the playlist (offline?)"
            )
        }
    }
}

private val TABS = listOf(
    Triple("home", "Home", Icons.Default.Home),
    Triple("search", "Search", Icons.Default.Search),
    Triple("library", "Your Library", Icons.Default.LibraryMusic),
)
private val TAB_ROUTES = TABS.map { it.first }

/**
 * Remembers the last tab switch so its page slides in from the side the tab is on
 * (left-hand tab from the left, right-hand tab from the right).
 */
private class TransitionFlags {
    var tabSwitchAt = 0L
    var direction = 1
    val isTabSwitch: Boolean get() = System.currentTimeMillis() - tabSwitchAt < 600
}

private const val SLIDE_MS = 280

@Composable
fun AppRoot() {
    val c = LocalContext.current.container
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember { AppActions(c, nav, scope, snackbar) }
    actions.haptics = LocalHapticFeedback.current
    val flags = remember { TransitionFlags() }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

    val playerState by c.player.state.collectAsStateWithLifecycle()
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    val downloads by c.downloads.states.collectAsStateWithLifecycle(emptyMap())
    val rowContext = RowContext(playerState.current?.mediaId, online, downloads)

    // The tab you're "in" is the last tab page on the back stack, even when deep inside an album.
    val backStack by nav.currentBackStack.collectAsStateWithLifecycle()
    val currentTab = backStack.lastOrNull { it.destination.route in TAB_ROUTES }?.destination?.route ?: "home"

    val onTabClick: (String) -> Unit = { dest ->
        if (dest == currentTab) {
            // Tapping the tab you're already in goes back to its main page.
            nav.popBackStack(dest, inclusive = false)
        } else {
            flags.direction = if (TAB_ROUTES.indexOf(dest) > TAB_ROUTES.indexOf(currentTab)) 1 else -1
            flags.tabSwitchAt = System.currentTimeMillis()
            nav.navigate(dest) {
                popUpTo("home") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val ease = FastOutSlowInEasing

    CompositionLocalProvider(LocalActions provides actions, LocalRowContext provides rowContext) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (landscape) SideRail(currentTab, onTabClick)
                Scaffold(
                    modifier = if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)) else Modifier,
                    containerColor = Background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        Column(if (landscape) Modifier.navigationBarsPadding().padding(bottom = 8.dp) else Modifier) {
                            if (playerState.current != null) MiniPlayer(playerState, onOpen = { nowPlayingOpen = true })
                            if (!landscape) BottomBar(currentTab, onTabClick)
                        }
                    },
                ) { padding ->
                    NavHost(
                        nav,
                        startDestination = "home",
                        modifier = Modifier.padding(padding),
                        enterTransition = {
                            val dir = if (flags.isTabSwitch) flags.direction else 1
                            slideInHorizontally(tween(SLIDE_MS, easing = ease)) { it * dir }
                        },
                        exitTransition = {
                            val dir = if (flags.isTabSwitch) flags.direction else 1
                            slideOutHorizontally(tween(SLIDE_MS, easing = ease)) { -it * dir / 4 }
                        },
                        popEnterTransition = { slideInHorizontally(tween(SLIDE_MS, easing = ease)) { -it / 4 } },
                        popExitTransition = { slideOutHorizontally(tween(SLIDE_MS, easing = ease)) { it } },
                    ) {
                        screen("home") { HomeScreen() }
                        screen("search") { SearchScreen() }
                        screen("library") { LibraryScreen() }
                        screen("liked") { LikedScreen() }
                        screen("downloads") { DownloadsScreen() }
                        screen("settings") { SettingsScreen() }
                        screen("settings/general") { GeneralSettings() }
                        screen("settings/storage") { StorageSettings() }
                        screen("settings/appearance") { AppearanceSettings() }
                        screen("album/{id}") { AlbumScreen(it.arguments!!.getString("id")!!) }
                        screen("artist/{id}") { ArtistScreen(it.arguments!!.getString("id")!!) }
                        screen("playlist/{id}") { PlaylistScreen(it.arguments!!.getString("id")!!) }
                        screen("genre/{name}") { GenreScreen(it.arguments!!.getString("name")!!) }
                        screen("queue") { QueueScreen() }
                        screen("mix/{index}") { MixScreen(it.arguments!!.getString("index")!!.toInt()) }
                        screen("stats/{key}") { StatsScreen(it.arguments!!.getString("key")!!) }
                        screen("playlist-edit/{id}") { PlaylistEditScreen(it.arguments!!.getString("id")!!) }
                    }
                }
            }

            actions.playlistPicker.value?.let { ids ->
                PlaylistPickerDialog(
                    songIds = ids,
                    onDismiss = { actions.playlistPicker.value = null },
                    onAdd = { id, name -> actions.confirmAddToPlaylist(id, name, ids) },
                    onCreate = { name -> actions.confirmAddToPlaylist(null, name, ids) },
                )
            }

            AnimatedVisibility(
                visible = nowPlayingOpen && playerState.current != null,
                enter = slideInVertically(tween(SLIDE_MS, easing = ease)) { it },
                exit = slideOutVertically(tween(SLIDE_MS, easing = ease)) { it },
            ) {
                BackHandler { nowPlayingOpen = false }
                NowPlayingScreen(
                    state = playerState,
                    onClose = { nowPlayingOpen = false },
                    onOpenQueue = { nowPlayingOpen = false; actions.open("queue") },
                )
            }
        }
    }
}

/** Every page gets an opaque background so pages sliding over each other don't show through. */
private fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
    composable(route) { entry ->
        Box(Modifier.fillMaxSize().background(Background)) { content(entry) }
    }
}

@Composable
private fun BottomBar(currentTab: String, onTabClick: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xF0101010)) {
        TABS.forEach { (dest, label, icon) ->
            NavigationBarItem(
                selected = currentTab == dest,
                onClick = { onTabClick(dest) },
                icon = { Icon(icon, label) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Accent.copy(alpha = 0.25f),
                ),
            )
        }
    }
}

@Composable
private fun SideRail(currentTab: String, onTabClick: (String) -> Unit) {
    NavigationRail(containerColor = Color(0xFF0C0C0C)) {
        Spacer(Modifier.weight(1f))
        TABS.forEach { (dest, label, icon) ->
            NavigationRailItem(
                selected = currentTab == dest,
                onClick = { onTabClick(dest) },
                icon = { Icon(icon, label) },
                label = { Text(label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Accent.copy(alpha = 0.25f),
                ),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
