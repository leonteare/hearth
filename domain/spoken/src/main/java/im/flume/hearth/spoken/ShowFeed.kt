package im.flume.hearth.spoken

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.Normalizer
import kotlin.math.abs

// A show's whole feed (every episode it ever published, fetched through Audiobookshelf's
// POST /api/podcasts/feed) lined up with the few episodes the server has actually downloaded. Pure
// logic, unit tested; ShowFeeds does the fetching and caching.

/** One episode from the feed: a light copy, enough to list it, match it and ask the server for it. */
@Serializable
data class FeedEpisode(
    val title: String,
    val subtitle: String? = null,
    val pubDate: String? = null,
    /** Epoch ms (0 when the feed's date couldn't be read). */
    val publishedAt: Long = 0,
    val durationSec: Double = 0.0,
    val enclosureUrl: String,
    val enclosureType: String? = null,
    val enclosureLength: String? = null,
    val guid: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val episodeType: String? = null,
) {
    /** Stable list key: the guid, or the audio URL when there's none. */
    val key: String get() = guid?.let { "g:$it" } ?: "u:$enclosureUrl"

    /**
     * The episode as Audiobookshelf's download-episodes expects it (the feed's own shape). Used when the
     * full object (with description and chapters) isn't to hand.
     */
    fun toRequestJson(): JsonObject = buildJsonObject {
        put("title", title)
        put("subtitle", subtitle.orEmpty())
        put("description", "")
        put("pubDate", pubDate.orEmpty())
        put("publishedAt", publishedAt.takeIf { it > 0 })
        put("season", season.orEmpty())
        put("episode", episode.orEmpty())
        put("episodeType", episodeType.orEmpty())
        putJsonObject("enclosure") {
            put("url", enclosureUrl)
            enclosureType?.let { put("type", it) }
            enclosureLength?.let { put("length", it) }
        }
        guid?.let { put("guid", it) }
    }
}

/** What's kept on the phone per show so its page opens instantly, offline too. */
@Serializable
data class CachedFeed(
    val feedUrl: String,
    val fetchedAt: Long,
    val title: String? = null,
    val author: String? = null,
    val image: String? = null,
    val description: String? = null,
    val episodes: List<FeedEpisode> = emptyList(),
)

object FeedParse {
    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Feed episodes as Audiobookshelf returns them (see podcastUtils.cleanEpisodeData), each with the
     * original object to send back when asking the server to download it. Episodes without an audio URL
     * are skipped; repeats (same guid or URL) keep the first.
     */
    fun episodes(raw: List<JsonElement>): List<Pair<FeedEpisode, JsonObject>> {
        val seen = HashSet<String>()
        return raw.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val enc = o["enclosure"] as? JsonObject
            val url = enc?.str("url") ?: return@mapNotNull null
            val ep = FeedEpisode(
                title = o.str("title") ?: "Untitled episode",
                subtitle = o.str("subtitle"),
                pubDate = o.str("pubDate"),
                publishedAt = (o["publishedAt"] as? JsonPrimitive)?.longOrNull ?: 0,
                durationSec = (o["durationSeconds"] as? JsonPrimitive)?.doubleOrNull ?: durationSeconds(o.str("duration")),
                enclosureUrl = url,
                enclosureType = enc.str("type"),
                enclosureLength = enc.str("length"),
                guid = o.str("guid"),
                season = o.str("season"),
                episode = o.str("episode"),
                episodeType = o.str("episodeType"),
            )
            if (seen.add(ep.key)) ep to o else null
        }
    }

    /** iTunes durations: "1696", "28:16", "1:02:03" (and "" for none). */
    fun durationSeconds(text: String?): Double {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return 0.0
        val parts = t.split(':').map { it.trim().toDoubleOrNull() ?: return 0.0 }
        return parts.fold(0.0) { acc, p -> acc * 60 + p }
    }

    fun metadata(meta: JsonObject?): Map<String, String?> = mapOf(
        "title" to meta?.str("title"),
        "author" to meta?.str("author"),
        "image" to meta?.str("image"),
        "description" to (meta?.str("descriptionPlain") ?: meta?.str("description")),
    )

}

/** An episode the server has, reduced to what matching needs. */
data class ServerEpisodeRef(val id: String, val enclosureUrl: String?, val guid: String?, val title: String, val publishedAt: Long)

object FeedMatch {
    /** Feeds and servers can disagree on a date by a time zone; this is how far apart "the same day" may be. */
    const val DATE_SLACK_MS = 36L * 3_600_000

    fun normTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Which server episode each feed episode is (feed key → server episode id): first by audio URL,
     * then by guid, then by the same title published on about the same date. Each server episode is
     * used once.
     */
    fun match(feed: List<FeedEpisode>, server: List<ServerEpisodeRef>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        fun pass(key: (FeedEpisode) -> String?, serverKey: (ServerEpisodeRef) -> String?) {
            val index = HashMap<String, ServerEpisodeRef>()
            server.forEach { s -> serverKey(s)?.let { k -> index.putIfAbsent(k, s) } }
            for (f in feed) {
                if (f.key in out) continue
                val s = key(f)?.let(index::get) ?: continue
                if (s.id in used) continue
                out[f.key] = s.id; used += s.id
            }
        }
        pass({ it.enclosureUrl.trim().takeIf(String::isNotEmpty) }, { it.enclosureUrl?.trim()?.takeIf(String::isNotEmpty) })
        pass({ it.guid?.trim()?.takeIf(String::isNotEmpty) }, { it.guid?.trim()?.takeIf(String::isNotEmpty) })
        // Title and date: the last resort, for episodes stored before the server kept URL / guid.
        val byTitle = server.groupBy { normTitle(it.title) }
        for (f in feed) {
            if (f.key in out) continue
            val candidates = byTitle[normTitle(f.title)].orEmpty().filter { it.id !in used }
            val s = candidates.firstOrNull { f.publishedAt <= 0 || it.publishedAt <= 0 || abs(it.publishedAt - f.publishedAt) <= DATE_SLACK_MS } ?: continue
            out[f.key] = s.id; used += s.id
        }
        return out
    }
}

enum class ShowEpisodeState { ON_SERVER, ON_PHONE, IN_PROGRESS, PLAYED, NOT_ON_SERVER, GETTING, FAILED }

/** A row of the show page: a feed episode, the server's copy of it, or both. */
data class ShowEpisode(val key: String, val feed: FeedEpisode?, val server: EpisodeRow?) {
    val title: String get() = server?.episode?.title ?: feed?.title.orEmpty()
    val publishedAt: Long get() = server?.episode?.publishedAt?.takeIf { it > 0 } ?: feed?.publishedAt ?: 0
    val durationSec: Double get() = server?.episode?.durationSec?.takeIf { it > 0 } ?: feed?.durationSec ?: 0.0
    val season: String? get() = (server?.episode?.season ?: feed?.season)?.trim()?.takeIf { it.isNotEmpty() }
    val subtitle: String? get() = server?.episode?.subtitle ?: feed?.subtitle
}

/** A line of the show page's list: a season heading or an episode. */
sealed interface ShowListEntry {
    val key: String
    data class Season(val season: String) : ShowListEntry { override val key get() = "season:$season" }
    data class Episode(val episode: ShowEpisode) : ShowListEntry { override val key get() = episode.key }
}

object ShowEpisodes {
    fun refOf(row: EpisodeRow) = row.episode.let { ServerEpisodeRef(it.id, it.enclosureUrl, it.guid, it.title, it.publishedAt) }

    /**
     * Every feed episode, with the server's copy where it has one, plus server episodes the feed no longer
     * lists (so nothing on the server goes missing from the page). When a server episode disappears its
     * feed row simply goes back to "not on your server".
     */
    fun merge(feed: List<FeedEpisode>, server: List<EpisodeRow>): List<ShowEpisode> {
        val matches = FeedMatch.match(feed, server.map(::refOf))
        val byId = server.associateBy { it.episode.id }
        val matched = matches.values.toHashSet()
        return feed.map { f -> ShowEpisode(f.key, f, matches[f.key]?.let(byId::get)) } +
            server.filter { it.episode.id !in matched }.map { ShowEpisode("s:${it.episode.id}", null, it) }
    }

    /** What a row shows. [fetch] is a "get it onto the server" request in flight for it, if any. */
    fun state(e: ShowEpisode, fetch: ServerFetch? = null): ShowEpisodeState {
        val s = e.server
        if (s == null) return when (fetch?.phase) {
            null, FetchPhase.READY -> ShowEpisodeState.NOT_ON_SERVER
            FetchPhase.FAILED -> ShowEpisodeState.FAILED
            else -> ShowEpisodeState.GETTING
        }
        return when {
            s.finished -> ShowEpisodeState.PLAYED
            s.started -> ShowEpisodeState.IN_PROGRESS
            s.downloadState == SpokenDownloadState.DONE -> ShowEpisodeState.ON_PHONE
            else -> ShowEpisodeState.ON_SERVER
        }
    }

    /** Words of [query] all found in the title (or subtitle), ignoring case and accents. */
    fun filter(list: List<ShowEpisode>, query: String): List<ShowEpisode> {
        val tokens = FeedMatch.normTitle(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return list
        return list.filter { e ->
            val hay = FeedMatch.normTitle(e.title + " " + e.subtitle.orEmpty())
            tokens.all { it in hay }
        }
    }

    fun sort(list: List<ShowEpisode>, newestFirst: Boolean): List<ShowEpisode> =
        if (newestFirst) list.sortedByDescending { it.publishedAt } else list.sortedBy { it.publishedAt }

    fun arrange(list: List<ShowEpisode>, query: String, newestFirst: Boolean): List<ShowEpisode> = sort(filter(list, query), newestFirst)

    /**
     * Season headings, only when the show has more than one season: a heading wherever the season
     * changes along the sorted list. Episodes without a season just follow.
     */
    fun withSeasons(list: List<ShowEpisode>): List<ShowListEntry> {
        val seasons = list.mapNotNull { it.season }.toSet()
        if (seasons.size < 2) return list.map { ShowListEntry.Episode(it) }
        val out = ArrayList<ShowListEntry>(list.size + seasons.size)
        val shown = HashSet<String>()
        var last: String? = null
        for (e in list) {
            val s = e.season
            if (s != null && s != last && shown.add(s)) out += ShowListEntry.Season(s)
            if (s != null) last = s
            out += ShowListEntry.Episode(e)
        }
        return out
    }
}

// ------------------------------------------------------------------ getting an episode onto the server

enum class FetchPhase { ASKING, QUEUED, DOWNLOADING, READY, FAILED }

/** What to do once the server has the episode. */
enum class FetchThen { PLAY, DOWNLOAD, NOTHING }

data class ServerFetch(
    val feedKey: String,
    val title: String,
    val phase: FetchPhase,
    val then: FetchThen,
    val startedAt: Long,
    /** Place in the server's download queue (1 = next), when it's waiting. */
    val queuePosition: Int = 0,
    val message: String? = null,
)

object FetchProgress {
    /** "Still getting it" after this long. */
    const val SLOW_MS = 20_000L
    /** Give up after this long without the episode appearing (and it's not waiting in the queue). */
    const val GIVE_UP_MS = 15 * 60_000L

    /** The phase after one look at the server. [queueIndex] is its index in the server's queue, or null. */
    fun phase(onServer: Boolean, queueIndex: Int?, elapsedMs: Long): FetchPhase = when {
        onServer -> FetchPhase.READY
        queueIndex != null -> FetchPhase.QUEUED
        elapsedMs > GIVE_UP_MS -> FetchPhase.FAILED
        else -> FetchPhase.DOWNLOADING
    }

    /** How long to wait before looking again: quickly at first, then more gently. */
    fun pollDelayMs(elapsedMs: Long): Long = when {
        elapsedMs < 30_000 -> 2_000
        elapsedMs < 120_000 -> 4_000
        else -> 10_000
    }

    /** The row's status line. */
    fun label(f: ServerFetch): String = when (f.phase) {
        FetchPhase.ASKING -> "Asking your server…"
        FetchPhase.QUEUED -> if (f.queuePosition > 1) "Getting it… (${f.queuePosition - 1} ahead)" else "Getting it…"
        FetchPhase.DOWNLOADING -> "Getting it…"
        FetchPhase.READY -> "Ready"
        FetchPhase.FAILED -> f.message ?: "Couldn't get it"
    }
}
