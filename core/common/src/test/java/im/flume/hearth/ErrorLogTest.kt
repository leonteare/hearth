package im.flume.hearth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogTest {
    private fun entry(n: Int) = "error $n " + "x".repeat(50) + "\n\n----\n\n"

    @Test
    fun `a log under the limit is kept as it is`() {
        val log = entry(1) + entry(2)
        assertEquals(log, newestLogEntries(log, 1_000))
    }

    @Test
    fun `trimming keeps the newest entries and starts at a whole one`() {
        val log = (1..20).joinToString("") { entry(it) }
        val kept = newestLogEntries(log, 200)
        assertTrue(kept.length <= 200)
        assertTrue(kept.startsWith("error "))
        assertTrue(kept.endsWith(entry(20)))
    }
}
