package im.flume.hearth.core.ui.screens

// Shared player helpers: where in the song (or episode / book) we are (polled only while it's on screen) and the playing cover.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.playback.EXTRA_COVER_ART
import im.flume.hearth.core.playback.PlayerUiState
import kotlinx.coroutines.delay

val PlayerUiState.coverArt: String? get() = current?.mediaMetadata?.extras?.getString(EXTRA_COVER_ART)

/**
 * Polls [read] (a playback position) 4 times a second while playing, once a second while paused,
 * and not at all while the app is in the background, where nothing is drawn. Hand the state to
 * whatever draws it rather than reading it at the top of a big composable: each new value only
 * redraws what reads it.
 */
@Composable
internal fun rememberPolledPosition(state: PlayerUiState, read: () -> Long): LongState {
    val pos = remember { mutableLongStateOf(read()) }
    val latestRead by rememberUpdatedState(read)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playing = state.isPlaying
    LaunchedEffect(state.current?.mediaId, playing, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                pos.longValue = latestRead()
                delay(if (playing) 250 else 1000)
            }
        }
    }
    return pos
}

/** Position in the current song, polled while visible (see [rememberPolledPosition]). */
@Composable
fun rememberPositionState(state: PlayerUiState): LongState {
    val player = LocalContext.current.container.player
    return rememberPolledPosition(state) { player.positionMs }
}

/** The current value of [rememberPositionState], for screens that redraw with it anyway (the seek bar, lyrics). */
@Composable
fun rememberPosition(state: PlayerUiState): Long = rememberPositionState(state).longValue

/** Position on the whole episode / book (not the current file), polled while visible (see [rememberPolledPosition]). */
@Composable
fun rememberSpokenPositionState(state: PlayerUiState): LongState {
    val player = LocalContext.current.container.player
    return rememberPolledPosition(state) { player.spokenPositionMs }
}

/** The current value of [rememberSpokenPositionState], for screens that redraw with it anyway. */
@Composable
fun rememberSpokenPosition(state: PlayerUiState): Long = rememberSpokenPositionState(state).longValue
