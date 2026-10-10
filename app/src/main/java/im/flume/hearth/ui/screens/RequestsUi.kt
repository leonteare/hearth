package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import im.flume.hearth.AppContainer
import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalArtist
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.api.TidalType
import im.flume.hearth.api.tidalImageUrl
import im.flume.hearth.container
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.data.RequestStatus
import im.flume.hearth.requests.RequestsRepository
import im.flume.hearth.ui.components.Actions
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.EmptyState
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MediaRow
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.TopBar
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary
import im.flume.hearth.util.userMessage
import kotlinx.coroutines.launch

/** Something on Tidal that could be requested. */
sealed interface TidalItem {
    val type: TidalType
    val id: String
    val title: String
    val subtitle: String
    val cover: String?
    val key: String get() = "${type.api}:$id"

    data class Track(val t: TidalTrack) : TidalItem {
        override val type = TidalType.TRACK
        override val id = t.id
        override val title = t.displayTitle
        override val subtitle = listOfNotNull("Song", t.artist.ifBlank { null }).joinToString(" · ")
        override val cover = t.cover
    }

    data class Album(val a: TidalAlbum) : TidalItem {
        override val type = TidalType.ALBUM
        override val id = a.id
        override val title = a.title
        override val subtitle = listOfNotNull(if (a.type == "EP") "EP" else if (a.type == "SINGLE") "Single" else "Album", a.artist.ifBlank { null }, a.year).joinToString(" · ")
        override val cover = a.cover
    }

    data class Artist(val a: TidalArtist) : TidalItem {
        override val type = TidalType.ARTIST
        override val id = a.id
        override val title = a.name
        override val subtitle = "Artist"
        override val cover = a.picture
    }
}

/** What the "On Tidal" part of Search is showing. */
sealed interface TidalSearchState {
    data object Hidden : TidalSearchState
    data object Loading : TidalSearchState
    data object Unavailable : TidalSearchState
    data class Results(val results: TidalSearchResults) : TidalSearchState
}

private fun RequestEntity.key() = "$type:$tidalId"

fun tidalAlbumRoute(id: String) = "tidal/album/${android.net.Uri.encode(id)}"
fun tidalArtistRoute(id: String) = "tidal/artist/${android.net.Uri.encode(id)}"

/** Request sheets (details, artist confirmation) and the actions behind the Request buttons. */
@Stable
class TidalRequester(private val c: AppContainer, private val actions: Actions) {
    var details by mutableStateOf<TidalItem?>(null)
    var confirmArtist by mutableStateOf<TidalArtist?>(null)

    /** Request button: artists ask first, since that's a whole discography. */
    fun onRequest(item: TidalItem) {
        when (item) {
            is TidalItem.Artist -> confirmArtist = item.a
            is TidalItem.Track -> send(item.title, c.requests.track(item.t))
            is TidalItem.Album -> send(item.title, c.requests.album(item.a))
        }
    }

    /** Songs open the details sheet; albums and artists open their Tidal page to look around first. */
    fun open(item: TidalItem) {
        when (item) {
            is TidalItem.Track -> details = item
            is TidalItem.Album -> openAlbum(item.id)
            is TidalItem.Artist -> openArtist(item.id)
        }
    }

    fun openAlbum(id: String) = actions.open(tidalAlbumRoute(id))
    fun openArtist(id: String) = actions.open(tidalArtistRoute(id))

    fun requestArtist(a: TidalArtist) = send(a.name, c.requests.artist(a), artist = true)

    private fun send(title: String, draft: RequestEntity, artist: Boolean = false) {
        c.appScope.launch {
            runCatching { c.requests.request(draft) }
                .onSuccess { outcome ->
                    actions.showMessage(
                        when (outcome) {
                            RequestsRepository.Outcome.ALREADY_REQUESTED -> "Already requested"
                            RequestsRepository.Outcome.ALREADY_ON_SERVER -> "Already on the server: liked and downloading to your phone"
                            RequestsRepository.Outcome.REQUESTED ->
                                if (artist) "Requested everything by $title" else "Requested \"$title\". It'll land on your phone when it's ready."
                        }
                    )
                }
                .onFailure { actions.showMessage("Couldn't request it: ${userMessage(it, "Tidarr didn't answer")}") }
        }
    }

    fun retry(r: RequestEntity) {
        c.appScope.launch {
            runCatching { c.requests.retry(r) }
                .onSuccess { actions.showMessage("Trying \"${r.title}\" again") }
                .onFailure { actions.showMessage("Couldn't retry: ${userMessage(it, "Tidarr didn't answer")}") }
        }
    }
}

@Composable
fun rememberTidalRequester(): TidalRequester {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    return remember(c, actions) { TidalRequester(c, actions) }
}

/** Rows for the "On Tidal" section of Search, below the library results. */
fun LazyListScope.tidalResults(state: TidalSearchState, requests: Map<String, RequestEntity>, requester: TidalRequester) {
    when (state) {
        TidalSearchState.Hidden -> return
        TidalSearchState.Loading -> {
            item(key = "tidal-head") { SectionHeader("On Tidal") }
            item(key = "tidal-loading") {
                Row(Modifier.padding(horizontal = Dimens.Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Searching Tidal…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        TidalSearchState.Unavailable -> item(key = "tidal-off") {
            Text(
                "Tidal search unavailable", color = TextSecondary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 16.dp),
            )
        }
        is TidalSearchState.Results -> {
            val r = state.results
            val all: List<TidalItem> = r.tracks.map { TidalItem.Track(it) } + r.albums.map { TidalItem.Album(it) } + r.artists.map { TidalItem.Artist(it) }
            if (all.isEmpty()) return
            item(key = "tidal-head") { SectionHeader("On Tidal") }
            item(key = "tidal-note") {
                Text(
                    "Not on your server yet. Request it and it'll be added, then downloaded to this phone.",
                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, bottom = 8.dp),
                )
            }
            items(all, key = { "tidal:${it.key}" }) { item ->
                TidalRow(item, requests[item.key], requester)
            }
        }
    }
}

@Composable
private fun TidalRow(item: TidalItem, request: RequestEntity?, requester: TidalRequester) {
    MediaRow(
        title = item.title,
        subtitle = item.subtitle,
        onClick = { requester.open(item) },
        cover = { TidalCover(item.cover, item.title) },
        round = item is TidalItem.Artist,
        trailing = { RequestControl(request, onRequest = { requester.onRequest(item) }, onRetry = requester::retry) },
    )
}

/** Album cover or artist picture from Tidal's public image server. */
@Composable
fun TidalCover(uuid: String?, title: String, size: Int = 320) {
    val url = tidalImageUrl(uuid, size)
    val placeholder = @Composable {
        Box(Modifier.fillMaxSize().background(SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = TextSecondary)
        }
    }
    if (url == null) placeholder() else SubcomposeAsyncImage(
        model = url, contentDescription = title, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(), error = { placeholder() }, loading = { placeholder() },
    )
}

/** "Request" button, or where the request is: Requested / Downloading / Adding / On your phone / Failed – Retry. */
@Composable
internal fun RequestControl(request: RequestEntity?, onRequest: () -> Unit, onRetry: (RequestEntity) -> Unit) {
    if (request == null) {
        TextButton(onClick = onRequest) { Text("Request", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        return
    }
    if (request.status == RequestStatus.FAILED) {
        TextButton(onClick = { onRetry(request) }) { Text("Failed – Retry", color = Destructive) }
        return
    }
    StatusChip(request, Modifier.padding(end = 12.dp))
}

fun statusLabel(r: RequestEntity): String = when (r.status) {
    RequestStatus.REQUESTED -> "Requested"
    RequestStatus.DOWNLOADING -> "Downloading"
    RequestStatus.ADDING -> "Adding"
    RequestStatus.DONE -> if (r.type == TidalType.ARTIST.api || r.matchedIds.isNullOrEmpty()) "On the server" else "On your phone"
    RequestStatus.FAILED -> "Failed"
}

@Composable
internal fun StatusChip(r: RequestEntity, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val (bg, fg) = when (r.status) {
        RequestStatus.DONE -> accent.copy(alpha = 0.2f) to accent
        RequestStatus.FAILED -> Destructive.copy(alpha = 0.15f) to Destructive
        else -> SurfaceHigh to Color.White
    }
    Row(
        modifier.clip(HearthShapes.Chip).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.status.active) {
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(statusLabel(r), color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

/** The details sheet (tapping a Tidal row) and the "whole discography?" confirmation. */
@Composable
fun TidalSheets(requester: TidalRequester, requests: Map<String, RequestEntity>) {
    requester.details?.let { item ->
        val request = requests[item.key]
        val lines = when (item) {
            is TidalItem.Track -> listOfNotNull(
                item.t.albumTitle?.let { "From $it" },
                item.t.durationSec?.takeIf { it > 0 }?.let { formatDuration(it.toLong()) },
                "Explicit".takeIf { item.t.explicit },
            )
            is TidalItem.Album -> listOfNotNull(
                item.a.numberOfTracks?.let { "$it tracks" },
                item.a.releaseDate?.let { "Released $it" },
            )
            is TidalItem.Artist -> listOf("Requesting an artist adds their whole discography to the server.")
        }
        ActionSheet(true, onDismiss = { requester.details = null }, title = item.title, subtitle = item.subtitle, showCover = false) {
            Box(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp).size(160.dp)
                    .clip(if (item is TidalItem.Artist) HearthShapes.Round else HearthShapes.Card)
            ) { TidalCover(item.cover, item.title) }
            lines.forEach {
                Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            Spacer(Modifier.size(8.dp))
            when {
                request == null -> MenuItem("Request", Icons.Outlined.CloudDownload) { requester.details = null; requester.onRequest(item) }
                request.status == RequestStatus.FAILED -> MenuItem("Retry request", Icons.Default.Refresh) { requester.details = null; requester.retry(request) }
                else -> MenuItem(statusLabel(request), Icons.Default.CloudDownload) { requester.details = null }
            }
            if (item is TidalItem.Track) {
                item.t.albumId?.let { id -> MenuItem("Go to album", Icons.Default.Album) { requester.details = null; requester.openAlbum(id) } }
                item.t.artistId?.let { id -> MenuItem("Go to artist", Icons.Default.Person) { requester.details = null; requester.openArtist(id) } }
            }
        }
    }
    requester.confirmArtist?.let { a ->
        ActionSheet(
            true, onDismiss = { requester.confirmArtist = null },
            title = "Download everything by ${a.name}?", subtitle = "This adds their whole discography to the server.", showCover = false,
        ) {
            MenuItem("Download discography", Icons.Default.CloudDownload) { requester.confirmArtist = null; requester.requestArtist(a) }
            MenuItem("Cancel", Icons.Default.Close) { requester.confirmArtist = null }
        }
    }
}

/** Search page row (when the query is empty) leading to the Requests page; only shown when there are any. */
fun LazyListScope.requestsEntry(requests: List<RequestEntity>, onOpen: () -> Unit) {
    if (requests.isEmpty()) return
    item(key = "requests-entry") {
        val inProgress = requests.count { it.status.active }
        val failed = requests.count { it.status == RequestStatus.FAILED }
        MediaRow(
            title = "Your requests",
            subtitle = when {
                inProgress > 0 -> "$inProgress in progress"
                failed > 0 -> "$failed couldn't download"
                else -> "${requests.size} added"
            },
            onClick = onOpen,
            cover = {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CloudDownload, null, tint = Color.White)
                }
            },
        )
    }
}

/** Everything this user requested, newest first. */
@Composable
fun RequestsScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val requester = rememberTidalRequester()
    val requests by remember { c.requests.requests }.collectAsStateWithLifecycle(emptyList())
    var menuFor by remember { mutableStateOf<RequestEntity?>(null) }
    // Look at Tidarr straight away rather than waiting for the next round.
    LaunchedEffect(Unit) { runCatching { c.requests.poll() } }

    /** Its Tidal page, for albums and artists that aren't on the server yet. */
    fun tidalRoute(r: RequestEntity): String? = when (r.type) {
        TidalType.ALBUM.api -> tidalAlbumRoute(r.tidalId)
        TidalType.ARTIST.api -> tidalArtistRoute(r.tidalId)
        else -> null
    }

    fun open(r: RequestEntity) {
        val first = r.matchedIds?.split(',')?.firstOrNull { it.isNotBlank() } ?: return
        when (r.type) {
            TidalType.ALBUM.api -> actions.openAlbum(first)
            TidalType.ARTIST.api -> actions.openArtist(first)
            else -> c.appScope.launch {
                c.db.library().song(first)?.albumId?.let { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { actions.openAlbum(id) } }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Requests") {
            if (requests.any { it.status == RequestStatus.DONE }) {
                TextButton(onClick = { c.appScope.launch { c.requests.clearFinished() } }) { Text("Clear finished", color = TextSecondary) }
            }
        }
        if (requests.isEmpty()) {
            EmptyState(Icons.Outlined.CloudDownload, "Nothing requested yet. Search for music that isn't on your server and tap Request.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(requests, key = { it.key() }) { r ->
                val kind = when (r.type) { TidalType.ALBUM.api -> "Album"; TidalType.ARTIST.api -> "Artist"; else -> "Song" }
                val detail = r.errorMessage ?: listOfNotNull(kind, r.artist.takeIf { r.type != TidalType.ARTIST.api }).joinToString(" · ")
                MediaRow(
                    title = r.title,
                    subtitle = detail,
                    subtitleColor = if (r.status == RequestStatus.FAILED) Destructive else TextSecondary,
                    onClick = {
                        val tidal = tidalRoute(r)
                        when {
                            r.status == RequestStatus.DONE && !r.matchedIds.isNullOrEmpty() -> open(r)
                            tidal != null -> actions.open(tidal)
                            else -> menuFor = r
                        }
                    },
                    onLongClick = { menuFor = r },
                    round = r.type == TidalType.ARTIST.api,
                    cover = { TidalCover(r.coverUuid, r.title) },
                    modifier = Modifier.animateItem(),
                    trailing = {
                        if (r.status == RequestStatus.FAILED) {
                            TextButton(onClick = { requester.retry(r) }) { Text("Retry", color = MaterialTheme.colorScheme.primary) }
                        } else {
                            StatusChip(r)
                        }
                        IconButton(onClick = { menuFor = r }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
                    },
                )
            }
        }
    }

    menuFor?.let { r ->
        ActionSheet(true, onDismiss = { menuFor = null }, title = r.title, subtitle = statusLabel(r), showCover = false) {
            if (r.status == RequestStatus.DONE && !r.matchedIds.isNullOrEmpty()) {
                MenuItem(if (r.type == TidalType.ARTIST.api) "Go to artist" else "Go to album", Icons.AutoMirrored.Filled.OpenInNew) { menuFor = null; open(r) }
            }
            tidalRoute(r)?.let { route ->
                MenuItem("Open on Tidal", Icons.AutoMirrored.Filled.OpenInNew) { menuFor = null; actions.open(route) }
            }
            // "Adding" can also be retried: Tidarr may have finished without downloading anything.
            if (r.status == RequestStatus.FAILED || r.status == RequestStatus.ADDING) {
                MenuItem(if (r.status == RequestStatus.FAILED) "Retry" else "Try again", Icons.Default.Refresh) { menuFor = null; requester.retry(r) }
            }
            val label = if (r.status.active || r.status == RequestStatus.FAILED) "Cancel request" else "Remove from this list"
            MenuItem(label, Icons.Default.Close, tint = Destructive, tintText = true) {
                menuFor = null
                c.appScope.launch {
                    runCatching { c.requests.cancel(r) }
                        .onFailure { actions.showMessage("Couldn't cancel: ${userMessage(it, "Tidarr didn't answer")}") }
                }
            }
        }
    }
}
