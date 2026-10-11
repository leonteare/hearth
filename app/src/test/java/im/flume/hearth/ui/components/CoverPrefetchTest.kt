package im.flume.hearth.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverPrefetchTest {
    @Test
    fun `the next two and the previous one are warmed, nearest first`() {
        assertEquals(listOf(6, 7, 4), CoverPrefetch.neighbours(current = 5, count = 10, repeatAll = false))
    }

    @Test
    fun `nothing past either end of the queue without repeat`() {
        assertEquals(listOf(1, 2), CoverPrefetch.neighbours(current = 0, count = 10, repeatAll = false))
        assertEquals(listOf(8), CoverPrefetch.neighbours(current = 9, count = 10, repeatAll = false))
    }

    @Test
    fun `repeat all wraps round the ends`() {
        assertEquals(listOf(0, 1, 8), CoverPrefetch.neighbours(current = 9, count = 10, repeatAll = true))
        assertEquals(listOf(1, 2, 9), CoverPrefetch.neighbours(current = 0, count = 10, repeatAll = true))
    }

    @Test
    fun `a short repeating queue never lists the current song or a song twice`() {
        assertEquals(listOf(1), CoverPrefetch.neighbours(current = 0, count = 2, repeatAll = true))
        assertEquals(emptyList<Int>(), CoverPrefetch.neighbours(current = 0, count = 1, repeatAll = true))
        assertEquals(emptyList<Int>(), CoverPrefetch.neighbours(current = 3, count = 2, repeatAll = false))
    }

    @Test
    fun `covers already showing or shared by an album are fetched once or not at all`() {
        val ids = listOf("a", "b", "b", "b", null, "c")
        // Current is the 2nd "b": next are "b" (showing) and null, previous is "b" too.
        assertEquals(emptyList<String>(), CoverPrefetch.covers(ids, current = 2, repeatAll = false))
        assertEquals(listOf("b"), CoverPrefetch.covers(ids, current = 0, repeatAll = false))
        assertEquals(listOf("c", "b"), CoverPrefetch.covers(listOf("b", "x", "c"), current = 1, repeatAll = false))
    }
}
