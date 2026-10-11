package im.flume.hearth.data

import java.text.Normalizer

/** A search result with how well its text matched (see [SearchMatch]); higher is better. */
data class Scored<out T>(val item: T, val relevance: Int)

/**
 * How well a query matches an item's title ([primary]) and the rest of its text ([secondary], e.g.
 * the artist or show). One scale for everything Search shows (songs, podcasts, music that isn't on
 * the server), so a single list can be ranked fairly: the exact title beats a title that starts with
 * the query, which beats one that contains it, which beats every word appearing somewhere, which
 * beats a near-miss with a typo.
 */
object SearchMatch {
    const val EXACT = 500
    const val STARTS_WITH = 400
    const val CONTAINS = 300
    const val ALL_WORDS = 200
    const val CLOSE = 100
    const val NONE = 0

    /** A query, normalised once. */
    class Query(raw: String) {
        val text: String = SearchIndex.norm(raw)
        val words: List<String> = text.split(' ').filter { it.isNotBlank() }
        val isBlank: Boolean get() = words.isEmpty()
    }

    /**
     * The match without typos, or [NONE] when some word of the query is missing. [primary] and
     * [secondary] must already be normalised with [SearchIndex.norm].
     */
    fun exact(q: Query, primary: String, secondary: String): Int {
        if (q.isBlank) return NONE
        val all = "$primary $secondary"
        if (!q.words.all { all.contains(it) }) return NONE
        return when {
            primary == q.text -> EXACT
            primary.startsWith(q.text) -> STARTS_WITH
            primary.contains(q.text) -> CONTAINS
            else -> ALL_WORDS
        }
    }

    /**
     * Every query word close to some word in the item (allowing a typo or two): [CLOSE] minus the
     * number of typos, or [NONE].
     */
    fun close(q: Query, primary: String, secondary: String): Int {
        if (q.isBlank) return NONE
        val words = "$primary $secondary".split(' ')
        var total = 0
        for (t in q.words) {
            val best = words.minOfOrNull { w -> SearchIndex.distance(t, w.take(t.length + 2)) } ?: return NONE
            if (best > SearchIndex.allowedTypos(t)) return NONE
            total += best
        }
        return (CLOSE - total).coerceAtLeast(1)
    }

    /** The exact match if there is one, else the close one. */
    fun score(q: Query, primary: String, secondary: String): Int =
        exact(q, primary, secondary).takeIf { it > NONE } ?: close(q, primary, secondary)

    /** Same as [score], normalising the raw [title] and [rest] first. */
    fun scoreText(q: Query, title: String, rest: String): Int = score(q, SearchIndex.norm(title), SearchIndex.norm(rest))
}

/**
 * In-memory search over the whole library. Matching ignores case, accents and a leading "the",
 * so "beyonce" finds "Beyoncé" and "beatles" finds "The Beatles". If nothing matches that way,
 * it falls back to near-misses ("beetles", "radiohed").
 */
class SearchIndex(songs: List<SongEntity>, albums: List<AlbumEntity>, artists: List<ArtistEntity>) {

    private class Entry<T>(val item: T, val primary: String, val secondary: String, val popularity: Long)

    private val songEntries = songs.map { Entry(it, norm(it.title), norm("${it.artist} ${it.album}"), it.playCount) }
    private val albumEntries = albums.map { Entry(it, norm(it.name), norm(it.artist), it.playCount) }
    private val artistEntries = artists.map { Entry(it, norm(it.name), "", it.albumCount.toLong()) }

    /** Each list best first: by [SearchMatch] relevance, then the most played (or most albums). */
    data class Results(
        val songHits: List<Scored<SongEntity>>,
        val albumHits: List<Scored<AlbumEntity>>,
        val artistHits: List<Scored<ArtistEntity>>,
        val fuzzy: Boolean,
    ) {
        val songs: List<SongEntity> get() = songHits.map { it.item }
        val albums: List<AlbumEntity> get() = albumHits.map { it.item }
        val artists: List<ArtistEntity> get() = artistHits.map { it.item }
        val isEmpty: Boolean get() = songHits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty()

        companion object {
            val EMPTY = Results(emptyList(), emptyList(), emptyList(), false)
        }
    }

    fun search(query: String): Results {
        val q = SearchMatch.Query(query)
        if (q.isBlank) return Results.EMPTY
        val exact = Results(
            rank(songEntries, 50) { SearchMatch.exact(q, it.primary, it.secondary) },
            rank(albumEntries, 10) { SearchMatch.exact(q, it.primary, it.secondary) },
            rank(artistEntries, 5) { SearchMatch.exact(q, it.primary, it.secondary) },
            fuzzy = false,
        )
        if (!exact.isEmpty) return exact
        return Results(
            rank(songEntries, 30) { SearchMatch.close(q, it.primary, it.secondary) },
            rank(albumEntries, 10) { SearchMatch.close(q, it.primary, it.secondary) },
            rank(artistEntries, 5) { SearchMatch.close(q, it.primary, it.secondary) },
            fuzzy = true,
        )
    }

    private fun <T> rank(entries: List<Entry<T>>, limit: Int, score: (Entry<T>) -> Int): List<Scored<T>> =
        entries.mapNotNull { e -> score(e).takeIf { it > SearchMatch.NONE }?.let { e to it } }
            .sortedWith(compareByDescending<Pair<Entry<T>, Int>> { it.second }.thenByDescending { it.first.popularity })
            .take(limit).map { Scored(it.first.item, it.second) }

    companion object {
        private val marks = Regex("\\p{Mn}+")
        private val punctuation = Regex("[^a-z0-9 ]")
        private val spaces = Regex("\\s+")

        fun norm(s: String): String {
            val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "")
                .replace("&", " and ").replace(punctuation, " ").replace(spaces, " ").trim()
            return plain.removePrefix("the ")
        }

        fun allowedTypos(word: String) = when {
            word.length <= 3 -> 0
            word.length <= 6 -> 1
            else -> 2
        }

        /** Damerau-Levenshtein (optimal string alignment) distance. */
        fun distance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            return d[a.length][b.length]
        }
    }
}
