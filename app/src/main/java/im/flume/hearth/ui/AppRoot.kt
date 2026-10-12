package im.flume.hearth.ui

// The signed-in app's composition root: app-wide state and CompositionLocals, then the page layers in drawing
// order (tabs and pages, sheets and dialogs, player overlays, messages on top). Routes, tabs, actions and the
// overlays themselves live in Routes.kt, NavBar.kt, AppActions.kt and PlayerOverlays.kt.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.DownloadCounts
import im.flume.hearth.domain.music.PlaylistRules
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.ui.components.DownloadsBadge
import im.flume.hearth.core.ui.components.GetMusicSheets
import im.flume.hearth.core.ui.components.LiveRows
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.LocalDownloadsBadge
import im.flume.hearth.core.ui.components.LocalGetMusic
import im.flume.hearth.core.ui.components.LocalLiveRows
import im.flume.hearth.core.ui.components.LocalPreview
import im.flume.hearth.core.ui.components.LocalRequests
import im.flume.hearth.core.ui.components.LocalRowContext
import im.flume.hearth.core.ui.components.LocalTabRequests
import im.flume.hearth.core.ui.components.PrefetchPlayerCovers
import im.flume.hearth.core.ui.components.RowContext
import im.flume.hearth.core.ui.components.rememberGetMusic
import im.flume.hearth.ui.screens.LocalSearchFocus
import im.flume.hearth.core.ui.screens.LocalSpokenNow
import im.flume.hearth.ui.screens.SearchFocusRequest
import im.flume.hearth.ui.screens.allDownloadCounts
import im.flume.hearth.core.ui.theme.HearthTheme

@Composable
fun AppRoot() {
    val c = LocalContext.current.container
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember { AppActions(c, nav, scope, snackbar) }
    actions.haptics = LocalHapticFeedback.current
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    // The queue lives inside the player: back from the queue returns to Now Playing.
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    // The visualiser also opens from the player, full screen above it.
    var visualiserOpen by rememberSaveable { mutableStateOf(false) }
    actions.closeOverlays = { visualiserOpen = false; queueOpen = false; nowPlayingOpen = false }

    val playerState by c.player.state.collectAsStateWithLifecycle()
    PrefetchPlayerCovers(playerState)
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    val downloads by c.downloads.states.collectAsStateWithLifecycle(emptyMap())
    val rowContext = RowContext(playerState.current?.mediaId, online, downloads)
    val liveRows = remember { LiveRows() }
    SideEffect { liveRows.playingId = playerState.current?.mediaId; liveRows.online = online; liveRows.downloads = downloads }
    // Music that isn't on the server: what's on its way, the preview playing, and the hearts that get it.
    val requestList by remember { c.requests.requests }.collectAsStateWithLifecycle(emptyList())
    val requestsByKey = remember(requestList) { requestList.associateBy { "${it.type}:${it.tidalId}" } }
    val preview by c.preview.current.collectAsStateWithLifecycle()
    val getMusic = rememberGetMusic(c, actions)
    LaunchedEffect(Unit) { c.preview.messages.collect { actions.showMessage(it) } }
    LaunchedEffect(Unit) { c.spoken.feeds.messages.collect { actions.showMessage(it) } }
    // An episode or book picked up from where another phone (or the web) left it: say so, and offer
    // to go back to where this phone was.
    LaunchedEffect(Unit) {
        c.spoken.resumeNotices.collect { n ->
            actions.showUndo(n.message) {
                val now = c.player.state.value.spoken
                if (now?.libraryItemId == n.libraryItemId && now.episodeId == n.episodeId) c.player.seekSpoken((n.fromSec * 1000).toLong())
            }
        }
    }

    // The tab you're "in" is the last tab page on the back stack, even when deep inside an album.
    val backStack by nav.currentBackStack.collectAsStateWithLifecycle()
    // The small downloads icon in every page's header: songs, episodes and books on their way (or
    // stuck), plus music coming to the server. Not on the Downloads page itself.
    val downloadCounts by remember { c.allDownloadCounts() }.collectAsStateWithLifecycle(DownloadCounts())
    val onDownloadsPage = backStack.lastOrNull()?.destination?.route == "downloads"
    val downloadsBadge = remember(downloadCounts, requestList, onDownloadsPage) {
        if (onDownloadsPage) null
        else DownloadsBadge.of(
            downloadCounts,
            coming = requestList.count { it.status.active },
            comingFailed = requestList.count { it.status == RequestStatus.FAILED },
        )
    }
    // How tall the mini player and tab bar are, so messages sit just above them.
    var bottomBarPx by remember { mutableIntStateOf(0) }
    val currentTab = backStack.lastOrNull { it.destination.route in TAB_ROUTES }?.destination?.route ?: "home"
    // A red dot on Your Library while a playlist invite is waiting for an answer.
    val playlistItems by remember { c.library.playlistItems }.collectAsStateWithLifecycle(emptyList())
    val badged = if (PlaylistRules.pendingInvites(playlistItems).isNotEmpty()) "library" else null

    val searchFocus = remember { SearchFocusRequest() }
    val onTabClick: (String) -> Unit = { dest ->
        // Tapping Search means "I want to type"; coming back to it from a result doesn't.
        if (dest == "search") searchFocus.pending = true
        if (dest == currentTab) {
            // Tapping the tab you're already in goes back to its main page.
            nav.popBackStack(dest, inclusive = false)
        } else {
            nav.navigate(dest) {
                popUpTo("home") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    actions.switchTab = onTabClick

    // Pages opened from outside, e.g. tapping an "Added to your phone" notification.
    val pendingRoute by c.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        c.pendingRoute.value = null
        runCatching { actions.open(route) }
    }

    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp

    CompositionLocalProvider(
        LocalActions provides actions,
        LocalRowContext provides rowContext,
        LocalLiveRows provides liveRows,
        LocalRequests provides requestsByKey,
        LocalPreview provides preview,
        LocalGetMusic provides getMusic,
        LocalSpokenNow provides playerState.spoken,
        LocalTabRequests provides actions.tabRequests,
        LocalDownloadsBadge provides downloadsBadge,
        LocalSearchFocus provides searchFocus,

    ) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (landscape) SideRail(currentTab, badged, onTabClick)
                Scaffold(
                    modifier = if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)) else Modifier,
                    containerColor = HearthTheme.colors.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        AppBottomBar(
                            playerState, landscape, currentTab, badged, onTabClick,
                            onOpenPlayer = { nowPlayingOpen = true },
                            onHeight = { bottomBarPx = it },
                        )
                    },
                ) { padding ->
                    AppNavHost(nav, Modifier.padding(padding))
                }
            }

            GetMusicSheets(getMusic)
            PlaylistDialogs(actions)

            PlayerOverlays(
                playerState, nowPlayingOpen, queueOpen, visualiserOpen,
                onCloseNowPlaying = { nowPlayingOpen = false },
                onQueueOpen = { queueOpen = it },
                onVisualiserOpen = { visualiserOpen = it },
            )

            // Drawn last so messages (and their Undo) from the player, queue or visualiser show over them.
            MessageHost(snackbar, overlayOpen = nowPlayingOpen && playerState.current != null, bottomBarPx, landscape)
        }
    }
}
