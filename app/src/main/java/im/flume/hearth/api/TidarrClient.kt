package im.flume.hearth.api

import im.flume.hearth.util.scrubSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where Tidarr lives. [apiKey] is optional (Tidarr's auth can be off) and is sent as `X-Api-Key`. */
data class TidarrConfig(val url: String, val apiKey: String?)

class TidarrException(message: String) : IOException(message)

/** Tidarr's names for what can be downloaded. */
enum class TidalType(val api: String) {
    TRACK("track"), ALBUM("album"), ARTIST("artist");

    companion object {
        fun of(api: String): TidalType = entries.firstOrNull { it.api == api } ?: TRACK
    }
}

data class TidalTrack(
    val id: String,
    val title: String,
    val version: String?,
    val durationSec: Int?,
    val isrc: String?,
    val explicit: Boolean,
    val artists: List<String>,
    val albumId: String?,
    val albumTitle: String?,
    val cover: String?,
    /** The first (main) artist's Tidal id, for "Go to artist". */
    val artistId: String? = null,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
) {
    /** "Title (2001 Remaster)" when Tidal lists a version that isn't already part of the title. */
    val displayTitle: String get() = displayTitle(title, version)
    val artist: String get() = artists.joinToString(", ")
}

data class TidalAlbum(
    val id: String,
    val title: String,
    val cover: String?,
    val numberOfTracks: Int?,
    val releaseDate: String?,
    val type: String?,
    val artists: List<String>,
    val artistId: String? = null,
    val durationSec: Int? = null,
) {
    val artist: String get() = artists.joinToString(", ")
    val year: String? get() = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
}

data class TidalArtist(val id: String, val name: String, val picture: String?)

data class TidalSearchResults(
    val tracks: List<TidalTrack> = emptyList(),
    val albums: List<TidalAlbum> = emptyList(),
    val artists: List<TidalArtist> = emptyList(),
)

/** Everything the Tidal artist page shows. */
data class TidalArtistPage(
    val artist: TidalArtist,
    val topTracks: List<TidalTrack>,
    val albums: List<TidalAlbum>,
    val singles: List<TidalAlbum>,
)

/** A Tidal album and its tracks, in disc and track order. */
data class TidalAlbumPage(val album: TidalAlbum, val tracks: List<TidalTrack>)

/**
 * A small in-memory cache whose entries expire after [ttlMs]; the least recently used entry goes
 * once it holds [max]. Keeps going back and forth between Tidal pages instant.
 */
class TtlCache<K, V>(private val max: Int, private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val map = LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true)

    @Synchronized
    fun get(key: K): V? {
        val (at, value) = map[key] ?: return null
        if (clock() - at > ttlMs) { map.remove(key); return null }
        return value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = clock() to value
        while (map.size > max) map.remove(map.keys.first())
    }
}

/** One row of Tidarr's download queue. [status] is Tidarr's own word (queue_download, download, …). */
data class TidarrQueueItem(
    val id: String,
    val type: String?,
    val title: String?,
    val artist: String?,
    val status: String?,
    val error: Boolean,
    val errorStage: String?,
)

fun displayTitle(title: String, version: String?): String {
    val v = version?.trim().orEmpty()
    return if (v.isEmpty() || title.contains(v, ignoreCase = true)) title else "$title ($v)"
}

/** Public Tidal image CDN; no login needed. Works for album covers and artist pictures. */
fun tidalImageUrl(uuid: String?, size: Int = 320): String? =
    uuid?.takeIf { it.isNotBlank() }?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/${size}x$size.jpg" }

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
                    is java.net.UnknownHostException -> "Couldn't find Tidarr (is the address right?)"
                    is javax.net.ssl.SSLException -> "Couldn't make a secure connection to Tidarr"
                    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "Tidarr took too long to answer"
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

/** JSON handling for Tidarr, kept free of networking so it can be unit tested against captured responses. */
object TidarrParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun items(root: JsonObject, key: String): List<JsonObject> =
        root[key].obj()?.get("items").arr()?.mapNotNull { it.obj() }.orEmpty()

    private fun artistNames(o: JsonObject): List<String> =
        o["artists"].arr()?.mapNotNull { it.obj()?.get("name").str() }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(o["artist"].obj()?.get("name").str())

    private fun firstArtistId(o: JsonObject): String? =
        o["artists"].arr()?.firstOrNull()?.obj()?.get("id").str() ?: o["artist"].obj()?.get("id").str()

    private fun parseTrack(t: JsonObject): TidalTrack? {
        val album = t["album"].obj()
        return TidalTrack(
            id = t["id"].str() ?: return null,
            title = t["title"].str() ?: return null,
            version = t["version"].str(),
            durationSec = t["duration"].int(),
            isrc = t["isrc"].str()?.uppercase(),
            explicit = t["explicit"].bool() ?: false,
            artists = artistNames(t),
            albumId = album?.get("id").str(),
            albumTitle = album?.get("title").str(),
            cover = album?.get("cover").str(),
            artistId = firstArtistId(t),
            trackNumber = t["trackNumber"].int(),
            volumeNumber = t["volumeNumber"].int(),
        )
    }

    private fun parseAlbum(a: JsonObject): TidalAlbum? = TidalAlbum(
        id = a["id"].str() ?: return null,
        title = a["title"].str() ?: return null,
        cover = a["cover"].str(),
        numberOfTracks = a["numberOfTracks"].int(),
        releaseDate = a["releaseDate"].str(),
        type = a["type"].str(),
        artists = artistNames(a),
        artistId = firstArtistId(a),
        durationSec = a["duration"].int(),
    )

    private fun parseArtist(a: JsonObject): TidalArtist? =
        TidalArtist(a["id"].str() ?: return null, a["name"].str() ?: return null, a["picture"].str())

    fun search(body: String): TidalSearchResults {
        val root = json.parseToJsonElement(body).obj() ?: return TidalSearchResults()
        return TidalSearchResults(
            items(root, "tracks").mapNotNull(::parseTrack),
            items(root, "albums").mapNotNull(::parseAlbum),
            items(root, "artists").mapNotNull(::parseArtist),
        )
    }

    private fun rootItems(body: String): List<JsonObject> =
        json.parseToJsonElement(body).obj()?.get("items").arr()?.mapNotNull { it.obj() }.orEmpty()

    /** v1/artists/{id}: the artist, or null if the answer isn't one. */
    fun artist(body: String): TidalArtist? = json.parseToJsonElement(body).obj()?.let(::parseArtist)

    /** v1/albums/{id}. */
    fun album(body: String): TidalAlbum? = json.parseToJsonElement(body).obj()?.let(::parseAlbum)

    /** A page of albums (v1/artists/{id}/albums). */
    fun albums(body: String): List<TidalAlbum> = rootItems(body).mapNotNull(::parseAlbum)

    /** A page of tracks (v1/artists/{id}/toptracks, v1/albums/{id}/tracks). */
    fun tracks(body: String): List<TidalTrack> = rootItems(body).mapNotNull(::parseTrack)

    fun queue(body: String): List<TidarrQueueItem> {
        val root = json.parseToJsonElement(body)
        val list = root.obj()?.get("queue").arr() ?: root.arr() ?: return emptyList()
        return list.mapNotNull { it.obj() }.mapNotNull { q ->
            val errorField = q["error"]
            TidarrQueueItem(
                id = q["id"].str() ?: return@mapNotNull null,
                type = q["type"].str(),
                title = q["title"].str(),
                artist = q["artist"].str(),
                status = q["status"].str(),
                error = errorField.bool() ?: (errorField.str()?.let { it != "false" } ?: false),
                errorStage = q["errorStage"].str(),
            )
        }
    }

    /** tiddl_config.auth.country_code from /api/settings, or null. */
    fun countryCode(body: String): String? =
        json.parseToJsonElement(body).obj()?.get("tiddl_config").obj()?.get("auth").obj()?.get("country_code").str()
            ?.takeIf { it.length == 2 }?.uppercase()

    fun saveBody(type: TidalType, id: String, title: String? = null, artist: String? = null): String = buildJsonObject {
        put("item", buildJsonObject {
            put("id", id)
            put("url", "https://listen.tidal.com/${type.api}/$id")
            put("type", type.api)
            put("status", "queue_download")
            title?.let { put("title", it) }
            artist?.let { put("artist", it) }
        })
    }.toString()
}
