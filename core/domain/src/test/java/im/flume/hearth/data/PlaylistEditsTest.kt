package im.flume.hearth.data

import im.flume.hearth.data.PlaylistEdits.mergePlaylistEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistEditsTest {
    private fun l(s: String) = if (s.isEmpty()) emptyList() else s.split(" ")

    @Test
    fun `nothing changed on the server keeps your order`() {
        assertEquals(l("c a b"), mergePlaylistEdit(l("a b c"), l("c a b"), l("a b c")))
    }

    @Test
    fun `songs added by the other person go at the end in server order`() {
        assertEquals(l("c b a x y"), mergePlaylistEdit(l("a b c"), l("c b a"), l("a x b c y")))
    }

    @Test
    fun `songs removed by the other person stay removed`() {
        assertEquals(l("c a"), mergePlaylistEdit(l("a b c"), l("c b a"), l("a c")))
    }

    @Test
    fun `songs you removed stay removed`() {
        assertEquals(l("c a z"), mergePlaylistEdit(l("a b c"), l("c a"), l("a b c z")))
    }

    @Test
    fun `everything at once`() {
        // You: reorder and drop b. Them: drop d, add x.
        assertEquals(l("c a x"), mergePlaylistEdit(l("a b c d"), l("d c a"), l("a b c x")))
    }

    @Test
    fun `duplicates are matched by occurrence`() {
        // Other person added a second a: it's appended, your single a keeps its place.
        assertEquals(l("b a a"), mergePlaylistEdit(l("a b"), l("b a"), l("a b a")))
        // Other person removed one of two a's: one a stays, where you put it.
        assertEquals(l("a b"), mergePlaylistEdit(l("a b a"), l("a a b"), l("b a")))
        // You removed one a, they removed one a: one a was ambiguous, so it's kept rather than lost.
        assertEquals(l("b a"), mergePlaylistEdit(l("a b a"), l("b a"), l("a b")))
        // Both a's still there, reordered.
        assertEquals(l("a a b"), mergePlaylistEdit(l("a b a"), l("a a b"), l("a b a")))
    }

    @Test
    fun `undo puts a removed song back while keeping concurrent changes`() {
        val before = l("a b c")
        val after = l("a c")
        // Meanwhile the other person added x and removed c.
        assertEquals(l("a b x"), mergePlaylistEdit(after, before, l("a x")))
    }

    @Test
    fun `occurrence lookups`() {
        val ids = l("a b a c a")
        assertEquals(0, PlaylistEdits.occurrenceAt(ids, 0))
        assertEquals(1, PlaylistEdits.occurrenceAt(ids, 2))
        assertEquals(2, PlaylistEdits.occurrenceAt(ids, 4))
        assertEquals(2, PlaylistEdits.indexOf(ids, "a", 1))
        assertEquals(4, PlaylistEdits.indexOf(ids, "a", 7)) // fewer than asked: the last one
        assertEquals(3, PlaylistEdits.indexOf(ids, "c", 0))
        assertNull(PlaylistEdits.indexOf(ids, "z", 0))
        // A song this phone hasn't synced shifts server positions; the id + occurrence still finds the right one.
        assertEquals(3, PlaylistEdits.indexOf(l("a x b a"), "a", 1))
    }

    @Test
    fun `replace ops only touch the changed tail`() {
        assertEquals(listOf(1, 2) to l("c b"), PlaylistEdits.replaceOps(l("a b c"), l("a c b")))
        assertEquals(emptyList<Int>() to emptyList<String>(), PlaylistEdits.replaceOps(l("a b"), l("a b")))
        assertEquals(listOf(0, 1) to emptyList<String>(), PlaylistEdits.replaceOps(l("a b"), emptyList()))
        assertEquals(emptyList<Int>() to l("x"), PlaylistEdits.replaceOps(l("a b"), l("a b x")))
        assertEquals(listOf(0, 1, 2) to l("c b a"), PlaylistEdits.replaceOps(l("a b c"), l("c b a")))
    }

    @Test
    fun `applying replace ops gives the target either way round`() {
        val current = l("a b c d")
        val target = l("a d x b")
        val (remove, add) = PlaylistEdits.replaceOps(current, target)
        val removedFirst = current.filterIndexed { i, _ -> i !in remove } + add
        val addedFirst = (current + add).filterIndexed { i, _ -> i !in remove }
        assertEquals(target, removedFirst)
        assertEquals(target, addedFirst)
    }
}
