package im.flume.hearth.ui.screens

// Now Playing's lower half for songs: title / artist / like, seek bar, transport buttons, lyrics, visualiser and queue.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import im.flume.hearth.container
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.Usage
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.ui.components.BigPlayButton
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.PlayerSlider
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.components.rememberSeekState
import im.flume.hearth.ui.theme.HearthTheme

@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    song: SongEntity?,
    onClose: () -> Unit,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenVisualiser: () -> Unit,
) {
    val actions = LocalActions.current
    val accent = MaterialTheme.colorScheme.primary
    val player = LocalContext.current.container.player
    val current = state.current
    val meta = current?.mediaMetadata ?: return
    val songId = current.mediaId
    val pos = rememberPosition(state)
    val seek = rememberSeekState()
    val colors = HearthTheme.colors
    val icons = HearthTheme.icons
    val spacing = HearthTheme.spacing

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(meta.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = song?.artistId != null, role = Role.Button, onClickLabel = "Go to artist") {
                        onClose(); song?.artistId?.let(actions::openArtist)
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    meta.artist?.toString().orEmpty(),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val starred = song?.starred == true
        IconButton(onClick = { actions.setStarred(songId, !starred) }) {
            Icon(if (starred) icons.likeOn else icons.like, if (starred) "Unlike" else "Like", tint = if (starred) accent else colors.text)
        }
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }

    val duration = state.durationMs.coerceAtLeast(1)
    PlayerSlider(
        fraction = pos.toFloat() / duration,
        seek = seek,
        onSeek = { player.seekTo((it * duration).toLong()) },
    )
    Row {
        Text(formatDuration((if (seek.dragging) (seek.value * duration).toLong() else pos) / 1000), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(state.durationMs / 1000), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }

    Row(Modifier.fillMaxWidth().padding(vertical = spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { player.toggleShuffle() }) {
            Icon(icons.shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", tint = if (state.shuffle) accent else colors.text)
        }
        IconButton(onClick = { player.previous() }, Modifier.size(56.dp)) { Icon(icons.previous, "Previous", Modifier.size(40.dp)) }
        BigPlayButton(state.isPlaying, onClick = { player.playPause() })
        IconButton(onClick = { player.next() }, Modifier.size(56.dp)) { Icon(icons.next, "Next", Modifier.size(40.dp)) }
        IconButton(onClick = { player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) icons.repeatOne else icons.repeat,
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) colors.text else accent,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val usage = LocalContext.current.container.usage
        Row {
            IconButton(onClick = { if (!showLyrics) usage.track(Usage.LYRICS); onToggleLyrics() }) {
                Icon(if (showLyrics) icons.lyricsOn else icons.lyrics, if (showLyrics) "Hide lyrics" else "Show lyrics", tint = if (showLyrics) accent else colors.text)
            }
            IconButton(onClick = onOpenVisualiser) { Icon(icons.visualiser, "Visualiser") }
        }
        IconButton(onClick = onOpenQueue) { Icon(icons.queue, "Queue") }
    }
}
