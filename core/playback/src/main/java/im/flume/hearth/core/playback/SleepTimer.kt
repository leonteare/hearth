@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import im.flume.hearth.core.playback.PlaybackService.Companion.SLEEP_END_OF_CHAPTER
import im.flume.hearth.core.playback.PlaybackService.Companion.SLEEP_END_OF_SONG
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
    val plan = SleepTimerPolicy.plan(ms, spoken = spoken != null)
    if (plan == SleepPlan.END_OF_CHAPTER && spoken != null) {
        val end = spoken.timeline.chapterEndSec(spokenPositionSec())
        sleepAt = SLEEP_END_OF_CHAPTER
        sleepJob = scope.launch {
            while (isActive) {
                val wait = SleepTimerPolicy.chapterCheckMs(end - spokenPositionSec(), player.playbackParameters.speed) ?: break
                delay(wait)
            }
            player.pause()
            sleepAt = 0
            publishExtras()
        }
        publishExtras()
        return
    }
    sleepAt = when (plan) {
        SleepPlan.END_OF_SONG -> SLEEP_END_OF_SONG.also { player.pauseAtEndOfMediaItems = true }
        SleepPlan.AFTER_DELAY -> (System.currentTimeMillis() + ms).also {
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

internal enum class SleepPlan { END_OF_CHAPTER, END_OF_SONG, AFTER_DELAY, CANCEL }

/** The sleep timer's decisions, apart from the player and the clock. */
internal object SleepTimerPolicy {
    /** What a sleep-timer request means. End of chapter only exists for spoken word; for music it cancels. */
    fun plan(ms: Long, spoken: Boolean): SleepPlan = when {
        ms == SLEEP_END_OF_CHAPTER && spoken -> SleepPlan.END_OF_CHAPTER
        ms == SLEEP_END_OF_SONG -> SleepPlan.END_OF_SONG
        ms > 0 -> SleepPlan.AFTER_DELAY
        else -> SleepPlan.CANCEL
    }

    /**
     * With [leftSec] of the chapter to go at [speed], how long to sleep before looking again, or null once
     * it's over. Wakes often enough to stop within a fraction of a second, at any speed.
     */
    fun chapterCheckMs(leftSec: Double, speed: Float): Long? =
        if (leftSec <= 0.05) null
        else ((leftSec / speed.coerceAtLeast(0.1f)) * 1000).toLong().coerceIn(20, 500)

    /** The player paused itself at the end of a song because of the end-of-song timer, which is now spent. */
    fun endOfSongReached(playWhenReady: Boolean, reason: Int, sleepAt: Long): Boolean =
        !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAt == SLEEP_END_OF_SONG
}
