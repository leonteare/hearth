package im.flume.hearth.domain.spoken

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import im.flume.hearth.core.data.spoken.OutboxKind
import im.flume.hearth.core.data.spoken.SpokenDownloadState

class SpokenLogicTest {
    private fun p(pos: Double, at: Long, finished: Boolean = false, duration: Double = 3600.0, finishedAt: Long? = null) =
        ProgressSnapshot(pos, duration, finished, at, finishedAt)

    // ------------------------------------------------------------------ progress merge

    @Test
    fun `newest position wins`() {
        val local = p(100.0, at = 2_000)
        val server = p(50.0, at = 1_000)
        val m = ProgressMerge.merge(local, server)!!
        assertEquals(100.0, m.merged.currentTime, 0.0)
        assertEquals(ProgressSource.LOCAL, m.winner)
        assertTrue(m.pushToServer)
        assertFalse(m.resumedElsewhere)

        val m2 = ProgressMerge.merge(p(100.0, at = 1_000), p(110.0, at = 2_000))!!
        assertEquals(110.0, m2.merged.currentTime, 0.0)
        assertEquals(ProgressSource.SERVER, m2.winner)
        assertFalse(m2.pushToServer)
        assertFalse(m2.resumedElsewhere) // only 10 s apart
    }

    @Test
    fun `a newer far-away server position is a resume from another device`() {
        val m = ProgressMerge.merge(p(100.0, at = 1_000), p(100.0 + 121, at = 2_000))!!
        assertTrue(m.resumedElsewhere)
        val near = ProgressMerge.merge(p(100.0, at = 1_000), p(100.0 + 119, at = 2_000))!!
        assertFalse(near.resumedElsewhere)
        // Going back counts too (e.g. the other device restarted the episode).
        assertTrue(ProgressMerge.merge(p(1000.0, at = 1_000), p(10.0, at = 2_000))!!.resumedElsewhere)
    }

    @Test
    fun `the most recent change wins for played and unplayed`() {
        // Server says finished, phone has a newer position (re-listening): in progress again.
        val a = ProgressMerge.merge(p(30.0, at = 5_000), p(3600.0, at = 1_000, finished = true, finishedAt = 900))!!
        assertFalse(a.merged.isFinished)
        assertTrue(a.pushToServer)
        // Phone finished after the server's last update: finished, and sent to the server.
        val b = ProgressMerge.merge(p(3500.0, at = 5_000, finished = true, finishedAt = 5_000), p(200.0, at = 1_000))!!
        assertTrue(b.merged.isFinished)
        assertTrue(b.pushToServer)
        // Marked unplayed on another device after this phone finished it: unplayed here too.
        val c = ProgressMerge.merge(p(3500.0, at = 1_000, finished = true, finishedAt = 1_000), p(0.0, at = 5_000))!!
        assertFalse(c.merged.isFinished)
        assertFalse(c.pushToServer)
        // A stale offline update can't undo a newer finish.
        val d = ProgressMerge.merge(p(1200.0, at = 1_000), p(3600.0, at = 5_000, finished = true, finishedAt = 5_000))!!
        assertTrue(d.merged.isFinished)
    }

    @Test
    fun `marking unplayed on this phone beats an older finished`() {
        val m = ProgressMerge.merge(p(0.0, at = 5_000), p(3600.0, at = 1_000, finished = true), localMarkedUnplayed = true)!!
        assertFalse(m.merged.isFinished)
        assertTrue(m.pushToServer)
        // ...but not a finish that happened after it.
        val later = ProgressMerge.merge(p(0.0, at = 1_000), p(3600.0, at = 5_000, finished = true), localMarkedUnplayed = true)!!
        assertTrue(later.merged.isFinished)
    }

    @Test
    fun `merging with one side missing`() {
        assertNull(ProgressMerge.merge(null, null))
        assertTrue(ProgressMerge.merge(p(10.0, 1), null)!!.pushToServer)
        val serverOnly = ProgressMerge.merge(null, p(10.0, 1))!!
        assertFalse(serverOnly.pushToServer)
        assertFalse(serverOnly.resumedElsewhere)
    }

    @Test
    fun `clock skew is handled by comparing on the server clock`() {
        // The phone's clock runs 10 minutes fast. Its update happened at server time 1_000_000 (phone
        // showed 1_600_000); the server's update came later, at 1_300_000. Using the phone clock the phone
        // would wrongly win; on the server clock (as SpokenProgress stores it) the server wins.
        val offsetMs = -600_000L
        val phoneClockAt = 1_600_000L
        val local = p(500.0, at = phoneClockAt + offsetMs)
        val server = p(900.0, at = 1_300_000)
        assertEquals(ProgressSource.SERVER, ProgressMerge.merge(local, server)!!.winner)
        assertEquals(ProgressSource.LOCAL, ProgressMerge.merge(local.copy(lastUpdate = phoneClockAt), server)!!.winner)
    }

    @Test
    fun `finished at 95 percent or the last 30 seconds`() {
        assertFalse(ProgressMerge.reachedEnd(3000.0, 3600.0))
        assertTrue(ProgressMerge.reachedEnd(3420.0, 3600.0)) // 95%
        assertTrue(ProgressMerge.reachedEnd(290.0, 300.0)) // within the last 30 s of a short one
        assertFalse(ProgressMerge.reachedEnd(260.0, 300.0))
        assertFalse(ProgressMerge.reachedEnd(10.0, 0.0)) // unknown length never finishes
    }

    // ------------------------------------------------------------------ outbox

    @Test
    fun `outbox goes in order, sessions batched, explicit changes alone`() {
        val items = listOf(
            OutboxItem("session:c", 5, OutboxKind.SESSION),
            OutboxItem("session:a", 1, OutboxKind.SESSION),
            OutboxItem("progress:x/", 3, OutboxKind.PROGRESS),
            OutboxItem("session:b", 2, OutboxKind.SESSION),
            OutboxItem("session:d", 6, OutboxKind.SESSION),
        )
        val batches = OutboxPlan.batches(items).map { b -> b.map { it.key } }
        assertEquals(listOf(listOf("session:a", "session:b"), listOf("progress:x/"), listOf("session:c", "session:d")), batches)
    }

    @Test
    fun `big session runs are split`() {
        val items = (1..45).map { OutboxItem("session:$it", it.toLong(), OutboxKind.SESSION) }
        assertEquals(listOf(20, 20, 5), OutboxPlan.batches(items).map { it.size })
    }

    @Test
    fun `which server errors are permanent`() {
        assertTrue(OutboxPlan.isPermanentRejection(404))
        assertTrue(OutboxPlan.isPermanentRejection(400))
        assertFalse(OutboxPlan.isPermanentRejection(401))
        assertFalse(OutboxPlan.isPermanentRejection(429))
        assertFalse(OutboxPlan.isPermanentRejection(500))
    }

    // ------------------------------------------------------------------ follower tags

    @Test
    fun `following keeps everyone else's tags`() {
        val tags = listOf("news", "hearth-follower:sadie")
        assertEquals(listOf("news", "hearth-follower:sadie", "hearth-follower:leon"), FollowTags.withFollower(tags, "Leon", follow = true))
        val both = listOf("hearth-follower:leon", "news", "hearth-follower:sadie")
        assertEquals(listOf("news", "hearth-follower:sadie"), FollowTags.withFollower(both, "leon", follow = false))
        // Idempotent and case-insensitive: no duplicate tag.
        assertEquals(both.filterNot { it.endsWith("leon") } + "hearth-follower:leon", FollowTags.withFollower(both + "Hearth-Follower:LEON", "leon", follow = true))
    }

    @Test
    fun `read-modify-write against a concurrent change keeps both followers`() {
        // Sadie's phone wrote her tag after Leon's phone last synced; Leon follows from fresh server tags.
        val staleLocal = listOf("news")
        val fresh = listOf("news", "hearth-follower:sadie")
        val written = FollowTags.withFollower(fresh, "leon", true)
        assertTrue(FollowTags.isFollowing(written, "sadie"))
        assertTrue(FollowTags.isFollowing(written, "leon"))
        // Using the stale copy would have dropped her.
        assertFalse(FollowTags.isFollowing(FollowTags.withFollower(staleLocal, "leon", true), "sadie"))
        assertEquals(listOf("sadie", "leon"), FollowTags.followers(written))
    }

    // ------------------------------------------------------------------ downloads

    @Test
    fun `auto delete only finished episodes not playing`() {
        assertTrue(SpokenDownloadPolicy.shouldAutoDelete(finished = true, isCurrent = false, state = SpokenDownloadState.DONE))
        assertFalse(SpokenDownloadPolicy.shouldAutoDelete(finished = true, isCurrent = true, state = SpokenDownloadState.DONE))
        assertFalse(SpokenDownloadPolicy.shouldAutoDelete(finished = false, isCurrent = false, state = SpokenDownloadState.DONE))
        assertFalse(SpokenDownloadPolicy.shouldAutoDelete(finished = true, isCurrent = false, state = SpokenDownloadState.REMOVED))
    }

    @Test
    fun `auto download keeps the newest three unplayed`() {
        val eps = listOf(
            EpisodeInfo("e5", 5, finished = false, started = false),
            EpisodeInfo("e4", 4, finished = true, started = false),
            EpisodeInfo("e3", 3, finished = false, started = false),
            EpisodeInfo("e2", 2, finished = false, started = false),
            EpisodeInfo("e1", 1, finished = false, started = true),
            EpisodeInfo("e0", 0, finished = false, started = false),
        )
        val existing = mapOf(
            "e3" to DownloadInfo(SpokenDownloadState.REMOVED, auto = true), // the user removed it: stays removed
            "e1" to DownloadInfo(SpokenDownloadState.DONE, auto = true), // started: kept even outside the window
            "e0" to DownloadInfo(SpokenDownloadState.DONE, auto = true), // old and unstarted: goes
        )
        val plan = SpokenDownloadPolicy.autoDownloadPlan(eps, existing)
        assertEquals(listOf("e5", "e2"), plan.toQueue)
        assertEquals(listOf("e0"), plan.toRemove)
        // Hand downloads are never removed by the planner.
        val manual = SpokenDownloadPolicy.autoDownloadPlan(eps, mapOf("e0" to DownloadInfo(SpokenDownloadState.DONE, auto = false)))
        assertTrue(manual.toRemove.isEmpty())
    }

    // ------------------------------------------------------------------ timeline

    private val book = SpokenTimeline(
        listOf(TimelineTrack("a", 1800.0), TimelineTrack("b", 1800.0), TimelineTrack("c", 600.0)),
        listOf(TimelineChapter(0.0, 1200.0, "One"), TimelineChapter(1200.0, 3000.0, "Two"), TimelineChapter(3000.0, 4200.0, "Three")),
    )

    @Test
    fun `book positions map to files and back`() {
        assertEquals(4200.0, book.durationSec, 0.0)
        assertEquals(listOf(0.0, 1800.0, 3600.0), book.starts)
        assertEquals(0 to 0L, book.locate(0.0))
        assertEquals(0 to 1_799_000L, book.locate(1799.0))
        assertEquals(1 to 0L, book.locate(1800.0)) // a boundary is the next file's start
        assertEquals(2 to 300_000L, book.locate(3900.0))
        assertEquals(2 to 600_000L, book.locate(99_999.0)) // clamped to the end
        assertEquals(0 to 0L, book.locate(-5.0))
        assertEquals(1830.5, book.globalSec(1, 30_500), 0.0001)
        assertEquals(3900.0, book.globalSec(2, 300_000), 0.0001)
        val (i, ms) = book.locate(2345.6)
        assertEquals(2345.6, book.globalSec(i, ms), 0.001)
    }

    @Test
    fun `chapters span files`() {
        assertEquals("Two", book.chapterAt(1900.0)!!.title) // chapter two crosses from file a into b
        assertEquals(3000.0, book.chapterEndSec(1900.0), 0.0)
        assertEquals("Three", book.chapterAt(3000.0)!!.title)
        val noChapters = SpokenTimeline(listOf(TimelineTrack("x", 100.0)))
        assertNull(noChapters.chapterAt(5.0))
        assertEquals(100.0, noChapters.chapterEndSec(5.0), 0.0)
    }

    // ------------------------------------------------------------------ payloads

    @Test
    fun `a finished item reports its end so the server keeps it finished`() {
        val s = LocalSessionPayload.Session("id", "lib", "item", "ep", "podcast", "T", "A", 600.0, 0.0, 20.0, 30.0, 1, 2, "2026-10-11", "Sunday")
        val dev = buildJsonObject { }
        assertEquals(600.0, LocalSessionPayload.build(s, finished = true, dev)["currentTime"]!!.jsonPrimitive.double, 0.0)
        assertEquals(20.0, LocalSessionPayload.build(s, finished = false, dev)["currentTime"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3, LocalSessionPayload.build(s, false, dev)["playMethod"]!!.jsonPrimitive.content.toInt())
        val body = LocalSessionPayload.progressBody(p(100.0, at = 42, finished = true, duration = 600.0, finishedAt = 40))
        assertTrue(body["isFinished"]!!.jsonPrimitive.boolean)
        assertEquals(600.0, body["currentTime"]!!.jsonPrimitive.double, 0.0)
        assertEquals("42", body["lastUpdate"]!!.jsonPrimitive.content)
    }
}
