package im.flume.hearth.domain.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Queue edits made while something else is going on: songs queued during a podcast, shuffle toggles, odd inputs. */
class QueueEditsTest {
    private fun saved(window: List<String>, manual: List<Boolean> = window.map { false }, index: Int = 0) = SavedQueue(
        window = window, manual = manual, index = index, positionMs = 42_000, pending = listOf("p1"),
        context = window, shuffled = false, label = "Long Winter", repeatMode = 0,
    )

    @Test
    fun `play next during a podcast puts the songs straight after the saved current song`() {
        val out = QueuePlanner.insertIntoSaved(saved(listOf("a", "b", "c"), index = 1), listOf("x", "y"), next = true)
        assertEquals(listOf("a", "b", "x", "y", "c"), out.window)
        assertEquals(listOf(false, false, true, true, false), out.manual)
        // Where you were in the music queue is kept for "Back to music".
        assertEquals(1, out.index)
        assertEquals(42_000L, out.positionMs)
        assertEquals(listOf("p1"), out.pending)
    }

    @Test
    fun `add to queue during a podcast goes after songs already queued by hand`() {
        val q = saved(listOf("a", "m1", "m2", "b"), manual = listOf(false, true, true, false), index = 0)
        val out = QueuePlanner.insertIntoSaved(q, listOf("x"), next = false)
        assertEquals(listOf("a", "m1", "m2", "x", "b"), out.window)
        assertEquals(listOf(false, true, true, true, false), out.manual)
    }

    @Test
    fun `with no music queue set aside, queued songs start a new one`() {
        for (empty in listOf(null, saved(emptyList()))) {
            val out = QueuePlanner.insertIntoSaved(empty, listOf("x", "y"), next = false)
            assertEquals(listOf("x", "y"), out.window)
            assertEquals(listOf(true, true), out.manual)
            assertEquals(0, out.index)
            assertTrue(out.pending.isEmpty())
        }
    }

    @Test
    fun `a saved queue with missing manual flags or an index past the end still takes the songs`() {
        val q = saved(listOf("a", "b"), manual = emptyList(), index = 9)
        val out = QueuePlanner.insertIntoSaved(q, listOf("x"), next = true)
        assertEquals(listOf("a", "b", "x"), out.window)
        assertEquals(listOf(false, false, true), out.manual)
    }

    @Test
    fun `turning shuffle on never queues the playing song again`() {
        val ctx = (1..20).map { "s$it" }
        val up = QueuePlanner.upcomingAfterToggle(ctx, "s5", shuffle = true, random = Random(7))
        assertEquals(19, up.size)
        assertEquals(ctx.toSet() - "s5", up.toSet())
    }

    @Test
    fun `turning shuffle off when the playing song isn't in the source plays the source from the top`() {
        val ctx = listOf("a", "b", "c")
        assertEquals(ctx, QueuePlanner.upcomingAfterToggle(ctx, "elsewhere", shuffle = false))
        assertEquals(ctx, QueuePlanner.upcomingAfterToggle(ctx, null, shuffle = false))
    }

    @Test
    fun `shuffling from a song that isn't in the list just shuffles the list`() {
        val ids = listOf("a", "b", "c", "d")
        val order = QueuePlanner.order(ids, "zz", shuffle = true, random = Random(1))
        assertEquals(ids.toSet(), order.toSet())
        assertEquals(4, order.size)
    }

    @Test
    fun `spreading artists leaves a one-artist queue alone`() {
        val order = listOf("a1", "a2", "a3")
        assertEquals(order, QueuePlanner.spreadArtists(order) { "same" })
    }

    @Test
    fun `add to queue at the end of the window goes after the last song`() {
        assertEquals(3, QueuePlanner.addToQueueIndex(currentIndex = 2, isManual = { false }, windowSize = 3))
        assertEquals(3, QueuePlanner.addToQueueIndex(currentIndex = 0, isManual = { true }, windowSize = 3))
    }
}
