package im.flume.hearth.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueuePlannerTest {
    private val library = (1..7000).map { "s$it" }

    @Test
    fun `shuffle all puts only a small window in the player`() {
        val plan = QueuePlanner.initial(library, startId = null, shuffle = true, random = Random(1))
        assertEquals(QueuePlanner.INITIAL_AHEAD + 1, plan.window.size)
        assertEquals(0, plan.startIndex)
        assertEquals(library.size, plan.window.size + plan.pending.size)
        assertEquals(library.toSet(), (plan.window + plan.pending).toSet())
    }

    @Test
    fun `shuffle with a chosen song plays that song first`() {
        val plan = QueuePlanner.initial(library, startId = "s4321", shuffle = true, random = Random(2))
        assertEquals("s4321", plan.window[plan.startIndex])
        assertEquals(library.size, (plan.window + plan.pending).toSet().size)
    }

    @Test
    fun `in-order play starts at the tapped song and keeps some history`() {
        val album = (1..12).map { "t$it" }
        val plan = QueuePlanner.initial(album, startId = "t5", shuffle = false)
        assertEquals(album, plan.window)
        assertEquals(4, plan.startIndex)
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun `in-order play deep in a long list trims history and defers the tail`() {
        val plan = QueuePlanner.initial(library, startId = "s3000", shuffle = false)
        assertEquals("s3000", plan.window[plan.startIndex])
        assertEquals(QueuePlanner.HISTORY_BEHIND, plan.startIndex)
        assertEquals("s3051", plan.pending.first())
        assertEquals("s7000", plan.pending.last())
    }

    @Test
    fun `empty source gives empty plan`() {
        val plan = QueuePlanner.initial(emptyList(), null, shuffle = true)
        assertTrue(plan.window.isEmpty())
    }

    @Test
    fun `toggling shuffle off continues in original order after the current song`() {
        val ctx = (1..10).map { "x$it" }
        assertEquals(listOf("x8", "x9", "x10"), QueuePlanner.upcomingAfterToggle(ctx, "x7", shuffle = false))
        val on = QueuePlanner.upcomingAfterToggle(ctx, "x7", shuffle = true, random = Random(3))
        assertEquals(9, on.size)
        assertFalse("x7" in on)
    }

    @Test
    fun `refill and trim thresholds`() {
        assertFalse(QueuePlanner.needsRefill(windowSize = 51, currentIndex = 0))
        assertTrue(QueuePlanner.needsRefill(windowSize = 51, currentIndex = 45))
        assertEquals(0, QueuePlanner.trimCount(10))
        assertEquals(31, QueuePlanner.trimCount(61))
    }

    @Test
    fun `add to queue goes after songs already queued by hand`() {
        val manual = setOf(3, 4)
        assertEquals(5, QueuePlanner.addToQueueIndex(currentIndex = 2, isManual = { it in manual }, windowSize = 10))
        assertEquals(3, QueuePlanner.addToQueueIndex(currentIndex = 2, isManual = { false }, windowSize = 10))
    }
}
