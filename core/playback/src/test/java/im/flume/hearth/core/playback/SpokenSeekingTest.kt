package im.flume.hearth.core.playback

import im.flume.hearth.domain.spoken.ResumeNotice
import im.flume.hearth.domain.spoken.SpokenTimeline
import im.flume.hearth.domain.spoken.StartPoint
import im.flume.hearth.domain.spoken.TimelineTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenSeekingTest {
    /** A book in three files: 0–100 s, 100–250 s, 250–300 s. */
    private val book = SpokenTimeline(listOf(TimelineTrack("1", 100.0), TimelineTrack("2", 150.0), TimelineTrack("3", 50.0)))

    @Test
    fun `seeking lands in the right file at the right offset`() {
        assertEquals(0 to 42_000L, SpokenSeeking.locate(book, 42.0))
        assertEquals(1 to 20_500L, SpokenSeeking.locate(book, 120.5))
        assertEquals(2 to 10_000L, SpokenSeeking.locate(book, 260.0))
    }

    @Test
    fun `a file boundary is the start of the next file`() {
        assertEquals(1 to 0L, SpokenSeeking.locate(book, 100.0))
    }

    @Test
    fun `skipping back past the start stops at the start`() {
        assertEquals(0 to 0L, SpokenSeeking.locate(book, 10.0 - PlaybackService.SKIP_BACK_SEC - 5))
    }

    @Test
    fun `skipping forward past the end stops at the end of the last file`() {
        assertEquals(2 to 50_000L, SpokenSeeking.locate(book, 290.0 + PlaybackService.SKIP_FORWARD_SEC))
    }

    @Test
    fun `speed stays within what the player offers`() {
        assertEquals(PlaybackService.MIN_SPEED, SpokenSeeking.speed(0.1f), 0f)
        assertEquals(PlaybackService.MAX_SPEED, SpokenSeeking.speed(10f), 0f)
        assertEquals(1.5f, SpokenSeeking.speed(1.5f), 0f)
    }

    private val notice = ResumeNotice("book", null, "Book", fromSec = 10.0, toSec = 500.0)

    @Test
    fun `a newer position from another device moves playback there`() {
        assertTrue(SpokenSeeking.jumpToServer(StartPoint(500.0, finished = false, notice = null, serverWon = true), hereSec = 10.0))
    }

    @Test
    fun `a position the user is told about is always jumped to`() {
        assertTrue(SpokenSeeking.jumpToServer(StartPoint(11.0, finished = false, notice = notice, serverWon = false), hereSec = 10.0))
    }

    @Test
    fun `a server position within two seconds isn't worth a jump`() {
        assertFalse(SpokenSeeking.jumpToServer(StartPoint(12.0, finished = false, notice = null, serverWon = true), hereSec = 10.0))
        assertTrue(SpokenSeeking.jumpToServer(StartPoint(12.5, finished = false, notice = null, serverWon = true), hereSec = 10.0))
    }

    @Test
    fun `the phone's own newer position stays`() {
        assertFalse(SpokenSeeking.jumpToServer(StartPoint(500.0, finished = false, notice = null, serverWon = false), hereSec = 10.0))
    }

    @Test
    fun `something finished elsewhere doesn't move playback`() {
        assertFalse(SpokenSeeking.jumpToServer(StartPoint(0.0, finished = true, notice = notice, serverWon = true), hereSec = 10.0))
    }
}
