// A song's options sheet and its Song info dialog.
package im.flume.hearth.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.ui.theme.HearthTheme

@Composable
fun SongMenu(song: SongEntity, expanded: Boolean, onDismiss: () -> Unit, onRemoveFromPlaylist: (() -> Unit)? = null) {
    val actions = LocalActions.current
    val dl = rememberSongRowState(song.id).value.downloadState
    var info by remember { mutableStateOf(false) }
    ActionSheet(expanded, onDismiss, title = song.title, subtitle = song.artist, coverArt = song.coverArt, fallback = song.album) {
        MenuItem("Play next", HearthTheme.icons.playNext) { actions.playNext(listOf(song.id)); onDismiss() }
        MenuItem("Add to queue", HearthTheme.icons.queue) { actions.addToQueue(listOf(song.id)); onDismiss() }
        MenuItem("Start radio", HearthTheme.icons.radio) { actions.startRadio(song); onDismiss() }
        MenuItem("Add to playlist", HearthTheme.icons.addToPlaylist) { actions.addToPlaylist(listOf(song.id)); onDismiss() }
        onRemoveFromPlaylist?.let { remove ->
            MenuItem("Remove from this playlist", HearthTheme.icons.removeFromPlaylist) { remove(); onDismiss() }
        }
        MenuItem(
            if (song.starred) "Remove from Liked Songs" else "Add to Liked Songs",
            if (song.starred) HearthTheme.icons.likeOn else HearthTheme.icons.like,
        ) { actions.setStarred(song.id, !song.starred); onDismiss() }
        when (dl) {
            null -> MenuItem("Download", HearthTheme.icons.download) { actions.download(listOf(song)); onDismiss() }
            DownloadState.DONE -> MenuItem("Remove download", HearthTheme.icons.downloaded) { actions.removeDownload(listOf(song.id)); onDismiss() }
            else -> MenuItem("Cancel download", HearthTheme.icons.close) { actions.removeDownload(listOf(song.id)); onDismiss() }
        }
        song.albumId?.let { id -> MenuItem("Go to album", HearthTheme.icons.album) { actions.openAlbum(id); onDismiss() } }
        song.artistId?.let { id -> MenuItem("Go to artist", HearthTheme.icons.artist) { actions.openArtist(id); onDismiss() } }
        MenuItem("Song info", HearthTheme.icons.info) { info = true; onDismiss() }
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
                        Text(label, color = HearthTheme.colors.textSecondary, modifier = Modifier.widthIn(min = 88.dp).padding(end = HearthTheme.spacing.m))
                        Text(value, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
    )
}
