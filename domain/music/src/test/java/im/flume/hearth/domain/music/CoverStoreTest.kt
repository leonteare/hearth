// Cover file naming and the sweep that drops art no download needs any more.
package im.flume.hearth.domain.music

import im.flume.hearth.domain.music.download.CoverStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverStoreTest {

    @Test
    fun `a cover id becomes a name a file system accepts`() {
        val name = CoverStore.fileName("al-1234/../etc passwd")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(' '))
        assertTrue(name.startsWith("al-1234"))
    }

    @Test
    fun `two ids that differ only in stripped characters get different names`() {
        assertNotEquals(CoverStore.fileName("mf-1/2"), CoverStore.fileName("mf-1-2"))
    }

    @Test
    fun `the same id always gives the same name`() {
        assertEquals(CoverStore.fileName("al-99"), CoverStore.fileName("al-99"))
    }

    @Test
    fun `a very long id still makes a sensible name`() {
        val name = CoverStore.fileName("x".repeat(500))
        assertTrue(name.length < 80)
    }

    @Test
    fun `sweeping keeps the covers still in use and drops the rest`() {
        val keep = setOf("a", "b")
        assertEquals(listOf("c"), CoverStore.orphans(listOf("a", "b", "c"), keep))
    }

    @Test
    fun `half written files are always swept, even for a cover still in use`() {
        assertEquals(listOf("a.part"), CoverStore.orphans(listOf("a", "a.part"), setOf("a")))
    }

    @Test
    fun `nothing downloaded means every cover goes`() {
        assertEquals(listOf("a", "b"), CoverStore.orphans(listOf("a", "b"), emptySet()))
    }
}
