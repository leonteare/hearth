// Queue edit arithmetic (QueueEditMath) applied to a real ExoPlayer under Robolectric, to check it agrees with
// the player's own list operations. Nothing is prepared or played: no audio, no network.
package im.flume.hearth.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** SDK 34 like the screenshot tests: newer Robolectric SDKs fail when the home folder path has a space. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueEditsOnPlayerTest {
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    }

    @After
    fun tearDown() = player.release()

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri(SongDataSource.uriFor(id)).build()

    private fun load(vararg ids: String, current: Int = 0) {
        player.setMediaItems(ids.map(::item), current, 0)
    }

    private fun ids() = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    private fun moveAfter(entry: String, afterEntry: String?) {
        val from = ids().indexOf(entry)
        val to = QueueEditMath.moveAfterTarget(from, player.currentMediaItemIndex, afterEntry?.let(ids()::indexOf) ?: -1) ?: return
        if (to != from) player.moveMediaItem(from, to)
    }

    @Test
    fun `dragging down on a real player lands straight after the song dropped on`() {
        load("now", "a", "b", "c", "d")
        moveAfter("a", "c")
        assertEquals(listOf("now", "b", "c", "a", "d"), ids())
        assertEquals("now", player.currentMediaItem?.mediaId)
    }

    @Test
    fun `dragging up on a real player lands straight after the song dropped on`() {
        load("played", "now", "a", "b", "c", current = 1)
        moveAfter("c", "a")
        assertEquals(listOf("played", "now", "a", "c", "b"), ids())
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `undo of add to queue on a real player removes the newest copies and keeps the current song`() {
        load("now", "x", "a", "y", "a")
        val manual = setOf(2, 4)
        QueueEditMath.queuedCopiesToRemove(player.currentMediaItemIndex, player.mediaItemCount, listOf("a", "a"), { ids()[it] }) { it in manual }
            .forEach { player.removeMediaItem(it) }
        assertEquals(listOf("now", "x", "y"), ids())
        assertEquals("now", player.currentMediaItem?.mediaId)
    }

    @Test
    fun `undo of a removal on a real player puts the song back between its old neighbours`() {
        load("now", "a", "c")
        val index = QueueEditMath.restoreIndex(player.currentMediaItemIndex, before = ids().indexOf("c"), after = ids().indexOf("a"))
        player.addMediaItem(index, item("b"))
        assertEquals(listOf("now", "a", "b", "c"), ids())
    }
}
