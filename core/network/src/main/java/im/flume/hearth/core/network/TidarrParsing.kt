// JSON handling for Tidarr and Tidal answers (TidarrParsing), unit tested against captured responses.
package im.flume.hearth.core.network

import java.util.Base64
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

    /**
     * Tidal's playback info: the stream URL from a "BTS" manifest (base64 JSON with `urls`), or null
     * when the manifest is anything else (e.g. DASH) or carries no URL.
     */
    fun playbackInfo(body: String): PreviewStream? {
        val root = json.parseToJsonElement(body).obj() ?: return null
        if (root["manifestMimeType"].str() != "application/vnd.tidal.bts") return null
        val encoded = root["manifest"].str() ?: return null
        val decoded = runCatching { String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8) }.getOrNull() ?: return null
        val manifest = runCatching { json.parseToJsonElement(decoded).obj() }.getOrNull() ?: return null
        val url = manifest["urls"].arr()?.firstNotNullOfOrNull { it.str() }?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        return PreviewStream(url, manifest["mimeType"].str(), manifest["codecs"].str())
    }

    /** Tidarr's /api/stream/sign answer: the relative play path, e.g. "/api/stream/play/1?exp=…&sig=…". */
    fun signedStreamPath(body: String): String? =
        json.parseToJsonElement(body).obj()?.get("url").str()

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
