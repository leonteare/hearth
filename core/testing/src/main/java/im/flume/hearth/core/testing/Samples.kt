// Sample data for tests and screenshots: realistic-looking but made up, with no server behind it.
package im.flume.hearth.core.testing

import im.flume.hearth.core.data.AlbumEntity
import im.flume.hearth.core.data.ArtistEntity
import im.flume.hearth.core.data.PlaylistEntity
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.playback.QueueEntry
import im.flume.hearth.core.playback.SpokenNowPlaying
import im.flume.hearth.core.playback.toMediaItem
import im.flume.hearth.core.data.spoken.BookEntity
import im.flume.hearth.core.data.spoken.BookRow
import im.flume.hearth.core.data.spoken.EpisodeEntity
import im.flume.hearth.core.data.spoken.EpisodeRow
import im.flume.hearth.core.data.spoken.PodcastEntity
import im.flume.hearth.core.data.spoken.SpokenDownloadState

/**
 * Builders with sensible defaults, so a test names only what matters to it. Cover ids are null by default:
 * tests never reach a server, and a null cover draws the initials placeholder, which is stable.
 */
object Samples {
    /** A fixed "now" (2026-01-15 12:00 UTC), so dates and "x days ago" never drift. */
    const val NOW_MS = 1_768_478_400_000L

    /** 12 Jun 2024, noon UTC: old enough that rows show the full date, which never changes with the day the tests run. */
    const val PUBLISHED_MS = 1_718_193_600_000L

    fun song(
        id: String = "s1",
        title: String = "Northern Lights",
        artist: String = "The Quiet Hours",
        album: String = "Long Winter",
        albumId: String? = "al1",
        track: Int = 1,
        disc: Int = 1,
        durationSec: Int = 214,
        starred: Boolean = false,
        coverArt: String? = null,
        year: Int? = 2021,
        genre: String? = "Indie",
        playCount: Long = 3,
    ) = SongEntity(
        id = id, title = title, album = album, albumId = albumId, artist = artist, artistId = "ar1",
        track = track, disc = disc, year = year, genre = genre, durationSec = durationSec, coverArt = coverArt,
        suffix = "flac", sizeBytes = 24_000_000, starred = starred, playCount = playCount, created = "2024-03-01T10:00:00Z",
    )

    /** An album's worth of songs, numbered 1..[count]. */
    fun songs(count: Int = 5, album: String = "Long Winter", artist: String = "The Quiet Hours"): List<SongEntity> {
        val titles = listOf("Northern Lights", "Paper Boats", "Glasshouse", "Slow Tide", "Ember", "Lantern", "Harbour", "Snowfall")
        return List(count) { i ->
            song(id = "s${i + 1}", title = titles[i % titles.size], album = album, artist = artist, track = i + 1, durationSec = 180 + i * 17)
        }
    }

    fun album(
        id: String = "al1",
        name: String = "Long Winter",
        artist: String = "The Quiet Hours",
        songCount: Int = 10,
        year: Int? = 2021,
        starred: Boolean = false,
    ) = AlbumEntity(
        id = id, name = name, artist = artist, artistId = "ar1", coverArt = null, songCount = songCount,
        durationSec = songCount * 200, year = year, genre = "Indie", created = "2024-03-01T10:00:00Z", starred = starred, playCount = 12,
    )

    fun artist(id: String = "ar1", name: String = "The Quiet Hours", albumCount: Int = 3, starred: Boolean = false) =
        ArtistEntity(id = id, name = name, albumCount = albumCount, coverArt = null, starred = starred)

    fun playlist(id: String = "pl1", name: String = "Sunday Morning", songCount: Int = 24, owner: String? = "leon") =
        PlaylistEntity(id = id, name = name, songCount = songCount, durationSec = songCount * 210, coverArt = null, owner = owner, changed = null)

    fun podcast(id: String = "pod1", title: String = "The Long Read", author: String? = "Weekly Audio", numEpisodes: Int = 120) =
        PodcastEntity(
            id = id, libraryId = "lib1", title = title, author = author, description = "Stories worth your time, read aloud.",
            feedUrl = null, imageUrl = null, language = "en", explicit = false, tags = "", autoDownloadEpisodes = false,
            numEpisodes = numEpisodes, addedAt = NOW_MS, updatedAt = NOW_MS, episodesFetchedAt = NOW_MS,
        )

    fun episode(
        id: String = "ep1",
        podcastId: String = "pod1",
        title: String = "The town that forgot to sleep",
        durationSec: Double = 2_520.0,
        publishedAt: Long = PUBLISHED_MS,
    ) = EpisodeEntity(
        id = id, libraryItemId = podcastId, title = title, subtitle = null, description = "A short description of the episode.",
        publishedAt = publishedAt, durationSec = durationSec, sizeBytes = 40_000_000, ino = "ino-$id", mimeType = "audio/mpeg",
        season = null, episode = null, episodeType = null, addedAt = publishedAt,
    )

    fun episodeRow(
        episode: EpisodeEntity = episode(),
        podcastTitle: String = "The Long Read",
        progressSec: Double? = null,
        finished: Boolean = false,
        downloadState: SpokenDownloadState? = null,
    ) = EpisodeRow(
        episode = episode, podcastTitle = podcastTitle, podcastAuthor = "Weekly Audio", progressSec = progressSec, finished = finished,
        progressUpdatedAt = null, downloadState = downloadState, downloadedBytes = null,
    )

    fun book(id: String = "bk1", title: String = "The Lighthouse Keeper", author: String? = "Mara Ellison", durationSec: Double = 36_000.0) =
        BookEntity(
            id = id, libraryId = "lib2", title = title, subtitle = null, author = author, narrator = "Sam Rowe", series = null,
            description = "A novel about a remote island.", publishedYear = "2019", durationSec = durationSec, sizeBytes = 300_000_000,
            numTracks = 1, numChapters = 24, tags = "", addedAt = NOW_MS, updatedAt = NOW_MS, detailsFetchedAt = NOW_MS,
        )

    fun bookRow(book: BookEntity = book(), progressSec: Double? = null, finished: Boolean = false, downloadState: SpokenDownloadState? = null) =
        BookRow(book = book, progressSec = progressSec, finished = finished, progressUpdatedAt = null, downloadState = downloadState, downloadedBytes = null)

    /** A queue entry for [song] at [index], with a fixed queue key so nothing random reaches a screenshot. */
    fun queueEntry(song: SongEntity, index: Int = 0) = QueueEntry(index, song.toMediaItem(queueKey = "k$index"))

    /** The player with [song] loaded, and [queue] (which should include it) as the queue. */
    fun playerState(
        song: SongEntity = song(),
        queue: List<SongEntity> = listOf(song),
        isPlaying: Boolean = true,
        shuffle: Boolean = false,
        sourceLabel: String = "Long Winter",
    ): PlayerUiState {
        val entries = queue.mapIndexed { i, s -> queueEntry(s, i) }
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        return PlayerUiState(
            current = entries.getOrNull(index)?.item ?: song.toMediaItem(queueKey = "k0"),
            currentIndex = index,
            isPlaying = isPlaying,
            durationMs = song.durationSec * 1000L,
            shuffle = shuffle,
            sourceLabel = sourceLabel,
            queue = entries,
        )
    }

    /**
     * The player with a podcast episode loaded. The media item looks like the one the service builds:
     * the episode's title, the show as artist.
     */
    fun spokenPlayerState(
        title: String = "The town that forgot to sleep",
        show: String = "The Long Read",
        durationMs: Long = 2_520_000,
        speed: Float = 1f,
        isPlaying: Boolean = true,
        book: Boolean = false,
    ): PlayerUiState {
        val item = song(id = "spoken-ep1", title = title, artist = show, album = show).toMediaItem(queueKey = "k0")
        return PlayerUiState(
            current = item,
            isPlaying = isPlaying,
            durationMs = durationMs,
            queue = listOf(QueueEntry(0, item)),
            spoken = SpokenNowPlaying(
                libraryItemId = "pod1", episodeId = if (book) null else "ep1", durationMs = durationMs, speed = speed, canGoBackToMusic = true,
            ),
        )
    }
}
