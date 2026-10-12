package im.flume.hearth.spoken

import im.flume.hearth.api.AbsBookSearchResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.Normalizer
import kotlin.math.abs

// Finding proper details (title, author, narrator, cover…) for a phone book whose files are badly
// named, like "Goldfinch_NTG". The server's book search (Audible first) gives candidates; the book's
// real length is what tells "The Goldfinch" (32 hours) from another "Goldfinch" (21 hours).
// Plain values only, so it's unit tested.

/** A book the details search found. [durationSec] is 0 when the provider didn't say. */
@Serializable
data class BookCandidate(
    val title: String,
    val subtitle: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val publishedYear: String? = null,
    val description: String? = null,
    val cover: String? = null,
    val series: String? = null,
    val asin: String? = null,
    val isbn: String? = null,
    val durationSec: Double = 0.0,
    val abridged: Boolean = false,
    /** The search provider, e.g. "audible.uk". */
    val provider: String = "",
) {
    /** Saved with the book, e.g. "audible.uk:B00GFCRTPI". */
    val source: String get() = provider + ((asin ?: isbn)?.let { ":$it" } ?: "")
}

/** What to search for: a cleaned title, and an author when the name or tags gave one. */
data class BookQuery(val title: String, val author: String?)

enum class MatchConfidence {
    /** The title matches and the length is within 5%: safe to apply without asking. */
    HIGH,
    /** Something plausible, but not sure enough to change the book: offered on its page. */
    LOW,
    NONE,
}

data class ScoredCandidate(
    val candidate: BookCandidate,
    val score: Double,
    /** 1 = same title, 0.7 = one contains the other, less for some words in common. */
    val titleScore: Double,
    /** How far the candidate's length is from the book's, as a fraction of the book's; null if either is unknown. */
    val lengthOff: Double?,
)

data class BookMatchResult(val ranked: List<ScoredCandidate>, val confidence: MatchConfidence) {
    val best: ScoredCandidate? get() = ranked.firstOrNull()
}

/** A search and what it found, kept so the book page can offer them without asking the server again. */
@Serializable
data class SavedBookSearch(val title: String, val candidates: List<BookCandidate>)

object BookMatch {
    /** UK listeners: the UK Audible store first, then the US one if that found nothing good. */
    val PROVIDERS = listOf("audible.uk", "audible")
    /** How many results the page offers. */
    const val KEEP = 8

    private const val LONG_BOOK_SEC = 3 * 3600.0
    private val ARTICLES = setOf("the", "a", "an")
    // Closing brackets escaped: Android's regex engine (ICU) rejects a bare "]" or "}" that Java accepts.
    private val BRACKETS = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""")
    private val SPACES = Regex("""\s+""")
    private val BITRATE = Regex("""\d{2,3}k(bps)?""", RegexOption.IGNORE_CASE)
    /** Release and format words that end scene-style names: "Goldfinch_MP3", "Dune Unabridged". */
    private val RELEASE_WORDS = setOf(
        "mp3", "m4b", "m4a", "aac", "flac", "ogg", "opus", "audiobook", "audiobooks", "unabridged", "abridged",
        "retail", "web", "webrip", "audible", "aax", "cbr", "vbr",
    )
    /** "Book 1", "Part 2", "Vol. 3" at the end: left out of the search, since stores word them differently. */
    private val SERIES_MARK = Regex("""[\s,:#-]*\b(book|part|volume|vol\.?|bk\.?)\s*\d+\s*$""", RegexOption.IGNORE_CASE)
    private val AUTHOR_SPLIT = Regex("""\s+-\s+""")
    /** Study guides, summaries and the like, which share the real book's title. */
    private val COMPANION = Regex("""\b(study guide|summary|summaries|analysis|workbook|sparknotes|cliffsnotes|abridged)\b""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ names

    /**
     * A file or folder name as a person would write the title: underscores (and dots in names without
     * spaces) become spaces, bracketed bits go, and release tags at the end ("NTG", "MP3", "64k") are
     * dropped. Short capitals count as a tag only in scene-style names, so "Stephen King - IT" stays.
     */
    fun cleanName(raw: String): String {
        var s = BRACKETS.replace(raw, " ")
        val sceneStyle = '_' in s || (' ' !in s.trim() && '.' in s)
        s = s.replace('_', ' ')
        if (sceneStyle) s = s.replace('.', ' ')
        var words = SPACES.split(s.trim()).filter { it.isNotEmpty() }
        while (words.size > 1) {
            val w = words.last().trim('-', ',', '.', ':')
            val rest = words.dropLast(1)
            val shortCaps = sceneStyle && w.length in 2..5 && w.all { it.isUpperCase() || it.isDigit() } && w.any { it.isLetter() } &&
                rest.any { r -> r.any { it.isLowerCase() } }
            if (!(w.isEmpty() || w.lowercase() in RELEASE_WORDS || BITRATE.matches(w) || shortCaps)) break
            words = rest
        }
        return words.joinToString(" ").trim(' ', '-', ',', ':').ifEmpty { raw.trim() }
    }

    /**
     * What to search for, from the book's name and author as the files have them. "Author - Title" is
     * split when the author isn't known and the first part looks like a person's name, or when one part
     * is the known author.
     */
    fun query(name: String, author: String?): BookQuery {
        var title = cleanName(name)
        var who = author?.trim()?.takeIf { it.isNotEmpty() }
        val parts = title.split(AUTHOR_SPLIT)
        if (parts.size == 2 && parts.all { it.isNotBlank() }) {
            val (left, right) = parts
            when {
                who != null && sameName(left, who) -> title = right
                who != null && sameName(right, who) -> title = left
                who == null && looksLikePersonName(left) -> { who = left; title = right }
            }
        }
        val forSearch = SERIES_MARK.replace(title, "").trim().ifEmpty { title }
        return BookQuery(forSearch, who)
    }

    /** "Donna Tartt", "J. R. R. Tolkien": two to four capitalised words, no digits. */
    private fun looksLikePersonName(s: String): Boolean {
        val words = s.trim().split(SPACES)
        if (words.size !in 2..4) return false
        if (words.any { it.lowercase() in ARTICLES }) return false
        return words.all { w -> w.first().isUpperCase() && w.none { it.isDigit() } }
    }

    private fun sameName(a: String, b: String) = norm(a) == norm(b)

    /** Lower case, no accents or punctuation, no leading "The"/"A"/"An", "&" as "and". */
    fun norm(s: String?): String {
        val plain = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .lowercase().replace("&", " and ").replace("'", "").replace("’", "")
        val words = plain.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().split(' ').filter { it.isNotEmpty() }
        return (if (words.size > 1 && words[0] in ARTICLES) words.drop(1) else words).joinToString(" ")
    }

    // ------------------------------------------------------------------ scoring

    fun titleScore(query: String, candidate: BookCandidate): Double {
        val q = norm(query)
        if (q.isEmpty()) return 0.0
        val full = norm(candidate.title)
        val main = norm(candidate.title.substringBefore(':'))
        return when {
            q == full || q == main -> 1.0
            containsWords(full, q) || containsWords(q, main) -> 0.7
            else -> 0.6 * dice(q.split(' ').toSet(), full.split(' ').toSet())
        }
    }

    private fun containsWords(hay: String, needle: String) = needle.isNotEmpty() && hay.isNotEmpty() && " $hay ".contains(" $needle ")

    private fun dice(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * (a intersect b).size / (a.size + b.size)

    fun lengthOff(bookSec: Double, candidateSec: Double): Double? =
        if (bookSec <= 0 || candidateSec <= 0) null else abs(candidateSec - bookSec) / bookSec

    /** Within 3% is as good as certain; a long way off rules a candidate out even with the same title. */
    fun lengthScore(off: Double?): Double = when {
        off == null -> 0.0
        off <= 0.03 -> 1.0
        off <= 0.05 -> 0.8
        off <= 0.10 -> 0.4
        off <= 0.20 -> 0.0
        off <= 0.35 -> -0.6
        else -> -1.2
    }

    private fun authorScore(known: String?, candidate: String?): Double {
        if (known.isNullOrBlank() || candidate.isNullOrBlank()) return 0.0
        val a = norm(known); val b = norm(candidate)
        val surname = a.substringAfterLast(' ')
        return if (a == b || containsWords(b, a) || containsWords(a, b) || containsWords(b, surname)) 0.4 else -0.2
    }

    /** Study guides and summaries of a long book, or a short abridged edition. */
    private fun isCompanion(c: BookCandidate) = COMPANION.containsMatchIn(c.title + " " + c.subtitle.orEmpty())

    fun score(query: BookQuery, bookSec: Double, c: BookCandidate): ScoredCandidate {
        val t = titleScore(query.title, c)
        val off = lengthOff(bookSec, c.durationSec)
        var s = 2 * t + lengthScore(off) + authorScore(query.author, c.author)
        if (bookSec > LONG_BOOK_SEC && isCompanion(c)) s -= 1.5
        if (c.abridged && (off == null || off > 0.10)) s -= 0.8
        return ScoredCandidate(c, s, t, off)
    }

    /**
     * Ranks what the search found for a book of [bookSec] seconds. HIGH only when the best has the
     * same title, its length is within 5%, it isn't a study guide, and no other book by someone else
     * fits as well. LOW for anything with a related title; the page offers it but nothing changes.
     */
    fun rank(query: BookQuery, bookSec: Double, candidates: List<BookCandidate>): BookMatchResult {
        val ranked = dedupe(candidates).map { score(query, bookSec, it) }.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return BookMatchResult(emptyList(), MatchConfidence.NONE)
        fun fits(s: ScoredCandidate) = s.titleScore >= 1.0 && s.lengthOff != null && s.lengthOff <= 0.05 && !(bookSec > LONG_BOOK_SEC && isCompanion(s.candidate))
        // Another author's book that fits nearly as well (same title, same length): the user should choose.
        val rival = ranked.drop(1).any { fits(it) && norm(it.candidate.author) != norm(best.candidate.author) && best.score - it.score < 0.5 }
        val confidence = when {
            fits(best) && !rival -> MatchConfidence.HIGH
            best.titleScore >= 0.5 && best.score > 0 -> MatchConfidence.LOW
            else -> MatchConfidence.NONE
        }
        return BookMatchResult(ranked.take(KEEP), confidence)
    }

    /**
     * The same edition from both Audible stores counts once. The stores give it different ids, so it's
     * matched on title, author and length instead.
     */
    fun dedupe(candidates: List<BookCandidate>): List<BookCandidate> =
        candidates.filter { it.title.isNotBlank() }
            .distinctBy { c -> "${norm(c.title)}|${norm(c.author)}|${(c.durationSec / 60).toLong()}" }

    // ------------------------------------------------------------------ applying

    /** "Audible" for "audible.uk:B00…", for the page's "Details from …" line. */
    fun providerName(source: String?): String {
        val p = source.orEmpty().substringBefore(':').lowercase()
        return when {
            p.startsWith("audible") -> "Audible"
            p == "google" -> "Google Books"
            p == "itunes" -> "Apple Books"
            p == "openlibrary" -> "Open Library"
            p == "fantlab" -> "FantLab"
            else -> "the server"
        }
    }

    /** The book with a candidate's details. The cover is the caller's (it has to be downloaded first). */
    fun applied(book: LocalBookEntity, c: BookCandidate, state: LocalMatchState, coverPath: String?, now: Long): LocalBookEntity =
        book.copy(
            title = c.title.trim().ifEmpty { book.title },
            author = c.author?.trim()?.takeIf { it.isNotEmpty() } ?: book.author,
            narrator = c.narrator?.trim()?.takeIf { it.isNotEmpty() },
            description = c.description?.trim()?.takeIf { it.isNotEmpty() },
            publishedYear = c.publishedYear?.trim()?.takeIf { it.isNotEmpty() },
            series = c.series?.trim()?.takeIf { it.isNotEmpty() },
            coverPath = coverPath,
            matchState = state.name,
            matchSource = c.source,
            matchCheckedAt = now,
        )

    /** Back to what the files say, and never looked up again unless the user asks. */
    fun declined(book: LocalBookEntity, coverPath: String?, now: Long): LocalBookEntity =
        book.copy(
            title = book.fileTitle ?: book.title,
            author = if (book.fileTitle != null) book.fileAuthor else book.author,
            narrator = null, description = null, publishedYear = null, series = null,
            coverPath = coverPath,
            matchState = LocalMatchState.DECLINED.name,
            matchSource = null,
            matchCheckedAt = maxOf(book.matchCheckedAt, now),
        )

    /** Covers downloaded for a match are named "<id>-online-….img", apart from the book's own. */
    fun isOnlineCover(path: String?): Boolean = isOnlineCoverPath(path)

    /** An online cover is only used when the book has none of its own (a folder image or embedded art). */
    fun wantsOnlineCover(coverPath: String?): Boolean = coverPath == null || isOnlineCover(coverPath)

    /** "Stormlight Archive, book 2" from a series name and number. */
    fun seriesLabel(name: String?, sequence: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seq = sequence?.trim()?.takeIf { it.isNotEmpty() }
        return if (seq == null) n else "$n, book $seq"
    }
}

/** A search result as a candidate. Length comes in minutes; series as a list, an object or plain text. */
fun AbsBookSearchResult.toCandidate(provider: String): BookCandidate {
    fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val firstSeries = when (val s = series) {
        is JsonArray -> s.firstOrNull()
        else -> s
    }
    val seriesText = when (firstSeries) {
        is JsonObject -> BookMatch.seriesLabel(firstSeries["series"].text() ?: firstSeries["name"].text(), firstSeries["sequence"].text())
        else -> firstSeries.text()
    }
    return BookCandidate(
        title = title?.trim().orEmpty(),
        subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() },
        author = author?.trim()?.takeIf { it.isNotEmpty() },
        narrator = narrator?.trim()?.takeIf { it.isNotEmpty() },
        publishedYear = publishedYear.text()?.take(4),
        description = (descriptionPlain ?: description)?.trim()?.takeIf { it.isNotEmpty() },
        cover = cover?.trim()?.takeIf { it.startsWith("http") },
        series = seriesText,
        asin = asin?.trim()?.takeIf { it.isNotEmpty() },
        isbn = isbn?.trim()?.takeIf { it.isNotEmpty() },
        durationSec = (duration.text()?.toDoubleOrNull() ?: 0.0) * 60,
        abridged = abridged.text()?.toBooleanStrictOrNull() == true,
        provider = provider,
    )
}
