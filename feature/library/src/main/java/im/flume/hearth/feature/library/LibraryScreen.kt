// The Your Library tab: which section and list you're on, its remembered sort, and pull to refresh.
package im.flume.hearth.feature.library

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.music.LibraryOrder
import im.flume.hearth.domain.music.PlaylistRules
import im.flume.hearth.domain.music.YourLibrary
import im.flume.hearth.domain.spoken.SpokenLists
import im.flume.hearth.domain.music.sync.SyncState
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.LocalTabRequests
import im.flume.hearth.core.ui.components.PageHeader
import im.flume.hearth.core.ui.components.plural
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import im.flume.hearth.core.ui.screens.EqualWidthChips
import im.flume.hearth.core.ui.screens.rememberOpmlImport

/** Keeps your place in Your Library across rotation and process death, in the same form as the prefs. */
private val LIBRARY_PLACE_SAVER = Saver<LibraryPlace, String>(save = { LibraryTabs.save(it) }, restore = { LibraryTabs.restore(it) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val c = LocalContext.current.container
    val dao = c.db.library()
    val actions = LocalActions.current
    val context = LocalContext.current
    val sortPrefs = remember { context.getSharedPreferences("library", Context.MODE_PRIVATE) }
    // Kept across app restarts too, so Your Library opens where you left it.
    var place by rememberSaveable(stateSaver = LIBRARY_PLACE_SAVER) {
        mutableStateOf(LibraryTabs.restore(sortPrefs.getString("place", null)))
    }
    fun goTo(next: LibraryPlace) {
        place = next
        sortPrefs.edit().putString("place", LibraryTabs.save(next)).apply()
    }
    val tab = place.tab
    // Kids mode (a kiosk phone) hides Podcasts; a saved podcasts place falls back to Music.
    val kids = c.session.settings.collectAsStateWithLifecycle().value.kidsMode
    LaunchedEffect(kids) { if (kids && place.section == LibrarySection.PODCASTS) goTo(place.copy(section = LibrarySection.MUSIC)) }
    // Opened on a given tab (Home's Podcasts and Audiobooks tiles).
    val tabRequests = LocalTabRequests.current
    LaunchedEffect(tabRequests.libraryTab) {
        val wanted = tabRequests.libraryTab ?: return@LaunchedEffect
        tabRequests.libraryTab = null
        goTo(LibraryTabs.open(place, wanted, podcasts = !kids))
    }
    var addPodcastsOpen by remember { mutableStateOf(false) }
    val playlistItems by remember { c.library.playlistItems }.collectAsStateWithLifecycle(emptyList())
    // Yours, ones you've joined, and invites waiting for an answer. Other people's playlists stay hidden.
    val playlists = remember(playlistItems) { PlaylistRules.visible(playlistItems) }
    // Personal: saved albums, and artists you follow or saved an album by. The whole server is under Search.
    val albums by remember { dao.savedAlbums() }.collectAsStateWithLifecycle(emptyList())
    val artists by remember { combine(dao.artists(), dao.savedAlbums(), YourLibrary::artists) }.collectAsStateWithLifecycle(emptyList())
    val songCount by remember { dao.songCount() }.collectAsStateWithLifecycle(0)
    val sync by c.sync.state.collectAsStateWithLifecycle()
    var sort by remember(tab) { mutableStateOf(sortPrefs.getInt("sort-${tab.name}", 0)) }
    val sortOptions = when (tab) {
        LibraryTab.PLAYLISTS -> listOf("A–Z", "Recently updated", "Most songs")
        LibraryTab.ARTISTS -> ARTIST_SORTS
        LibraryTab.ALBUMS -> ALBUM_SORTS
        LibraryTab.PODCASTS -> listOf("A–Z", "Newest episode")
        LibraryTab.AUDIOBOOKS -> listOf("A–Z", "Recently added", "Author", "Recently listened")
    }
    // Podcasts you follow and the server's audiobooks (each person follows their own shows).
    val spokenAccount by c.spoken.account.collectAsStateWithLifecycle()
    val spokenSyncing by c.spoken.syncing.collectAsStateWithLifecycle()
    // Null until loaded, so "No podcasts yet" doesn't flash up before the shows you follow.
    val followedOrNull by remember { c.spoken.followedPodcasts() }.collectAsStateWithLifecycle(null)
    val followed = followedOrNull.orEmpty()
    val latestEpisodes by remember { c.spoken.latestEpisodes(300) }.collectAsStateWithLifecycle(emptyList())
    val books by remember { c.spoken.books() }.collectAsStateWithLifecycle(emptyList())
    val newCounts = remember(latestEpisodes) { SpokenLists.newCounts(latestEpisodes) }
    val sortedPodcasts = remember(followed, latestEpisodes, sort, tab) {
        if (tab == LibraryTab.PODCASTS && sort == 1) {
            val newest = latestEpisodes.groupBy { it.episode.libraryItemId }.mapValues { (_, eps) -> eps.maxOf { it.episode.publishedAt } }
            followed.sortedByDescending { newest[it.id] ?: 0L }
        } else LibraryOrder.alphabetical(followed) { it.title }
    }
    val phoneBooks by remember { c.spoken.local.books() }.collectAsStateWithLifecycle(emptyList())
    val sortedBooks = remember(books, sort, tab) {
        if (tab != LibraryTab.AUDIOBOOKS) books else when (sort) {
            1 -> books.sortedByDescending { it.book.addedAt }
            2 -> books.sortedWith(compareBy({ it.book.author.orEmpty().lowercase() }, { it.book.title.lowercase() }))
            3 -> books.sortedByDescending { it.progressUpdatedAt ?: 0L }
            else -> LibraryOrder.alphabetical(books) { it.book.title }
        }
    }
    val importOpml = rememberOpmlImport()
    val spokenTab = tab == LibraryTab.PODCASTS || tab == LibraryTab.AUDIOBOOKS
    val sortedPlaylists = when (sort) {
        1 -> playlists.sortedByDescending { it.playlist.changed }
        2 -> playlists.sortedByDescending { it.playlist.songCount }
        else -> LibraryOrder.alphabetical(playlists) { it.playlist.name }
    }
    val sortedArtists = remember(artists, sort, tab) { if (tab == LibraryTab.ARTISTS) sortArtists(artists, sort) else artists }
    val sortedAlbums = remember(albums, sort, tab) { if (tab == LibraryTab.ALBUMS) sortAlbums(albums, sort) else albums }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Your Library")
        EqualWidthChips(LibraryTabs.sections(!kids), place.section, { it.label }, MaterialTheme.typography.labelLarge, minHeight = 36.dp) {
            goTo(place.copy(section = it))
        }
        LibraryControls(
            place = place,
            showAddPodcasts = tab == LibraryTab.PODCASTS && spokenAccount != null,
            onGoTo = { goTo(it) },
            onAddPodcasts = { addPodcastsOpen = true },
            sortOptions = sortOptions,
            sort = sort,
            onSort = {
                sort = it
                sortPrefs.edit().putInt("sort-${tab.name}", it).apply()
            },
        )
        AddPodcastsSheet(addPodcastsOpen, onDismiss = { addPodcastsOpen = false }, onImport = importOpml)
        PullToRefreshBox(
            isRefreshing = if (spokenTab) spokenSyncing else sync is SyncState.Running,
            onRefresh = {
                if (spokenTab) c.appScope.launch { c.spoken.refresh(force = true) }
                else c.appScope.launch { c.sync.fullSync(c.api.scanStatus()?.lastScan) }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                when (tab) {
                    LibraryTab.PLAYLISTS -> playlistsList(sortedPlaylists, actions)
                    LibraryTab.ARTISTS -> artistsList(sortedArtists, actions)
                    LibraryTab.ALBUMS -> albumsList(sortedAlbums, actions)
                    LibraryTab.PODCASTS -> podcastsList(
                        signedIn = spokenAccount != null,
                        noneFollowed = followedOrNull?.isEmpty() == true,
                        podcasts = sortedPodcasts,
                        newCounts = newCounts,
                        importOpml = importOpml,
                        actions = actions,
                    )
                    LibraryTab.AUDIOBOOKS -> audiobooksList(
                        sort = sort,
                        signedIn = spokenAccount != null,
                        books = sortedBooks,
                        noPhoneBooks = phoneBooks.isEmpty(),
                        syncing = spokenSyncing,
                        actions = actions,
                    )
                }
                libraryFooter(
                    when (tab) {
                        LibraryTab.PODCASTS -> if (spokenAccount == null || followed.isEmpty()) "" else "Following ${plural(followed.size, "podcast")} • pull down to refresh"
                        LibraryTab.AUDIOBOOKS -> if (spokenAccount == null || books.isEmpty()) "" else "${plural(books.size, "audiobook")} on the server • pull down to refresh"
                        else -> "${plural(songCount, "song")} on the server • pull down to refresh"
                    }
                )
            }
        }
    }
}
