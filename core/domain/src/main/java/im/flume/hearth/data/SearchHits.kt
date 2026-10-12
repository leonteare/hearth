package im.flume.hearth.data

import im.flume.hearth.api.AbsPodcastSearchResult
import im.flume.hearth.api.TidalSearchResults
import im.flume.hearth.spoken.BookRow
import im.flume.hearth.spoken.EpisodeRow
import im.flume.hearth.spoken.FeedUrls
import im.flume.hearth.spoken.PodcastEntity
import im.flume.hearth.spoken.SpokenSearchResults

/** The chips above Search's results. Episodes count as podcasts. */
enum class SearchFilter(val label: String) {
    ALL("All"), ARTISTS("Artists"), ALBUMS("Albums"), SONGS("Songs"), PODCASTS("Podcasts"), BOOKS("Books"),
}

/**
 * One row of Search's single list: anything that matched, from the library, the podcast and
 * audiobook catalogue, music not on the server yet, or the podcast directory. [score] orders the
 * list (see [SearchRanking]); [owned] is false for things that aren't on the server.
 */
sealed interface SearchHit {
    val key: String
    val score: Int
    val owned: Boolean
    val filter: SearchFilter

    data class Song(val item: SongItem, override val score: Int) : SearchHit {
        override val key get() = "song:${item.key}"
        override val owned get() = item is SongItem.Owned
        override val filter get() = SearchFilter.SONGS
    }

    data class Album(val item: AlbumItem, override val score: Int) : SearchHit {
        override val key get() = "album:${item.key}"
        override val owned get() = item is AlbumItem.Owned
        override val filter get() = SearchFilter.ALBUMS
    }

    data class Artist(val item: ArtistItem, override val score: Int) : SearchHit {
        override val key get() = "artist:${item.key}"
        override val owned get() = item is ArtistItem.Owned
        override val filter get() = SearchFilter.ARTISTS
    }

    /** A show on the server. */
    data class Podcast(val podcast: PodcastEntity, override val score: Int) : SearchHit {
        override val key get() = "pod:${podcast.id}"
        override val owned get() = true
        override val filter get() = SearchFilter.PODCASTS
    }

    /** A show from the podcast directory that isn't among the server's matches. */
    data class DirectoryPodcast(val result: AbsPodcastSearchResult, override val score: Int) : SearchHit {
        override val key get() = "dir:${result.feedUrl}"
        override val owned get() = false
        override val filter get() = SearchFilter.PODCASTS
    }

    data class Episode(val row: EpisodeRow, override val score: Int) : SearchHit {
        override val key get() = "ep:${row.episode.id}"
        override val owned get() = true
        override val filter get() = SearchFilter.PODCASTS
    }

    data class Book(val row: BookRow, override val score: Int) : SearchHit {
        override val key get() = "book:${row.book.id}"
        override val owned get() = true
        override val filter get() = SearchFilter.BOOKS
    }
}

/**
 * Puts every kind of search result into one list, best match first. Everything is scored on the
 * same text match ([SearchMatch]), so an exact title always beats a partial one whatever its kind.
 * Among equally good matches, what's already on the server comes first, then artists, albums, shows
 * and books before single songs and episodes (a name usually means the artist), then each source's
 * own order (most played, newest).
 */
object SearchRanking {
    /** Small next to the gaps between match levels, so it only breaks ties. */
    const val OWNED_BOOST = 20

    private fun weight(filter: SearchFilter, episode: Boolean) = when {
        episode -> 0
        filter == SearchFilter.ARTISTS -> 3
        filter == SearchFilter.SONGS -> 1
        else -> 2
    }

    private fun score(relevance: Int, owned: Boolean, filter: SearchFilter, episode: Boolean = false) =
        relevance + (if (owned) OWNED_BOOST else 0) + weight(filter, episode)

    fun merge(
        query: String,
        library: SearchIndex.Results,
        remote: TidalSearchResults? = null,
        spoken: SpokenSearchResults = SpokenSearchResults(),
        directory: List<AbsPodcastSearchResult> = emptyList(),
    ): List<SearchHit> {
        val q = SearchMatch.Query(query)
        if (q.isBlank) return emptyList()
        val missing = Availability.notInLibrary(library.songs, library.albums, library.artists, remote)
        val ownedFeeds = spoken.podcasts.mapNotNull { FeedUrls.norm(it.feedUrl) }.toSet()
        // Listed in each source's own order; the stable sort below keeps it among equal scores.
        val hits = buildList<SearchHit> {
            library.artistHits.forEach { add(SearchHit.Artist(ArtistItem.Owned(it.item), score(it.relevance, true, SearchFilter.ARTISTS))) }
            library.albumHits.forEach { add(SearchHit.Album(AlbumItem.Owned(it.item), score(it.relevance, true, SearchFilter.ALBUMS))) }
            library.songHits.forEach { add(SearchHit.Song(SongItem.Owned(it.item), score(it.relevance, true, SearchFilter.SONGS))) }
            spoken.podcastHits.forEach { add(SearchHit.Podcast(it.item, score(it.relevance, true, SearchFilter.PODCASTS))) }
            spoken.bookHits.forEach { add(SearchHit.Book(it.item, score(it.relevance, true, SearchFilter.BOOKS))) }
            spoken.episodeHits.forEach { add(SearchHit.Episode(it.item, score(it.relevance, true, SearchFilter.PODCASTS, episode = true))) }
            missing.artists.forEach {
                add(SearchHit.Artist(ArtistItem.Missing(it), score(SearchMatch.scoreText(q, it.name, ""), false, SearchFilter.ARTISTS)))
            }
            missing.albums.forEach {
                add(SearchHit.Album(AlbumItem.Missing(it), score(SearchMatch.scoreText(q, it.title, it.artist), false, SearchFilter.ALBUMS)))
            }
            missing.tracks.forEach {
                val rest = "${it.artist} ${it.albumTitle.orEmpty()}"
                add(SearchHit.Song(SongItem.Missing(it), score(SearchMatch.scoreText(q, it.displayTitle, rest), false, SearchFilter.SONGS)))
            }
            directory.filter { FeedUrls.norm(it.feedUrl) !in ownedFeeds }.forEach {
                add(SearchHit.DirectoryPodcast(it, score(SearchMatch.scoreText(q, it.title, it.artistName.orEmpty()), false, SearchFilter.PODCASTS)))
            }
        }
        return hits.sortedByDescending { it.score }.distinctBy { it.key }
    }

    /** The hits a filter chip shows; [SearchFilter.ALL] shows everything. */
    fun filter(hits: List<SearchHit>, filter: SearchFilter): List<SearchHit> =
        if (filter == SearchFilter.ALL) hits else hits.filter { it.filter == filter }
}
