package im.flume.hearth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
data class SubsonicResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val error: SubsonicError? = null,
    val searchResult3: SearchResult3? = null,
    val artists: ArtistsIndex? = null,
    val albumList2: AlbumList2? = null,
    val album: AlbumDto? = null,
    val playlists: Playlists? = null,
    val playlist: PlaylistDto? = null,
    val scanStatus: ScanStatus? = null,
    val lyricsList: LyricsList? = null,
    val lyrics: PlainLyrics? = null,
    val artistInfo2: ArtistInfo? = null,
    val albumInfo: AlbumInfo? = null,
    val similarSongs: SongList? = null,
    val similarSongs2: SongList? = null,
    val starred2: Starred2? = null,
    val users: Users? = null,
    val user: UserDto? = null,
)

@Serializable
data class Users(val user: List<UserDto> = emptyList())

@Serializable
data class UserDto(val username: String = "", val adminRole: Boolean = false)

@Serializable
data class Starred2(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class SongList(val song: List<SongDto> = emptyList())

@Serializable
data class ReplayGain(
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
)

@Serializable
data class SubsonicError(val code: Int = 0, val message: String = "")

@Serializable
data class SongDto(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val size: Long? = null,
    val starred: String? = null,
    val playCount: Long? = null,
    val created: String? = null,
    val isDir: Boolean = false,
    val replayGain: ReplayGain? = null,
    /** OpenSubsonic: Navidrome sends a list of strings; tolerate a single string too. */
    val isrc: kotlinx.serialization.json.JsonElement? = null,
) {
    fun isrcCodes(): List<String> = when (val v = isrc) {
        is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        is kotlinx.serialization.json.JsonPrimitive -> if (v.isString) listOf(v.content) else emptyList()
        else -> emptyList()
    }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
}

@Serializable
data class AlbumDto(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null,
    val starred: String? = null,
    val playCount: Long? = null,
    val song: List<SongDto> = emptyList(),
    val releaseDate: ItemDate? = null,
    val originalReleaseDate: ItemDate? = null,
    val recordLabels: List<NamedItem> = emptyList(),
    val releaseTypes: List<String> = emptyList(),
)

@Serializable
data class ItemDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class NamedItem(val name: String = "")

@Serializable
data class ArtistInfo(
    val biography: String? = null,
    val lastFmUrl: String? = null,
    val similarArtist: List<ArtistDto> = emptyList(),
)

@Serializable
data class AlbumInfo(val notes: String? = null, val lastFmUrl: String? = null)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val starred: String? = null,
)

@Serializable
data class ArtistsIndex(val index: List<ArtistIndexEntry> = emptyList())

@Serializable
data class ArtistIndexEntry(val name: String = "", val artist: List<ArtistDto> = emptyList())

@Serializable
data class SearchResult3(
    val song: List<SongDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val artist: List<ArtistDto> = emptyList(),
)

@Serializable
data class AlbumList2(val album: List<AlbumDto> = emptyList())

@Serializable
data class Playlists(val playlist: List<PlaylistDto> = emptyList())

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val owner: String? = null,
    val changed: String? = null,
    val comment: String? = null,
    @SerialName("public") val isPublic: Boolean = false,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class ScanStatus(
    val scanning: Boolean = false,
    val count: Long? = null,
    val lastScan: String? = null,
)

/** OpenSubsonic getLyricsBySongId. [LyricLine.start] is in ms and only present when synced. */
@Serializable
data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())

@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val offset: Long = 0,
    val line: List<LyricLine> = emptyList(),
)

@Serializable
data class LyricLine(val start: Long? = null, val value: String = "")

/** Legacy Subsonic getLyrics (plain text). */
@Serializable
data class PlainLyrics(val artist: String? = null, val title: String? = null, val value: String? = null)
