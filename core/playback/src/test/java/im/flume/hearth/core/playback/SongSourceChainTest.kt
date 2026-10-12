// The song data source chain wired the way PlayerSetup wires it (downloaded file, then the streaming cache,
// then the server) under Robolectric, with a real cache in a temp folder and a fake server for the network.
package im.flume.hearth.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SDK 34 like the screenshot tests: newer Robolectric SDKs fail when the home folder path has a space. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongSourceChainTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var cache: SimpleCache
    private val serverBytes = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val serverOpens = mutableListOf<String>()
    private val downloads = mutableMapOf<String, File>()
    private var cannotStream = false

    /** Stands in for OkHttp: serves [serverBytes] for any URL and remembers what was asked for. */
    private inner class FakeServer : DataSource {
        private var body: ByteArrayDataSource? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            serverOpens += dataSpec.uri.toString()
            return ByteArrayDataSource(serverBytes).also { body = it }.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            body?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = body?.uri
        override fun close() {
            body?.close()
            body = null
        }
    }

    private val fakeServer = DataSource.Factory { FakeServer() }

    private fun target(id: String) = SongDataSource.StreamTarget("https://music.example/rest/stream?id=$id", "$id@192")

    private fun chain(): DataSource {
        val stream = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(fakeServer)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val cacheOnly = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
        return SongDataSource.Factory(
            fileFactory = FileDataSource.Factory(),
            streamFactory = stream,
            cacheOnlyFactory = cacheOnly,
            localFile = { downloads[it] },
            streamTarget = ::target,
            cachedTarget = { id -> target(id).takeIf { cache.isCached(it.cacheKey, 0, serverBytes.size.toLong()) } },
            cannotStream = { cannotStream },
        ).createDataSource()
    }

    private fun readSong(id: String): ByteArray {
        val source = chain()
        val out = ByteArrayOutputStream()
        try {
            source.open(DataSpec(SongDataSource.uriFor(id)))
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        @Suppress("DEPRECATION") // the database-free constructor: no SQLite needed for a throwaway cache
        cache = SimpleCache(tmp.newFolder("cache"), NoOpCacheEvictor())
    }

    @After
    fun tearDown() = cache.release()

    @Test
    fun `a downloaded song plays from its file without touching the server`() {
        val bytes = ByteArray(1000) { it.toByte() }
        downloads["s1"] = tmp.newFile("s1.mp3").apply { writeBytes(bytes) }
        assertArrayEquals(bytes, readSong("s1"))
        assertTrue(serverOpens.isEmpty())
    }

    @Test
    fun `a streamed song is fetched from the server at its pinned quality`() {
        assertArrayEquals(serverBytes, readSong("s2"))
        assertEquals(listOf("https://music.example/rest/stream?id=s2"), serverOpens)
    }

    @Test
    fun `a song streamed once plays again offline from the cache`() {
        readSong("s3")
        cannotStream = true
        assertArrayEquals(serverBytes, readSong("s3"))
        assertEquals(1, serverOpens.size)
    }

    @Test
    fun `offline a song that isn't on the phone fails at once as a connection failure`() {
        cannotStream = true
        try {
            readSong("s4")
            fail("expected the song to fail offline")
        } catch (e: SongDataSource.OfflineException) {
            assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.reason)
            assertEquals(ErrorAction.WAIT_FOR_CONNECTION, PlaybackErrors.classify(e.reason, httpStatus = null))
        }
        assertTrue(serverOpens.isEmpty())
    }

    @Test
    fun `a downloaded song still plays offline`() {
        cannotStream = true
        val bytes = ByteArray(10) { 7 }
        downloads["s5"] = tmp.newFile("s5.mp3").apply { writeBytes(bytes) }
        assertArrayEquals(bytes, readSong("s5"))
    }
}
