package im.flume.hearth.domain.music

import im.flume.hearth.core.data.LikeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikeOutboxPlanTest {
    private fun like(id: String, liked: Boolean, at: Long, kind: LikeKind = LikeKind.SONG) =
        PendingLike(kind, id, liked, at)

    @Test
    fun `unliking then liking again leaves one change, the last one`() {
        val queue = LikeOutboxPlan.pending(listOf(like("s1", false, 100), like("s1", true, 200)))
        assertEquals(1, queue.size)
        assertTrue(queue.single().liked)
    }

    @Test
    fun `the same id as a song and as an album are different things`() {
        val queue = LikeOutboxPlan.pending(
            listOf(like("x", true, 100), like("x", false, 200, LikeKind.ALBUM))
        )
        assertEquals(listOf(LikeKind.SONG to true, LikeKind.ALBUM to false), queue.map { it.kind to it.liked })
    }

    @Test
    fun `changes are pushed oldest first`() {
        val queue = LikeOutboxPlan.pending(listOf(like("c", true, 300), like("a", true, 100), like("b", false, 200)))
        assertEquals(listOf("a", "b", "c"), queue.map { it.id })
    }

    @Test
    fun `the first change that cannot reach the server stops the push`() {
        val queue = LikeOutboxPlan.pending(listOf(like("a", true, 100), like("b", true, 200), like("c", true, 300)))
        val sent = ArrayList<String>()
        val left = ArrayList<String>()
        var offline = false
        for (item in queue) {
            if (offline) { left += item.id; continue }
            // "b" is where the connection drops; nothing after it may be sent, so it can't overtake "b".
            val next = if (item.id == "b") LikeOutboxPlan.next(ok = false, code = null) else LikeOutboxPlan.next(ok = true, code = null)
            when (next) {
                LikeOutboxPlan.Next.SENT, LikeOutboxPlan.Next.DROP -> sent += item.id
                LikeOutboxPlan.Next.STOP -> { offline = true; left += item.id }
            }
        }
        assertEquals(listOf("a"), sent)
        assertEquals(listOf("b", "c"), left)
    }

    @Test
    fun `a change the server refuses is dropped, not retried for ever`() {
        assertEquals(LikeOutboxPlan.Next.DROP, LikeOutboxPlan.next(ok = false, code = 404))
        assertEquals(LikeOutboxPlan.Next.DROP, LikeOutboxPlan.next(ok = false, code = 400))
        // Subsonic's own "the song is gone" answer counts as refused too.
        assertEquals(LikeOutboxPlan.Next.DROP, LikeOutboxPlan.next(ok = false, code = LikeOutboxPlan.NOT_FOUND))
        // A sign-in, timeout, rate limit or broken server may work next time, so the change waits.
        assertEquals(LikeOutboxPlan.Next.STOP, LikeOutboxPlan.next(ok = false, code = 401))
        assertEquals(LikeOutboxPlan.Next.STOP, LikeOutboxPlan.next(ok = false, code = 429))
        assertEquals(LikeOutboxPlan.Next.STOP, LikeOutboxPlan.next(ok = false, code = 500))
        assertEquals(LikeOutboxPlan.Next.STOP, LikeOutboxPlan.next(ok = false, code = null))
        assertEquals(LikeOutboxPlan.Next.SENT, LikeOutboxPlan.next(ok = true, code = null))
        assertTrue(LikeOutboxPlan.isPermanentRejection(404))
        assertFalse(LikeOutboxPlan.isPermanentRejection(408))
    }

    @Test
    fun `a sync does not undo a like that is still waiting to be pushed`() {
        val pending = listOf(
            like("al2", true, 100, LikeKind.ALBUM),
            like("al3", false, 200, LikeKind.ALBUM),
            like("s1", true, 300),
        )
        // The server still thinks al3 is saved and hasn't heard about al2.
        val merged = LikeOutboxPlan.merge(listOf("al1", "al3"), pending, LikeKind.ALBUM)
        assertEquals(listOf("al1", "al2"), merged)
        // Songs and artists are left alone by an album merge.
        assertEquals(listOf("ar1"), LikeOutboxPlan.merge(listOf("ar1"), pending, LikeKind.ARTIST))
        assertEquals(mapOf("s1" to true), LikeOutboxPlan.likedByIds(pending, LikeKind.SONG))
    }

    @Test
    fun `nothing waiting leaves the server's answer as it is`() {
        assertEquals(listOf("al1", "al2"), LikeOutboxPlan.merge(listOf("al1", "al2"), emptyList(), LikeKind.ALBUM))
    }
}
