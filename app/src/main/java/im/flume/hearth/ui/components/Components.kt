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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import im.flume.hearth.data.SearchIndex
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RemoveCircleOutline
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
import im.flume.hearth.ui.theme.Dimens
import im.flume.hearth.ui.theme.DividerColor
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
    fun startRadio(song: SongEntity)
    fun startArtistRadio(artistId: String, name: String)
    /** Opens the "Add to playlist" picker for these songs. */
    fun addToPlaylist(songIds: List<String>)
    /** Shows [message] with an Undo button that runs [undo]. */
    fun showUndo(message: String, undo: suspend () -> Unit)
    /** Shows a short [message], e.g. when something couldn't be saved. */
    fun showMessage(message: String) {}
    /** Opens the "New playlist" dialog. */
    fun newPlaylist() {}
    /** Saves an album to Your Library or removes it. */
    fun setAlbumSaved(albumId: String, saved: Boolean) {}
    /** Follows or unfollows an artist, which puts them in Your Library. */
    fun setArtistFollowed(artistId: String, followed: Boolean) {}
}

val LocalActions = compositionLocalOf<Actions> { error("Actions not provided") }

/** Current playing song id and offline state, used to highlight / dim rows. */
data class RowContext(val playingId: String?, val online: Boolean, val downloads: Map<String, DownloadState>)

val LocalRowContext = compositionLocalOf { RowContext(null, true, emptyMap()) }

@Composable
fun CoverArt(
    coverArt: String?,
    size: Dp?,
    modifier: Modifier = Modifier,
    corner: Dp = Dimens.CoverCorner,
    requestSize: Int = 300,
    /** Album / playlist / artist name, shown as initials on a coloured tile when there's no artwork. */
    fallback: String? = null,
) {
    val actions = LocalActions.current
    val m = (if (size != null) modifier.size(size) else modifier).clip(RoundedCornerShape(corner)).background(SurfaceHigh)
    val url = actions.coverUrl(coverArt, requestSize)
    if (url == null) {
        Box(m) { Placeholder(fallback) }
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = m,
        error = { Placeholder(fallback) },
        loading = { Placeholder(null) },
    )
}

private val PLACEHOLDER_COLOURS = listOf(0xFF4A3B6B, 0xFF2F5D62, 0xFF6B3B3B, 0xFF3B4F6B, 0xFF5E5A2E, 0xFF3E6B3B, 0xFF6B4A2E, 0xFF55305E)

@Composable
private fun Placeholder(text: String?) {
    val initials = text?.let { t ->
        SearchIndex.norm(t).split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    }.orEmpty()
    if (initials.isEmpty()) {
        Box(Modifier.fillMaxSize().background(SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = TextSecondary)
        }
        return
    }
    val colour = Color(PLACEHOLDER_COLOURS[(text.hashCode() and 0x7fffffff) % PLACEHOLDER_COLOURS.size])
    BoxWithConstraints(Modifier.fillMaxSize().background(colour), contentAlignment = Alignment.Center) {
        val fontSize = with(LocalDensity.current) { (maxWidth * 0.36f).toSp() }
        Text(initials, color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = fontSize)
    }
}

/** Bottom sheet for song / player options, with an optional header showing what it's about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    coverArt: String? = null,
    fallback: String? = subtitle,
    /** False for plain text headers, e.g. "Sort by". */
    showCover: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceHigh,
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showCover) {
                    CoverArt(coverArt, 52.dp, requestSize = 150, fallback = fallback)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = DividerColor)
        }
        Column(Modifier.padding(bottom = 16.dp), content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    showCover: Boolean = true,
    leading: String? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Null when not selecting; otherwise whether this row is selected. */
    selected: Boolean? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val ctx = LocalRowContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val dl = ctx.downloads[song.id]
    val playable = ctx.online || dl == DownloadState.DONE
    val isPlaying = ctx.playingId == song.id
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected == true) accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(
                enabled = playable || selected != null,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (onLongPress != null) onLongPress() else menu = true
                },
            )
            .padding(horizontal = Dimens.Gutter, vertical = Dimens.RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                if (selected) "Selected" else "Not selected",
                tint = if (selected) accent else TextSecondary,
                modifier = Modifier.padding(end = Dimens.CoverGap).size(24.dp),
            )
        }
        if (leading != null) {
            Text(leading, Modifier.width(28.dp), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (showCover) {
            CoverArt(song.coverArt, Dimens.RowCover, requestSize = 150, fallback = song.album)
            Spacer(Modifier.width(Dimens.CoverGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    isPlaying -> accent
                    !playable -> TextSecondary.copy(alpha = 0.5f)
                    else -> Color.White
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (dl) {
                    DownloadState.DONE -> Icon(Icons.Default.CheckCircle, "Downloaded", Modifier.size(11.dp), tint = accent)
                    DownloadState.QUEUED, DownloadState.DOWNLOADING -> Icon(Icons.Default.Downloading, "Downloading", Modifier.size(14.dp), tint = TextSecondary)
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
        if (song.starred) Icon(Icons.Default.Favorite, "Liked", Modifier.size(16.dp), tint = accent)
        if (selected == null) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextSecondary) }
            SongMenu(song, menu, onDismiss = { menu = false }, onRemoveFromPlaylist = onRemoveFromPlaylist)
        }
    }
}

@Composable
fun SongMenu(song: SongEntity, expanded: Boolean, onDismiss: () -> Unit, onRemoveFromPlaylist: (() -> Unit)? = null) {
    val actions = LocalActions.current
    val dl = LocalRowContext.current.downloads[song.id]
    var info by remember { mutableStateOf(false) }
    ActionSheet(expanded, onDismiss, title = song.title, subtitle = song.artist, coverArt = song.coverArt, fallback = song.album) {
        MenuItem("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { actions.playNext(listOf(song.id)); onDismiss() }
        MenuItem("Start radio", Icons.Default.Radio) { actions.startRadio(song); onDismiss() }
        MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { actions.addToPlaylist(listOf(song.id)); onDismiss() }
        onRemoveFromPlaylist?.let { remove ->
            MenuItem("Remove from this playlist", Icons.Default.RemoveCircleOutline) { remove(); onDismiss() }
        }
        MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { actions.addToQueue(listOf(song.id)); onDismiss() }
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
                        Text(label, color = TextSecondary, modifier = Modifier.widthIn(min = 88.dp).padding(end = 12.dp))
                        Text(value, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
    )
}

/**
 * One row of an [ActionSheet]. [icon] may be null for plain choice lists; [checked] shows a tick on
 * the current choice. [tint] colours the icon (and the text, for destructive items).
 */
@Composable
fun MenuItem(
    text: String,
    icon: ImageVector?,
    tint: Color = Color.White,
    checked: Boolean = false,
    tintText: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(20.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                tintText -> tint
                checked -> MaterialTheme.colorScheme.primary
                else -> Color.Unspecified
            },
            modifier = Modifier.weight(1f),
        )
        if (checked) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

/** A sheet of mutually exclusive choices, with a tick on the current one. */
@Composable
fun <T> ChoiceSheet(open: Boolean, title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    ActionSheet(open, onDismiss, title = title, showCover = false) {
        options.forEach { (value, label) ->
            MenuItem(label, null, checked = value == selected) { onPick(value); onDismiss() }
        }
    }
}

@Composable
fun AlbumRow(title: String, albums: List<AlbumEntity>) {
    if (albums.isEmpty()) return
    val actions = LocalActions.current
    SectionHeader(title)
    LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.CarouselSpacing)) {
        items(albums, key = { it.id }) { a -> MediaCard(a.name, a.artist, onClick = { actions.openAlbum(a.id) }, coverArt = a.coverArt) }
    }
}

/** Big round play button + shuffle button used on album / playlist / artist headers. */
@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
        extra()
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShuffle, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Shuffle, "Shuffle", tint = accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
            Icon(Icons.Default.PlayArrow, "Play", modifier = Modifier.size(32.dp))
        }
    }
}

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

/** "1 song", "3 songs". */
fun plural(count: Int, one: String, many: String = one + "s"): String = "$count ${if (count == 1) one else many}"
