@file:OptIn(UnstableApi::class)

package im.flume.hearth.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import im.flume.hearth.playback.PlaybackService.Companion.SLEEP_END_OF_CHAPTER
import im.flume.hearth.playback.PlaybackService.Companion.SLEEP_END_OF_SONG
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * The sleep timer on PlaybackService: pause after a set time, at the end of the song, or (spoken
 * word) at the end of the chapter. The time it fires is published to the UI through the session extras.
 */

/**
 * [ms] > 0: pause after that long; [SLEEP_END_OF_SONG]: pause when this song ends;
 * [SLEEP_END_OF_CHAPTER]: pause at the end of the chapter playing (spoken word; the end of the
 * episode or file when there are no chapters); 0: cancel.
 */
internal fun PlaybackService.setSleepTimer(ms: Long) {
    sleepJob?.cancel()
    player.pauseAtEndOfMediaItems = false
    val spoken = spokenNow
    if (ms == SLEEP_END_OF_CHAPTER && spoken != null) {
        val end = spoken.timeline.chapterEndSec(spokenPositionSec())
        sleepAt = SLEEP_END_OF_CHAPTER
        sleepJob = scope.launch {
            while (isActive) {
                val left = end - spokenPositionSec()
                if (left <= 0.05) break
                // Wake often enough to stop within a fraction of a second, at any speed.
                delay(((left / player.playbackParameters.speed.coerceAtLeast(0.1f)) * 1000).toLong().coerceIn(20, 500))
            }
            player.pause()
            sleepAt = 0
            publishExtras()
        }
        publishExtras()
        return
    }
    sleepAt = when {
        ms == SLEEP_END_OF_SONG -> SLEEP_END_OF_SONG.also { player.pauseAtEndOfMediaItems = true }
        ms > 0 -> (System.currentTimeMillis() + ms).also {
            sleepJob = scope.launch {
                delay(ms)
                player.pause()
                sleepAt = 0
                publishExtras()
            }
        }
        else -> 0
    }
    publishExtras()
}
