// A song's options sheet and its Song info dialog.
package im.flume.hearth.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.SongEntity
import im.flume.hearth.ui.theme.TextSecondary

@Composable
fun SongMenu(song: SongEntity, expanded: Boolean, onDismiss: () -> Unit, onRemoveFromPlaylist: (() -> Unit)? = null) {
    val actions = LocalActions.current
    val dl = rememberSongRowState(song.id).value.downloadState
    var info by remember { mutableStateOf(false) }
    ActionSheet(expanded, onDismiss, title = song.title, subtitle = song.artist, coverArt = song.coverArt, fallback = song.album) {
        MenuItem("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { actions.playNext(listOf(song.id)); onDismiss() }
        MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { actions.addToQueue(listOf(song.id)); onDismiss() }
        MenuItem("Start radio", Icons.Default.Radio) { actions.startRadio(song); onDismiss() }
        MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { actions.addToPlaylist(listOf(song.id)); onDismiss() }
        onRemoveFromPlaylist?.let { remove ->
            MenuItem("Remove from this playlist", Icons.Default.RemoveCircleOutline) { remove(); onDismiss() }
        }
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
