// The album page, for albums on the server and ones that aren't ("tidal:" ids).
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.TidalAlbumPage
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.container
import im.flume.hearth.data.Availability
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.SongItem
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LoadingPage
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalGetMusic
import im.flume.hearth.ui.components.LocalRequests
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.REMOTE_PREFIX
import im.flume.hearth.ui.components.UnavailablePage
import im.flume.hearth.ui.components.albumKey
import im.flume.hearth.ui.components.albumKind
import im.flume.hearth.ui.components.comingLabel
import im.flume.hearth.ui.components.formatLongDuration
import im.flume.hearth.ui.components.isComing
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.components.remoteArtistId
import im.flume.hearth.ui.components.trackKey
import im.flume.hearth.ui.components.willDownload
import im.flume.hearth.ui.components.willLike
import im.flume.hearth.ui.theme.HearthTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AlbumScreen(id: String) {
    if (id.startsWith(REMOTE_PREFIX)) return RemoteAlbumScreen(id.removePrefix(REMOTE_PREFIX))
    val c = LocalContext.current.container
    val dao = c.db.library()
    val album by remember(id) { dao.album(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.albumSongs(id) }.collectAsStateWithLifecycle(emptyList())
    val a = album
    val actions = LocalActions.current
    val get = LocalGetMusic.current

    // The same album remotely, for the songs the server is missing; never holds up the page.
    val lookups = lookupsOn()
    var remote by remember(id) { mutableStateOf<TidalAlbumPage?>(null) }
    LaunchedEffect(id, a?.name, a?.artist, lookups) {
        if (!lookups || a == null) return@LaunchedEffect
        remote = try {
            withContext(Dispatchers.IO) { c.requests.albumByName(a.name, a.artist) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val tracks = remember(songs, remote) { remote?.takeIf { songs.isNotEmpty() }?.let { Availability.albumTracks(songs, it.tracks) } }
    val missing = remember(tracks) { tracks.orEmpty().mapNotNull { (it as? SongItem.Missing)?.track } }
    val requests = LocalRequests.current
    // Missing songs not already on their way: saving or downloading the album gets them onto the server.
    val waiting = missing.filterNot { isComing(requests[trackKey(it.id)]) }
    val albumName = a?.name.orEmpty()
    val getMissing = { message: String ->
        if (waiting.isEmpty()) actions.showMessage("The missing songs are already on their way")
        else get.getTracks(waiting, message)
    }
    var albumMenu by remember(id) { mutableStateOf(false) }
    if (albumMenu) {
        val ids = songs.map { it.id }
        ActionSheet(
            true, onDismiss = { albumMenu = false }, title = albumName,
            subtitle = if (missing.isNotEmpty()) "${plural(missing.size, "song")} not on your server yet" else a?.artist ?: "Album",
            coverArt = a?.coverArt ?: songs.firstOrNull()?.coverArt, fallback = albumName,
        ) {
            if (ids.isNotEmpty()) {
                MenuItem("Play next", HearthTheme.icons.playNext) { albumMenu = false; actions.playNext(ids) }
                MenuItem("Add to queue", HearthTheme.icons.queue) { albumMenu = false; actions.addToQueue(ids) }
                MenuItem("Add to playlist", HearthTheme.icons.addToPlaylist) { albumMenu = false; actions.addToPlaylist(ids) }
            }
            a?.artistId?.let { artistId ->
                MenuItem("Go to artist", HearthTheme.icons.artist) { albumMenu = false; actions.openArtist(artistId) }
            }
            if (missing.isNotEmpty()) {
                MenuItem("Add missing songs to server", HearthTheme.icons.addToServer) {
                    albumMenu = false
                    getMissing("Getting ${plural(waiting.size, "missing song")} of \"$albumName\" onto your server")
                }
            }
        }
    }

    CollectionScreen(
        title = a?.name ?: songs.firstOrNull()?.album.orEmpty(),
        subtitle = listOfNotNull(a?.artist, a?.year?.toString()).joinToString(" • "),
        cover = a?.coverArt ?: songs.firstOrNull()?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.ALBUM, id, a?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_ALBUM, id),
        showTrackNumbers = true,
        saved = a?.starred,
        onSavedChange = { save ->
            actions.setAlbumSaved(id, save)
            // Saving an album means wanting all of it: its missing songs go onto the server (not liked one by one).
            if (save && waiting.isNotEmpty()) get.getTracks(waiting, "Getting ${plural(waiting.size, "missing song")} of \"$albumName\" onto your server")
        },
        // Downloading it means the whole album on the phone: the missing songs join it as they arrive.
        onDownloaded = {
            if (waiting.isNotEmpty()) get.getTracks(waiting, "Getting ${plural(waiting.size, "missing song")} of \"$albumName\" onto your server. They'll download with the album.")
        },
        headerActions = {
            IconButton(onClick = { albumMenu = true }) { Icon(HearthTheme.icons.more, "Album options") }
        },
        tracks = tracks?.takeIf { missing.isNotEmpty() },
        topContent = {
            if (missing.isNotEmpty()) item(key = "get-rest") { MissingNote(missing, Modifier.animateItem()) }
        },
        extraContent = {
            item { AlbumDetails(a, id, songs.size, songs.sumOf { it.durationSec.toLong() }) }
            a?.artistId?.let { artistId ->
                item {
                    TextButton(onClick = { actions.openArtist(artistId) }, modifier = Modifier.padding(horizontal = HearthTheme.spacing.s)) {
                        Text("More by ${a.artist}")
                    }
                }
            }
        },
    )
}

/** "3 songs not on your server yet", under an album's buttons; saving or downloading the album gets them. */
@Composable
private fun MissingNote(missing: List<TidalTrack>, modifier: Modifier = Modifier) {
    val requests = LocalRequests.current
    val waiting = missing.filterNot { isComing(requests[trackKey(it.id)]) }
    Row(
        modifier.fillMaxWidth().padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.s, top = HearthTheme.spacing.s, bottom = HearthTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HearthTheme.icons.notOnServer, null, tint = HearthTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(HearthTheme.spacing.s))
        Text(
            if (waiting.isEmpty()) "${plural(missing.size, "song")} coming to your server…"
            else "${plural(waiting.size, "song")} not on your server yet",
            color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
    }
}

private data class RemoteAlbum(val page: TidalAlbumPage, val localAlbumId: String?, val owned: Map<String, SongEntity>)

@Composable
private fun RemoteAlbumScreen(id: String) {
    val c = LocalContext.current.container
    val state = rememberLoad("album:$id") {
        val page = c.requests.albumPage(id)
        RemoteAlbum(page, c.requests.ownedAlbums(listOf(page.album))[page.album.id]?.id, c.requests.ownedTracks(page.tracks))
    }
    when (state) {
        Load.Loading -> LoadingPage()
        Load.Failed -> UnavailablePage(HearthTheme.icons.offline, "Couldn't load this album. Check your connection and try again.")
        is Load.Ready -> {
            val local = state.value.localAlbumId
            // The server has it after all (or it just arrived): that's the page to show.
            if (local != null) AlbumScreen(local) else RemoteAlbumContent(state.value)
        }
    }
}

@Composable
private fun RemoteAlbumContent(view: RemoteAlbum) {
    val actions = LocalActions.current
    val get = LocalGetMusic.current
    val album = view.page.album
    val request = LocalRequests.current[albumKey(album.id)]
    val tracks = remember(view) { ownedItems(view.page.tracks, view.owned) }
    val songs = remember(tracks) { tracks.mapNotNull { (it as? SongItem.Owned)?.song } }
    val source = PlaySource(PlaySource.Kind.SONGS, label = album.title, songIds = songs.map { it.id })
    val totalSec = album.durationSec?.toLong() ?: view.page.tracks.sumOf { (it.durationSec ?: 0).toLong() }
    val count = view.page.tracks.size.takeIf { it > 0 } ?: album.numberOfTracks ?: 0
    val firstMissing = tracks.firstNotNullOfOrNull { (it as? SongItem.Missing)?.track }
    CollectionScreen(
        title = album.title,
        subtitle = "",
        cover = null,
        songs = songs,
        source = source,
        showTrackNumbers = true,
        saved = willLike(request),
        onSavedChange = { get.heartAlbum(album, request) },
        headerActions = {
            IconButton(onClick = { get.albumMenuFor = album }) { Icon(HearthTheme.icons.more, "More", tint = HearthTheme.colors.textSecondary) }
        },
        tracks = tracks,
        remote = RemoteHeader(
            coverUuid = album.cover,
            info = listOfNotNull(
                albumKind(album), album.artist.ifBlank { null }, album.year, plural(count, "song"),
                formatLongDuration(totalSec).takeIf { totalSec > 0 },
            ).joinToString(" • "),
            status = comingLabel(request) ?: "Not on your server yet",
            request = request,
            onPlay = { if (firstMissing != null) get.preview(firstMissing) else actions.play(source) },
            toPhone = willDownload(request),
            onDownload = { get.downloadAlbum(album, request) },
        ),
        extraContent = {
            album.artistId?.let { artistId ->
                item {
                    TextButton(onClick = { actions.openArtist(remoteArtistId(artistId)) }, modifier = Modifier.padding(horizontal = HearthTheme.spacing.s, vertical = HearthTheme.spacing.m)) {
                        Text("More by ${album.artist}")
                    }
                }
            }
        },
    )
}
