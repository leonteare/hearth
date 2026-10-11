package im.flume.hearth.spoken

import im.flume.hearth.api.AbsPodcastSearchResult
import im.flume.hearth.data.Scored
import im.flume.hearth.data.SearchIndex
import im.flume.hearth.data.SearchMatch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToLong

// Plain-words formatting and small decisions for the podcasts & audiobooks screens. No Android here,
// so it's all unit-tested.

object SpokenFormat {
    private fun hoursMinutes(totalMin: Long): String {
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    /** An episode's or book's length: "45 min", "1 hr 5 min" (never "0 min" for something short). */
    fun length(sec: Double): String {
        if (sec <= 0) return ""
        return hoursMinutes((sec / 60).roundToLong().coerceAtLeast(1))
    }

    /**
     * Listening time left, at [speed]: "23 min left", "1 hr 5 min left", "Less than a minute left".
     * Rounded up, so "1 min left" really means there's still some left.
     */
    fun remaining(sec: Double, speed: Float = 1f): String {
        val real = sec.coerceAtLeast(0.0) / speed.coerceAtLeast(0.1f)
        if (real < 60) return "Less than a minute left"
        return hoursMinutes(ceil(real / 60).toLong()) + " left"
    }

    /** "1×", "1.25×", "0.8×". */
    fun speed(speed: Float): String {
        val rounded = (speed * 100).roundToLong() / 100.0
        val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString().trimEnd('0')
        return "$text×"
    }

    /** When an episode came out: "Today", "Yesterday", "3 days ago", "12 Oct", "12 Oct 2024". */
    fun date(epochMs: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        if (epochMs <= 0) return ""
        // Count calendar days, not 24-hour blocks: last night at 11pm is "Yesterday".
        val a = Calendar.getInstance(zone).apply { timeInMillis = epochMs; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
        val b = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
        val days = ((b.timeInMillis - a.timeInMillis) / 86_400_000.0).roundToLong()
        val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        return when {
            days <= 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            sameYear -> SimpleDateFormat("d MMM", Locale.UK).apply { timeZone = zone }.format(Date(epochMs))
            else -> SimpleDateFormat("d MMM yyyy", Locale.UK).apply { timeZone = zone }.format(Date(epochMs))
        }
    }

    /** "Played", "23 min left" (started) or the episode's length. */
    fun episodeStatus(durationSec: Double, progressSec: Double?, finished: Boolean): String = when {
        finished -> "Played"
        (progressSec ?: 0.0) > 0 && durationSec > 0 -> remaining(durationSec - progressSec!!)
        else -> length(durationSec)
    }

    /** The line under an episode's title: "Yesterday · 45 min" or "12 Oct · 23 min left". */
    fun episodeLine(row: EpisodeRow, now: Long = System.currentTimeMillis(), withShow: Boolean = false): String =
        listOfNotNull(
            row.podcastTitle.takeIf { withShow },
            date(row.episode.publishedAt, now).ifEmpty { null },
            episodeStatus(row.episode.durationSec, row.progressSec, row.finished).ifEmpty { null },
        ).joinToString(" · ")

    /** How far through something is, 0..1 (null when not started or finished). */
    fun fraction(progressSec: Double?, durationSec: Double, finished: Boolean): Float? {
        val p = progressSec ?: return null
        if (finished || p <= 0 || durationSec <= 0) return null
        return (p / durationSec).toFloat().coerceIn(0f, 1f)
    }

    /** Clock-style position for chapter lists: "0:00", "12:34", "1:02:03". */
    fun clock(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/** Where chapters start along the progress bar, and which one is playing. */
object ChapterMarks {
    /** Fractions (0..1) of the bar where chapters start; the first chapter (at 0) and the very end are left out. */
    fun ticks(chapters: List<ChapterEntity>, durationSec: Double): List<Float> {
        if (durationSec <= 0 || chapters.size < 2) return emptyList()
        return chapters.map { it.startSec }
            .filter { it > 1.0 && it < durationSec - 1.0 }
            .map { (it / durationSec).toFloat() }
            .distinct()
    }

    /** Index of the chapter playing at [positionSec], or -1 before the first one / without chapters. */
    fun current(chapters: List<ChapterEntity>, positionSec: Double): Int =
        chapters.indexOfLast { it.startSec <= positionSec + 0.001 }

    /** Seconds left in the chapter playing at [positionSec] (null without chapters). */
    fun leftInChapter(chapters: List<ChapterEntity>, positionSec: Double, durationSec: Double): Double? {
        val i = current(chapters, positionSec).takeIf { it >= 0 } ?: return null
        val end = chapters[i].endSec.takeIf { it > chapters[i].startSec } ?: chapters.getOrNull(i + 1)?.startSec ?: durationSec
        return (end - positionSec).coerceAtLeast(0.0)
    }
}

/** The speeds offered in the player's speed sheet. */
val SPOKEN_SPEEDS = listOf(0.8f, 1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f)

/** Podcasts, episodes and books that match a search, from the phone's catalogue (instant, offline). */
data class SpokenSearchResults(
    val podcastHits: List<Scored<PodcastEntity>> = emptyList(),
    val episodeHits: List<Scored<EpisodeRow>> = emptyList(),
    val bookHits: List<Scored<BookRow>> = emptyList(),
) {
    val podcasts: List<PodcastEntity> get() = podcastHits.map { it.item }
    val episodes: List<EpisodeRow> get() = episodeHits.map { it.item }
    val books: List<BookRow> get() = bookHits.map { it.item }
    val isEmpty: Boolean get() = podcastHits.isEmpty() && episodeHits.isEmpty() && bookHits.isEmpty()
}

object SpokenSearch {
    private class Entry<T>(val item: T, val primary: String, val secondary: String, val recency: Long)

    /**
     * Every word of [query] must appear in the title or the show / author (accents, case and a leading
     * "the" ignored, like music search). Titles that are or start with the query come first, then the
     * newest. Shows are searched across every podcast on the server, not only followed ones. Scored on
     * the same scale as music ([SearchMatch]), so Search can rank them in one list.
     */
    fun search(
        query: String,
        podcasts: List<PodcastEntity>,
        episodes: List<EpisodeRow>,
        books: List<BookRow>,
        podcastLimit: Int = 5,
        episodeLimit: Int = 10,
        bookLimit: Int = 5,
    ): SpokenSearchResults {
        val q = SearchMatch.Query(query)
        if (q.isBlank) return SpokenSearchResults()
        return SpokenSearchResults(
            podcastHits = match(podcasts.map { Entry(it, SearchIndex.norm(it.title), SearchIndex.norm(it.author.orEmpty()), it.addedAt) }, q, podcastLimit),
            episodeHits = match(episodes.map { Entry(it, SearchIndex.norm(it.episode.title), SearchIndex.norm(it.podcastTitle), it.episode.publishedAt) }, q, episodeLimit),
            bookHits = match(
                books.map { b -> Entry(b, SearchIndex.norm(b.book.title), SearchIndex.norm(listOfNotNull(b.book.author, b.book.narrator, b.book.series).joinToString(" ")), b.book.addedAt) },
                q, bookLimit,
            ),
        )
    }

    private fun <T> match(entries: List<Entry<T>>, q: SearchMatch.Query, limit: Int): List<Scored<T>> =
        entries.mapNotNull { e -> SearchMatch.exact(q, e.primary, e.secondary).takeIf { it > SearchMatch.NONE }?.let { e to it } }
            .sortedWith(compareByDescending<Pair<Entry<T>, Int>> { it.second }.thenByDescending { it.first.recency })
            .take(limit).map { Scored(it.first.item, it.second) }
}

/** A podcast feed address, compared loosely (scheme, trailing slash and case don't matter). */
object FeedUrls {
    fun norm(url: String?): String? = url?.trim()?.trimEnd('/')?.removePrefix("https://")?.removePrefix("http://")?.lowercase()?.ifEmpty { null }
    fun same(a: String?, b: String?): Boolean = norm(a) != null && norm(a) == norm(b)
}

/** What a podcast from the directory is to this user. */
enum class DirectoryState { NEW, IN_LIBRARY, FOLLOWING }

object SpokenDiscovery {
    /** Whether a directory result is already on the server, and followed by [username]. */
    fun state(result: AbsPodcastSearchResult, library: List<PodcastEntity>, username: String?): Pair<DirectoryState, PodcastEntity?> {
        val existing = library.firstOrNull { FeedUrls.same(it.feedUrl, result.feedUrl) } ?: return DirectoryState.NEW to null
        val following = username != null && FollowTags.isFollowing(existing.tagList, username)
        return (if (following) DirectoryState.FOLLOWING else DirectoryState.IN_LIBRARY) to existing
    }

    /** Directory results worth showing: ones with a feed, without duplicates of the same feed. */
    fun usable(results: List<AbsPodcastSearchResult>): List<AbsPodcastSearchResult> =
        results.filter { !it.feedUrl.isNullOrBlank() }.distinctBy { FeedUrls.norm(it.feedUrl) }
}

object SpokenLists {
    /** Episodes nobody here has started yet, newest first: Home's "New episodes". */
    fun newEpisodes(latest: List<EpisodeRow>, limit: Int = 5): List<EpisodeRow> =
        latest.filter { !it.finished && (it.progressSec ?: 0.0) <= 0.0 && it.episode.ino != null }
            .sortedByDescending { it.episode.publishedAt }
            .take(limit)

    /** Per show: unplayed episodes that came out in the last [windowDays] days ("3 new"). */
    fun newCounts(latest: List<EpisodeRow>, now: Long = System.currentTimeMillis(), windowDays: Int = 14): Map<String, Int> {
        val since = now - windowDays * 86_400_000L
        return latest.filter { !it.finished && (it.progressSec ?: 0.0) <= 0.0 && it.episode.publishedAt >= since }
            .groupingBy { it.episode.libraryItemId }.eachCount()
    }

    /** The episode "Play" on a show's page starts: the newest one not finished (or the newest). */
    fun playLatest(episodes: List<EpisodeRow>): EpisodeRow? {
        val playable = episodes.filter { it.episode.ino != null }.sortedByDescending { it.episode.publishedAt }
        return playable.firstOrNull { !it.finished } ?: playable.firstOrNull()
    }

    /** "More from this show" in the player's queue: unfinished episodes other than [currentEpisodeId], newest first. */
    fun upNext(episodes: List<EpisodeRow>, currentEpisodeId: String?, limit: Int = 20): List<EpisodeRow> =
        episodes.filter { it.episode.id != currentEpisodeId && !it.finished && it.episode.ino != null }
            .sortedByDescending { it.episode.publishedAt }
            .take(limit)
}
