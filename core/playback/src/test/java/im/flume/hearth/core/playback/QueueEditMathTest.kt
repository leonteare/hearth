package im.flume.hearth.core.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditMathTest {
    /** What Player.moveMediaItem does: take the item out, then insert it at [to] in the shorter list. */
    private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
        toMutableList().apply { add(to, removeAt(from)) }

    private fun moveAfter(queue: List<String>, current: Int, entry: String, afterEntry: String?): List<String> {
        val from = queue.indexOf(entry)
        val to = QueueEditMath.moveAfterTarget(from, current, afterEntry?.let(queue::indexOf) ?: -1) ?: return queue
        return if (to == from) queue else queue.moved(from, to)
    }

    @Test
    fun `play next goes straight after the current song`() {
        assertEquals(4, QueueEditMath.insertIndex(next = true, current = 3, size = 10) { true })
    }

    @Test
    fun `add to queue goes after the songs already queued by hand`() {
        val manual = setOf(4, 5)
        assertEquals(6, QueueEditMath.insertIndex(next = false, current = 3, size = 10) { it in manual })
        assertEquals(4, QueueEditMath.insertIndex(next = false, current = 3, size = 10) { false })
        assertEquals(10, QueueEditMath.insertIndex(next = false, current = 3, size = 10) { true })
    }

    @Test
    fun `dragging a song down puts it straight after the one dropped on`() {
        val queue = listOf("now", "a", "b", "c", "d")
        assertEquals(listOf("now", "b", "c", "a", "d"), moveAfter(queue, 0, entry = "a", afterEntry = "c"))
    }

    @Test
    fun `dragging a song up puts it straight after the one dropped on`() {
        val queue = listOf("now", "a", "b", "c", "d")
        assertEquals(listOf("now", "a", "d", "b", "c"), moveAfter(queue, 0, entry = "d", afterEntry = "a"))
    }

    @Test
    fun `dropping on the current song or on a gone song puts it next`() {
        val queue = listOf("played", "now", "a", "b", "c")
        assertEquals(listOf("played", "now", "c", "a", "b"), moveAfter(queue, 1, entry = "c", afterEntry = "now"))
        assertEquals(listOf("played", "now", "c", "a", "b"), moveAfter(queue, 1, entry = "c", afterEntry = null))
        // A song that has already played isn't a place in what's coming up.
        assertEquals(listOf("played", "now", "c", "a", "b"), moveAfter(queue, 1, entry = "c", afterEntry = "played"))
    }

    @Test
    fun `dropping a song where it already is changes nothing`() {
        assertEquals(2, QueueEditMath.moveAfterTarget(from = 2, current = 0, after = 1))
        assertEquals(2, QueueEditMath.moveAfterTarget(from = 2, current = 0, after = 2))
    }

    @Test
    fun `the playing song and songs already played can't be dragged`() {
        assertNull(QueueEditMath.moveAfterTarget(from = 3, current = 3, after = 5))
        assertNull(QueueEditMath.moveAfterTarget(from = 1, current = 3, after = 5))
        assertNull(QueueEditMath.moveAfterTarget(from = -1, current = 0, after = 2))
    }

    @Test
    fun `undo puts a removed song back in front of its old next neighbour`() {
        assertEquals(5, QueueEditMath.restoreIndex(current = 2, before = 5, after = 4))
    }

    @Test
    fun `undo falls back to after the old previous neighbour when the next one is gone`() {
        assertEquals(5, QueueEditMath.restoreIndex(current = 2, before = -1, after = 4))
        // The previous neighbour was the current song: straight after it.
        assertEquals(3, QueueEditMath.restoreIndex(current = 2, before = -1, after = 2))
    }

    @Test
    fun `undo puts the song next when both neighbours have gone or played`() {
        assertEquals(3, QueueEditMath.restoreIndex(current = 2, before = -1, after = -1))
        assertEquals(3, QueueEditMath.restoreIndex(current = 2, before = 1, after = 0))
        assertEquals(3, QueueEditMath.restoreIndex(current = 2, before = 2, after = 1))
    }

    private val ids = listOf("x", "a", "b", "a", "c", "a")

    @Test
    fun `undoing add to queue removes the newest hand-queued copy of each song`() {
        val manual = setOf(1, 3, 4, 5)
        val removed = QueueEditMath.queuedCopiesToRemove(0, ids.size, listOf("a", "c"), ids::get) { it in manual }
        assertEquals(listOf(5, 4), removed)
    }

    @Test
    fun `undo removes as many copies as were queued`() {
        val removed = QueueEditMath.queuedCopiesToRemove(0, ids.size, listOf("a", "a"), ids::get) { true }
        assertEquals(listOf(5, 3), removed)
    }

    @Test
    fun `undo leaves songs that came from the source and songs already played`() {
        val manual = setOf(1)
        assertEquals(listOf(1), QueueEditMath.queuedCopiesToRemove(0, ids.size, listOf("a"), ids::get) { it in manual })
        // The only manual "a" is the one playing: nothing to remove.
        assertTrue(QueueEditMath.queuedCopiesToRemove(1, ids.size, listOf("a"), ids::get) { it in manual }.isEmpty())
    }

    @Test
    fun `undo indexes come highest first so removing them in order is safe`() {
        val removed = QueueEditMath.queuedCopiesToRemove(0, ids.size, listOf("a", "b", "c"), ids::get) { true }
        assertEquals(removed.sortedDescending(), removed)
        val left = ids.toMutableList().apply { removed.forEach { removeAt(it) } }
        assertEquals(listOf("x", "a", "a"), left)
    }

    @Test
    fun `turning shuffle on keeps the hand-queued songs right after the current one`() {
        val manual = setOf(4, 5)
        assertEquals(listOf(9, 8, 7, 6), QueueEditMath.replacedOnShuffle(current = 3, size = 10) { it in manual })
    }

    @Test
    fun `turning shuffle on with nothing queued by hand replaces everything after the current song`() {
        assertEquals((9 downTo 4).toList(), QueueEditMath.replacedOnShuffle(current = 3, size = 10) { false })
        assertEquals(emptyList<Int>(), QueueEditMath.replacedOnShuffle(current = 9, size = 10) { false })
    }

    @Test
    fun `repeat all with nothing waiting starts the source over`() {
        assertTrue(QueueEditMath.startOverOnRepeat(pendingEmpty = true, Player.REPEAT_MODE_ALL, hasSource = true))
    }

    @Test
    fun `without repeat all or with songs still waiting nothing starts over`() {
        assertFalse(QueueEditMath.startOverOnRepeat(pendingEmpty = true, Player.REPEAT_MODE_OFF, hasSource = true))
        assertFalse(QueueEditMath.startOverOnRepeat(pendingEmpty = true, Player.REPEAT_MODE_ONE, hasSource = true))
        assertFalse(QueueEditMath.startOverOnRepeat(pendingEmpty = false, Player.REPEAT_MODE_ALL, hasSource = true))
    }

    @Test
    fun `hand-built queues with no source don't start over`() {
        assertFalse(QueueEditMath.startOverOnRepeat(pendingEmpty = true, Player.REPEAT_MODE_ALL, hasSource = false))
    }

    @Test
    fun `offline only songs on the phone are brought forward and the rest keep their places`() {
        val pending = listOf("a", "B", "c", "D", "E", "f")
        val (taken, rest) = QueueEditMath.take(pending, max = 2) { it[0].isUpperCase() }
        assertEquals(listOf("B", "D"), taken)
        assertEquals(listOf("a", "c", "E", "f"), rest.toList())
    }

    @Test
    fun `taking from the waiting songs never takes more than asked`() {
        val (taken, rest) = QueueEditMath.take(listOf("a", "b", "c"), max = 0) { true }
        assertTrue(taken.isEmpty())
        assertEquals(listOf("a", "b", "c"), rest.toList())
    }

    @Test
    fun `with nothing on the phone nothing is taken`() {
        val (taken, rest) = QueueEditMath.take(listOf("a", "b"), max = 5) { false }
        assertTrue(taken.isEmpty())
        assertEquals(listOf("a", "b"), rest.toList())
    }

    @Test
    fun `shuffling keeps a hand-queued song even when it sits after source songs`() {
        // 0 current, 1 source, 2 hand-queued (e.g. after a drag), 3 source.
        assertEquals(listOf(3, 1), QueueEditMath.replacedOnShuffle(current = 0, size = 4) { it == 2 })
    }
}
