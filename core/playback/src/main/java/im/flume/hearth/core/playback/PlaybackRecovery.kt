@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * What PlaybackService does when a song fails to play: skip it, jump to something that's on the phone,
 * or pause and try again once the server is back. Decisions come from [PlaybackErrors].
 */

internal suspend fun PlaybackService.recover(action: ErrorAction) {
    val wasPlaying = player.playWhenReady
    val online = c.network.isOnline.value
    val next = if (action == ErrorAction.SKIP) null else nextPlayableIndex()
    when (val r = PlaybackErrors.recover(action, online, next)) {
        Recovery.SkipNext -> {
            consecutiveErrors++
            // Skip songs that won't load, but don't spin through the whole queue if they're all broken.
            if (PlaybackErrors.keepSkipping(consecutiveErrors, player.hasNextMediaItem())) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
        // In the car with patchy signal: keep the music going with what's on the phone.
        is Recovery.JumpTo -> {
            recoveryJob?.cancel()
            player.seekTo(r.index, 0)
            player.prepare()
            if (wasPlaying) player.play()
        }
        // Nothing on the phone left to play: keep the song and position, and pick up when the server's back.
        Recovery.WaitForConnection -> {
            player.pause()
            scheduleRecovery(wasPlaying, waitForConnection = true)
        }
        Recovery.RetryLater -> {
            player.pause()
            scheduleRecovery(wasPlaying, waitForConnection = false)
        }
    }
}

/**
 * Index of the next queued song that plays without a connection. When none is left in the player,
 * the first such song still waiting in [pending] is brought in at the end and its index returned.
 */
private suspend fun PlaybackService.nextPlayableIndex(): Int? {
    PlaybackErrors.nextPlayable(player.currentMediaItemIndex, player.mediaItemCount) {
        isOnPhone(player.getMediaItemAt(it).mediaId)
    }?.let { return it }
    val id = takePending(1, this::isOnPhone).firstOrNull() ?: return null
    val song = c.library.songsByIds(listOf(id)).firstOrNull() ?: return null
    player.addMediaItem(song.toMediaItem())
    publishExtras()
    return player.mediaItemCount - 1
}

/** Moves on to the next song that's on the phone, if there is one (used while offline). */
internal suspend fun PlaybackService.jumpToPlayable() {
    if (isOnPhone(player.currentMediaItem?.mediaId ?: return)) return
    val index = nextPlayableIndex() ?: return
    player.seekTo(index, 0)
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
}

internal fun PlaybackService.httpStatus(error: PlaybackException): Int? =
    generateSequence(error.cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode

/**
 * Re-prepares the player after a network failure: once the server is reachable again, or after a
 * short backoff for other temporary errors. Gives up quietly (staying paused) after a few tries.
 */
private fun PlaybackService.scheduleRecovery(wasPlaying: Boolean, waitForConnection: Boolean) {
    recoveryJob?.cancel()
    val attempt = recoveryAttempts++
    val wait = PlaybackErrors.retryDelayMs(attempt, waitForConnection) ?: return
    recoveryJob = scope.launch {
        if (wait > 0) delay(wait)
        // Spoken word comes from Audiobookshelf, not Navidrome: just retry after the backoff.
        while (spokenNow == null && !c.network.isOnline.value) {
            if (c.network.offlineMode) {
                // Offline mode is the user's choice: no pinging, just wait for it to be switched off.
                c.network.isOnline.first { it }
                break
            }
            // The monitor only re-pings every few minutes while it thinks the server is fine.
            c.network.checkServer()
            withTimeoutOrNull(15_000) { c.network.isOnline.first { it } }
        }
        if (player.playerError == null || player.mediaItemCount == 0) return@launch
        player.prepare()
        if (wasPlaying) player.play()
    }
}
