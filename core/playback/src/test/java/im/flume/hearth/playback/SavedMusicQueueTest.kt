package im.flume.hearth.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Play next" / "Add to queue" while a podcast or audiobook plays go into the music queue set aside for later. */
class SavedMusicQueueTest {
    private val saved = SavedQueue(
        window = listOf("a", "b", "q1", "c"),
        manual = listOf(false, false, true, false),
        index = 1, positionMs = 42_000,
        pending = listOf("d"), context = listOf("a", "b", "c", "d"),
        shuffled = false, label = "Album", repeatMode = 0,
    )

    @Test
    fun `play next goes straight after the song that was playing`() {
        val q = QueuePlanner.insertIntoSaved(saved, listOf("x", "y"), next = true)
        assertEquals(listOf("a", "b", "x", "y", "q1", "c"), q.window)
        assertEquals(listOf(false, false, true, true, true, false), q.manual)
        assertEquals(1, q.index)
        assertEquals(42_000, q.positionMs)
        assertEquals(listOf("d"), q.pending)
        assertEquals("Album", q.label)
    }

    @Test
    fun `add to queue goes after songs already queued by hand`() {
        val q = QueuePlanner.insertIntoSaved(saved, listOf("x"), next = false)
        assertEquals(listOf("a", "b", "q1", "x", "c"), q.window)
        assertEquals(listOf(false, false, true, true, false), q.manual)
    }

    @Test
    fun `with no music queue the songs become one`() {
        val q = QueuePlanner.insertIntoSaved(null, listOf("x", "y"), next = false)
        assertEquals(listOf("x", "y"), q.window)
        assertEquals(listOf(true, true), q.manual)
        assertEquals(0, q.index)
        assertEquals(0, q.positionMs)
        assertEquals(null, q.spokenItemId)
    }

    @Test
    fun `a short manual list doesn't break the insert`() {
        val q = QueuePlanner.insertIntoSaved(saved.copy(manual = emptyList()), listOf("x"), next = false)
        assertEquals(listOf("a", "b", "x", "q1", "c"), q.window)
        assertEquals(5, q.manual.size)
    }
}
