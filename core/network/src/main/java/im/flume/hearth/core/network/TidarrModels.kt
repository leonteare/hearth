// What Tidarr and Tidal hand back: tracks, albums, artists, pages, queue rows; plus TtlCache and image URLs.
package im.flume.hearth.core.network

/** Where Tidarr lives. [apiKey] is optional (Tidarr's auth can be off) and is sent as `X-Api-Key`. */
data class TidarrConfig(val url: String, val apiKey: String?)

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

/** A streamable URL for a preview. [fullQuality] streams (FLAC from Tidarr) should only play a short window. */
data class PreviewStream(val url: String, val mimeType: String?, val codecs: String?, val fullQuality: Boolean = false)

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
