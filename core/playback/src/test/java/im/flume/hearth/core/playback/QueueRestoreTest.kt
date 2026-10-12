package im.flume.hearth.core.playback

import im.flume.hearth.core.data.spoken.SpokenProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRestoreTest {
    private fun progress(currentTime: Double, finished: Boolean) = SpokenProgressEntity(
        libraryItemId = "book", episodeId = "", currentTime = currentTime, duration = 3600.0,
        isFinished = finished, lastUpdate = 0, finishedAt = null, dirty = false, markedUnplayed = false,
    )

    @Test
    fun `a saved queue picks up on the song it was on`() {
        assertEquals(3, QueueRestore.startIndex(savedIndex = 3, itemCount = 10))
    }

    @Test
    fun `a saved index past the songs still in the library starts on the last one`() {
        assertEquals(4, QueueRestore.startIndex(savedIndex = 9, itemCount = 5))
    }

    @Test
    fun `a broken negative index starts at the beginning`() {
        assertEquals(0, QueueRestore.startIndex(savedIndex = -1, itemCount = 5))
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
