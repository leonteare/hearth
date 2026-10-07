package im.flume.hearth.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.ui.components.AlbumCard
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.components.DownloadToggle
import im.flume.hearth.ui.components.rememberCoverColor
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalRowContext
import im.flume.hearth.ui.components.PlayShuffleButtons
import im.flume.hearth.ui.components.SectionHeader
import im.flume.hearth.ui.components.SongRow
import im.flume.hearth.ui.components.formatBytes
import im.flume.hearth.ui.components.formatLongDuration
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.TextSecondary

/** Pin target for the download toggle: albums and playlists stay in sync; other lists are one-off downloads. */
data class PinTarget(val kind: String, val id: String)

@Composable
fun BackButton() {
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    IconButton(onClick = { dispatcher?.onBackPressed() }, modifier = Modifier.statusBarsPadding()) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
    }
}

@Composable
fun CollectionScreen(
    title: String,
    subtitle: String,
    cover: String?,
    songs: List<SongEntity>,
    source: PlaySource,
    pin: PinTarget? = null,
    showTrackNumbers: Boolean = false,
    headerIcon: (@Composable () -> Unit)? = null,
    extraContent: LazyListScope.() -> Unit = {},
) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val downloads = LocalRowContext.current.downloads
    val pinned by remember { c.downloads.pinned }.collectAsStateWithLifecycle(emptySet())
    val isPinned = pin != null && "${pin.kind}:${pin.id}" in pinned
    val allDownloaded = songs.isNotEmpty() && songs.all { downloads[it.id] == DownloadState.DONE }
    val anyDownloading = songs.any { downloads[it.id] == DownloadState.QUEUED || downloads[it.id] == DownloadState.DOWNLOADING }
    val doneCount = songs.count { downloads[it.id] == DownloadState.DONE }
    val headerColor = rememberCoverColor(cover)
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Stop downloading?") },
            text = { Text("This removes \"$title\" from your downloads, including songs that already finished.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    if (pin != null) c.downloads.unpinAndRemove(pin.kind, pin.id, songs.map { it.id })
                    else actions.removeDownload(songs.map { it.id })
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep downloading") } },
        )
    }
    val totalSec = songs.sumOf { it.durationSec.toLong() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerColor, Color.Transparent)))
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { BackButton() }
                    if (headerIcon != null) headerIcon() else CoverArt(cover, 220.dp, requestSize = 600)
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(
                        listOfNotNull(subtitle.takeIf { it.isNotBlank() }, "${songs.size} songs", formatLongDuration(totalSec).takeIf { totalSec > 0 })
                            .joinToString(" • "),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        item {
            PlayShuffleButtons(
                onPlay = { actions.play(source) },
                onShuffle = { actions.play(source, shuffle = true) },
                extra = {
                    DownloadToggle(
                        downloaded = allDownloaded || (isPinned && !anyDownloading),
                        progress = if (anyDownloading) doneCount.toFloat() / songs.size.coerceAtLeast(1) else null,
                    ) {
                        when {
                            anyDownloading -> confirmCancel = true
                            pin != null && (isPinned || allDownloaded) -> c.downloads.unpinAndRemove(pin.kind, pin.id, songs.map { it.id })
                            pin != null -> c.downloads.pinAndDownload(pin.kind, pin.id, songs)
                            allDownloaded -> actions.removeDownload(songs.map { it.id })
                            else -> actions.download(songs)
                        }
                    }
                },
            )
        }
        itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { _, song ->
            SongRow(
                song,
                onClick = { actions.play(source, startSongId = song.id) },
                showCover = !showTrackNumbers,
                leading = if (showTrackNumbers) song.track.takeIf { it > 0 }?.toString() ?: "–" else null,
            )
        }
        extraContent()
    }
}

@Composable
fun AlbumScreen(id: String) {
    val dao = LocalContext.current.container.db.library()
    val album by remember(id) { dao.album(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.albumSongs(id) }.collectAsStateWithLifecycle(emptyList())
    val a = album
    val actions = LocalActions.current
    CollectionScreen(
        title = a?.name ?: songs.firstOrNull()?.album.orEmpty(),
        subtitle = listOfNotNull(a?.artist, a?.year?.toString()).joinToString(" • "),
        cover = a?.coverArt ?: songs.firstOrNull()?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.ALBUM, id, a?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_ALBUM, id),
        showTrackNumbers = true,
        extraContent = {
            item { AlbumDetails(a, id, songs.size, songs.sumOf { it.durationSec.toLong() }) }
            a?.artistId?.let { artistId ->
                item {
                    TextButton(onClick = { actions.openArtist(artistId) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("More by ${a.artist}", color = Accent)
                    }
                }
            }
        },
    )
}

@Composable
fun PlaylistScreen(id: String) {
    val dao = LocalContext.current.container.db.library()
    val playlist by remember(id) { dao.playlist(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.playlistSongs(id) }.collectAsStateWithLifecycle(emptyList())
    CollectionScreen(
        title = playlist?.name.orEmpty(),
        subtitle = playlist?.owner.orEmpty(),
        cover = playlist?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.PLAYLIST, id, playlist?.name.orEmpty()),
        pin = PinTarget(DownloadRepository.KIND_PLAYLIST, id),
    )
}

@Composable
fun ArtistScreen(id: String) {
    val dao = LocalContext.current.container.db.library()
    val artist by remember(id) { dao.artist(id) }.collectAsStateWithLifecycle(null)
    val songs by remember(id) { dao.artistSongs(id) }.collectAsStateWithLifecycle(emptyList())
    val albums by remember(id) { dao.artistAlbums(id) }.collectAsStateWithLifecycle(emptyList())
    val actions = LocalActions.current
    val name = artist?.name ?: songs.firstOrNull()?.artist.orEmpty()
    val source = PlaySource(PlaySource.Kind.ARTIST, id, name)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val artistColor = rememberCoverColor(artist?.coverArt ?: albums.firstOrNull()?.coverArt)
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(artistColor, Color.Transparent)))) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { BackButton() }
                    CoverArt(artist?.coverArt ?: albums.firstOrNull()?.coverArt, 180.dp, Modifier.clip(CircleShape), requestSize = 600)
                    Spacer(Modifier.height(16.dp))
                    Text(name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("${albums.size} albums • ${songs.size} songs", color = TextSecondary, modifier = Modifier.padding(4.dp))
                }
            }
        }
        item { PlayShuffleButtons(onPlay = { actions.play(source) }, onShuffle = { actions.play(source, shuffle = true) }) }
        if (songs.isNotEmpty()) {
            item { SectionHeader("Popular") }
            items(songs.take(5), key = { "top:${it.id}" }) { s ->
                SongRow(s, onClick = { actions.play(PlaySource(PlaySource.Kind.SONGS, label = name, songIds = songs.map { it.id }), startSongId = s.id) })
            }
        }
        if (albums.isNotEmpty()) {
            item {
                SectionHeader("Albums")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albums, key = { it.id }) { AlbumCard(it, subtitle = it.year?.toString().orEmpty()) }
                }
            }
        }
        if (songs.size > 5) {
            item { SectionHeader("All songs") }
            items(songs.drop(5), key = { "all:${it.id}" }) { s ->
                SongRow(s, onClick = { actions.play(source, startSongId = s.id) })
            }
        }
        item { ArtistAbout(id) }
    }
}

@Composable
fun GenreScreen(name: String) {
    val dao = LocalContext.current.container.db.library()
    val songs by remember(name) { dao.genreSongs(name) }.collectAsStateWithLifecycle(emptyList())
    CollectionScreen(
        title = name,
        subtitle = "Genre",
        cover = songs.firstOrNull()?.coverArt,
        songs = songs,
        source = PlaySource(PlaySource.Kind.GENRE, name, name),
    )
}

@Composable
fun LikedScreen() {
    val dao = LocalContext.current.container.db.library()
    val songs by remember { dao.starredSongs() }.collectAsStateWithLifecycle(emptyList())
    CollectionScreen(
        title = "Liked Songs",
        subtitle = "",
        cover = null,
        songs = songs,
        source = PlaySource.Liked,
        headerIcon = { BigIcon(Icons.Default.Favorite, Color(0xFF5038A0)) },
    )
}

@Composable
fun DownloadsScreen() {
    val c = LocalContext.current.container
    val songs by remember { c.db.library().downloadedOrQueuedSongs() }.collectAsStateWithLifecycle(emptyList())
    val bytes by remember { c.downloads.totalBytes }.collectAsStateWithLifecycle(0L)
    CollectionScreen(
        title = "Downloads",
        subtitle = formatBytes(bytes),
        cover = null,
        songs = songs,
        source = PlaySource.Downloads,
        headerIcon = { BigIcon(Icons.Default.DownloadDone, Color(0xFF1E6B52)) },
        extraContent = {
            if (songs.isNotEmpty()) {
                item {
                    TextButton(onClick = { c.downloads.removeAll() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Remove all downloads", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                item {
                    Text(
                        "Nothing downloaded yet. Tap the download icon on an album or playlist to keep it on your phone.",
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun BigIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(Modifier.size(180.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(72.dp), tint = Color.White)
    }
}
