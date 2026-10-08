package im.flume.hearth.data

import java.text.Normalizer

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

    data class Results(val songs: List<SongEntity>, val albums: List<AlbumEntity>, val artists: List<ArtistEntity>, val fuzzy: Boolean)

    fun search(query: String): Results {
        val q = norm(query)
        if (q.isBlank()) return Results(emptyList(), emptyList(), emptyList(), false)
        val tokens = q.split(' ').filter { it.isNotBlank() }
        val exact = Results(
            exact(songEntries, q, tokens, 50),
            exact(albumEntries, q, tokens, 10),
            exact(artistEntries, q, tokens, 5),
            fuzzy = false,
        )
        if (exact.songs.isNotEmpty() || exact.albums.isNotEmpty() || exact.artists.isNotEmpty()) return exact
        return Results(fuzzy(songEntries, tokens, 30), fuzzy(albumEntries, tokens, 10), fuzzy(artistEntries, tokens, 5), fuzzy = true)
    }

    private fun <T> exact(entries: List<Entry<T>>, q: String, tokens: List<String>, limit: Int): List<T> =
        entries.mapNotNull { e ->
            val all = "${e.primary} ${e.secondary}"
            if (!tokens.all { all.contains(it) }) return@mapNotNull null
            val rank = when {
                e.primary == q -> 0
                e.primary.startsWith(q) -> 1
                e.primary.contains(q) -> 2
                else -> 3
            }
            Triple(e, rank, e.popularity)
        }.sortedWith(compareBy<Triple<Entry<T>, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(limit).map { it.first.item }

    /** Every query word must be close to some word in the item (allowing a typo or two). */
    private fun <T> fuzzy(entries: List<Entry<T>>, tokens: List<String>, limit: Int): List<T> =
        entries.mapNotNull { e ->
            val words = "${e.primary} ${e.secondary}".split(' ')
            var total = 0
            for (t in tokens) {
                val best = words.minOfOrNull { w -> distance(t, w.take(t.length + 2)) } ?: return@mapNotNull null
                if (best > allowedTypos(t)) return@mapNotNull null
                total += best
            }
            Triple(e, total, e.popularity)
        }.sortedWith(compareBy<Triple<Entry<T>, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(limit).map { it.first.item }

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
