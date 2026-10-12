package im.flume.hearth.ui.screens

// Now Playing's ⋯ menu for a song, its sleep timer picker, and the "Stops in 23 min" line under the header.

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.domain.music.Usage
import im.flume.hearth.core.playback.PlaybackService
import im.flume.hearth.core.ui.components.ActionSheet
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.LocalRowContext
import im.flume.hearth.core.ui.components.MenuItem
import im.flume.hearth.core.ui.components.SongInfoDialog
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.delay
import im.flume.hearth.core.ui.screens.LocalSpokenNow
import im.flume.hearth.core.ui.screens.coverArt

@Composable
internal fun NowPlayingMenu(song: SongEntity?, sleepAt: Long, onClose: () -> Unit) {
    val actions = LocalActions.current
    val icons = HearthTheme.icons
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
    IconButton(onClick = { open = true }, enabled = song != null) { Icon(icons.moreHorizontal, "More options") }
    ActionSheet(open, { open = false }, title = song?.title, subtitle = song?.artist, coverArt = song?.coverArt, fallback = song?.album) {
        if (song != null) {
            MenuItem("Play next", icons.playNext) { open = false; actions.playNext(listOf(song.id)) }
            MenuItem("Add to queue", icons.queue) { open = false; actions.addToQueue(listOf(song.id)) }
            MenuItem("Start radio", icons.radio) { open = false; actions.startRadio(song) }
            MenuItem("Add to playlist", icons.addToPlaylist) { open = false; actions.addToPlaylist(listOf(song.id)) }
            when (dl) {
                null -> MenuItem("Download", icons.download) { open = false; actions.download(listOf(song)) }
                DownloadState.DONE -> MenuItem("Remove download", icons.downloaded) { open = false; actions.removeDownload(listOf(song.id)) }
                else -> MenuItem("Cancel download", icons.close) { open = false; actions.removeDownload(listOf(song.id)) }
            }
        }
        MenuItem(if (sleepAt != 0L) "Sleep timer (on)" else "Sleep timer", icons.sleepTimer, tint = if (sleepAt != 0L) MaterialTheme.colorScheme.primary else HearthTheme.colors.text) {
            open = false; sleepPicker = true
        }
        song?.albumId?.let { id -> MenuItem("Go to album", icons.album) { open = false; onClose(); actions.openAlbum(id) } }
        song?.artistId?.let { id -> MenuItem("Go to artist", icons.artist) { open = false; onClose(); actions.openArtist(id) } }
        if (song != null) MenuItem("Song info", icons.info) { open = false; info = true }
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
        Icon(HearthTheme.icons.sleepTimer, null, tint = accent, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(HearthTheme.spacing.xs))
        Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
private fun SleepTimerSheet(active: Boolean, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(15, 30, 45, 60).map { "$it minutes" to it * 60_000L } + ("End of this song" to PlaybackService.SLEEP_END_OF_SONG)
    val icons = HearthTheme.icons
    ActionSheet(true, onDismiss, title = "Sleep timer", showCover = false) {
        options.forEach { (label, ms) -> MenuItem(label, icons.timer) { onPick(ms) } }
        if (active) MenuItem("Turn off timer", icons.timerOff, tint = MaterialTheme.colorScheme.primary, tintText = true) { onPick(0) }
    }
}
