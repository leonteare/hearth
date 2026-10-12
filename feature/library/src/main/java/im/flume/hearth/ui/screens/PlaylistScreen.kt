// The page for one playlist: its songs, removing songs with Undo, and the invite banner.
package im.flume.hearth.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.domain.music.PlaylistEdits
import im.flume.hearth.domain.music.PlaylistRules
import im.flume.hearth.domain.music.ServerMixes
import im.flume.hearth.domain.music.download.DownloadRepository
import im.flume.hearth.core.ui.components.EmptyState
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.theme.HearthTheme
import im.flume.hearth.core.common.couldNot
import kotlinx.coroutines.launch
import im.flume.hearth.core.ui.screens.coverArt

@Composable
fun PlaylistScreen(id: String) {
    val c = LocalContext.current.container
    val dao = c.db.library()
    val item by remember(id) { c.library.playlistItem(id) }.collectAsStateWithLifecycle(null)
    val playlist = item?.playlist
    val access = item?.access
    val actions = LocalActions.current
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    // Entries keep their server positions, including songs this phone hasn't synced yet.
    val entries by remember(id) { dao.playlistEntries(id) }.collectAsStateWithLifecycle(null)
    val shown = remember(entries) { entries.orEmpty().withIndex().filter { it.value.song != null } }
    val songs = remember(shown) { shown.map { it.value.song!! } }
    val loadedSongs = entries?.let { songs }
    val screenScope = rememberCoroutineScope()
    // Shared playlists change from the other phone; re-read on open so those edits show up right away.
    // Also re-read when an entry's song isn't known here yet, which brings its details in.
    val shared = access != null && (access.isShared || !access.isOwner)
    val unknownSongs = entries?.any { it.song == null } == true
    LaunchedEffect(id, shared, online, unknownSongs) {
        if ((shared || unknownSongs) && online) runCatching { c.library.refreshPlaylist(id) }
    }
    CollectionScreen(
        searchable = true,
        title = playlist?.name.orEmpty(),
        // Only name the owner when it's someone else's playlist. A server mix says when it leaves instead.
        subtitle = when {
            playlist != null && ServerMixes.isMix(playlist!!) -> ServerMixes.description(playlist!!)
            access == null || access.isOwner -> ""
            else -> "by ${PlaylistRules.displayName(access.owner)}"
        },
        onRemoveSong = if (access?.canEdit == true) { index ->
            // Identify the song by id and occurrence, not list position: the server re-finds it.
            val all = entries.orEmpty().map { it.songId }
            shown.getOrNull(index)?.let { (at, entry) ->
                val occurrence = PlaylistEdits.occurrenceAt(all, at)
                c.appScope.launch {
                    runCatching { c.library.removeFromPlaylist(id, entry.songId, occurrence) }
                        .onSuccess { removal ->
                            if (removal != null) actions.showUndo("Removed from playlist") {
                                runCatching { c.library.undoRemoval(id, removal) }
                                    .onFailure { actions.showMessage(c.library.editError(it, access.owner, couldNot("Couldn't put the song back", it, !c.network.isOnline.value))) }
                            }
                        }
                        .onFailure { actions.showMessage(c.library.editError(it, access.owner, couldNot("Couldn't remove the song", it, !c.network.isOnline.value))) }
                }
            }
        } else null,
        cover = playlist?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.PLAYLIST, id, playlist?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_PLAYLIST, id),
        emptyState = if (loadedSongs?.isEmpty() == true) {
            { EmptyState(HearthTheme.icons.queue, "This playlist is empty. Add songs from any song's menu.") }
        } else null,
        topContent = {
            item?.takeIf { it.access.invite != null }?.let { pending -> item(key = "invite") { InviteBanner(pending) } }
        },
        headerActions = {
            val current = item
            // Always there, in the same spot as on album and show pages; what's inside depends on your access.
            if (current != null) PlaylistMenu(current, screenScope, songs.map { it.id })
        },
    )
}
