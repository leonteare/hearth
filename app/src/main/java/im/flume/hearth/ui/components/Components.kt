package im.flume.hearth.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import im.flume.hearth.data.AlbumEntity
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SongEntity
import im.flume.hearth.ui.theme.Accent
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary

/** Everything a song row or card can trigger. Provided once at the root. */
interface Actions {
    fun play(source: PlaySource, startSongId: String? = null, shuffle: Boolean = false)
    fun playNext(songIds: List<String>)
    fun addToQueue(songIds: List<String>)
    fun setStarred(songId: String, starred: Boolean)
    fun download(songs: List<SongEntity>)
    fun removeDownload(songIds: List<String>)
    fun openAlbum(id: String)
    fun openArtist(id: String)
    fun openPlaylist(id: String)
    fun openGenre(name: String)
    fun open(route: String)
    fun coverUrl(coverArt: String?, size: Int = 300): String?
}

val LocalActions = compositionLocalOf<Actions> { error("Actions not provided") }

/** Current playing song id and offline state, used to highlight / dim rows. */
data class RowContext(val playingId: String?, val online: Boolean, val downloads: Map<String, DownloadState>)

val LocalRowContext = compositionLocalOf { RowContext(null, true, emptyMap()) }

@Composable
fun CoverArt(coverArt: String?, size: Dp?, modifier: Modifier = Modifier, corner: Dp = 4.dp, requestSize: Int = 300) {
    val actions = LocalActions.current
    val m = (if (size != null) modifier.size(size) else modifier).clip(RoundedCornerShape(corner)).background(SurfaceHigh)
    SubcomposeAsyncImage(
        model = actions.coverUrl(coverArt, requestSize),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = m,
        error = { Placeholder() },
        loading = { Placeholder() },
    )
}

@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize().background(SurfaceHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = TextSecondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    showCover: Boolean = true,
    leading: String? = null,
) {
    val actions = LocalActions.current
    val ctx = LocalRowContext.current
    var menu by remember { mutableStateOf(false) }
    val dl = ctx.downloads[song.id]
    val playable = ctx.online || dl == DownloadState.DONE
    val isPlaying = ctx.playingId == song.id
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = playable, onClick = onClick, onLongClick = { menu = true })
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(leading, Modifier.width(28.dp), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (showCover) {
            CoverArt(song.coverArt, 48.dp, requestSize = 150)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    isPlaying -> Accent
                    !playable -> TextSecondary.copy(alpha = 0.5f)
                    else -> Color.White
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (dl) {
                    DownloadState.DONE -> Icon(Icons.Default.CheckCircle, "Downloaded", Modifier.size(11.dp), tint = Accent)
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> Icon(Icons.Default.Downloading, null, Modifier.size(14.dp), tint = TextSecondary)
                    else -> {}
                }
                if (dl != null && dl != DownloadState.FAILED) Spacer(Modifier.width(4.dp))
                Text(
                    song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = TextSecondary.copy(alpha = if (playable) 1f else 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (song.starred) Icon(Icons.Default.Favorite, null, Modifier.size(16.dp), tint = Accent)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
            SongMenu(song, menu, onDismiss = { menu = false })
        }
    }
}

@Composable
fun SongMenu(song: SongEntity, expanded: Boolean, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val dl = LocalRowContext.current.downloads[song.id]
    var info by remember { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem("Play next", Icons.AutoMirrored.Filled.QueueMusic) { actions.playNext(listOf(song.id)); onDismiss() }
        MenuItem("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd) { actions.addToQueue(listOf(song.id)); onDismiss() }
        MenuItem(
            if (song.starred) "Remove from Liked Songs" else "Add to Liked Songs",
            if (song.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
        ) { actions.setStarred(song.id, !song.starred); onDismiss() }
        when (dl) {
            null -> MenuItem("Download", Icons.Outlined.ArrowCircleDown) { actions.download(listOf(song)); onDismiss() }
            DownloadState.DONE -> MenuItem("Remove download", Icons.Default.CheckCircle) { actions.removeDownload(listOf(song.id)); onDismiss() }
            else -> MenuItem("Cancel download", Icons.Default.Close) { actions.removeDownload(listOf(song.id)); onDismiss() }
        }
        song.albumId?.let { id -> MenuItem("Go to album", Icons.Default.Album) { actions.openAlbum(id); onDismiss() } }
        song.artistId?.let { id -> MenuItem("Go to artist", Icons.Default.Person) { actions.openArtist(id); onDismiss() } }
        MenuItem("Song info", Icons.Outlined.Info) { info = true; onDismiss() }
    }
    if (info) SongInfoDialog(song, onDismiss = { info = false })
}

/** Details for the curious: format, quality, size, play count. */
@Composable
fun SongInfoDialog(song: SongEntity, onDismiss: () -> Unit) {
    val kbps = if (song.durationSec > 0 && song.sizeBytes > 0) song.sizeBytes * 8 / 1000 / song.durationSec else null
    val rows = listOfNotNull(
        "Album" to song.album,
        "Artist" to song.artist,
        song.track.takeIf { it > 0 }?.let { "Track" to (if (song.disc > 1) "Disc ${song.disc}, track $it" else "$it") },
        song.year?.let { "Year" to it.toString() },
        song.genre?.let { "Genre" to it },
        "Length" to formatDuration(song.durationSec.toLong()),
        song.suffix?.let { "Format" to it.uppercase() + (kbps?.let { k -> " · ~$k kbps" } ?: "") },
        song.sizeBytes.takeIf { it > 0 }?.let { "File size" to formatBytes(it) },
        "Plays" to song.playCount.toString(),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (label, value) ->
                    Row {
                        Text(label, color = TextSecondary, modifier = Modifier.width(88.dp))
                        Text(value)
                    }
                }
            }
        },
    )
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
fun AlbumCard(album: AlbumEntity, width: Dp = 140.dp, subtitle: String = album.artist) {
    val actions = LocalActions.current
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickableCompat { actions.openAlbum(album.id) }
    ) {
        CoverArt(album.coverArt, width)
        Spacer(Modifier.height(8.dp))
        Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }
}

@Composable
fun AlbumRow(title: String, albums: List<AlbumEntity>) {
    if (albums.isEmpty()) return
    SectionHeader(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums, key = { it.id }) { AlbumCard(it) }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
    )
}

/** Big round play button + shuffle button used on album / playlist / artist headers. */
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        extra()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShuffle, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Shuffle, "Shuffle", tint = Accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.Black),
        ) {
            Icon(Icons.Default.PlayArrow, "Play", modifier = Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatLongDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "$h hr $m min" else "$m min"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
