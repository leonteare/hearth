package im.flume.hearth.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Subsonic token credentials. The plain password is never stored, only md5(password + salt). */
data class Credentials(
    val serverUrl: String,
    val username: String,
    val salt: String,
    val token: String,
) {
    companion object {
        fun fromPassword(serverUrl: String, username: String, password: String): Credentials {
            val salt = randomSalt()
            return Credentials(normalizeUrl(serverUrl), username.trim(), salt, md5Hex(password + salt))
        }

        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            return url
        }

        fun randomSalt(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun md5Hex(input: String): String =
            MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

class SubsonicException(val code: Int, message: String) : IOException(message)

class SubsonicClient(
    private val http: OkHttpClient,
    private val credentialsProvider: () -> Credentials?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun creds(): Credentials =
        credentialsProvider() ?: throw SubsonicException(-1, "Not logged in")

    fun endpoint(method: String, creds: Credentials = creds()): HttpUrl.Builder =
        "${creds.serverUrl}/rest/$method".toHttpUrl().newBuilder()
            .addQueryParameter("u", creds.username)
            .addQueryParameter("t", creds.token)
            .addQueryParameter("s", creds.salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")

    /** Stream URL. [maxBitRate] 0 means original file, no transcoding. */
    fun streamUrl(songId: String, maxBitRate: Int, creds: Credentials? = credentialsProvider()): String? {
        creds ?: return null
        return endpoint("stream", creds).apply {
            addQueryParameter("id", songId)
            if (maxBitRate > 0) {
                addQueryParameter("maxBitRate", maxBitRate.toString())
                addQueryParameter("format", "mp3")
                addQueryParameter("estimateContentLength", "true")
            } else {
                addQueryParameter("format", "raw")
            }
        }.build().toString()
    }

    fun coverArtUrl(coverArtId: String?, size: Int = 600): String? {
        if (coverArtId.isNullOrEmpty()) return null
        val c = credentialsProvider() ?: return null
        return endpoint("getCoverArt", c)
            .addQueryParameter("id", coverArtId)
            .addQueryParameter("size", size.toString())
            .build().toString()
    }

    private suspend fun call(
        method: String,
        creds: Credentials = creds(),
        params: HttpUrl.Builder.() -> Unit = {},
    ): SubsonicResponse = withContext(Dispatchers.IO) {
        val url = endpoint(method, creds).apply(params).build()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw SubsonicException(resp.code, "HTTP ${resp.code} from server")
            val body = resp.body?.string() ?: throw SubsonicException(-1, "Empty response")
            val parsed = json.decodeFromString(SubsonicEnvelope.serializer(), body).response
            if (parsed.status != "ok") {
                val err = parsed.error
                throw SubsonicException(err?.code ?: -1, err?.message ?: "Server returned an error")
            }
            parsed
        }
    }

    suspend fun ping(creds: Credentials): SubsonicResponse = call("ping", creds)

    suspend fun searchSongs(query: String, count: Int, offset: Int): List<SongDto> =
        call("search3") {
            addQueryParameter("query", query)
            addQueryParameter("songCount", count.toString())
            addQueryParameter("songOffset", offset.toString())
            addQueryParameter("albumCount", "0")
            addQueryParameter("artistCount", "0")
        }.searchResult3?.song.orEmpty()

    suspend fun albumList(type: String, size: Int, offset: Int = 0): List<AlbumDto> =
        call("getAlbumList2") {
            addQueryParameter("type", type)
            addQueryParameter("size", size.toString())
            addQueryParameter("offset", offset.toString())
        }.albumList2?.album.orEmpty()

    suspend fun album(id: String): AlbumDto? = call("getAlbum") { addQueryParameter("id", id) }.album

    suspend fun artists(): List<ArtistDto> =
        call("getArtists").artists?.index.orEmpty().flatMap { it.artist }

    suspend fun playlists(): List<PlaylistDto> = call("getPlaylists").playlists?.playlist.orEmpty()

    suspend fun playlist(id: String): PlaylistDto? =
        call("getPlaylist") { addQueryParameter("id", id) }.playlist

    suspend fun scanStatus(): ScanStatus? = runCatching { call("getScanStatus").scanStatus }.getOrNull()

    /** Structured (possibly synced) lyrics; empty when the song has none. */
    suspend fun lyricsBySongId(songId: String): List<StructuredLyrics> =
        call("getLyricsBySongId") { addQueryParameter("id", songId) }.lyricsList?.structuredLyrics.orEmpty()

    /** Older plain-text lyrics endpoint, matched by artist and title. */
    suspend fun lyricsByName(artist: String, title: String): String? =
        call("getLyrics") {
            addQueryParameter("artist", artist)
            addQueryParameter("title", title)
        }.lyrics?.value?.takeIf { it.isNotBlank() }

    suspend fun artistInfo(id: String): ArtistInfo? =
        call("getArtistInfo2") { addQueryParameter("id", id); addQueryParameter("count", "12") }.artistInfo2

    suspend fun albumInfo(id: String): AlbumInfo? = call("getAlbumInfo2") { addQueryParameter("id", id) }.albumInfo

    suspend fun similarSongs(songId: String, count: Int = 50): List<SongDto> =
        call("getSimilarSongs") { addQueryParameter("id", songId); addQueryParameter("count", count.toString()) }.similarSongs?.song.orEmpty()

    suspend fun similarSongsForArtist(artistId: String, count: Int = 50): List<SongDto> =
        call("getSimilarSongs2") { addQueryParameter("id", artistId); addQueryParameter("count", count.toString()) }.similarSongs2?.song.orEmpty()

    /** Creates a playlist and returns its id. */
    suspend fun createPlaylist(name: String, songIds: List<String>): String? =
        call("createPlaylist") {
            addQueryParameter("name", name)
            songIds.forEach { addQueryParameter("songId", it) }
        }.playlist?.id

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        call("updatePlaylist") {
            addQueryParameter("playlistId", playlistId)
            songIds.forEach { addQueryParameter("songIdToAdd", it) }
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int) {
        call("updatePlaylist") {
            addQueryParameter("playlistId", playlistId)
            addQueryParameter("songIndexToRemove", index.toString())
        }
    }

    suspend fun star(songId: String) { call("star") { addQueryParameter("id", songId) } }

    suspend fun unstar(songId: String) { call("unstar") { addQueryParameter("id", songId) } }

    /** [submission] false = "now playing", true = counts as a play. [timeMs] is when playback started. */
    suspend fun scrobble(songId: String, submission: Boolean, timeMs: Long? = null) {
        call("scrobble") {
            addQueryParameter("id", songId)
            addQueryParameter("submission", submission.toString())
            if (timeMs != null) addQueryParameter("time", timeMs.toString())
        }
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Hearth"
    }
}
