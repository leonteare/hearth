package im.flume.hearth.api

import im.flume.hearth.spoken.FollowTags
import im.flume.hearth.spoken.LocalSessionPayload
import im.flume.hearth.spoken.ProgressSnapshot
import im.flume.hearth.spoken.toEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import im.flume.hearth.core.network.AbsClient
import im.flume.hearth.core.network.AbsTokens
import im.flume.hearth.core.network.Jwt

/**
 * End-to-end check against a real Audiobookshelf server. Skipped unless ABS_LIVE_URL, ABS_LIVE_USER and
 * ABS_LIVE_PASSWORD are set (never commit them). Leaves the server as it found it: the follower tag and
 * the test progress are removed again, and the session is logged out.
 */
class AbsLiveTest {
    @Test
    fun `real server round trip`() {
        val url = System.getenv("ABS_LIVE_URL").orEmpty()
        val user = System.getenv("ABS_LIVE_USER").orEmpty()
        val password = System.getenv("ABS_LIVE_PASSWORD").orEmpty()
        assumeTrue(url.isNotBlank() && user.isNotBlank() && password.isNotBlank())
        runBlocking { roundTrip(url, user, password) }
    }

    private suspend fun roundTrip(url: String, user: String, password: String) {
        val store = AbsClientTest.FakeStore(url.trimEnd('/'), null)
        val client = AbsClient(OkHttpClient(), store)

        // Sign in, then renew: the refresh token rotates and the new access token works.
        val login = client.login(url, user, password)
        store.t = AbsTokens(login.user.accessToken!!, login.user.refreshToken!!)
        assertNotNull(Jwt.expiresAt(store.t!!.accessToken))
        val before = store.t!!
        val renewed = client.refreshBlocking(null)
        assertFalse(renewed.accessToken == before.accessToken)
        assertEquals(login.user.id, client.me().id)
        assertTrue(client.clock.measured)
        println("server clock offset: ${client.clock.offsetMs} ms")

        val libs = client.libraries()
        val podLib = libs.first { it.mediaType == "podcast" }
        assertTrue(libs.any { it.mediaType == "book" })
        val podcast = client.allLibraryItems(podLib.id).first()
        val item = client.item(podcast.id)!!
        val episode = item.media.episodes.orEmpty().first { it.audioFile != null }.toEntity(item.id)
        println("podcast ${item.media.metadata.title}: ${item.media.episodes.orEmpty().size} episodes")

        // Streaming: a ranged request with the header works; without it the server refuses.
        client.http.newCall(Request.Builder().url(client.fileUrl(item.id, episode.ino!!)).header("Range", "bytes=0-1023").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals(1024, it.body!!.bytes().size)
        }
        OkHttpClient().newCall(Request.Builder().url(client.fileUrl(item.id, checkNotNull(episode.ino))).build()).execute().use { assertEquals(401, it.code) }
        OkHttpClient().newCall(Request.Builder().url(checkNotNull(client.coverUrl(item.id))).build()).execute().use { assertEquals(200, it.code) }

        // Progress: an explicit update, then local sessions: the newer one moves progress, an older one doesn't.
        val existing = client.progress(item.id, episode.id)
        val now = client.clock.serverNow()
        client.patchProgress(item.id, episode.id, LocalSessionPayload.progressBody(ProgressSnapshot(100.0, episode.durationSec, false, now - 60_000)))
        assertEquals(100.0, client.progress(item.id, episode.id)!!.currentTime, 0.5)
        fun session(at: Long, pos: Double) = LocalSessionPayload.build(
            LocalSessionPayload.Session(UUID.randomUUID().toString(), podLib.id, item.id, episode.id, "podcast", episode.title, "test",
                episode.durationSec, 100.0, pos, 30.0, at - 30_000, at, "2026-10-11", "Sunday"),
            finished = false, buildJsonObject { put("deviceId", "hearth-live-test"); put("clientName", "Hearth") },
        )
        val results = client.syncLocalSessions(listOf(session(now, 130.0), session(now - 600_000, 999.0)), buildJsonObject { put("deviceId", "hearth-live-test") })
        assertTrue(results.all { it.success })
        val after = client.progress(item.id, episode.id)!!
        assertEquals(130.0, after.currentTime, 0.5)
        assertEquals(now, after.lastUpdate)

        // Finished: explicit, then a session with the end position keeps it finished.
        client.patchProgress(item.id, episode.id, LocalSessionPayload.progressBody(ProgressSnapshot(130.0, episode.durationSec, true, now + 1_000, now + 1_000)))
        assertTrue(client.progress(item.id, episode.id)!!.isFinished)

        // Follow tag read-modify-write, then put the tags back as they were.
        val originalTags = item.media.tags
        val followed = FollowTags.withFollower(client.item(item.id)!!.media.tags, user, true)
        client.setTags(item.id, followed)
        assertTrue(FollowTags.isFollowing(client.item(item.id)!!.media.tags, user))
        client.setTags(item.id, FollowTags.withFollower(client.item(item.id)!!.media.tags, user, false))
        assertEquals(originalTags, client.item(item.id)!!.media.tags)

        assertTrue(client.searchPodcasts("up first").isNotEmpty())

        // Clean up: remove the test progress and end the session.
        println("progress before the test: ${existing?.currentTime}")
        val id = client.progress(item.id, episode.id)!!.id!!
        client.http.newCall(Request.Builder().url(client.url("api/me/progress/$id")).delete().build()).execute().close()
        client.logout()
    }
}
