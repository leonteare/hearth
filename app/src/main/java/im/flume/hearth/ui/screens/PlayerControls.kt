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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import im.flume.hearth.container
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.Usage
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.PlayPauseIcon
import im.flume.hearth.ui.components.formatDuration
import im.flume.hearth.ui.theme.TextSecondary

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
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

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
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val starred = song?.starred == true
        IconButton(onClick = { actions.setStarred(songId, !starred) }) {
            Icon(if (starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (starred) "Unlike" else "Like", tint = if (starred) accent else Color.White)
        }
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }

    val duration = state.durationMs.coerceAtLeast(1)
    Slider(
        value = if (dragging) dragValue else (pos.toFloat() / duration).coerceIn(0f, 1f),
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = { player.seekTo((dragValue * duration).toLong()); dragging = false },
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
    )
    Row {
        Text(formatDuration((if (dragging) (dragValue * duration).toLong() else pos) / 1000), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(state.durationMs / 1000), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { player.toggleShuffle() }) {
            Icon(Icons.Default.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", tint = if (state.shuffle) accent else Color.White)
        }
        IconButton(onClick = { player.previous() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(40.dp)) }
        FilledIconButton(
            onClick = { player.playPause() },
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            PlayPauseIcon(state.isPlaying, Modifier.size(40.dp))
        }
        IconButton(onClick = { player.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(40.dp)) }
        IconButton(onClick = { player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) Color.White else accent,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val usage = LocalContext.current.container.usage
        Row {
            IconButton(onClick = { if (!showLyrics) usage.track(Usage.LYRICS); onToggleLyrics() }) {
                Icon(if (showLyrics) Icons.Filled.Mic else Icons.Outlined.Mic, if (showLyrics) "Hide lyrics" else "Show lyrics",tint = if (showLyrics) accent else Color.White)
            }
            IconButton(onClick = onOpenVisualiser) { Icon(Icons.Default.GraphicEq, "Visualiser") }
        }
        IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
    }
}
