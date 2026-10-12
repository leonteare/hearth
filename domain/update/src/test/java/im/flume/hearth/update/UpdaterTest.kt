package im.flume.hearth.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun `version comparison`() {
        assertTrue(Updater.isNewer("1.1.1", "1.1.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("2.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.1.0", "1.1.0"))
        assertFalse(Updater.isNewer("1.0.9", "1.1.0"))
        assertFalse(Updater.isNewer("1.1", "1.1.0"))
    }
}
