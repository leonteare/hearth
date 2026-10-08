package im.flume.hearth.sync

import im.flume.hearth.sync.LibrarySync.Companion.looksComplete
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {
    @Test
    fun `an empty server never replaces a library`() {
        assertFalse(looksComplete(remoteCount = 0, localCount = 7000))
        assertFalse(looksComplete(remoteCount = 0, localCount = 5))
    }

    @Test
    fun `first sync of an empty server is fine`() {
        assertTrue(looksComplete(remoteCount = 0, localCount = 0))
    }

    @Test
    fun `a large library that more than halves is rejected`() {
        assertFalse(looksComplete(remoteCount = 3000, localCount = 7000))
        assertFalse(looksComplete(remoteCount = 12, localCount = 7000))
    }

    @Test
    fun `normal changes go through`() {
        assertTrue(looksComplete(remoteCount = 3500, localCount = 7000))
        assertTrue(looksComplete(remoteCount = 6950, localCount = 7000))
        assertTrue(looksComplete(remoteCount = 9000, localCount = 7000))
        assertTrue(looksComplete(remoteCount = 7000, localCount = 0))
    }

    @Test
    fun `small libraries may shrink a lot`() {
        assertTrue(looksComplete(remoteCount = 20, localCount = 200))
        assertTrue(looksComplete(remoteCount = 1, localCount = 150))
    }
}
