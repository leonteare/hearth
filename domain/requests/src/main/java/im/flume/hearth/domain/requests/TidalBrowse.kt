// Searching and browsing Tidal through Tidarr, with results checked against what the server already has.
package im.flume.hearth.domain.requests

import im.flume.hearth.core.network.TidalAlbumPage
import im.flume.hearth.core.network.TidalArtistPage
import im.flume.hearth.core.network.TidalSearchResults
import im.flume.hearth.domain.music.Availability
import im.flume.hearth.domain.music.RequestMatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Extensions on [RequestsRepository], which owns the caches they use.
 */

/** Changes whenever the library does (a sync, or requested music arriving). */
internal fun RequestsRepository.catalogKey() = "${session.lastSyncAt}:$catalogVersion"

internal suspend fun RequestsRepository.localCatalog(): RequestMatching.LocalCatalog = withContext(Dispatchers.Default) {
    val key = catalogKey()
    catalog?.takeIf { it.first == key }?.second
        ?: RequestMatching.LocalCatalog(db.library().allSongsOnce(), db.library().allAlbumsOnce(), db.library().allArtistsOnce()).also { catalog = key to it }
}

/** The body of [RequestsRepository.searchTidal]. */
internal suspend fun RequestsRepository.searchNotOnServer(query: String): TidalSearchResults {
    val raw = tidarr.search(query, limit = 15)
    val filtered = localCatalog().filter(raw)
    val cat = localCatalog()
    // Artists the server has are shown from the library (their page fills in what's missing).
    val artists = filtered.artists.filter { cat.localArtist(it.name) == null }
    return TidalSearchResults(filtered.tracks.take(5), filtered.albums.take(5), artists.take(3))
        .also { recentSearches.put(query, catalogKey(), it) }
}

/** The body of [RequestsRepository.artistByName]. */
internal suspend fun RequestsRepository.findArtistByName(name: String): TidalArtistPage? {
    val key = RequestMatching.baseTitle(name).ifEmpty { name.lowercase() }
    val id = artistIds.get(key) ?: run {
        val found = Availability.pickArtist(tidarr.search(name, limit = 10).artists, name)?.id.orEmpty()
        artistIds.put(key, found)
        found
    }
    return id.takeIf { it.isNotEmpty() }?.let { tidarr.artistPage(it) }
}

/** The body of [RequestsRepository.albumByName]. */
internal suspend fun RequestsRepository.findAlbumByName(title: String, artist: String): TidalAlbumPage? {
    val key = "$title|$artist".lowercase()
    val id = albumIds.get(key) ?: run {
        val q = "${RequestMatching.searchTitle(title)} ${RequestMatching.splitArtists(artist).first()}"
        val found = Availability.pickAlbum(tidarr.search(q, limit = 15).albums, title, artist)?.id.orEmpty()
        albumIds.put(key, found)
        found
    }
    return id.takeIf { it.isNotEmpty() }?.let { tidarr.albumPage(it) }
}

/**
 * Recent searches (5 minutes), so retyping or going back shows results instantly. Each is tied to
 * the library as it was: once music arrives or a sync runs, it's searched again, so something that
 * just landed shows as yours instead of still "not on your server".
 */
internal class RecentSearches {
    private val searches = object : LinkedHashMap<String, Triple<Long, String, TidalSearchResults>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<Long, String, TidalSearchResults>>?) = size > 30
    }

    fun put(query: String, catalogKey: String, results: TidalSearchResults) = synchronized(searches) {
        searches[query.lowercase()] = Triple(System.currentTimeMillis(), catalogKey, results)
    }

    fun get(query: String, catalogKey: String): TidalSearchResults? = synchronized(searches) {
        searches[query.lowercase()]
            ?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 && it.second == catalogKey }?.third
    }
}
