package im.flume.hearth.data

import im.flume.hearth.api.TidalAlbum
import im.flume.hearth.api.TidalArtist
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.data.RequestMatching.artistMatches
import im.flume.hearth.data.RequestMatching.baseTitle
import im.flume.hearth.data.SearchIndex.Companion.norm

/**
 * Music is either on the server ([Owned]) or not on it yet ([Missing]); the UI shows both in the same
 * rows, cards and pages, the missing ones dimmed. Pure, so all of the merging is unit tested.
 */
sealed interface SongItem {
    val key: String
    data class Owned(val song: SongEntity) : SongItem { override val key get() = "s:${song.id}" }
    data class Missing(val track: TidalTrack) : SongItem { override val key get() = "t:${track.id}" }
}

sealed interface AlbumItem {
    val key: String
    val title: String
    val year: Int?
    data class Owned(val album: AlbumEntity) : AlbumItem {
        override val key get() = "s:${album.id}"
        override val title get() = album.name
        override val year get() = album.year
    }
    data class Missing(val album: TidalAlbum) : AlbumItem {
        override val key get() = "t:${album.id}"
        override val title get() = album.title
        override val year get() = album.year?.toIntOrNull()
    }
}

sealed interface ArtistItem {
    val key: String
    data class Owned(val artist: ArtistEntity) : ArtistItem { override val key get() = "s:${artist.id}" }
    data class Missing(val artist: TidalArtist) : ArtistItem { override val key get() = "t:${artist.id}" }
}

/** An artist's releases: albums, then singles and EPs, owned and missing together. */
data class Discography(val albums: List<AlbumItem>, val singles: List<AlbumItem>)

object Availability {
    /** Same release, ignoring editions: "Album (Deluxe Edition)" is "Album". */
    fun sameRelease(a: String, b: String): Boolean =
        norm(a) == norm(b) || baseTitle(a).let { it.isNotEmpty() && it == baseTitle(b) }

    /**
     * The [remote] search results (already filtered against the whole server) that aren't among the
     * library results shown: drops any that still look like one of them (same title and artist, or
     * same name), and remote duplicates of each other.
     */
    fun notInLibrary(
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        remote: TidalSearchResults?,
    ): TidalSearchResults {
        val r = remote ?: return TidalSearchResults()
        val artistNames = artists.map { norm(it.name) }.toSet()
        return TidalSearchResults(
            tracks = r.tracks
                .filterNot { t -> songs.any { s -> norm(s.title) == norm(t.displayTitle) && artistMatches(t.artists, s.artist) } }
                .distinctBy { norm(it.displayTitle) + "|" + norm(it.artist) },
            albums = r.albums
                .filterNot { a -> albums.any { l -> sameRelease(l.name, a.title) && artistMatches(a.artists, l.artist) } }
                .distinctBy { baseTitle(it.title) + "|" + norm(it.artist) },
            artists = r.artists.filterNot { norm(it.name) in artistNames }.distinctBy { norm(it.name) },
        )
    }

    /**
     * Owned albums plus the ones the server doesn't have, newest first. A remote release counts as
     * owned when its title (ignoring editions) matches an owned one; remote editions of the same
     * release appear once. Owned albums that are singles or EPs remotely go with the singles.
     */
    fun discography(owned: List<AlbumEntity>, remoteAlbums: List<TidalAlbum>, remoteSingles: List<TidalAlbum>): Discography {
        fun ownedMatch(a: TidalAlbum) = owned.firstOrNull { sameRelease(it.name, a.title) }
        val ownedSingles = owned.filter { o -> remoteSingles.any { sameRelease(o.name, it.title) } && remoteAlbums.none { sameRelease(o.name, it.title) } }
        val ownedAlbums = owned - ownedSingles.toSet()
        fun missing(list: List<TidalAlbum>, alsoSkip: List<TidalAlbum>) = list
            .filter { ownedMatch(it) == null }
            .distinctBy { baseTitle(it.title).ifEmpty { norm(it.title) } }
            .filter { a -> alsoSkip.none { sameRelease(it.title, a.title) } }
        val albums = ownedAlbums.map { AlbumItem.Owned(it) } + missing(remoteAlbums, emptyList()).map { AlbumItem.Missing(it) }
        // A single that is also on an album is shown once, with the albums.
        val singles = ownedSingles.map { AlbumItem.Owned(it) } + missing(remoteSingles, remoteAlbums).map { AlbumItem.Missing(it) }
        return Discography(newestFirst(albums), newestFirst(singles))
    }

    private fun newestFirst(list: List<AlbumItem>): List<AlbumItem> =
        list.sortedWith(compareByDescending<AlbumItem> { it.year ?: Int.MIN_VALUE }.thenBy { if (it is AlbumItem.Owned) 0 else 1 })

    /** Disc and track number for ordering an album's songs. */
    private fun position(item: SongItem): Pair<Int, Int> = when (item) {
        is SongItem.Owned -> item.song.disc to item.song.track
        is SongItem.Missing -> (item.track.volumeNumber ?: 1) to (item.track.trackNumber ?: 0)
    }

    /**
     * An album's songs: the server's, plus the remote tracks it doesn't have, in disc and track order.
     * A remote track is owned when an owned song has its ISRC or the same title (ignoring "Remastered"
     * style extras); within one album the artist doesn't need to match.
     */
    fun albumTracks(owned: List<SongEntity>, remote: List<TidalTrack>): List<SongItem> {
        if (remote.isEmpty()) return owned.map { SongItem.Owned(it) }
        val claimed = HashSet<String>()
        val missing = remote.filter { t ->
            val match = owned.firstOrNull { s -> s.id !in claimed && t.isrc != null && t.isrc.uppercase() in RequestMatching.isrcs(s.isrc) }
                ?: owned.firstOrNull { s -> s.id !in claimed && (norm(s.title) == norm(t.displayTitle) || norm(s.title) == norm(t.title)) }
                ?: owned.firstOrNull { s -> s.id !in claimed && baseTitle(s.title).let { it.isNotEmpty() && it == baseTitle(t.title) } }
            if (match != null) claimed += match.id
            match == null
        }
        val items = owned.map { SongItem.Owned(it) } + missing.map { SongItem.Missing(it) }
        // Stable: owned songs keep their order among themselves; a missing track goes after an owned one at the same spot.
        return items.sortedWith(compareBy<SongItem>({ position(it).first }, { position(it).second }, { if (it is SongItem.Owned) 0 else 1 }))
    }

    /** The best remote artist for a name: exact normalised name, else null (a wrong artist page is worse than none). */
    fun pickArtist(candidates: List<TidalArtist>, name: String): TidalArtist? = candidates.firstOrNull { norm(it.name) == norm(name) }

    /** The remote album that is [title] by [artist]: same release and artist, preferring the exact title, then the longest. */
    fun pickAlbum(candidates: List<TidalAlbum>, title: String, artist: String): TidalAlbum? {
        val byArtist = candidates.filter { artistMatches(it.artists, artist) }
        return byArtist.firstOrNull { norm(it.title) == norm(title) }
            ?: byArtist.filter { sameRelease(it.title, title) }.maxByOrNull { it.numberOfTracks ?: 0 }
    }
}

/** Where a 30 second preview starts and how long it plays, in milliseconds. */
object PreviewWindow {
    const val LENGTH_MS = 30_000L
    const val FADE_MS = 1_000L

    /**
     * Starts 35% of the way in, or 30 s in if that's earlier, moved back so the whole 30 s fits.
     * Songs shorter than 30 s play from the start; an unknown length starts at 0.
     */
    fun of(durationMs: Long): Pair<Long, Long> {
        if (durationMs <= 0) return 0L to LENGTH_MS
        if (durationMs <= LENGTH_MS) return 0L to durationMs
        val start = minOf((durationMs * 0.35).toLong(), 30_000L).coerceAtMost(durationMs - LENGTH_MS).coerceAtLeast(0)
        return start to LENGTH_MS
    }

    /** Volume at [elapsedMs] into a preview of [lengthMs]: full, then a linear fade over the last second. */
    fun volume(elapsedMs: Long, lengthMs: Long): Float {
        val left = lengthMs - elapsedMs
        return if (left >= FADE_MS) 1f else (left.toFloat() / FADE_MS).coerceIn(0f, 1f)
    }
}
