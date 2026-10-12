package im.flume.hearth.core.playback

import im.flume.hearth.core.data.spoken.SpokenProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRestoreTest {
    private fun progress(currentTime: Double, finished: Boolean) = SpokenProgressEntity(
        libraryItemId = "book", episodeId = "", currentTime = currentTime, duration = 3600.0,
        isFinished = finished, lastUpdate = 0, finishedAt = null, dirty = false, markedUnplayed = false,
    )

    private val all = List(10) { true }

    @Test
    fun `a saved queue picks up on the song it was on at the saved position`() {
        assertEquals(3 to 42_000L, QueueRestore.start(all, savedIndex = 3, savedPositionMs = 42_000))
    }

    @Test
    fun `songs gone from the library ahead of the current one move the start up with them`() {
        // Songs 0 and 2 are gone: the song that was at 4 is now at 2, still at the saved position.
        val kept = listOf(false, true, false, true, true, true)
        assertEquals(2 to 42_000L, QueueRestore.start(kept, savedIndex = 4, savedPositionMs = 42_000))
    }

    @Test
    fun `if the current song itself is gone the next one starts from the beginning`() {
        val kept = listOf(true, true, false, true)
        assertEquals(2 to 0L, QueueRestore.start(kept, savedIndex = 2, savedPositionMs = 42_000))
    }

    @Test
    fun `if the current song and everything after it are gone the last one left starts`() {
        val kept = listOf(true, true, false, false)
        assertEquals(1 to 0L, QueueRestore.start(kept, savedIndex = 2, savedPositionMs = 42_000))
    }

    @Test
    fun `a broken index is kept within the saved queue`() {
        assertEquals(0 to 5L, QueueRestore.start(all, savedIndex = -1, savedPositionMs = 5))
        assertEquals(9 to 5L, QueueRestore.start(all, savedIndex = 99, savedPositionMs = 5))
    }

    @Test
    fun `a spoken queue resumes where this phone last recorded`() {
        assertEquals(1234.5 to false, QueueRestore.spokenStart(progress(1234.5, finished = false), savedSec = 1000.0))
    }

    @Test
    fun `with no record on the phone the position saved with the queue is used`() {
        assertEquals(1000.0 to false, QueueRestore.spokenStart(null, savedSec = 1000.0))
    }

    @Test
    fun `with nothing saved anywhere it starts from the beginning`() {
        assertEquals(0.0 to false, QueueRestore.spokenStart(null, savedSec = null))
    }

    @Test
    fun `a finished episode or book is reported as finished at the queue's position`() {
        assertEquals(1000.0 to true, QueueRestore.spokenStart(progress(3600.0, finished = true), savedSec = 1000.0))
        assertEquals(0.0 to true, QueueRestore.spokenStart(progress(3600.0, finished = true), savedSec = null))
    }
}
