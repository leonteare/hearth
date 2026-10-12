package im.flume.hearth.core.network

import im.flume.hearth.core.common.scrubSecrets
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import im.flume.hearth.core.common.TidarrException

/**
 * Talks to the user's Tidarr: searches Tidal through its proxy, adds downloads, and reads its queue.
 * The API key is only ever put in a header, and error messages never include URLs or the key.
 */
class TidarrClient(http: OkHttpClient, private val config: () -> TidarrConfig?) {
    // Searching is interactive: give up quickly rather than leave a spinner up.
    private val fast = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    private val normal = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    @Volatile private var country: Pair<String, String>? = null

    private fun cfg(): TidarrConfig =
        config()?.takeIf { it.url.isNotBlank() } ?: throw TidarrException("Tidarr isn't set up")

    private suspend fun send(
        path: String,
        verb: String = "GET",
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        quick: Boolean = false,
        cfg: TidarrConfig = cfg(),
    ): String = withContext(Dispatchers.IO) {
        val base = normalizeUrl(cfg.url).toHttpUrlOrNull() ?: throw TidarrException("The Tidarr address isn't valid")
        val url = base.newBuilder().addPathSegments(path.trimStart('/')).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).apply {
            cfg.apiKey?.takeIf { it.isNotBlank() }?.let { header("X-Api-Key", it) }
            val rb = body?.toRequestBody(JSON)
            when (verb) {
                "GET" -> get()
                "POST" -> post(rb ?: ByteArray(0).toRequestBody(null))
                "DELETE" -> delete(rb)
                else -> method(verb, rb)
            }
        }.build()
        try {
            (if (quick) fast else normal).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw TidarrException(
                        when (resp.code) {
                            401, 403 -> "Tidarr refused the request (check the API key)"
                            404 -> "Tidarr didn't recognise the request (HTTP 404)"
                            else -> "Tidarr returned HTTP ${resp.code}"
                        }
                    )
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: TidarrException) {
            throw e
        } catch (e: IOException) {
            throw TidarrException(
                when (e) {
                    is UnknownHostException -> "Couldn't find Tidarr (is the address right?)"
                    is SSLException -> "Couldn't make a secure connection to Tidarr"
                    is SocketTimeoutException, is InterruptedIOException -> "Tidarr took too long to answer"
                    else -> "Couldn't reach Tidarr" + (e.message?.let { ": " + scrubSecrets(it) } ?: "")
                }
            )
        }
    }

    /** Cheap check that the address points at a Tidarr. */
    suspend fun isAuthActive(cfg: TidarrConfig = cfg()): String = send("api/is-auth-active", quick = true, cfg = cfg)

    /** The Tidal account's country (needed for searching), cached per address. */
    suspend fun countryCode(cfg: TidarrConfig = cfg()): String {
        country?.takeIf { it.first == cfg.url }?.let { return it.second }
        val code = runCatching { TidarrParsing.countryCode(send("api/settings", quick = true, cfg = cfg)) }.getOrNull()
        // Only a real answer is cached; the fallback is tried again next time.
        if (code != null) country = cfg.url to code
        return code ?: "GB"
    }

    suspend fun search(query: String, limit: Int = 10, cfg: TidarrConfig = cfg()): TidalSearchResults {
        val body = send(
            "proxy/tidal/v2/search",
            query = linkedMapOf(
                "query" to query,
                "countryCode" to countryCode(cfg),
                "deviceType" to "BROWSER",
                "locale" to "en_US",
                "limit" to limit.toString(),
                "offset" to "0",
            ),
            quick = true,
            cfg = cfg,
        )
        return TidarrParsing.search(body)
    }

    /** Adds [id] to Tidarr's download queue; [title] and [artist] are what Tidarr's own page shows. */
    suspend fun save(type: TidalType, id: String, title: String? = null, artist: String? = null) {
        send("api/save", verb = "POST", body = TidarrParsing.saveBody(type, id, title, artist))
    }

    private val pages = TtlCache<String, String>(max = 40, ttlMs = 10 * 60_000L)

    /** GET on Tidal's v1 API through Tidarr's proxy; answers are cached for 10 minutes. */
    private suspend fun tidal(path: String, params: Map<String, String> = emptyMap()): String {
        val cfg = cfg()
        val query = linkedMapOf("countryCode" to countryCode(cfg), "deviceType" to "BROWSER", "locale" to "en_US") + params
        val key = cfg.url + "|" + path + "|" + query.entries.joinToString("&")
        pages.get(key)?.let { return it }
        return send("proxy/tidal/$path", query = query, cfg = cfg).also { pages.put(key, it) }
    }

    /** The artist, their 10 most played tracks, albums, and singles & EPs, fetched side by side. */
    suspend fun artistPage(id: String): TidalArtistPage = coroutineScope {
        val artist = async { TidarrParsing.artist(tidal("v1/artists/$id")) }
        val top = async { TidarrParsing.tracks(tidal("v1/artists/$id/toptracks", mapOf("limit" to "10"))) }
        val albums = async { TidarrParsing.albums(tidal("v1/artists/$id/albums", mapOf("limit" to "50"))) }
        val singles = async { TidarrParsing.albums(tidal("v1/artists/$id/albums", mapOf("filter" to "EPSANDSINGLES", "limit" to "50"))) }
        TidalArtistPage(
            artist.await() ?: throw TidarrException("Tidal doesn't have this artist"),
            top.await(), albums.await(), singles.await(),
        )
    }

    suspend fun albumPage(id: String): TidalAlbumPage = coroutineScope {
        val album = async { TidarrParsing.album(tidal("v1/albums/$id")) }
        val tracks = async { TidarrParsing.tracks(tidal("v1/albums/$id/tracks", mapOf("limit" to "100"))) }
        val a = album.await() ?: throw TidarrException("Tidal doesn't have this album")
        // Album track lists may leave out the cover; the album has it.
        TidalAlbumPage(a, tracks.await().sortedWith(compareBy({ it.volumeNumber ?: 1 }, { it.trackNumber ?: 0 })).map { t -> if (t.cover == null) t.copy(cover = a.cover, albumId = t.albumId ?: a.id, albumTitle = t.albumTitle ?: a.title) else t })
    }

    /**
     * Where to stream [trackId] from for a preview: Tidal's CDN at low quality (a signed URL that needs
     * no login) when the playback info carries a plain manifest, otherwise Tidarr's own signed stream.
     * Never cached: the URLs expire.
     */
    suspend fun previewStream(trackId: String): PreviewStream {
        val cfg = cfg()
        val direct = runCatching {
            val body = send(
                "proxy/tidal/v1/tracks/$trackId/playbackinfopostpaywall",
                query = linkedMapOf(
                    "audioquality" to "LOW", "playbackmode" to "STREAM", "assetpresentation" to "FULL",
                    "countryCode" to countryCode(cfg),
                ),
                quick = true, cfg = cfg,
            )
            TidarrParsing.playbackInfo(body)
        }.getOrNull()
        if (direct != null) return direct
        val signed = TidarrParsing.signedStreamPath(send("api/stream/sign/$trackId", quick = true, cfg = cfg))
            ?: throw TidarrException("No preview for this song")
        val base = normalizeUrl(cfg.url).toHttpUrlOrNull() ?: throw TidarrException("The Tidarr address isn't valid")
        val url = base.resolve(signed) ?: throw TidarrException("No preview for this song")
        return PreviewStream(url.toString(), mimeType = null, codecs = null, fullQuality = true)
    }

    suspend fun queue(): List<TidarrQueueItem> = TidarrParsing.queue(send("api/queue/list"))

    /** Tries every failed item in Tidarr's queue again. */
    suspend fun retryFailed() {
        send("api/retry-failed", verb = "POST")
    }

    suspend fun remove(id: String) {
        send("api/remove", verb = "DELETE", body = buildJsonObject { put("id", id) }.toString())
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            return url
        }
    }
}
