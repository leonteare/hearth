package im.flume.hearth.core.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import im.flume.hearth.domain.music.playback.SavedQueue

class QueueSavesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val big = SavedQueue(
        window = listOf("a", "b", "c"),
        manual = listOf(false, false, false),
        index = 1, positionMs = 10_000,
        pending = List(7000) { "song$it" }, context = List(7003) { "song$it" },
        shuffled = true, label = "All songs", repeatMode = 0,
        revision = 5,
    )

    @Test
    fun `nothing saved yet writes the whole queue`() {
        assertEquals(QueueSaves.Write.FULL, QueueSaves.plan(null, big))
    }

    @Test
    fun `only the position moving writes just the position`() {
        assertEquals(QueueSaves.Write.POSITION, QueueSaves.plan(big, big.copy(positionMs = 25_000, revision = 0)))
    }

    @Test
    fun `a spoken position moving writes just the position`() {
        val spoken = big.copy(spokenItemId = "book", spokenPositionSec = 100.0)
        assertEquals(QueueSaves.Write.POSITION, QueueSaves.plan(spoken, spoken.copy(spokenPositionSec = 115.0)))
    }

    @Test
    fun `nothing changed writes nothing`() {
        assertEquals(QueueSaves.Write.NOTHING, QueueSaves.plan(big, big.copy(revision = 0)))
    }

    @Test
    fun `a new song, a queue edit or shuffle writes the whole queue`() {
        assertEquals(QueueSaves.Write.FULL, QueueSaves.plan(big, big.copy(index = 2, positionMs = 0)))
        assertEquals(QueueSaves.Write.FULL, QueueSaves.plan(big, big.copy(window = listOf("a", "b", "x", "c"), manual = listOf(false, false, true, false))))
        assertEquals(QueueSaves.Write.FULL, QueueSaves.plan(big, big.copy(shuffled = false)))
        assertEquals(QueueSaves.Write.FULL, QueueSaves.plan(big, big.copy(pending = big.pending.drop(1))))
    }

    @Test
    fun `a position from the same save is applied`() {
        val merged = QueueSaves.merge(big, SavedPosition(revision = 5, positionMs = 42_000))
        assertEquals(42_000, merged.positionMs)
        assertEquals(1, merged.index)
    }

    @Test
    fun `a position left over from an older save is ignored`() {
        assertEquals(big, QueueSaves.merge(big, SavedPosition(revision = 4, positionMs = 42_000)))
        assertEquals(big, QueueSaves.merge(big, null))
    }

    @Test
    fun `revisions keep increasing even if the clock goes back`() {
        assertEquals(1_000L, QueueSaves.nextRevision(previous = 0, nowMs = 1_000))
        assertEquals(2_001L, QueueSaves.nextRevision(previous = 2_000, nowMs = 1_000))
    }

    @Test
    fun `the store writes the big file once and the position on its own after that`() = runTest {
        val dir = tmp.newFolder()
        val store = QueueStore(dir)
        store.clear()
        store.save(big.copy(revision = 0))
        val queueFile = File(dir, "queue.json")
        val firstWrite = queueFile.readText()
        assertFalse(File(dir, "queue-position.json").exists())

        store.save(big.copy(positionMs = 90_000, revision = 0))
        assertEquals(firstWrite, queueFile.readText())
        assertTrue(File(dir, "queue-position.json").exists())
        assertEquals(90_000, QueueStore(dir).load()!!.positionMs)

        // Next song: the whole queue again, and the old position no longer applies.
        store.save(big.copy(index = 2, positionMs = 1_000, revision = 0))
        val loaded = QueueStore(dir).load()!!
        assertEquals(2, loaded.index)
        assertEquals(1_000, loaded.positionMs)
        store.clear()
    }
}
