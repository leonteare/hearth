package im.flume.hearth.playback

import kotlin.random.Random

/**
 * The player only ever holds a small window of the queue (recent history + the next ~50 songs).
 * Everything else waits in a "pending" list and is fed in as playback advances. This keeps
 * "shuffle 7,000 songs" instant and keeps the media session's timeline small.
 */
object QueuePlanner {
    const val INITIAL_AHEAD = 50
    const val HISTORY_BEHIND = 20
    const val REFILL_WHEN_LEFT = 10
    const val REFILL_COUNT = 50
    const val TRIM_WHEN_BEHIND = 60
    const val KEEP_BEHIND = 30

    data class Plan(val window: List<String>, val startIndex: Int, val pending: List<String>)

    /** Order to play [ids] in. Shuffled with [startId] (if any) first, else original order. */
    fun order(ids: List<String>, startId: String?, shuffle: Boolean, random: Random = Random.Default): List<String> {
        if (!shuffle) return ids
        val rest = if (startId != null) ids.filter { it != startId } else ids
        val shuffled = rest.shuffled(random)
        return if (startId != null && startId in ids) listOf(startId) + shuffled else shuffled
    }

    fun initial(ids: List<String>, startId: String?, shuffle: Boolean, random: Random = Random.Default): Plan {
        val order = order(ids, startId, shuffle, random)
        if (order.isEmpty()) return Plan(emptyList(), 0, emptyList())
        val start = if (shuffle || startId == null) 0 else order.indexOf(startId).coerceAtLeast(0)
        val from = (start - HISTORY_BEHIND).coerceAtLeast(0)
        val to = (start + 1 + INITIAL_AHEAD).coerceAtMost(order.size)
        return Plan(order.subList(from, to), start - from, order.subList(to, order.size))
    }

    /** What should follow the current song after the user toggles shuffle. */
    fun upcomingAfterToggle(context: List<String>, currentId: String?, shuffle: Boolean, random: Random = Random.Default): List<String> =
        if (shuffle) {
            context.filter { it != currentId }.shuffled(random)
        } else {
            val idx = if (currentId == null) -1 else context.indexOf(currentId)
            if (idx >= 0) context.subList(idx + 1, context.size) else context
        }

    /**
     * Nudges a shuffled order so the same artist rarely plays twice in a row: whenever two
     * neighbours share an artist, swap in the nearest later song by someone else.
     */
    fun spreadArtists(order: List<String>, artistOf: (String) -> String?): List<String> {
        val out = order.toMutableList()
        for (i in 1 until out.size) {
            val prev = artistOf(out[i - 1]) ?: continue
            if (artistOf(out[i]) != prev) continue
            val j = (i + 1 until out.size).firstOrNull { artistOf(out[it]) != prev } ?: break
            out[i] = out[j].also { out[j] = out[i] }
        }
        return out
    }

    fun needsRefill(windowSize: Int, currentIndex: Int): Boolean = windowSize - currentIndex - 1 < REFILL_WHEN_LEFT

    /** How many already-played items to drop from the front of the window, or 0. */
    fun trimCount(currentIndex: Int): Int = if (currentIndex > TRIM_WHEN_BEHIND) currentIndex - KEEP_BEHIND else 0

    /** Index at which "Add to queue" should insert: after the current song and any songs already queued by hand. */
    fun addToQueueIndex(currentIndex: Int, isManual: (Int) -> Boolean, windowSize: Int): Int {
        var i = currentIndex + 1
        while (i < windowSize && isManual(i)) i++
        return i
    }

    /**
     * "Play next" / "Add to queue" while a podcast or audiobook plays: the songs go into the music queue
     * that was set aside (after its current song, or after the hand-queued ones), or start a new one if
     * there wasn't one. "Back to music" then plays them.
     */
    fun insertIntoSaved(saved: SavedQueue?, songIds: List<String>, next: Boolean): SavedQueue {
        if (saved == null || saved.window.isEmpty()) {
            return SavedQueue(
                window = songIds, manual = songIds.map { true }, index = 0, positionMs = 0,
                pending = emptyList(), context = emptyList(), shuffled = false, label = "", repeatMode = 0,
            )
        }
        val manual = List(saved.window.size) { saved.manual.getOrElse(it) { false } }
        val current = saved.index.coerceIn(0, saved.window.lastIndex)
        val at = if (next) current + 1 else addToQueueIndex(current, { manual[it] }, saved.window.size)
        return saved.copy(
            window = saved.window.take(at) + songIds + saved.window.drop(at),
            manual = manual.take(at) + songIds.map { true } + manual.drop(at),
        )
    }
}
