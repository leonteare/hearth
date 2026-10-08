package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixTest {
    @Test
    fun `similar artists end up in the same mix and everyone is used once`() {
        val top = listOf("rockA", "popA", "rockB", "jazzA", "popB", "rockC")
        val similar = mapOf("rockA" to setOf("rockB", "rockC"), "popA" to setOf("popB"))
        val groups = MixRepository.groupArtists(top, similar)
        assertEquals(3, groups.size)
        assertEquals(listOf("rockA", "rockB"), groups[0].take(2))
        assertTrue("popB" in groups[1])
        assertEquals(top.toSet(), groups.flatten().toSet())
        assertEquals(top.size, groups.flatten().size)
    }

    @Test
    fun `without similarity data artists are dealt out by rank`() {
        val groups = MixRepository.groupArtists(listOf("a", "b", "c", "d", "e", "f"), emptyMap())
        assertEquals(listOf(listOf("a", "d"), listOf("b", "e"), listOf("c", "f")), groups)
    }
}
