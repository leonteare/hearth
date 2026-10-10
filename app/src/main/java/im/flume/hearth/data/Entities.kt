package im.flume.hearth.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import im.flume.hearth.api.AlbumDto
import im.flume.hearth.api.ArtistDto
import im.flume.hearth.api.PlaylistDto
import im.flume.hearth.api.SongDto

@Entity(
    tableName = "songs",
    indices = [Index("albumId"), Index("artistId"), Index("genre")],
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val album: String,
    val albumId: String?,
    val artist: String,
    val artistId: String?,
    val track: Int,
    val disc: Int,
    val year: Int?,
    val genre: String?,
    val durationSec: Int,
    val coverArt: String?,
    val suffix: String?,
    val sizeBytes: Long,
    val starred: Boolean,
    val playCount: Long,
    val created: String?,
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    /** ISRC code(s) from Navidrome (OpenSubsonic), comma-separated; used to spot songs already on the server. */
    val isrc: String? = null,
)

@Entity(tableName = "albums", indices = [Index("artistId")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artist: String,
    val artistId: String?,
    val coverArt: String?,
    val songCount: Int,
    val durationSec: Int,
    val year: Int?,
    val genre: String?,
    val created: String?,
    val starred: Boolean,
    val playCount: Long,
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val albumCount: Int,
    val coverArt: String?,
    val starred: Boolean,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val songCount: Int,
    val durationSec: Int,
    val coverArt: String?,
    val owner: String?,
    val changed: String?,
    /** Navidrome comment; Hearth keeps its sharing details on the last line (see [PlaylistSharing]). */
    val comment: String? = null,
    @ColumnInfo(defaultValue = "0") val isPublic: Boolean = false,
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "position"])
data class PlaylistSongEntity(
    val playlistId: String,
    val position: Int,
    val songId: String,
)

enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val state: DownloadState,
    val path: String?,
    val bytes: Long,
    val addedAt: Long,
    /** When the download finished (0 if it hasn't, or finished before this was recorded). */
    @ColumnInfo(defaultValue = "0") val completedAt: Long = 0,
)

/**
 * What the user said about downloading one song, which wins over any album, playlist or Liked Songs
 * download: [wanted] true = they downloaded the song itself, false = they removed it.
 */
@Entity(tableName = "song_download_prefs")
data class SongDownloadPrefEntity(
    @PrimaryKey val songId: String,
    val wanted: Boolean,
    val updatedAt: Long,
)

/** An album or playlist the user chose to keep offline; new playlist songs get downloaded on sync. */
@Entity(tableName = "pinned", primaryKeys = ["kind", "id"])
data class PinnedEntity(val kind: String, val id: String)

@Entity(tableName = "play_history", indices = [Index("playedAt")])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val songId: String,
    val albumId: String?,
    val playedAt: Long,
)

@Entity(tableName = "pending_scrobbles")
data class PendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val songId: String,
    val time: Long,
)

data class GenreCount(val genre: String, val songCount: Int)

fun SongDto.toEntity() = SongEntity(
    id = id,
    title = title,
    album = album.orEmpty(),
    albumId = albumId,
    artist = artist.orEmpty(),
    artistId = artistId,
    track = track ?: 0,
    disc = discNumber ?: 1,
    year = year,
    genre = genre?.takeIf { it.isNotBlank() },
    durationSec = duration ?: 0,
    coverArt = coverArt,
    suffix = suffix,
    sizeBytes = size ?: 0,
    starred = starred != null,
    playCount = playCount ?: 0,
    created = created,
    trackGain = replayGain?.trackGain,
    albumGain = replayGain?.albumGain,
    trackPeak = replayGain?.trackPeak,
    isrc = isrcCodes().takeIf { it.isNotEmpty() }?.joinToString(","),
)

fun AlbumDto.toEntity() = AlbumEntity(
    id = id,
    name = name,
    artist = artist.orEmpty(),
    artistId = artistId,
    coverArt = coverArt,
    songCount = songCount,
    durationSec = duration,
    year = year,
    genre = genre,
    created = created,
    starred = starred != null,
    playCount = playCount ?: 0,
)

fun ArtistDto.toEntity() = ArtistEntity(id, name, albumCount, coverArt, starred != null)

fun PlaylistDto.toEntity() = PlaylistEntity(id, name, songCount, duration, coverArt, owner, changed, comment, isPublic)

/** Where a "get this onto the server" request is. See RequestsRepository. */
enum class RequestStatus {
    /** Sent to Tidarr, waiting in its queue. */
    REQUESTED,
    /** Tidarr is downloading or processing it. */
    DOWNLOADING,
    /** Tidarr finished; waiting for Navidrome to scan it and for this phone to find it. */
    ADDING,
    DONE,
    FAILED;

    val active: Boolean get() = this != DONE && this != FAILED
}

/**
 * Music this user asked Tidarr to fetch onto the server. Kept per phone: the phone that asked is the
 * one that likes and downloads the result. [type] is "track", "album" or "artist" (Tidarr's names).
 */
@Entity(tableName = "requests", primaryKeys = ["type", "tidalId"])
data class RequestEntity(
    val tidalId: String,
    val type: String,
    val title: String,
    val artist: String,
    val coverUuid: String?,
    /** Tracks only. */
    val isrc: String?,
    /** Tracks: the album it's on; albums: release year. Shown under the title. */
    val albumTitle: String?,
    /** Tracks only, seconds; helps pick the right song once it's on the server. */
    val durationSec: Int?,
    val requestedAt: Long,
    val requestedBy: String,
    val status: RequestStatus,
    val errorMessage: String?,
    /** Local ids it was matched to: song ids for a track, the album id for an album, artist id for an artist. */
    val matchedIds: String?,
    val updatedAt: Long,
    /** True once the item has been seen in Tidarr's queue, so its disappearing means it finished (or was cleared). */
    val seenInQueue: Boolean,
    /** When Tidarr finished it (status became ADDING). */
    val finishedAt: Long?,
    /** How many times the server has been searched for it since it finished. */
    val lookupAttempts: Int,
)
