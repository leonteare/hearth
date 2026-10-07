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
)

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
)

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
