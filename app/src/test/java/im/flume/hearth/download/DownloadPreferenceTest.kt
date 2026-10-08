package im.flume.hearth.download

import im.flume.hearth.data.DownloadCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The song's own download choice beats any album, playlist or Liked Songs download. */
class DownloadPreferenceTest {

    @Test
    fun `song preference wins over the collection`() {
        assertTrue(DownloadPolicy.shouldKeep(songPref = null, inDownloadedCollection = true))
        assertFalse(DownloadPolicy.shouldKeep(songPref = null, inDownloadedCollection = false))
        // Removed by hand: stays removed even though a downloaded album holds it.
        assertFalse(DownloadPolicy.shouldKeep(songPref = false, inDownloadedCollection = true))
        // Downloaded by hand: stays even when no downloaded collection holds it.
        assertTrue(DownloadPolicy.shouldKeep(songPref = true, inDownloadedCollection = false))
    }

    @Test
    fun `sync only queues songs that are new and not removed by hand`() {
        val queue = DownloadPolicy.toQueue(
            collectionSongs = listOf("done", "queued", "removed", "new", "wanted"),
            existing = setOf("done", "queued"),
            prefs = mapOf("removed" to false, "wanted" to true),
        )
        assertEquals(listOf("new", "wanted"), queue)
    }

    @Test
    fun `re-downloading a collection clears removals, so its songs queue again`() {
        // Pinning again deletes the "removed" rows for the collection's songs; the rule then queues them.
        val before = mapOf("a" to false)
        assertEquals(emptyList<String>(), DownloadPolicy.toQueue(listOf("a"), emptySet(), before))
        assertEquals(listOf("a"), DownloadPolicy.toQueue(listOf("a"), emptySet(), before - "a"))
    }

    @Test
    fun `removing a collection keeps songs other downloads or the user want`() {
        val gone = DownloadPolicy.removeOnUnpin(
            songIds = listOf("plain", "inLiked", "byHand", "removed", "plain"),
            stillWanted = setOf("inLiked"),
            prefs = mapOf("byHand" to true, "removed" to false),
        )
        assertEquals(listOf("plain", "removed"), gone)
    }

    @Test
    fun `downloads shortcut label`() {
        assertEquals("Downloading · 34 left", DownloadCounts(queued = 33, downloading = 1, failed = 2).summary)
        assertEquals("Downloads · 2 couldn't download", DownloadCounts(failed = 2).summary)
        assertFalse(DownloadCounts().active)
        assertTrue(DownloadCounts(failed = 1).active)
    }
}
