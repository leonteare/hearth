package im.flume.hearth.ui.screens

// Now Playing's ⋯ menu for a song, its sleep timer picker, and the "Stops in 23 min" line under the header.

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import im.flume.hearth.container
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.Usage
import im.flume.hearth.playback.PlaybackService
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.LocalRowContext
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.components.SongInfoDialog
import kotlinx.coroutines.delay

@Composable
internal fun NowPlayingMenu(song: SongEntity?, sleepAt: Long, onClose: () -> Unit) {
    val actions = LocalActions.current
    val player = LocalContext.current.container.player
    val dl = song?.let { LocalRowContext.current.downloads[it.id] }
    var open by remember { mutableStateOf(false) }
    var sleepPicker by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val usage = LocalContext.current.container.usage
    if (sleepPicker) {
        SleepTimerSheet(active = sleepAt != 0L, onPick = { if (it != 0L) usage.track(Usage.SLEEP); player.setSleepTimer(it); sleepPicker = false }, onDismiss = { sleepPicker = false })
    }
    if (info && song != null) SongInfoDialog(song, onDismiss = { info = false })
    IconButton(onClick = { open = true }, enabled = song != null) { Icon(Icons.Default.MoreHoriz, "More options") }
    ActionSheet(open, { open = false }, title = song?.title, subtitle = song?.artist, coverArt = song?.coverArt, fallback = song?.album) {
        if (song != null) {
            MenuItem("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { open = false; actions.playNext(listOf(song.id)) }
            MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { open = false; actions.addToQueue(listOf(song.id)) }
            MenuItem("Start radio", Icons.Default.Radio) { open = false; actions.startRadio(song) }
            MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { open = false; actions.addToPlaylist(listOf(song.id)) }
            when (dl) {
                null -> MenuItem("Download", Icons.Outlined.ArrowCircleDown) { open = false; actions.download(listOf(song)) }
                DownloadState.DONE -> MenuItem("Remove download", Icons.Default.CheckCircle) { open = false; actions.removeDownload(listOf(song.id)) }
                else -> MenuItem("Cancel download", Icons.Default.Close) { open = false; actions.removeDownload(listOf(song.id)) }
            }
        }
        MenuItem(if (sleepAt != 0L) "Sleep timer (on)" else "Sleep timer", Icons.Outlined.Bedtime, tint = if (sleepAt != 0L) MaterialTheme.colorScheme.primary else Color.White) {
            open = false; sleepPicker = true
        }
        song?.albumId?.let { id -> MenuItem("Go to album", Icons.Default.Album) { open = false; onClose(); actions.openAlbum(id) } }
        song?.artistId?.let { id -> MenuItem("Go to artist", Icons.Default.Person) { open = false; onClose(); actions.openArtist(id) } }
        if (song != null) MenuItem("Song info", Icons.Outlined.Info) { open = false; info = true }
    }
}

/** "Stops in 23 min" / "Stops after this song" under the source label. */
@Composable
internal fun SleepStatus(sleepAt: Long) {
    if (sleepAt == 0L) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAt) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val text = when (sleepAt) {
        PlaybackService.SLEEP_END_OF_SONG -> if (LocalSpokenNow.current != null) "Stops at the end" else "Stops after this song"
        PlaybackService.SLEEP_END_OF_CHAPTER -> "Stops at the end of this chapter"
        else -> "Stops in ${((sleepAt - now) / 60_000 + 1).coerceAtLeast(1)} min"
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Bedtime, null, tint = accent, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
private fun SleepTimerSheet(active: Boolean, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(15, 30, 45, 60).map { "$it minutes" to it * 60_000L } + ("End of this song" to PlaybackService.SLEEP_END_OF_SONG)
    ActionSheet(true, onDismiss, title = "Sleep timer", showCover = false) {
        options.forEach { (label, ms) -> MenuItem(label, Icons.Outlined.Timer) { onPick(ms) } }
        if (active) MenuItem("Turn off timer", Icons.Outlined.TimerOff, tint = MaterialTheme.colorScheme.primary, tintText = true) { onPick(0) }
    }
}
