package im.flume.hearth.domain.spoken

import org.junit.Assert.assertEquals
import org.junit.Test
import im.flume.hearth.core.data.spoken.SpokenDownloadState

/** The follower tags the server's cleanup job reads, self-healing, and clearing a show off the phone on unfollow. */
class FollowCleanupTest {
    @Test
    fun `follow adds this user and strips any unfollowed marker`() {
        assertEquals(listOf("news", "hearth-follower:leon"), FollowTags.nextTags(listOf("news", "hearth-unfollowed:100"), "Leon", follow = true, serverNowMs = 500))
        assertEquals(listOf("hearth-follower:sadie", "hearth-follower:leon"), FollowTags.nextTags(listOf("hearth-follower:sadie"), "leon", true, 500))
    }

    @Test
    fun `last follower leaving marks the time, replacing an older marker`() {
        assertEquals(listOf("news", "hearth-unfollowed:500"), FollowTags.nextTags(listOf("news", "hearth-follower:leon"), "leon", follow = false, serverNowMs = 500))
        assertEquals(listOf("hearth-unfollowed:900"), FollowTags.nextTags(listOf("hearth-unfollowed:100", "Hearth-Follower:LEON"), "leon", false, 900))
    }

    @Test
    fun `unfollowing while someone else follows leaves no marker`() {
        assertEquals(listOf("hearth-follower:sadie"), FollowTags.nextTags(listOf("hearth-follower:leon", "hearth-follower:sadie"), "leon", false, 500))
    }

    @Test
    fun `re-follow after the marker`() {
        val unfollowed = FollowTags.nextTags(listOf("hearth-follower:leon"), "leon", false, 500)
        assertEquals(listOf("hearth-follower:leon"), FollowTags.nextTags(unfollowed, "leon", true, 600))
    }

    @Test
    fun `heal writes the tag again when the server lost it`() {
        assertEquals(FollowTags.Heal.ADD_TAG, FollowTags.heal(true, emptyList(), "leon"))
        assertEquals(FollowTags.Heal.NONE, FollowTags.heal(true, listOf("hearth-follower:leon"), "Leon"))
        assertEquals(FollowTags.Heal.REMOVE_TAG, FollowTags.heal(false, listOf("hearth-follower:leon"), "leon"))
        assertEquals(FollowTags.Heal.NONE, FollowTags.heal(false, listOf("hearth-follower:sadie"), "leon"))
        assertEquals(FollowTags.Heal.NONE, FollowTags.heal(null, emptyList(), "leon"))
    }

    @Test
    fun `unfollow clears every download of the show except the one playing`() {
        val rows = listOf(
            DownloadRowInfo("a", SpokenDownloadState.DONE),
            DownloadRowInfo("b", SpokenDownloadState.QUEUED),
            DownloadRowInfo("c", SpokenDownloadState.DONE),
            DownloadRowInfo("d", SpokenDownloadState.REMOVED),
        )
        val plan = SpokenDownloadPolicy.unfollowPlan(rows, currentEpisodeId = "c")
        assertEquals(listOf("a", "b"), plan.removeNow)
        assertEquals(listOf("c"), plan.removeLater)
        assertEquals(listOf("a", "b", "c"), SpokenDownloadPolicy.unfollowPlan(rows, null).removeNow)
    }

    @Test
    fun `unfollowing clears the server only when nobody else follows`() {
        assertEquals(true, UnfollowServerCleanup.shouldClear(listOf("hearth-unfollowed:500")))
        assertEquals(false, UnfollowServerCleanup.shouldClear(listOf("hearth-follower:sadie")))
    }

    @Test
    fun `unfollowing keeps what someone else is part-way through and what is in the player`() {
        assertEquals(listOf("a", "d"), UnfollowServerCleanup.toDelete(listOf("a", "b", "c", "d"), othersPartWay = setOf("b"), currentEpisodeId = "c"))
        assertEquals(listOf("a", "b"), UnfollowServerCleanup.toDelete(listOf("a", "b"), emptySet(), null))
    }
}
