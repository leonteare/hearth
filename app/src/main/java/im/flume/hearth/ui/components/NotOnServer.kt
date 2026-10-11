package im.flume.hearth.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import im.flume.hearth.AppContainer
import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalArtist
import im.flume.hearth.api.TidalArtistPage
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.api.TidalType
import im.flume.hearth.api.tidalImageUrl
import im.flume.hearth.container
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.data.RequestStatus
import im.flume.hearth.playback.PreviewPlayer
import im.flume.hearth.requests.RequestsRepository
import im.flume.hearth.ui.theme.Destructive
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.HearthShapes
import im.flume.hearth.ui.theme.NotOnServerAlpha
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/*
 * Music that isn't on the server yet is a state, not a place: it shows in the same rows, cards and
 * pages as everything else, dimmed, with a small cloud. Tapping a song previews 30 seconds; the
 * heart gets it (onto the server, then liked and downloaded to this phone).
 */

/** Routes for pages of music that isn't on the server: the same album and artist pages. */
fun remoteAlbumId(id: String) = "$REMOTE_PREFIX$id"
fun remoteArtistId(id: String) = "$REMOTE_PREFIX$id"
const val REMOTE_PREFIX = "tidal:"

/** This user's requests by "type:id"; provided once at the root. */
val LocalRequests = compositionLocalOf<Map<String, RequestEntity>> { emptyMap() }

/** The preview playing or loading, provided once at the root. */
val LocalPreview = compositionLocalOf<PreviewPlayer.Preview?> { null }

val LocalGetMusic = compositionLocalOf<GetMusic> { error("GetMusic not provided") }

fun trackKey(id: String) = "${TidalType.TRACK.api}:$id"
fun albumKey(id: String) = "${TidalType.ALBUM.api}:$id"
fun artistKey(id: String) = "${TidalType.ARTIST.api}:$id"

/** What's happening to something that's on its way, or null when nothing is. */
fun comingLabel(r: RequestEntity?): String? = when (r?.status) {
    RequestStatus.REQUESTED -> "Coming to your server…"
    RequestStatus.DOWNLOADING -> "Downloading…"
    RequestStatus.ADDING -> "Adding…"
    RequestStatus.FAILED -> "Couldn't get it"
    else -> null
}

/** Why getting it failed, in the app's words (the stored message names the services behind it). */
fun failureText(r: RequestEntity): String? = when {
    r.status != RequestStatus.FAILED -> null
    r.errorMessage == im.flume.hearth.data.RequestTracking.NOTHING_ARRIVED -> "It finished, but nothing arrived on your server. Try again later."
    else -> "Couldn't download it. Try again later."
}

/** True while it's been asked for and hasn't arrived or failed: the heart shows filled. */
fun isComing(r: RequestEntity?) = r != null && r.status.active

private fun RequestEntity.asDraft() = copy(
    status = RequestStatus.REQUESTED, errorMessage = null, matchedIds = null, seenInQueue = false, finishedAt = null, lookupAttempts = 0,
)

/**
 * Getting music onto the server: the hearts, Follow on an artist the server doesn't have, the ⋯ menu
 * of a song that isn't there, and the sheets they open. One per app, provided at the root.
 */
@Stable
class GetMusic(private val c: AppContainer, private val actions: Actions) {
    /** The ⋯ menu of a song that isn't on the server. */
    var menuFor by mutableStateOf<TidalTrack?>(null)
    /** "Get their music?" for an artist just followed. */
    var artistSheet by mutableStateOf<Pair<TidalArtist, TidalArtistPage?>?>(null)
    /** The ⋯ menu of something on its way (Downloads page). */
    var requestMenu by mutableStateOf<RequestEntity?>(null)

    fun preview(t: TidalTrack) {
        if (!c.network.isOnline.value) { actions.showMessage("Preview unavailable"); return }
        c.preview.toggle(t)
    }

    /** ♡ on a song: get it; on one on its way, stop getting it (with Undo); a failed one tries again. */
    fun heartTrack(t: TidalTrack, r: RequestEntity?) = heart(r, t.displayTitle) { c.requests.track(t) }

    /** ♡ on an album: get the whole album; it arrives saved and downloaded. */
    fun heartAlbum(a: TidalAlbum, r: RequestEntity?) = heart(r, a.title) { c.requests.album(a) }

    private fun heart(r: RequestEntity?, title: String, draft: () -> RequestEntity) {
        when {
            r == null || r.status == RequestStatus.DONE -> send(title, draft())
            r.status == RequestStatus.FAILED -> retry(r)
            else -> cancel(r)
        }
    }

    /** Gets a few songs at once, e.g. the rest of an album; quietly skips ones already on their way. */
    fun getTracks(tracks: List<TidalTrack>, what: String) {
        if (tracks.isEmpty()) return
        c.appScope.launch {
            var failed = 0
            tracks.forEach { t -> runCatching { c.requests.request(c.requests.track(t)) }.onFailure { failed++ } }
            actions.showMessage(if (failed == tracks.size) "Couldn't get them right now (offline?)" else "Getting $what. They'll arrive in Liked Songs and on your phone.")
        }
    }

    private fun send(title: String, draft: RequestEntity) {
        c.appScope.launch {
            runCatching { c.requests.request(draft) }
                .onSuccess { outcome ->
                    actions.showMessage(
                        when (outcome) {
                            RequestsRepository.Outcome.ALREADY_REQUESTED -> "\"$title\" is already on its way"
                            RequestsRepository.Outcome.ALREADY_ON_SERVER -> "\"$title\" was already on your server: added to your library and phone"
                            RequestsRepository.Outcome.REQUESTED -> when (draft.type) {
                                TidalType.ARTIST.api -> "Getting everything by $title"
                                TidalType.ALBUM.api -> "Getting \"$title\". It'll be in your library and on your phone when it arrives."
                                else -> "Getting \"$title\". It'll be in Liked Songs and on your phone when it arrives."
                            }
                        }
                    )
                }
                .onFailure { actions.showMessage("Couldn't get \"$title\" right now (offline?)") }
        }
    }

    fun retry(r: RequestEntity) {
        c.appScope.launch {
            runCatching { c.requests.retry(r) }
                .onSuccess { actions.showMessage("Trying \"${r.title}\" again") }
                .onFailure { actions.showMessage("Couldn't try again right now (offline?)") }
        }
    }

    /** Stops getting [r], with Undo. */
    fun cancel(r: RequestEntity) {
        c.appScope.launch {
            runCatching { c.requests.cancel(r) }
                .onSuccess { actions.showUndo("Stopped getting \"${r.title}\"") { c.requests.request(r.asDraft()) } }
                .onFailure { actions.showMessage("Couldn't stop it right now (offline?)") }
        }
    }

    /** Follow on an artist the server doesn't have: remembered, and offers to get their music. */
    fun follow(artist: TidalArtist, page: TidalArtistPage?) {
        if (c.requests.isFollowIntent(artist.name)) {
            c.requests.setFollowIntent(artist.name, false)
            actions.showUndo("Unfollowed") { c.requests.setFollowIntent(artist.name, true) }
            return
        }
        c.requests.setFollowIntent(artist.name, true)
        artistSheet = artist to page
    }

    /** Opens "Get their music?" without following. */
    fun offerArtist(artist: TidalArtist, page: TidalArtistPage?) { artistSheet = artist to page }

    enum class ArtistChoice { POPULAR, LATEST, EVERYTHING }

    fun getArtist(artist: TidalArtist, page: TidalArtistPage?, choice: ArtistChoice) {
        artistSheet = null
        c.appScope.launch {
            val p = page ?: runCatching { c.requests.artistPage(artist.id) }.getOrNull()
            when (choice) {
                ArtistChoice.EVERYTHING -> send(artist.name, c.requests.artist(artist))
                ArtistChoice.POPULAR -> {
                    val top = p?.topTracks.orEmpty()
                    val owned = runCatching { c.requests.ownedTracks(top) }.getOrDefault(emptyMap())
                    val missing = top.filter { it.id !in owned }
                    if (p == null) actions.showMessage("Couldn't get their music right now (offline?)")
                    else if (missing.isEmpty()) actions.showMessage("Their popular songs are already on your server")
                    else getTracks(missing, "${missing.size} popular songs by ${artist.name}")
                }
                ArtistChoice.LATEST -> {
                    val latest = p?.albums?.maxByOrNull { it.releaseDate.orEmpty() } ?: p?.singles?.maxByOrNull { it.releaseDate.orEmpty() }
                    if (latest == null) actions.showMessage("Couldn't find their latest album")
                    else send(latest.title, c.requests.album(latest))
                }
            }
        }
    }
}

@Composable
fun rememberGetMusic(c: AppContainer, actions: Actions): GetMusic = remember(c, actions) { GetMusic(c, actions) }

/** Album cover or artist picture of music that isn't on the server (public image CDN). Never dimmed. */
@Composable
fun RemoteCover(uuid: String?, title: String, size: Int = 320) {
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

/**
 * The small icon before a not-on-server subtitle, the same size as the downloaded tick: a cloud;
 * a ring while it's on its way or previewing; a warning when getting it failed.
 */
@Composable
fun AvailabilityIcon(request: RequestEntity?, previewing: Boolean = false, loading: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    when {
        previewing && loading -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
        previewing -> {
            val progress by LocalContext.current.container.preview.progress.collectAsStateWithLifecycle()
            CircularProgressIndicator(
                progress = { progress }, modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp,
                color = accent, trackColor = TextSecondary.copy(alpha = 0.3f),
            )
        }
        request?.status == RequestStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, "Couldn't get it", Modifier.size(14.dp), tint = Destructive)
        isComing(request) -> CircularProgressIndicator(
            Modifier.size(12.dp), strokeWidth = 1.5.dp, color = TextSecondary, trackColor = TextSecondary.copy(alpha = 0.3f),
        )
        else -> Icon(Icons.Outlined.Cloud, "Not on your server yet", Modifier.size(14.dp), tint = TextSecondary)
    }
}

/** ♡ for music that isn't on the server: outline = get it; filled = on its way (tap to stop). */
@Composable
fun GetHeart(request: RequestEntity?, size: Dp = 22.dp, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val coming = isComing(request)
    IconButton(onClick = {
        haptics.performHapticFeedback(if (coming) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        onClick()
    }) {
        Icon(
            if (coming) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (coming) "Stop getting it" else "Get it",
            tint = if (coming) MaterialTheme.colorScheme.primary else TextSecondary,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * A song that isn't on the server, in the same layout as [SongRow]: tap previews it, ♡ gets it,
 * ⋯ (or a long press) has the rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MissingSongRow(
    track: TidalTrack,
    modifier: Modifier = Modifier,
    leading: String? = null,
    showCover: Boolean = true,
    subtitle: String = track.artist,
) {
    val get = LocalGetMusic.current
    val request = LocalRequests.current[trackKey(track.id)]
    val preview = LocalPreview.current?.takeIf { it.track.id == track.id }
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { get.preview(track) },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); get.menuFor = track },
            )
            .padding(start = Dimens.Gutter, end = 4.dp, top = Dimens.RowPaddingV, bottom = Dimens.RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(leading, Modifier.width(28.dp), color = TextSecondary.copy(alpha = NotOnServerAlpha), style = MaterialTheme.typography.bodyMedium)
        }
        if (showCover) {
            Box(Modifier.size(Dimens.RowCover).clip(HearthShapes.Cover)) { RemoteCover(track.cover, track.displayTitle, 160) }
            Spacer(Modifier.width(Dimens.CoverGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (preview != null) accent else Color.White.copy(alpha = NotOnServerAlpha),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvailabilityIcon(request, previewing = preview != null, loading = preview?.loading == true)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (preview != null) "Preview" else comingLabel(request) ?: subtitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = (if (request?.status == RequestStatus.FAILED) Destructive else TextSecondary).copy(alpha = NotOnServerAlpha),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        GetHeart(request, size = 20.dp) { get.heartTrack(track, request) }
        IconButton(onClick = { get.menuFor = track }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
    }
}

/** An album that isn't on the server, as a carousel card. */
@Composable
fun MissingAlbumCard(album: TidalAlbum, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val request = LocalRequests.current[albumKey(album.id)]
    MediaCard(
        album.title,
        comingLabel(request) ?: subtitle,
        onClick = onClick,
        modifier = modifier,
        dimmed = true,
        subtitleIcon = { AvailabilityIcon(request) },
        cover = { Box(Modifier.size(Dimens.CardWidth).clip(HearthShapes.Cover)) { RemoteCover(album.cover, album.title) } },
    )
}

/** An album that isn't on the server, as a list row (Search). */
@Composable
fun MissingAlbumRow(album: TidalAlbum, onClick: () -> Unit) {
    val request = LocalRequests.current[albumKey(album.id)]
    MediaRow(
        album.title,
        comingLabel(request) ?: listOfNotNull(albumKind(album), album.artist.ifBlank { null }, album.year).joinToString(" • "),
        onClick = onClick,
        cover = { RemoteCover(album.cover, album.title) },
        titleColor = Color.White.copy(alpha = NotOnServerAlpha),
        subtitleColor = TextSecondary.copy(alpha = NotOnServerAlpha),
        subtitleIcon = { AvailabilityIcon(request) },
    )
}

/** An artist the server doesn't have, as a list row (Search). */
@Composable
fun MissingArtistRow(artist: TidalArtist, onClick: () -> Unit) {
    val request = LocalRequests.current[artistKey(artist.id)]
    MediaRow(
        artist.name,
        comingLabel(request) ?: "Artist",
        onClick = onClick,
        round = true,
        cover = { RemoteCover(artist.picture, artist.name) },
        titleColor = Color.White.copy(alpha = NotOnServerAlpha),
        subtitleColor = TextSecondary.copy(alpha = NotOnServerAlpha),
        subtitleIcon = { AvailabilityIcon(request) },
    )
}

fun albumKind(a: TidalAlbum) = when (a.type) { "EP" -> "EP"; "SINGLE" -> "Single"; else -> "Album" }

/** Slim bar above the mini player while a preview plays: what it is, ♡ to get it, and stop. */
@Composable
fun PreviewBar() {
    val c = LocalContext.current.container
    val get = LocalGetMusic.current
    val current = LocalPreview.current
    // Keep the last preview while the bar animates away.
    var shown by remember { mutableStateOf(current) }
    if (current != null) shown = current
    AnimatedVisibility(current != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val p = shown ?: return@AnimatedVisibility
        val t = p.track
        val request = LocalRequests.current[trackKey(t.id)]
        val progress by c.preview.progress.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp).clip(HearthShapes.Card).background(SurfaceHigh)
        ) {
            Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(HearthShapes.Cover)) { RemoteCover(t.cover, t.displayTitle, 160) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOf(if (p.loading) "Loading preview…" else "Preview", t.artist).filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary, style = MaterialTheme.typography.labelMedium,
                    )
                }
                GetHeart(request) { get.heartTrack(t, request) }
                IconButton(onClick = { c.preview.stop() }) { Icon(Icons.Default.Stop, "Stop preview") }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.15f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/** The sheets [GetMusic] opens. Shown once, at the root. */
@Composable
fun GetMusicSheets(get: GetMusic) {
    val actions = LocalActions.current
    val requests = LocalRequests.current
    get.menuFor?.let { t ->
        val request = requests[trackKey(t.id)]
        val close = { get.menuFor = null }
        ActionSheet(true, onDismiss = close, title = t.displayTitle, subtitle = comingLabel(request) ?: "Not on your server yet", showCover = false) {
            MenuItem("Preview", Icons.Default.PlayArrow) { close(); get.preview(t) }
            when {
                request == null || request.status == RequestStatus.DONE ->
                    MenuItem("Get it", Icons.Outlined.CloudDownload) { close(); get.heartTrack(t, request) }
                request.status == RequestStatus.FAILED -> MenuItem("Try again", Icons.Default.Refresh) { close(); get.retry(request) }
                else -> MenuItem("Stop getting it", Icons.Default.Close) { close(); get.cancel(request) }
            }
            t.albumId?.let { id -> MenuItem("Go to album", Icons.Default.Album) { close(); actions.openAlbum(remoteAlbumId(id)) } }
            t.artistId?.let { id -> MenuItem("Go to artist", Icons.Default.Person) { close(); actions.openArtist(remoteArtistId(id)) } }
        }
    }
    get.artistSheet?.let { (artist, page) ->
        ActionSheet(
            true, onDismiss = { get.artistSheet = null },
            title = "Get their music?", subtitle = "Following ${artist.name}. Their music isn't on your server yet.", showCover = false,
        ) {
            MenuItem("Popular songs", Icons.Outlined.Whatshot) { get.getArtist(artist, page, GetMusic.ArtistChoice.POPULAR) }
            MenuItem("Latest album", Icons.Outlined.Album) { get.getArtist(artist, page, GetMusic.ArtistChoice.LATEST) }
            MenuItem("Everything", Icons.Outlined.LibraryMusic) { get.getArtist(artist, page, GetMusic.ArtistChoice.EVERYTHING) }
            MenuItem("Not now", Icons.Default.Close) { get.artistSheet = null }
        }
    }
    get.requestMenu?.let { r ->
        val close = { get.requestMenu = null }
        ActionSheet(true, onDismiss = close, title = r.title, subtitle = failureText(r) ?: comingLabel(r), showCover = false) {
            // "Adding" can be tried again too: it may have finished without anything arriving.
            if (r.status == RequestStatus.FAILED || r.status == RequestStatus.ADDING) {
                MenuItem("Try again", Icons.Default.Refresh) { close(); get.retry(r) }
            }
            if (r.type == TidalType.ALBUM.api) MenuItem("Go to album", Icons.Default.Album) { close(); actions.openAlbum(remoteAlbumId(r.tidalId)) }
            if (r.type == TidalType.ARTIST.api) MenuItem("Go to artist", Icons.Default.Person) { close(); actions.openArtist(remoteArtistId(r.tidalId)) }
            MenuItem(if (r.status == RequestStatus.FAILED) "Remove" else "Cancel", Icons.Default.Close, tint = Destructive, tintText = true) { close(); get.cancel(r) }
        }
    }
}
