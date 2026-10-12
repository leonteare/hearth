package im.flume.hearth.domain.spoken

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import im.flume.hearth.core.network.AbsClient
import im.flume.hearth.core.network.AbsTokens

/**
 * The show page's "every episode" flow against a real Audiobookshelf server: add a show (followed, as
 * Search does), check the follower tag landed, read its whole feed, ask the server for one older
 * episode, watch it arrive and match it to its feed entry, then unfollow (marker tag) and delete the
 * show again. Skipped unless ABS_LIVE_URL / ABS_LIVE_USER / ABS_LIVE_PASSWORD and ABS_LIVE_FEED are set;
 * ABS_LIVE_CA can name a PEM certificate to trust (a home CA). Never commit them.
 */
class ShowFeedLiveTest {
    @Test
    fun `every episode of a show, and fetching one onto the server`() {
        val url = System.getenv("ABS_LIVE_URL").orEmpty()
        val user = System.getenv("ABS_LIVE_USER").orEmpty()
        val password = System.getenv("ABS_LIVE_PASSWORD").orEmpty()
        val feedUrl = System.getenv("ABS_LIVE_FEED").orEmpty()
        assumeTrue(url.isNotBlank() && user.isNotBlank() && password.isNotBlank() && feedUrl.isNotBlank())
        runBlocking { run(url.trimEnd('/'), user, password, feedUrl) }
    }

    private fun http(): OkHttpClient {
        val ca = System.getenv("ABS_LIVE_CA")?.takeIf { it.isNotBlank() } ?: return OkHttpClient()
        val certs = File(ca).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); certs.forEachIndexed { i, c -> setCertificateEntry("ca$i", c) } }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val tm = tmf.trustManagers.first() as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm).build()
    }

    private suspend fun run(url: String, user: String, password: String, feedUrl: String) {
        val store = AbsClientTest.FakeStore(url, null)
        val client = AbsClient(http(), store)
        val login = client.login(url, user, password)
        store.t = AbsTokens(login.user.accessToken!!, login.user.refreshToken!!)
        val lib = client.libraries().first { it.mediaType == "podcast" }
        assumeTrue("the test show is already on the server", client.allLibraryItems(lib.id).none { FeedMatchUrls.same(it.media.metadata.feedUrl, feedUrl) })

        // The whole feed, through the server.
        val t0 = System.currentTimeMillis()
        val feed = client.podcastFeed(feedUrl)
        val parsed = FeedParse.episodes(feed.episodes)
        println("feed: ${parsed.size} episodes in ${System.currentTimeMillis() - t0} ms")
        assertTrue(parsed.size > 10)

        // Added and followed, as Search does; the follower tag must be there.
        val folder = lib.folders.first()
        val item = client.createPodcast(lib.id, folder.id, folder.fullPath, feed.metadata, feedUrl, null, listOf(FollowTags.tagFor(user)), autoDownload = false)
        try {
            assertTrue(FollowTags.isFollowing(client.item(item.id)!!.media.tags, user))

            // An older episode: not on the server, so the page would show it with a cloud.
            val (older, raw) = parsed.sortedByDescending { it.first.publishedAt }[5]
            val before = client.item(item.id)!!.media.episodes.orEmpty().map { it.toEntity(item.id) }
            assertEquals(ShowEpisodeState.NOT_ON_SERVER, ShowEpisodes.state(ShowEpisodes.merge(listOf(older), emptyList()).first()))
            assertTrue(FeedMatch.match(listOf(older), before.map { ServerEpisodeRef(it.id, it.enclosureUrl, it.guid, it.title, it.publishedAt) }).isEmpty())

            client.downloadEpisodesOnServer(item.id, JsonArray(listOf(raw)))
            val started = System.currentTimeMillis()
            var found: String? = null
            while (found == null) {
                val now = client.item(item.id)!!
                found = FeedMatch.match(listOf(older), now.media.episodes.orEmpty().map { ServerEpisodeRef(it.id, it.enclosure?.url, it.guid, it.title, it.publishedAt ?: it.addedAt) })[older.key]
                if (found != null) {
                    val ep = now.media.episodes!!.first { it.id == found }
                    println("on the server after ${System.currentTimeMillis() - started} ms: ${ep.title} (url match: ${ep.enclosure?.url == older.enclosureUrl}, guid: ${ep.guid})")
                    assertNotNull(ep.audioFile)
                    break
                }
                val queue = client.episodeDownloadQueue(item.id)
                val phase = FetchProgress.phase(false, queue.indexOfFirst { it.url == older.enclosureUrl }.takeIf { it >= 0 }, System.currentTimeMillis() - started)
                assertFalse("gave up waiting", phase == FetchPhase.FAILED)
                delay(FetchProgress.pollDelayMs(System.currentTimeMillis() - started))
            }

            // Unfollowing as the only follower leaves the "hearth-unfollowed:<time>" marker.
            val tags = client.item(item.id)!!.media.tags
            val next = FollowTags.nextTags(tags, user, false, client.clock.serverNow())
            client.setTags(item.id, next)
            val after = client.item(item.id)!!.media.tags
            assertFalse(FollowTags.isFollowing(after, user))
            assertTrue(after.any(FollowTags::isUnfollowedMarker))
        } finally {
            // Leave the server as it was.
            client.http.newCall(Request.Builder().url(client.url("api/items/${item.id}", mapOf("hard" to "1"))).delete().build()).execute().use {
                println("deleted test show: HTTP ${it.code}")
            }
            client.logout()
        }
    }

    private object FeedMatchUrls {
        fun same(a: String?, b: String?) = FeedUrls.same(a, b)
    }
}
