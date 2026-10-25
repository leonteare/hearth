package im.flume.hearth.domain.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsFilterTest {
    @Test
    fun `a block list is any playlist whose comment carries the marker`() {
        assertTrue(KidsFilter.isBlockList("Songs the kids' player hides. [hearth-not-for-kids]"))
        assertFalse(KidsFilter.isBlockList("Leaves at midnight unless you save it. [hearth-mix]"))
        assertFalse(KidsFilter.isBlockList(null))
    }

    @Test
    fun `songs on every block list are blocked, once`() {
        assertEquals(setOf("a", "b", "c"), KidsFilter.blocked(listOf(listOf("a", "b"), listOf("b", "c"), emptyList())))
        assertEquals(emptySet<String>(), KidsFilter.blocked(emptyList()))
    }

    @Test
    fun `an album stays only while a song of it is left`() {
        assertEquals(setOf("al1", "al2"), KidsFilter.keptAlbumIds(listOf("al1", null, "al2", "al1")))
    }
}
