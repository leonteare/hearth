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

    private val day = 24 * 60 * 60 * 1000L
    private val synced = 100 * day

    private fun needs(
        nowMs: Long = synced + day,
        remote: String? = "2026-01-01T00:00",
        local: String? = "2026-01-01T00:00",
        force: Boolean = false,
        lastSyncAt: Long = synced,
        schema: Int = LibrarySync.SCHEMA,
    ) = LibrarySync.needsFullSync(force, lastSyncAt, nowMs, remote, local, schema)

    @Test
    fun `opening the app a day later with no server rescan doesn't resync`() {
        assertFalse(needs())
        assertFalse(needs(nowMs = synced + 6 * day))
    }

    @Test
    fun `a server rescan resyncs`() {
        assertTrue(needs(remote = "2026-01-02T00:00"))
    }

    @Test
    fun `a week without a sync resyncs as a safety net`() {
        assertTrue(needs(nowMs = synced + 8 * day))
    }

    @Test
    fun `first sync, a forced sync and an older schema resync`() {
        assertTrue(needs(lastSyncAt = 0))
        assertTrue(needs(force = true))
        assertTrue(needs(schema = LibrarySync.SCHEMA - 1))
    }

    @Test
    fun `a server that doesn't say when it scanned resyncs after six hours`() {
        assertFalse(needs(remote = null, nowMs = synced + 5 * 60 * 60 * 1000L))
        assertTrue(needs(remote = null, nowMs = synced + 7 * 60 * 60 * 1000L))
    }

    @Test
    fun `small libraries may shrink a lot`() {
        assertTrue(looksComplete(remoteCount = 20, localCount = 200))
        assertTrue(looksComplete(remoteCount = 1, localCount = 150))
    }
}
