package im.flume.hearth.domain.spoken

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import im.flume.hearth.core.data.spoken.OutboxKind
import im.flume.hearth.core.data.spoken.SpokenDownloadState

// Decisions about progress, following, downloads and timelines that don't need Android, so they can
// be unit tested. Everything with a clock uses the server's clock (see ServerClock).

/** Progress on one episode or book. [lastUpdate] is on the server's clock. */
data class ProgressSnapshot(
    val currentTime: Double,
    val duration: Double,
    val isFinished: Boolean,
    val lastUpdate: Long,
    val finishedAt: Long? = null,
)

enum class ProgressSource { LOCAL, SERVER }

data class MergeResult(
    val merged: ProgressSnapshot,
    /** Whose position was kept. */
    val winner: ProgressSource,
    /** The server's position was newer and far from this phone's: tell the user they've jumped. */
    val resumedElsewhere: Boolean,
    /** The server is behind (or lost "finished"): send [merged] to it. */
    val pushToServer: Boolean,
)

object ProgressMerge {
    /** A position more than this far from ours, from another device, earns a "resumed from…" notice. */
    const val JUMP_SECONDS = 120.0
    const val FINISHED_FRACTION = 0.95
    const val FINISHED_TAIL_SECONDS = 30.0

    /** Heard enough to count as finished: 95% of it, or into the last 30 seconds. */
    fun reachedEnd(position: Double, duration: Double): Boolean =
        duration > 0 && (position >= duration * FINISHED_FRACTION || duration - position <= FINISHED_TAIL_SECONDS)

    /**
     * Combines this phone's progress with the server's. The most recent change wins, for position and
     * for played/unplayed alike (by lastUpdate, both on the server's clock), so marking something
     * unplayed on another device sticks too. A late, stale offline update can't undo a newer finish,
     * because it's older. [localMarkedUnplayed] is kept for callers; the timestamp already decides.
     */
    fun merge(local: ProgressSnapshot?, server: ProgressSnapshot?, localMarkedUnplayed: Boolean = false): MergeResult? {
        if (local == null && server == null) return null
        if (server == null) return MergeResult(local!!, ProgressSource.LOCAL, resumedElsewhere = false, pushToServer = true)
        if (local == null) return MergeResult(server, ProgressSource.SERVER, resumedElsewhere = false, pushToServer = false)

        val localNewer = local.lastUpdate > server.lastUpdate
        val newer = if (localNewer) local else server
        @Suppress("UNUSED_VARIABLE") val markedUnplayedHere = localMarkedUnplayed
        val finished = newer.isFinished
        val finishedAt = when {
            !finished -> null
            else -> listOfNotNull(local.finishedAt.takeIf { local.isFinished }, server.finishedAt.takeIf { server.isFinished }).minOrNull()
                ?: newer.lastUpdate
        }
        val merged = ProgressSnapshot(
            currentTime = newer.currentTime,
            duration = maxOf(local.duration, server.duration),
            isFinished = finished,
            lastUpdate = maxOf(local.lastUpdate, server.lastUpdate),
            finishedAt = finishedAt,
        )
        val resumedElsewhere = !localNewer && !finished && abs(server.currentTime - local.currentTime) > JUMP_SECONDS
        val push = localNewer && (abs(local.currentTime - server.currentTime) >= 1.0 || local.isFinished != server.isFinished)
        return MergeResult(merged, if (localNewer) ProgressSource.LOCAL else ProgressSource.SERVER, resumedElsewhere, push)
    }
}

/** A queued update waiting for the server (an outbox row, without the Room bits). */
data class OutboxItem(val key: String, val seq: Long, val kind: OutboxKind)

object OutboxPlan {
    const val MAX_SESSIONS_PER_BATCH = 20

    /**
     * Splits the outbox into requests, keeping [OutboxItem.seq] order: runs of listening sessions go
     * together (the server applies a batch in order), each explicit progress change on its own. The
     * caller sends them in turn and stops at the first network failure, so nothing overtakes an
     * older update.
     */
    fun batches(items: List<OutboxItem>): List<List<OutboxItem>> {
        val out = ArrayList<List<OutboxItem>>()
        var run = ArrayList<OutboxItem>()
        for (item in items.sortedBy { it.seq }) {
            if (item.kind == OutboxKind.SESSION) {
                run += item
                if (run.size == MAX_SESSIONS_PER_BATCH) { out += run; run = ArrayList() }
            } else {
                if (run.isNotEmpty()) { out += run; run = ArrayList() }
                out += listOf(item)
            }
        }
        if (run.isNotEmpty()) out += run
        return out
    }

    /** Failed requests that the server will never accept (e.g. the episode was deleted) are dropped after this many tries. */
    const val MAX_REJECTED_ATTEMPTS = 5

    /** 4xx other than auth/timeouts/rate limiting: the server understood and refused, so retrying won't help. */
    fun isPermanentRejection(httpCode: Int): Boolean = httpCode in 400..499 && httpCode !in setOf(401, 403, 408, 429)
}

/**
 * Who follows a podcast, kept on the server as tags on the podcast ("hearth-follower:leon"), so each
 * person follows on their own and the server can see who still wants new episodes.
 */
object FollowTags {
    const val PREFIX = "hearth-follower:"

    fun tagFor(username: String): String = PREFIX + username.trim().lowercase()

    fun followers(tags: List<String>): List<String> =
        tags.filter { it.startsWith(PREFIX, ignoreCase = true) }.map { it.substring(PREFIX.length).lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun isFollowing(tags: List<String>, username: String): Boolean = tagFor(username) in tags.map { it.lowercase() }

    /**
     * [tags] with this user's follower tag added or removed. Every other tag (including other people's
     * follower tags) is kept as it was, in order. Apply it to tags read from the server just before writing.
     */
    fun withFollower(tags: List<String>, username: String, follow: Boolean): List<String> {
        val mine = tagFor(username)
        val others = tags.filterNot { it.equals(mine, ignoreCase = true) }
        return if (follow) others + mine else others
    }

    /** Marks when the last follower left; the server's cleanup job deletes what was on the server before then. */
    const val UNFOLLOWED_PREFIX = "hearth-unfollowed:"

    fun isUnfollowedMarker(tag: String): Boolean = tag.startsWith(UNFOLLOWED_PREFIX, ignoreCase = true)

    /**
     * The tags to write when [username] follows or unfollows, given the tags just read from the server.
     * Following strips any "hearth-unfollowed:" marker. Unfollowing as the last follower replaces any old
     * marker with "hearth-unfollowed:<[serverNowMs]>"; while someone else still follows, no marker.
     */
    fun nextTags(tags: List<String>, username: String, follow: Boolean, serverNowMs: Long): List<String> {
        val base = withFollower(tags, username, follow).filterNot(::isUnfollowedMarker)
        return if (!follow && followers(base).isEmpty()) base + (UNFOLLOWED_PREFIX + serverNowMs) else base
    }

    enum class Heal { NONE, ADD_TAG, REMOVE_TAG }

    /**
     * Self-heal on sync: this phone remembers what this user last chose ([wantFollow]) for a while after
     * choosing it. If the server's tags disagree (e.g. a write that never landed), write it again.
     */
    fun heal(wantFollow: Boolean?, serverTags: List<String>, username: String): Heal = when {
        wantFollow == null -> Heal.NONE
        wantFollow && !isFollowing(serverTags, username) -> Heal.ADD_TAG
        !wantFollow && isFollowing(serverTags, username) -> Heal.REMOVE_TAG
        else -> Heal.NONE
    }

    /** How long a follow / unfollow choice is remembered for [heal]. */
    const val INTENT_TTL_MS = 3L * 24 * 3_600_000
}

/** Episode facts the download planner needs. */
data class EpisodeInfo(val id: String, val publishedAt: Long, val finished: Boolean, val started: Boolean)

/** The phone's download row for an episode, if any. */
data class DownloadInfo(val state: SpokenDownloadState, val auto: Boolean)

/** One of a show's download rows, for [SpokenDownloadPolicy.unfollowPlan]. */
data class DownloadRowInfo(val episodeId: String, val state: SpokenDownloadState)

/** What unfollowing clears off the server straight away. */
object UnfollowServerCleanup {
    /** Only when nobody follows the show any more (someone else following keeps it all, as before). */
    fun shouldClear(tags: List<String>): Boolean = FollowTags.followers(tags).isEmpty()

    /**
     * Every episode on the server goes, except one someone else is part-way through (picked from the
     * show page and still listening) and the one loaded in this phone's player. The server's hourly
     * cleanup removes those once they're finished.
     */
    fun toDelete(episodeIds: List<String>, othersPartWay: Set<String>, currentEpisodeId: String?): List<String> =
        episodeIds.filter { it !in othersPartWay && it != currentEpisodeId }
}

object SpokenDownloadPolicy {
    const val KEEP_UNPLAYED = 3

    /**
     * Delete this phone's copy once this user finished the episode, but never the one that's loaded in
     * the player right now (it may still be playing its last seconds). Books are kept until removed.
     */
    /** A finished episode or book leaves this phone (books stay on the server); never the one playing. */
    fun shouldAutoDelete(finished: Boolean, isCurrent: Boolean, state: SpokenDownloadState): Boolean =
        finished && !isCurrent && state != SpokenDownloadState.REMOVED

    data class Plan(val toQueue: List<String>, val toRemove: List<String>)

    data class UnfollowPlan(val removeNow: List<String>, val removeLater: List<String>)

    /**
     * Unfollowing clears a show off this phone: every downloaded (or downloading) episode goes now,
     * except the one loaded in the player ([currentEpisodeId]), which goes when playback moves on.
     * Rows already REMOVED are left alone.
     */
    fun unfollowPlan(downloads: List<DownloadRowInfo>, currentEpisodeId: String?): UnfollowPlan {
        val live = downloads.filter { it.state != SpokenDownloadState.REMOVED }.map { it.episodeId }
        return UnfollowPlan(live.filter { it != currentEpisodeId }, live.filter { it == currentEpisodeId })
    }

    /**
     * Auto-download for a followed show: the newest [keep] episodes not yet finished should be on the
     * phone. Episodes with any download row are left alone (already there, on the way, or removed by the
     * user, which stays removed). Auto-downloads that fell out of the newest [keep] are removed unless
     * they've been started; finished ones are handled by [shouldAutoDelete].
     */
    fun autoDownloadPlan(episodes: List<EpisodeInfo>, existing: Map<String, DownloadInfo>, keep: Int = KEEP_UNPLAYED): Plan {
        val window = episodes.filter { !it.finished }.sortedByDescending { it.publishedAt }.take(keep).map { it.id }.toSet()
        val toQueue = episodes.filter { it.id in window && it.id !in existing }.sortedByDescending { it.publishedAt }.map { it.id }
        val byId = episodes.associateBy { it.id }
        val toRemove = existing.filter { (id, d) ->
            val ep = byId[id]
            d.auto && d.state != SpokenDownloadState.REMOVED && id !in window && ep != null && !ep.started && !ep.finished
        }.keys.toList()
        return Plan(toQueue, toRemove)
    }
}

/** One audio file on an item's timeline. */
data class TimelineTrack(val ino: String, val durationSec: Double)

data class TimelineChapter(val startSec: Double, val endSec: Double, val title: String)

/**
 * A book (or episode) as one continuous timeline over its audio files, so positions and progress are
 * book-global even though the player plays file by file.
 */
class SpokenTimeline(val tracks: List<TimelineTrack>, val chapters: List<TimelineChapter> = emptyList()) {
    /** Where each track starts, in seconds from the start of the item. */
    val starts: List<Double> = tracks.runningFold(0.0) { acc, t -> acc + t.durationSec }.dropLast(1)
    val durationSec: Double = tracks.sumOf { it.durationSec }

    /** The track and offset (ms) within it for a position on the whole timeline. */
    fun locate(globalSec: Double): Pair<Int, Long> {
        if (tracks.isEmpty()) return 0 to 0L
        val pos = globalSec.coerceIn(0.0, durationSec)
        var i = starts.indexOfLast { it <= pos }.coerceAtLeast(0)
        // Exactly at a boundary: the start of the next track, not the end of this one.
        if (i < tracks.lastIndex && pos >= starts[i] + tracks[i].durationSec) i++
        return i to ((pos - starts[i]) * 1000).toLong().coerceAtLeast(0)
    }

    /** Position on the whole timeline for [positionMs] into track [index]. */
    fun globalSec(index: Int, positionMs: Long): Double =
        if (tracks.isEmpty()) positionMs / 1000.0
        else (starts[index.coerceIn(0, tracks.lastIndex)] + positionMs / 1000.0).coerceIn(0.0, durationSec)

    fun chapterIndexAt(globalSec: Double): Int = chapters.indexOfLast { it.startSec <= globalSec + 0.001 }

    fun chapterAt(globalSec: Double): TimelineChapter? = chapters.getOrNull(chapterIndexAt(globalSec))

    /** Where the chapter playing at [globalSec] ends (the end of the item if there are no chapters). */
    fun chapterEndSec(globalSec: Double): Double = chapterAt(globalSec)?.endSec ?: durationSec
}

/**
 * Builds the body Audiobookshelf's /api/session/local-all expects for a listening session recorded on
 * this phone ("local" play method). Listening time is real seconds heard; times are on the server's clock.
 */
object LocalSessionPayload {
    const val PLAY_METHOD_LOCAL = 3

    data class Session(
        val id: String,
        val libraryId: String,
        val libraryItemId: String,
        val episodeId: String?,
        val mediaType: String,
        val displayTitle: String,
        val displayAuthor: String,
        val duration: Double,
        val startTime: Double,
        val currentTime: Double,
        val timeListening: Double,
        val startedAt: Long,
        val updatedAt: Long,
        /** Local date (yyyy-MM-dd) and weekday name the listening happened on, for the server's stats. */
        val date: String,
        val dayOfWeek: String,
    )

    /**
     * When the item is finished, the reported position is its end: the server un-finishes an item whose
     * position moves backwards from a session, and finished is sticky in Hearth.
     */
    fun build(s: Session, finished: Boolean, deviceInfo: JsonObject): JsonObject = buildJsonObject {
        put("id", s.id)
        put("libraryId", s.libraryId)
        put("libraryItemId", s.libraryItemId)
        s.episodeId?.let { put("episodeId", it) }
        put("mediaType", s.mediaType)
        put("displayTitle", s.displayTitle)
        put("displayAuthor", s.displayAuthor)
        put("duration", s.duration)
        put("playMethod", PLAY_METHOD_LOCAL)
        put("mediaPlayer", "Hearth")
        put("deviceInfo", deviceInfo)
        put("date", s.date)
        put("dayOfWeek", s.dayOfWeek)
        put("timeListening", s.timeListening)
        put("startTime", s.startTime)
        put("currentTime", if (finished && s.duration > 0) s.duration else s.currentTime)
        put("startedAt", s.startedAt)
        put("updatedAt", s.updatedAt)
    }

    /** PATCH /api/me/progress body for an explicit change (finished, unplayed, or a position the server is missing). */
    fun progressBody(p: ProgressSnapshot): JsonObject = buildJsonObject {
        put("duration", p.duration)
        put("isFinished", p.isFinished)
        if (p.isFinished) {
            put("currentTime", p.duration.takeIf { it > 0 } ?: p.currentTime)
            put("progress", 1.0)
            p.finishedAt?.let { put("finishedAt", it) }
        } else {
            put("currentTime", p.currentTime)
            put("progress", if (p.duration > 0) (p.currentTime / p.duration).coerceIn(0.0, 1.0) else 0.0)
        }
        put("lastUpdate", p.lastUpdate)
    }
}
