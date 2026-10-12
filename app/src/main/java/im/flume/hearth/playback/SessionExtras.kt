@file:OptIn(UnstableApi::class)

package im.flume.hearth.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import im.flume.hearth.playback.PlaybackService.Companion.CMD_LIKE
import im.flume.hearth.playback.PlaybackService.Companion.CMD_SKIP_BACK
import im.flume.hearth.playback.PlaybackService.Companion.CMD_SKIP_FORWARD
import im.flume.hearth.playback.PlaybackService.Companion.CMD_TOGGLE_SHUFFLE
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_HAS_MUSIC_QUEUE
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_PENDING_COUNT
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_SHUFFLE
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_SLEEP_AT
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_SOURCE_LABEL
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_SPEED
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_SPOKEN_EPISODE
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_SPOKEN_ITEM
import im.flume.hearth.playback.PlaybackService.Companion.EXTRA_SPOKEN_TOTAL_MS
import kotlinx.coroutines.launch

/*
 * What PlaybackService tells the outside world beyond the player itself: the Like / Shuffle (or
 * back 15 s / forward 30 s) buttons on the lock screen and notification, and the session extras the
 * app's UI reads (shuffle, source, sleep timer, spoken-word state, waiting songs).
 */

/** Two extra buttons next to play/skip on the lock screen and notification: Like and Shuffle. */
internal fun PlaybackService.updateMediaButtons() {
    if (spokenNow != null) {
        // Spoken word: back 15 s / forward 30 s in place of previous / next.
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setDisplayName("Back 15 seconds")
            .setSessionCommand(SessionCommand(CMD_SKIP_BACK, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setSessionCommand(SessionCommand(CMD_SKIP_FORWARD, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        session.setMediaButtonPreferences(listOf(back, forward))
        return
    }
    val like = CommandButton.Builder(if (currentLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
        .setDisplayName(if (currentLiked) "Unlike" else "Like")
        .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
        .build()
    val shuffle = CommandButton.Builder(if (shuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
        .setDisplayName(if (shuffled) "Shuffle off" else "Shuffle on")
        .setSessionCommand(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
        .build()
    session.setMediaButtonPreferences(listOf(like, shuffle))
}

internal fun PlaybackService.toggleLike() {
    val id = player.currentMediaItem?.mediaId ?: return
    val liked = !currentLiked
    currentLiked = liked
    updateMediaButtons()
    scope.launch { runCatching { c.library.setStarred(id, liked) } }
}

internal fun PlaybackService.publishExtras() {
    updateMediaButtons()
    session.setSessionExtras(Bundle().apply {
        putBoolean(EXTRA_SHUFFLE, shuffled)
        putString(EXTRA_SOURCE_LABEL, sourceLabel)
        putInt(EXTRA_PENDING_COUNT, pending.size)
        putLong(EXTRA_SLEEP_AT, sleepAt)
        spokenNow?.let { s ->
            putString(EXTRA_SPOKEN_ITEM, s.libraryItemId)
            putString(EXTRA_SPOKEN_EPISODE, s.episodeId)
            putLong(EXTRA_SPOKEN_TOTAL_MS, (s.timeline.durationSec * 1000).toLong())
            putFloat(EXTRA_SPEED, player.playbackParameters.speed)
        }
        putBoolean(EXTRA_HAS_MUSIC_QUEUE, spokenNow != null && store.hasMusic())
    })
    c.queueInfo.value = QueueInfo(pending.toList(), trimmed)
}
