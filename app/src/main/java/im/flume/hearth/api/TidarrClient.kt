package im.flume.hearth.api

import im.flume.hearth.util.scrubSecrets
import kotlinx.coroutines.Dispatchers
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

    /** Adds [id] to Tidarr's download queue. */
    suspend fun save(type: TidalType, id: String) {
        send("api/save", verb = "POST", body = TidarrParsing.saveBody(type, id))
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

    fun search(body: String): TidalSearchResults {
        val root = json.parseToJsonElement(body).obj() ?: return TidalSearchResults()
        val tracks = items(root, "tracks").mapNotNull { t ->
            val id = t["id"].str() ?: return@mapNotNull null
            val album = t["album"].obj()
            TidalTrack(
                id = id,
                title = t["title"].str() ?: return@mapNotNull null,
                version = t["version"].str(),
                durationSec = t["duration"].int(),
                isrc = t["isrc"].str()?.uppercase(),
                explicit = t["explicit"].bool() ?: false,
                artists = artistNames(t),
                albumId = album?.get("id").str(),
                albumTitle = album?.get("title").str(),
                cover = album?.get("cover").str(),
            )
        }
        val albums = items(root, "albums").mapNotNull { a ->
            TidalAlbum(
                id = a["id"].str() ?: return@mapNotNull null,
                title = a["title"].str() ?: return@mapNotNull null,
                cover = a["cover"].str(),
                numberOfTracks = a["numberOfTracks"].int(),
                releaseDate = a["releaseDate"].str(),
                type = a["type"].str(),
                artists = artistNames(a),
            )
        }
        val artists = items(root, "artists").mapNotNull { a ->
            TidalArtist(a["id"].str() ?: return@mapNotNull null, a["name"].str() ?: return@mapNotNull null, a["picture"].str())
        }
        return TidalSearchResults(tracks, albums, artists)
    }

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

    fun saveBody(type: TidalType, id: String): String = buildJsonObject {
        put("item", buildJsonObject {
            put("id", id)
            put("url", "https://listen.tidal.com/${type.api}/$id")
            put("type", type.api)
            put("status", "queue_download")
        })
    }.toString()
}
