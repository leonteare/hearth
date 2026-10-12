package im.flume.hearth.core.playback

import androidx.media3.common.Player
import im.flume.hearth.core.playback.PlaybackService.Companion.SLEEP_END_OF_CHAPTER
import im.flume.hearth.core.playback.PlaybackService.Companion.SLEEP_END_OF_SONG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerPolicyTest {
    @Test
    fun `a time pauses after that long`() {
        assertEquals(SleepPlan.AFTER_DELAY, SleepTimerPolicy.plan(30 * 60_000L, spoken = false))
        assertEquals(SleepPlan.AFTER_DELAY, SleepTimerPolicy.plan(1L, spoken = true))
    }

    @Test
    fun `end of song works for music and spoken word`() {
        assertEquals(SleepPlan.END_OF_SONG, SleepTimerPolicy.plan(SLEEP_END_OF_SONG, spoken = false))
        assertEquals(SleepPlan.END_OF_SONG, SleepTimerPolicy.plan(SLEEP_END_OF_SONG, spoken = true))
    }

    @Test
    fun `end of chapter only means something for spoken word`() {
        assertEquals(SleepPlan.END_OF_CHAPTER, SleepTimerPolicy.plan(SLEEP_END_OF_CHAPTER, spoken = true))
        assertEquals(SleepPlan.CANCEL, SleepTimerPolicy.plan(SLEEP_END_OF_CHAPTER, spoken = false))
    }

    @Test
    fun `zero or anything unknown cancels the timer`() {
        assertEquals(SleepPlan.CANCEL, SleepTimerPolicy.plan(0L, spoken = false))
        assertEquals(SleepPlan.CANCEL, SleepTimerPolicy.plan(-7L, spoken = true))
    }

    @Test
    fun `the chapter timer stops once the chapter is over`() {
        assertNull(SleepTimerPolicy.chapterCheckMs(leftSec = 0.05, speed = 1f))
        assertNull(SleepTimerPolicy.chapterCheckMs(leftSec = 0.0, speed = 1f))
        assertNull(SleepTimerPolicy.chapterCheckMs(leftSec = -3.0, speed = 1f))
    }

    @Test
    fun `far from the end the chapter timer checks twice a second`() {
        assertEquals(500L, SleepTimerPolicy.chapterCheckMs(leftSec = 600.0, speed = 1f))
        assertEquals(500L, SleepTimerPolicy.chapterCheckMs(leftSec = 600.0, speed = 3f))
    }

    @Test
    fun `near the end the chapter timer wakes just in time for the speed`() {
        assertEquals(250L, SleepTimerPolicy.chapterCheckMs(leftSec = 0.25, speed = 1f))
        assertEquals(125L, SleepTimerPolicy.chapterCheckMs(leftSec = 0.25, speed = 2f))
    }

    @Test
    fun `the chapter timer never spins faster than every 20 ms`() {
        assertEquals(20L, SleepTimerPolicy.chapterCheckMs(leftSec = 0.06, speed = 3f))
    }

    @Test
    fun `a zero speed doesn't stall the chapter timer`() {
        assertEquals(500L, SleepTimerPolicy.chapterCheckMs(leftSec = 0.1, speed = 0f))
    }

    @Test
    fun `the end of song timer is spent when the player pauses at the end of the song`() {
        assertTrue(SleepTimerPolicy.endOfSongReached(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, SLEEP_END_OF_SONG))
    }

    @Test
    fun `pausing by hand leaves the end of song timer set`() {
        assertFalse(SleepTimerPolicy.endOfSongReached(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, SLEEP_END_OF_SONG))
        assertFalse(SleepTimerPolicy.endOfSongReached(true, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, SLEEP_END_OF_SONG))
    }

    @Test
    fun `other timers aren't touched by the end of a song`() {
        assertFalse(SleepTimerPolicy.endOfSongReached(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, 0L))
        assertFalse(SleepTimerPolicy.endOfSongReached(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, 1_700_000_000_000L))
        assertFalse(SleepTimerPolicy.endOfSongReached(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM, SLEEP_END_OF_CHAPTER))
    }
}
