// "All artists", "All albums" and "All songs": everything on the server, with a remembered sort.
package im.flume.hearth.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.ArtistEntity
import im.flume.hearth.domain.music.LibraryOrder
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.domain.music.SongOrder
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.SongRow
import im.flume.hearth.core.ui.components.TopBar
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import im.flume.hearth.core.ui.screens.AlbumListRow
import im.flume.hearth.core.ui.screens.ArtistRow
import im.flume.hearth.core.ui.screens.SortMenu

internal val ARTIST_SORTS = listOf("A–Z", "Most albums")
internal val ALBUM_SORTS = listOf("A–Z", "Recently added", "Artist", "Most played", "Year")
private val SONG_SORTS = listOf("A–Z", "Artist", "Recently added", "Most played")

internal fun sortArtists(artists: List<ArtistEntity>, sort: Int) = LibraryOrder.artists(artists, sort)

internal fun sortAlbums(albums: List<AlbumEntity>, sort: Int) = LibraryOrder.albums(albums, sort)

/** Every artist on the server, for when you want more than Your Library. */
@Composable
fun BrowseArtistsScreen() {
    val dao = LocalContext.current.container.db.library()
    val artists by remember { dao.artists() }.collectAsStateWithLifecycle(emptyList())
    BrowseList("All artists", "browse-ARTISTS", ARTIST_SORTS) { sort ->
        items(sortArtists(artists, sort), key = { it.id }) { ArtistRow(it) }
    }
}

/** Every album on the server, saved or not. */
@Composable
fun BrowseAlbumsScreen() {
    val dao = LocalContext.current.container.db.library()
    val albums by remember { dao.albums() }.collectAsStateWithLifecycle(emptyList())
    BrowseList("All albums", "browse-ALBUMS", ALBUM_SORTS) { sort ->
        items(sortAlbums(albums, sort), key = { it.id }) { AlbumListRow(it) }
    }
}

@Composable
fun BrowseSongsScreen() {
    val context = LocalContext.current
    val dao = context.container.db.library()
    val actions = LocalActions.current
    val songs by remember { dao.allSongs() }.collectAsStateWithLifecycle(emptyList())
    // Sorting ~7k songs takes too long for the main thread: every order is sorted in the background,
    // once per version of the list, the saved one first. Until an order is first ready its list is empty.
    val savedOrder = remember { SongOrder.fromMenu(context.getSharedPreferences("library", Context.MODE_PRIVATE).getInt("browse-SONGS", 0)) }
    val sortedByOrder by produceState(emptyMap<SongOrder, List<SongEntity>>(), songs) {
        val orders = listOf(savedOrder) + SONG_SORTS.indices.map { SongOrder.fromMenu(it) }.filter { it != savedOrder }
        // The previous version's lists stay up until each is replaced, so nothing blanks on a resync.
        var done = value
        for (order in orders) {
            done = done + (order to withContext(Dispatchers.Default) { order.sort(songs) })
            value = done
        }
    }
    BrowseList(
        "All songs", "browse-SONGS", SONG_SORTS,
        extraActions = {
            IconButton(onClick = { actions.play(PlaySource.All, shuffle = true) }) {
                Icon(HearthTheme.icons.shuffle, "Shuffle all songs", tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) { sort ->
        val order = SongOrder.fromMenu(sort)
        val sorted = sortedByOrder[order].orEmpty()
        // Tapping a song plays everything, in this order, starting from that song. The service sorts
        // the library itself: thousands of ids in a Bundle can exceed the Binder limit.
        items(sorted, key = { it.id }) { song ->
            SongRow(song, onClick = {
                actions.play(PlaySource(PlaySource.Kind.ALL, label = "All songs", order = order), startSongId = song.id)
            })
        }
    }
}

@Composable
private fun BrowseList(
    title: String,
    prefKey: String,
    sortOptions: List<String>,
    extraActions: @Composable () -> Unit = {},
    content: LazyListScope.(sort: Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", Context.MODE_PRIVATE) }
    var sort by remember { mutableStateOf(prefs.getInt(prefKey, 0)) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title) {
            extraActions()
            SortMenu(sortOptions, sort) {
                sort = it
                prefs.edit().putInt(prefKey, it).apply()
            }
        }
        LazyColumn(Modifier.fillMaxSize()) { content(sort) }
    }
}
