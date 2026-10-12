package im.flume.hearth.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.IconButton
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.willDownload
import im.flume.hearth.ui.components.willLike
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.TidalAlbumPage
import im.flume.hearth.api.TidalArtistPage
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.api.tidalImageUrl
import im.flume.hearth.container
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.data.AlbumItem
import im.flume.hearth.data.Availability
import im.flume.hearth.data.Discography
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SearchIndex
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.SongItem
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.ui.components.AvailabilityIcon
import im.flume.hearth.ui.components.BackButton
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.LoadingPage
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalGetMusic
import im.flume.hearth.ui.components.LocalRequests
import im.flume.hearth.ui.components.MediaCard
import im.flume.hearth.ui.components.MissingAlbumCard
import im.flume.hearth.ui.components.MissingSongRow
import im.flume.hearth.ui.components.PlayShuffleButtons
import im.flume.hearth.ui.components.REMOTE_PREFIX
import im.flume.hearth.ui.components.RemoteCover
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.UnavailablePage
import im.flume.hearth.ui.components.albumKey
import im.flume.hearth.ui.components.albumKind
import im.flume.hearth.ui.components.artistKey
import im.flume.hearth.ui.components.comingLabel
import im.flume.hearth.ui.components.formatLongDuration
import im.flume.hearth.ui.components.isComing
import im.flume.hearth.ui.components.plural
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.components.remoteAlbumId
import im.flume.hearth.ui.components.remoteArtistId
import im.flume.hearth.ui.components.trackKey
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.NotOnServerAlpha
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * One album page and one artist page, whether or not the server has the music. Pages for music on the
 * server show it straight away; what it doesn't have is looked up in the background and slides in.
 * Pages for music it doesn't have ("tidal:" ids) render the same layout from the remote catalogue.
 */

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Failed : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

@Composable
private fun <T> rememberLoad(key: String, load: suspend () -> T): Load<T> {
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key) {
        state = try {
            Load.Ready(withContext(Dispatchers.IO) { load() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed
        }
    }
    return state
}

/** True when pages should look up music the server doesn't have. */
@Composable
private fun lookupsOn(): Boolean {
    val c = LocalContext.current.container
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    return settings.tidalSearchEnabled && online
}

/** Songs the server has for each track, one song per track at most (so list keys stay unique). */
private fun ownedItems(tracks: List<TidalTrack>, owned: Map<String, SongEntity>): List<SongItem> {
    val used = HashSet<String>()
    return tracks.map { t ->
        val song = owned[t.id]?.takeIf { used.add(it.id) }
        if (song != null) SongItem.Owned(song) else SongItem.Missing(t)
    }
}

// --- album ---

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
        ActionSheet(true, onDismiss = { albumMenu = false }, title = albumName, subtitle = "${plural(missing.size, "song")} not on your server yet", showCover = false) {
            MenuItem("Add missing songs to server", Icons.Outlined.CloudUpload) {
                albumMenu = false
                getMissing("Getting ${plural(waiting.size, "missing song")} of \"$albumName\" onto your server")
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
            if (missing.isNotEmpty()) IconButton(onClick = { albumMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
        },
        tracks = tracks?.takeIf { missing.isNotEmpty() },
        topContent = {
            if (missing.isNotEmpty()) item(key = "get-rest") { MissingNote(missing, Modifier.animateItem()) }
        },
        extraContent = {
            item { AlbumDetails(a, id, songs.size, songs.sumOf { it.durationSec.toLong() }) }
            a?.artistId?.let { artistId ->
                item {
                    TextButton(onClick = { actions.openArtist(artistId) }, modifier = Modifier.padding(horizontal = 8.dp)) {
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
        modifier.fillMaxWidth().padding(start = Dimens.Gutter, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Cloud, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (waiting.isEmpty()) "${plural(missing.size, "song")} coming to your server…"
            else "${plural(waiting.size, "song")} not on your server yet",
            color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
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
        Load.Failed -> UnavailablePage(Icons.Default.CloudOff, "Couldn't load this album. Check your connection and try again.")
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
            IconButton(onClick = { get.albumMenuFor = album }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
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
                    TextButton(onClick = { actions.openArtist(remoteArtistId(artistId)) }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                        Text("More by ${album.artist}")
                    }
                }
            }
        },
    )
}

// --- artist ---

private data class ArtistExtras(val page: TidalArtistPage, val owned: Map<String, SongEntity>, val ownedAlbums: Map<String, AlbumEntity> = emptyMap())

@Composable
fun ArtistScreen(id: String) {
    if (id.startsWith(REMOTE_PREFIX)) return RemoteArtistScreen(id.removePrefix(REMOTE_PREFIX))
    val c = LocalContext.current.container
    val dao = c.db.library()
    val artist by remember(id) { dao.artist(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.artistSongs(id) }.collectAsStateWithLifecycle(emptyList())
    val albums by remember(id) { dao.artistAlbums(id) }.collectAsStateWithLifecycle(emptyList())
    val actions = LocalActions.current
    val name = artist?.name ?: songs.firstOrNull()?.artist.orEmpty()
    val source = PlaySource(PlaySource.Kind.ARTIST, id, name)

    // Popular songs and the releases the server is missing, looked up once the page is showing.
    val lookups = lookupsOn()
    var extras by remember(id) { mutableStateOf<ArtistExtras?>(null) }
    LaunchedEffect(id, name, lookups) {
        if (!lookups || name.isBlank()) return@LaunchedEffect
        extras = try {
            withContext(Dispatchers.IO) {
                c.requests.artistByName(name)?.let { page -> ArtistExtras(page, c.requests.ownedTracks(page.topTracks)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val x = extras
    val popular = remember(songs, x) {
        x?.page?.topTracks?.takeIf { it.isNotEmpty() }?.let { ownedItems(it, x.owned) } ?: songs.take(5).map { SongItem.Owned(it) }
    }
    val discography = remember(albums, x) { Availability.discography(albums, x?.page?.albums.orEmpty(), x?.page?.singles.orEmpty()) }
    val shownIds = remember(popular) { popular.mapNotNull { (it as? SongItem.Owned)?.song?.id }.toSet() }
    val rest = if (x != null) songs.filterNot { it.id in shownIds } else songs.drop(5)
    val artistCover = artist?.coverArt ?: albums.firstOrNull()?.coverArt

    ArtistLayout(
        name = name,
        headerColor = rememberCoverColor(artistCover),
        art = { size -> CoverArt(artistCover, size, Modifier.clip(CircleShape), requestSize = im.flume.hearth.ui.theme.CoverSize.LARGE, fallback = name) },
        counts = "${plural(albums.size, "album")} • ${plural(songs.size, "song")}",
        buttons = {
            PlayShuffleButtons(
                onPlay = { actions.play(source) },
                onShuffle = { actions.play(source, shuffle = true) },
                extra = {
                    artist?.let { ar -> FollowButton(ar.starred) { actions.setArtistFollowed(id, !ar.starred) } }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { actions.startArtistRadio(id, name) }) {
                        Icon(Icons.Default.Radio, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Radio")
                    }
                },
            )
        },
        popular = popular,
        onPlayOwned = { song ->
            val ids = popular.mapNotNull { (it as? SongItem.Owned)?.song?.id }
            actions.play(PlaySource(PlaySource.Kind.SONGS, label = name, songIds = ids), startSongId = song.id)
        },
        discography = discography,
        more = {
            if (rest.isNotEmpty()) {
                item(key = "all-head") { SectionHeader("All songs") }
                items(rest, key = { "all:${it.id}" }) { s ->
                    Column(Modifier.animateItem()) { SongRow(s, onClick = { actions.play(source, startSongId = s.id) }) }
                }
            }
            item(key = "about") { ArtistAbout(id) }
        },
    )
}

private data class RemoteArtist(val extras: ArtistExtras, val localArtistId: String?)

@Composable
private fun RemoteArtistScreen(id: String) {
    val c = LocalContext.current.container
    val state = rememberLoad("artist:$id") {
        val page = c.requests.artistPage(id)
        val owned = c.requests.ownedTracks(page.topTracks)
        val ownedAlbums = c.requests.ownedAlbums(page.albums + page.singles)
        RemoteArtist(ArtistExtras(page, owned, ownedAlbums), c.requests.localArtist(page.artist.name)?.id)
    }
    when (state) {
        Load.Loading -> LoadingPage()
        Load.Failed -> UnavailablePage(Icons.Default.CloudOff, "Couldn't load this artist. Check your connection and try again.")
        is Load.Ready -> {
            val local = state.value.localArtistId
            if (local != null) ArtistScreen(local) else RemoteArtistContent(state.value.extras)
        }
    }
}

@Composable
private fun RemoteArtistContent(x: ArtistExtras) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val get = LocalGetMusic.current
    val page = x.page
    val artist = page.artist
    val request = LocalRequests.current[artistKey(artist.id)]
    val intents by c.requests.followIntents.collectAsStateWithLifecycle()
    val following = SearchIndex.norm(artist.name) in intents || isComing(request)
    val popular = remember(x) { ownedItems(page.topTracks, x.owned) }
    val discography = remember(x) { Availability.discography(x.ownedAlbums.values.distinctBy { it.id }, page.albums, page.singles) }
    val firstMissing = popular.firstNotNullOfOrNull { (it as? SongItem.Missing)?.track }
    val counts = listOfNotNull(
        page.albums.size.takeIf { it > 0 }?.let { plural(it, "album") },
        page.singles.size.takeIf { it > 0 }?.let { plural(it, "single or EP", "singles and EPs") },
    ).joinToString(" • ")

    ArtistLayout(
        name = artist.name,
        headerColor = rememberCoverColor(null, imageUrl = tidalImageUrl(artist.picture, 160)),
        art = { size -> Box(Modifier.size(size).clip(CircleShape)) { RemoteCover(artist.picture, artist.name, 750) } },
        counts = counts,
        dimmed = true,
        status = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvailabilityIcon(request)
                Spacer(Modifier.width(4.dp))
                Text(comingLabel(request) ?: "Not on your server yet", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        },
        buttons = {
            PlayShuffleButtons(
                onPlay = { if (firstMissing != null) get.preview(firstMissing) else actions.showMessage("Nothing to preview") },
                onShuffle = null,
                playLabel = "Play preview",
                extra = {
                    FollowButton(following) { get.follow(artist, page) }
                    if (following && request == null) {
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { get.offerArtist(artist, page) }) {
                            Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Get music")
                        }
                    }
                },
            )
        },
        popular = popular,
        onPlayOwned = { song ->
            val ids = popular.mapNotNull { (it as? SongItem.Owned)?.song?.id }
            actions.play(PlaySource(PlaySource.Kind.SONGS, label = artist.name, songIds = ids), startSongId = song.id)
        },
        discography = discography,
    )
}

/** The artist page layout, shared by artists on the server and ones that aren't. */
@Composable
private fun ArtistLayout(
    name: String,
    headerColor: Color,
    art: @Composable (Dp) -> Unit,
    counts: String,
    buttons: @Composable () -> Unit,
    popular: List<SongItem>,
    onPlayOwned: (SongEntity) -> Unit,
    discography: Discography,
    dimmed: Boolean = false,
    status: (@Composable () -> Unit)? = null,
    more: LazyListScope.() -> Unit = {},
) {
    val actions = LocalActions.current
    val wide = isWideWindow()
    val titleColor = if (dimmed) Color.White.copy(alpha = NotOnServerAlpha) else Color.Unspecified
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerColor, Color.Transparent)))) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { BackButton() }
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
                            art(160.dp)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(name, style = MaterialTheme.typography.headlineMedium, color = titleColor, modifier = Modifier.padding(horizontal = Dimens.Gutter))
                                Text(counts, color = TextSecondary, modifier = Modifier.padding(horizontal = Dimens.Gutter, vertical = 4.dp))
                                status?.let { Box(Modifier.padding(horizontal = Dimens.Gutter)) { it() } }
                                buttons()
                            }
                        }
                    } else {
                        art(180.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(name, style = MaterialTheme.typography.headlineMedium, color = titleColor, textAlign = TextAlign.Center)
                        if (counts.isNotBlank()) Text(counts, color = TextSecondary, modifier = Modifier.padding(4.dp))
                        status?.invoke()
                    }
                }
            }
        }
        if (!wide) item(key = "buttons") { buttons() }
        if (popular.isNotEmpty()) {
            item(key = "popular-head") { SectionHeader("Popular") }
            itemsIndexed(popular, key = { _, it -> "top:${it.key}" }) { i, item ->
                Column(Modifier.animateItem()) {
                    when (item) {
                        is SongItem.Owned -> SongRow(item.song, onClick = { onPlayOwned(item.song) })
                        is SongItem.Missing -> MissingSongRow(item.track, subtitle = item.track.albumTitle ?: item.track.artist)
                    }
                }
            }
        }
        albumCarousel("albums", "Albums", discography.albums) { actions.openAlbum(it) }
        albumCarousel("singles", "Singles & EPs", discography.singles) { actions.openAlbum(it) }
        more()
    }
}

private fun LazyListScope.albumCarousel(key: String, title: String, albums: List<AlbumItem>, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = key) {
        Column(Modifier.animateItem()) {
            SectionHeader(title)
            LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.CarouselSpacing)) {
                items(albums, key = { it.key }) { a ->
                    when (a) {
                        is AlbumItem.Owned -> MediaCard(
                            a.album.name, a.album.year?.toString().orEmpty(), onClick = { onOpen(a.album.id) },
                            coverArt = a.album.coverArt, modifier = Modifier.animateItem(),
                        )
                        is AlbumItem.Missing -> MissingAlbumCard(
                            a.album, a.album.year ?: albumKind(a.album), modifier = Modifier.animateItem(),
                        ) { onOpen(remoteAlbumId(a.album.id)) }
                    }
                }
            }
        }
    }
}

/** Outlined pill: "Follow" adds the artist to Your Library, "Following" when they're already there. */
@Composable
internal fun FollowButton(following: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (following) accent else TextSecondary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp),
    ) {
        Text(if (following) "Following" else "Follow", color = if (following) accent else Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
