package im.flume.hearth.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.api.TidalType
import im.flume.hearth.container
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.data.RequestStatus
import im.flume.hearth.requests.TidalAlbumView
import im.flume.hearth.requests.TidalArtistView
import im.flume.hearth.ui.components.BackButton
import im.flume.hearth.ui.components.CoverColorFallback
import im.flume.hearth.ui.components.EmptyState
import im.flume.hearth.ui.components.LoadingPage
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaCard
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.TopBar
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.components.formatLongDuration
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.TextSecondary
import im.flume.hearth.util.userMessage
import kotlinx.coroutines.CancellationException

/** A Tidal page being fetched through Tidarr. */
private sealed interface TidalLoad<out T> {
    data object Loading : TidalLoad<Nothing>
    data class Failed(val message: String) : TidalLoad<Nothing>
    data class Ready<T>(val value: T) : TidalLoad<T>
}

/** Loads [load] for [key]; the second value retries. Tidarr's answers are cached, so going back is instant. */
@Composable
private fun <T> rememberTidalLoad(key: String, load: suspend () -> T): Pair<TidalLoad<T>, () -> Unit> {
    var attempt by remember(key) { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<TidalLoad<T>>(TidalLoad.Loading) }
    LaunchedEffect(key, attempt) {
        state = TidalLoad.Loading
        state = try {
            TidalLoad.Ready(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TidalLoad.Failed(userMessage(e, "Couldn't reach Tidarr"))
        }
    }
    return state to { attempt++ }
}

@Composable
private fun TidalErrorPage(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(null)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Default.CloudOff, message, action = "Retry", onAction = onRetry)
        }
    }
}

/** This user's requests by "type:tidalId". */
@Composable
private fun rememberRequestsByKey(): Map<String, RequestEntity> {
    val c = LocalContext.current.container
    val list by remember { c.requests.requests }.collectAsStateWithLifecycle(emptyList())
    return remember(list) { list.associateBy { "${it.type}:${it.tidalId}" } }
}

private fun trackKey(id: String) = "${TidalType.TRACK.api}:$id"
private fun albumKey(id: String) = "${TidalType.ALBUM.api}:$id"

private fun albumKind(a: TidalAlbum) = when (a.type) { "EP" -> "EP"; "SINGLE" -> "Single"; else -> "Album" }

/** Small muted chip for things the server already has. */
@Composable
private fun OnServerChip(modifier: Modifier = Modifier) {
    Text(
        "On server",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(end = 12.dp).clip(HearthShapes.Chip)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** What sits at the end of a Tidal track row: its request, "On server", or a Request button. */
@Composable
private fun TrackTrailing(onServer: Boolean, request: RequestEntity?, requester: TidalRequester, track: TidalTrack) {
    if (onServer && (request == null || request.status != RequestStatus.DONE)) OnServerChip()
    else RequestControl(request, onRequest = { requester.onRequest(TidalItem.Track(track)) }, onRetry = requester::retry)
}

/** Header gradient for Tidal pages: neutral, since cover colours come from the server's artwork. */
private val headerBrush = Brush.verticalGradient(listOf(CoverColorFallback, Color.Transparent))

// --- artist ---

@Composable
fun TidalArtistScreen(id: String) {
    val c = LocalContext.current.container
    val requester = rememberTidalRequester()
    val requests = rememberRequestsByKey()
    val (state, retry) = rememberTidalLoad("artist:$id") { c.requests.tidalArtist(id) }
    when (state) {
        TidalLoad.Loading -> LoadingPage()
        is TidalLoad.Failed -> TidalErrorPage(state.message, retry)
        is TidalLoad.Ready -> TidalArtistContent(state.value, requests, requester)
    }
    TidalSheets(requester, requests)
}

@Composable
private fun TidalArtistContent(view: TidalArtistView, requests: Map<String, RequestEntity>, requester: TidalRequester) {
    val actions = LocalActions.current
    val page = view.page
    val artist = page.artist
    val wide = isWideWindow()
    val artistRequest = requests["${TidalType.ARTIST.api}:${artist.id}"]
    val counts = listOfNotNull(
        page.albums.size.takeIf { it > 0 }?.let { plural(it, "album") },
        page.singles.size.takeIf { it > 0 }?.let { plural(it, "single or EP", "singles and EPs") },
    ).joinToString(" • ").ifEmpty { "On Tidal" }
    val picture: @Composable (Dp) -> Unit = { size ->
        Box(Modifier.size(size).clip(HearthShapes.Round)) { TidalCover(artist.picture, artist.name, 750) }
    }
    val buttons: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (wide) Arrangement.Start else Arrangement.Center,
        ) {
            if (artistRequest == null) {
                OutlinedButton(
                    onClick = { requester.confirmArtist = artist },
                    border = BorderStroke(1.dp, TextSecondary),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                ) { Text("Request everything", color = Color.White, style = MaterialTheme.typography.labelLarge) }
            } else {
                RequestControl(artistRequest, onRequest = {}, onRetry = requester::retry)
            }
            view.localArtistId?.let { localId ->
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { actions.openArtist(localId) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("On your server")
                }
            }
        }
    }
    // Popular songs the server has play from there, in this order.
    val localTopIds = page.topTracks.mapNotNull { view.localTracks[it.id] }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth().background(headerBrush)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { BackButton() }
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                            picture(160.dp)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(artist.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = Dimens.Gutter))
                                Text(counts, color = TextSecondary, modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp))
                                buttons()
                            }
                        }
                    } else {
                        picture(180.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(artist.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Dimens.Gutter))
                        Text(counts, color = TextSecondary, modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
        if (!wide) item(key = "buttons") { buttons() }
        if (page.topTracks.isNotEmpty()) {
            item(key = "popular-head") { SectionHeader("Popular") }
            itemsIndexed(page.topTracks, key = { _, t -> "top:${t.id}" }) { i, t ->
                val localId = view.localTracks[t.id]
                MediaRow(
                    title = t.displayTitle,
                    subtitle = listOfNotNull(t.artist.ifBlank { null }, t.albumTitle).joinToString(" · "),
                    onClick = {
                        if (localId != null) actions.play(PlaySource(PlaySource.Kind.SONGS, label = artist.name, songIds = localTopIds), startSongId = localId)
                        else requester.details = TidalItem.Track(t)
                    },
                    leading = { Text("${i + 1}", Modifier.width(28.dp), color = TextSecondary, style = MaterialTheme.typography.bodyMedium) },
                    cover = { TidalCover(t.cover, t.displayTitle) },
                    trailing = { TrackTrailing(localId != null, requests[trackKey(t.id)], requester, t) },
                )
            }
        }
        albumCarousel("albums", "Albums", page.albums, view.localAlbums, requests, requester)
        albumCarousel("singles", "Singles & EPs", page.singles, view.localAlbums, requests, requester)
        if (page.topTracks.isEmpty() && page.albums.isEmpty() && page.singles.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Default.MusicNote, "Tidal has nothing by ${artist.name} to show here.") }
        }
    }
}

private fun LazyListScope.albumCarousel(
    key: String,
    title: String,
    albums: List<TidalAlbum>,
    local: Map<String, String>,
    requests: Map<String, RequestEntity>,
    requester: TidalRequester,
) {
    if (albums.isEmpty()) return
    item(key = key) {
        SectionHeader(title)
        LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.CarouselSpacing)) {
            items(albums, key = { it.id }) { a ->
                val status = requests[albumKey(a.id)]?.let(::statusLabel) ?: "On your server".takeIf { a.id in local }
                MediaCard(
                    a.title,
                    listOfNotNull(a.year, status).joinToString(" · ").ifEmpty { albumKind(a) },
                    onClick = { requester.openAlbum(a.id) },
                    cover = { Box(Modifier.size(Dimens.CardWidth).clip(HearthShapes.Cover)) { TidalCover(a.cover, a.title) } },
                )
            }
        }
    }
}

// --- album ---

@Composable
fun TidalAlbumScreen(id: String) {
    val c = LocalContext.current.container
    val requester = rememberTidalRequester()
    val requests = rememberRequestsByKey()
    val (state, retry) = rememberTidalLoad("album:$id") { c.requests.tidalAlbum(id) }
    when (state) {
        TidalLoad.Loading -> LoadingPage()
        is TidalLoad.Failed -> TidalErrorPage(state.message, retry)
        is TidalLoad.Ready -> TidalAlbumContent(state.value, requests, requester)
    }
    TidalSheets(requester, requests)
}

@Composable
private fun TidalAlbumContent(view: TidalAlbumView, requests: Map<String, RequestEntity>, requester: TidalRequester) {
    val actions = LocalActions.current
    val album = view.page.album
    val tracks = view.page.tracks
    val wide = isWideWindow()
    val accent = MaterialTheme.colorScheme.primary
    val request = requests[albumKey(album.id)]
    val totalSec = album.durationSec?.toLong() ?: tracks.sumOf { (it.durationSec ?: 0).toLong() }
    val songCount = tracks.size.takeIf { it > 0 } ?: album.numberOfTracks ?: 0
    val info = listOfNotNull(albumKind(album), album.year, plural(songCount, "song"), formatLongDuration(totalSec).takeIf { totalSec > 0 })
        .joinToString(" • ")
    val localSongIds = tracks.mapNotNull { view.localTracks[it.id] }
    val multiDisc = tracks.any { (it.volumeNumber ?: 1) != (tracks.first().volumeNumber ?: 1) }

    val art: @Composable (Dp) -> Unit = { size ->
        Box(Modifier.size(size).clip(HearthShapes.Cover)) { TidalCover(album.cover, album.title, 640) }
    }
    val titleBlock: @Composable (TextAlign, Alignment.Horizontal) -> Unit = { align, horizontal ->
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), horizontalAlignment = horizontal) {
            Text(album.title, style = MaterialTheme.typography.headlineMedium, textAlign = align)
            if (album.artist.isNotBlank()) {
                Text(
                    album.artist,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = align,
                    modifier = Modifier.padding(top = 4.dp).clip(HearthShapes.Chip)
                        .clickable(enabled = album.artistId != null) { album.artistId?.let(requester::openArtist) },
                )
            }
            Text(info, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = align, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
    val primary: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (wide) Arrangement.Start else Arrangement.Center,
        ) {
            val localAlbum = view.localAlbumId
            when {
                localAlbum != null && (request == null || request.status == RequestStatus.DONE) -> {
                    Text("On your server", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { actions.openAlbum(localAlbum) }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Open") }
                }
                request != null -> RequestControl(request, onRequest = {}, onRetry = requester::retry)
                else -> Button(
                    onClick = { requester.onRequest(TidalItem.Album(album)) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) { Text("Request album") }
            }
        }
        if (view.localAlbumId == null && localSongIds.isNotEmpty()) {
            Text(
                "${plural(localSongIds.size, "song")} of ${tracks.size} already on your server",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter),
            )
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth().background(headerBrush)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { BackButton() }
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                            art(160.dp)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                titleBlock(TextAlign.Start, Alignment.Start)
                                primary()
                            }
                        }
                    } else {
                        art(220.dp)
                        Spacer(Modifier.height(16.dp))
                        titleBlock(TextAlign.Center, Alignment.CenterHorizontally)
                    }
                }
            }
        }
        if (!wide) item(key = "primary") { primary() }
        if (tracks.isEmpty()) item(key = "empty") { EmptyState(Icons.Default.MusicNote, "Tidal didn't list any songs for this album.") }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
            Column {
                val disc = t.volumeNumber ?: 1
                if (multiDisc && (index == 0 || (tracks[index - 1].volumeNumber ?: 1) != disc)) {
                    Text(
                        "Disc $disc",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = Dimens.Gutter, top = if (index == 0) 4.dp else 16.dp, bottom = 4.dp),
                    )
                }
                val localId = view.localTracks[t.id]
                TidalTrackRow(
                    t,
                    number = t.trackNumber?.toString() ?: "${index + 1}",
                    onClick = {
                        if (localId != null) actions.play(PlaySource(PlaySource.Kind.SONGS, label = album.title, songIds = localSongIds), startSongId = localId)
                        else requester.details = TidalItem.Track(t)
                    },
                    trailing = { TrackTrailing(localId != null, requests[trackKey(t.id)], requester, t) },
                )
            }
        }
    }
}

/** An album track: number, title and artist (no cover; the header has it), like the server's album page. */
@Composable
private fun TidalTrackRow(t: TidalTrack, number: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = Dimens.Gutter, end = 4.dp, top = Dimens.RowPaddingV, bottom = Dimens.RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number, Modifier.width(28.dp), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.explicit) {
                    Text(
                        "E", color = Color.Black, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp).clip(HearthShapes.Cover).background(TextSecondary).padding(horizontal = 4.dp),
                    )
                }
                Text(
                    listOfNotNull(t.artist.ifBlank { null }, t.durationSec?.takeIf { it > 0 }?.let { formatDuration(it.toLong()) }).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        trailing()
    }
}
