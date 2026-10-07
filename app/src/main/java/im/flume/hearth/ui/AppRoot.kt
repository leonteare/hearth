package im.flume.hearth.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import im.flume.hearth.AppContainer
import im.flume.hearth.container
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.ui.components.Actions
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalRowContext
import im.flume.hearth.ui.components.RowContext
import im.flume.hearth.ui.screens.AlbumScreen
import im.flume.hearth.ui.screens.ArtistScreen
import im.flume.hearth.ui.screens.DownloadsScreen
import im.flume.hearth.ui.screens.GenreScreen
import im.flume.hearth.ui.screens.HomeScreen
import im.flume.hearth.ui.screens.LibraryScreen
import im.flume.hearth.ui.screens.LikedScreen
import im.flume.hearth.ui.screens.MiniPlayer
import im.flume.hearth.ui.screens.NowPlayingScreen
import im.flume.hearth.ui.screens.PlaylistScreen
import im.flume.hearth.ui.screens.QueueScreen
import im.flume.hearth.ui.screens.SearchScreen
import im.flume.hearth.ui.screens.SettingsScreen
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
        if (!c.network.isOnline.value) {
            scope.launch { snackbar.showSnackbar("Offline: playing downloaded songs only") }
        }
        c.player.play(source, startSongId, shuffle)
    }

    override fun playNext(songIds: List<String>) {
        c.player.playNext(songIds)
        scope.launch { snackbar.showSnackbar("Playing next") }
    }

    override fun addToQueue(songIds: List<String>) {
        c.player.addToQueue(songIds)
        scope.launch { snackbar.showSnackbar("Added to queue") }
    }

    override fun setStarred(songId: String, starred: Boolean) {
        scope.launch {
            runCatching { c.library.setStarred(songId, starred) }
                .onFailure { snackbar.showSnackbar("Couldn't update Liked Songs (offline?)") }
        }
    }

    override fun download(songs: List<SongEntity>) {
        c.downloads.download(songs)
        scope.launch {
            val wifi = if (c.session.settings.value.wifiOnlyDownloads) " (waits for Wi-Fi)" else ""
            snackbar.showSnackbar("Downloading ${songs.size} song${if (songs.size == 1) "" else "s"}$wifi")
        }
    }

    override fun removeDownload(songIds: List<String>) { c.downloads.remove(songIds) }
    override fun openAlbum(id: String) = nav.navigate("album/${Uri.encode(id)}")
    override fun openArtist(id: String) = nav.navigate("artist/${Uri.encode(id)}")
    override fun openPlaylist(id: String) = nav.navigate("playlist/${Uri.encode(id)}")
    override fun openGenre(name: String) = nav.navigate("genre/${Uri.encode(name)}")
    override fun open(route: String) = nav.navigate(route)
    override fun coverUrl(coverArt: String?, size: Int) = c.api.coverArtUrl(coverArt, size)
}

@Composable
fun AppRoot() {
    val c = LocalContext.current.container
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember { AppActions(c, nav, scope, snackbar) }
    var nowPlayingOpen by remember { mutableStateOf(false) }

    val playerState by c.player.state.collectAsStateWithLifecycle()
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    val downloads by c.downloads.states.collectAsStateWithLifecycle(emptyMap())
    val rowContext = RowContext(playerState.current?.mediaId, online, downloads)

    CompositionLocalProvider(LocalActions provides actions, LocalRowContext provides rowContext) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        if (playerState.current != null) MiniPlayer(playerState, onOpen = { nowPlayingOpen = true })
                        BottomBar(nav)
                    }
                },
            ) { padding ->
                NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                    composable("home") { HomeScreen() }
                    composable("search") { SearchScreen() }
                    composable("library") { LibraryScreen() }
                    composable("liked") { LikedScreen() }
                    composable("downloads") { DownloadsScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("album/{id}") { AlbumScreen(it.arguments!!.getString("id")!!) }
                    composable("artist/{id}") { ArtistScreen(it.arguments!!.getString("id")!!) }
                    composable("playlist/{id}") { PlaylistScreen(it.arguments!!.getString("id")!!) }
                    composable("genre/{name}") { GenreScreen(it.arguments!!.getString("name")!!) }
                    composable("queue") { QueueScreen() }
                }
            }

            AnimatedVisibility(
                visible = nowPlayingOpen && playerState.current != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BackHandler { nowPlayingOpen = false }
                NowPlayingScreen(
                    state = playerState,
                    onClose = { nowPlayingOpen = false },
                    onOpenQueue = { nowPlayingOpen = false; nav.navigate("queue") },
                )
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    NavigationBar(containerColor = Color(0xF0101010)) {
        listOf(
            Triple("home", "Home", Icons.Default.Home),
            Triple("search", "Search", Icons.Default.Search),
            Triple("library", "Your Library", Icons.Default.LibraryMusic),
        ).forEach { (dest, label, icon) ->
            NavigationBarItem(
                selected = route == dest,
                onClick = {
                    nav.navigate(dest) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
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
