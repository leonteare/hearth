@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import im.flume.hearth.domain.music.PlaySource
import kotlin.random.Random
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import im.flume.hearth.domain.music.playback.QueuePlanner

/*
 * Music queue changes on PlaybackService: playing a source, Play next / Add to queue, the queue
 * screen's edits (move, skip to, remove, undo), shuffle on and off, and topping the player up from
 * the songs still waiting ([PlaybackService.pending]). Decisions come from [QueuePlanner].
 */

internal suspend fun PlaybackService.playSource(source: PlaySource, startId: String?, shuffle: Boolean) {
    restoring?.cancel()
    if (spokenNow != null) { exitSpoken(); store.clearMusic() }
    // Right after start the server's state isn't known yet: ask first rather than shuffle songs that can't stream.
    val offline = !c.network.awaitServerKnown()
    val songs = c.library.resolve(source, offlineOnly = offline)
    if (songs.isEmpty()) return
    val ids = songs.map { it.id }
    val byId = songs.associateBy { it.id }
    val raw = QueuePlanner.initial(ids, startId, shuffle)
    val plan = if (shuffle && c.session.settings.value.smartShuffle) {
        val all = QueuePlanner.spreadArtists(raw.window + raw.pending, { byId[it]?.artistId ?: byId[it]?.artist })
        QueuePlanner.Plan(all.take(raw.window.size), raw.startIndex, all.drop(raw.window.size))
    } else raw
    trimmed = 0

    context = ids
    shuffled = shuffle
    sourceLabel = source.label
    pending = ArrayDeque(plan.pending)
    player.setMediaItems(plan.window.mapNotNull { byId[it]?.toMediaItem() }, plan.startIndex, 0)
    player.prepare()
    player.play()
    publishExtras()
    saveQueue()
}

internal suspend fun PlaybackService.insertSongs(songIds: List<String>, next: Boolean) = queueOps.withLock {
    val songs = c.library.songsByIds(songIds)
    if (songs.isEmpty()) return@withLock
    val items = songs.map { it.toMediaItem(manual = true) }
    if (spokenNow != null) {
        // Songs can't join a spoken-word queue, and shouldn't cut the episode off: they go into the
        // music queue set aside for "Back to music" (or start one), where they play when you go back.
        store.saveMusic(QueuePlanner.insertIntoSaved(store.loadMusic(), songs.map { it.id }, next))
        publishExtras()
        return@withLock
    }
    if (player.mediaItemCount == 0) {
        player.setMediaItems(items)
        player.prepare()
        return@withLock
    }
    val index = QueueEditMath.insertIndex(next, player.currentMediaItemIndex, player.mediaItemCount) { player.getMediaItemAt(it).isManual }
    player.addMediaItems(index, items)
    saveQueue()
}

// ---------------------------------------------------------------- queue edits from the queue screen
// Entries are found by queue key + song id when the command runs, never by a position the UI saw:
// played songs get trimmed from the front, so positions shift under the UI.

private fun PlaybackService.indexOfEntry(key: String?, songId: String?): Int {
    if (key == null) return -1
    return (0 until player.mediaItemCount).firstOrNull {
        val item = player.getMediaItemAt(it)
        item.queueKey == key && (songId == null || item.mediaId == songId)
    } ?: -1
}

/** Swipe-right in the queue: this song plays straight after the current one, as a hand-queued song. */
internal fun PlaybackService.moveToNext(key: String?, songId: String?) {
    val index = indexOfEntry(key, songId)
    val cur = player.currentMediaItemIndex
    if (index <= cur) return
    val manual = player.getMediaItemAt(index).withManual(true)
    player.removeMediaItem(index)
    player.addMediaItem(cur + 1, manual)
    saveQueue()
}

internal fun PlaybackService.skipTo(key: String?, songId: String?) {
    val index = indexOfEntry(key, songId)
    if (index < 0) return
    player.seekTo(index, 0)
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
    player.play()
}

internal fun PlaybackService.removeEntry(key: String?, songId: String?) {
    val index = indexOfEntry(key, songId)
    if (index < 0) return
    player.removeMediaItem(index)
    saveQueue()
}

/** Drag-to-reorder: puts the entry straight after [afterKey] (null or gone: straight after the current song). */
internal fun PlaybackService.moveAfter(key: String?, songId: String?, afterKey: String?) {
    val from = indexOfEntry(key, songId)
    val to = QueueEditMath.moveAfterTarget(from, player.currentMediaItemIndex, indexOfEntry(afterKey, null)) ?: return
    if (to != from) player.moveMediaItem(from, to)
    saveQueue()
}

/** Undo of a removal: back between its old neighbours, with its "added by you" flag as it was. */
internal suspend fun PlaybackService.restoreEntry(songId: String, key: String?, manual: Boolean, afterKey: String?, beforeKey: String?) = queueOps.withLock {
    val song = c.library.songsByIds(listOf(songId)).firstOrNull() ?: return@withLock
    val item = song.toMediaItem(manual = manual, queueKey = key?.takeIf { indexOfEntry(it, null) < 0 } ?: newQueueKey())
    if (player.mediaItemCount == 0) {
        player.setMediaItems(listOf(item))
        player.prepare()
        return@withLock
    }
    val index = QueueEditMath.restoreIndex(player.currentMediaItemIndex, indexOfEntry(beforeKey, null), indexOfEntry(afterKey, null))
    player.addMediaItem(index, item)
    saveQueue()
}

/** Undo for "Play next" / "Add to queue": removes the most recently queued copies of these songs. */
internal suspend fun PlaybackService.removeQueued(songIds: List<String>) = queueOps.withLock {
    // Highest index first, so each removal leaves the indexes still to remove where they were.
    QueueEditMath.queuedCopiesToRemove(
        player.currentMediaItemIndex, player.mediaItemCount, songIds,
        idAt = { player.getMediaItemAt(it).mediaId },
        isManual = { player.getMediaItemAt(it).isManual },
    ).forEach { player.removeMediaItem(it) }
    saveQueue()
}

internal fun PlaybackService.toggleShuffle() {
    if (spokenNow != null) return // episodes and books play in order
    shuffled = !shuffled
    val current = player.currentMediaItem?.mediaId
    val cur = player.currentMediaItemIndex
    // Keep songs the user queued by hand (wherever they sit after the current one); replace the rest.
    QueueEditMath.replacedOnShuffle(cur, player.mediaItemCount) { player.getMediaItemAt(it).isManual }
        .forEach { player.removeMediaItem(it) }
    val upcoming = QueuePlanner.upcomingAfterToggle(context, current, shuffled)
    pending = ArrayDeque(upcoming)
    scope.launch {
        if (shuffled && c.session.settings.value.smartShuffle) {
            val artists = c.library.songsByIds(upcoming).associate { it.id to (it.artistId ?: it.artist) }
            pending = ArrayDeque(QueuePlanner.spreadArtists(upcoming, artists::get))
        }
        refill(force = true)
        publishExtras()
        saveQueue()
    }
}

/** Moves songs from [pending] into the player when it's running low. */
internal suspend fun PlaybackService.refill(force: Boolean = false) {
    if (player.mediaItemCount == 0) return
    if (!force && !QueuePlanner.needsRefill(player.mediaItemCount, player.currentMediaItemIndex)) return
    if (QueueEditMath.startOverOnRepeat(pending.isEmpty(), player.repeatMode, context.isNotEmpty())) {
        pending = ArrayDeque(QueuePlanner.order(context, null, shuffled, Random.Default))
    }
    if (pending.isEmpty()) return
    val batch = if (c.network.isOnline.value) {
        List(minOf(QueuePlanner.REFILL_COUNT, pending.size)) { pending.removeFirst() }
    } else {
        // Offline: bring forward only songs on the phone; the rest stay in line for when the server's back.
        takePending(QueuePlanner.REFILL_COUNT, this::isOnPhone)
    }
    if (batch.isEmpty()) return
    val songs = c.library.songsByIds(batch)
    player.addMediaItems(songs.map { it.toMediaItem() })

    val trim = QueuePlanner.trimCount(player.currentMediaItemIndex)
    if (trim > 0) {
        player.removeMediaItems(0, trim)
        trimmed += trim
    }
    publishExtras()
}

/** Removes and returns up to [max] songs from [pending] that match [keep], leaving the others in order. */
internal fun PlaybackService.takePending(max: Int, keep: (String) -> Boolean): List<String> {
    val (taken, rest) = QueueEditMath.take(pending, max, keep)
    if (taken.isNotEmpty()) pending = rest
    return taken
}

/**
 * Index arithmetic for the queue edits above, on plain positions so it can be tested without a player.
 * [current] is the playing song's index; songs before it have played.
 */
internal object QueueEditMath {
    /** Play next: straight after the current song. Add to queue: after the songs already queued by hand. */
    fun insertIndex(next: Boolean, current: Int, size: Int, isManual: (Int) -> Boolean): Int =
        if (next) current + 1 else QueuePlanner.addToQueueIndex(current, isManual, size)

    /**
     * Drag-to-reorder: where `moveMediaItem` should put the entry at [from] so it lands straight after the
     * entry at [after] (-1 or not upcoming: straight after the current song). The target is the index in the
     * list once the entry is taken out. Null when [from] isn't an upcoming song (gone, playing or played).
     */
    fun moveAfterTarget(from: Int, current: Int, after: Int): Int? {
        if (from <= current) return null
        val anchor = after.takeIf { it >= current } ?: current
        return if (from <= anchor) anchor else anchor + 1
    }

    /**
     * Undo of a removal: back in front of its old next neighbour [before] if that's still upcoming, else
     * after its old previous neighbour [after], else straight after the current song (-1: gone).
     */
    fun restoreIndex(current: Int, before: Int, after: Int): Int =
        before.takeIf { it > current }
            ?: after.takeIf { it >= current }?.plus(1)
            ?: (current + 1)

    /**
     * Undo of Play next / Add to queue: the indexes of the upcoming hand-queued copies of [songIds], newest
     * (furthest down the queue) first, one copy per id given. Highest index first, so removing them in
     * order never shifts one still to remove.
     */
    fun queuedCopiesToRemove(
        current: Int,
        size: Int,
        songIds: List<String>,
        idAt: (Int) -> String,
        isManual: (Int) -> Boolean,
    ): List<Int> {
        val remaining = songIds.toMutableList()
        val found = ArrayList<Int>()
        for (i in size - 1 downTo current + 1) {
            if (remaining.isEmpty()) break
            if (isManual(i) && remaining.remove(idAt(i))) found += i
        }
        return found
    }

    /**
     * Shuffle on or off: the songs after the current one that the new order replaces, highest index first
     * (so removing them in order is safe). Hand-queued songs are kept wherever they sit: after an undo or a
     * drag they need not be straight after the current song.
     */
    fun replacedOnShuffle(current: Int, size: Int, isManual: (Int) -> Boolean): List<Int> =
        (current + 1 until size).filterNot(isManual).reversed()

    /** Repeat all with nothing left waiting: the source starts over (in a fresh order when shuffled). */
    fun startOverOnRepeat(pendingEmpty: Boolean, repeatMode: Int, hasSource: Boolean): Boolean =
        pendingEmpty && repeatMode == Player.REPEAT_MODE_ALL && hasSource

    /** Splits [pending] into up to [max] ids that match [keep] (in order) and the rest, still in order. */
    fun take(pending: Collection<String>, max: Int, keep: (String) -> Boolean): Pair<List<String>, ArrayDeque<String>> {
        val taken = ArrayList<String>()
        val rest = ArrayDeque<String>(pending.size)
        for (id in pending) if (taken.size < max && keep(id)) taken += id else rest += id
        return taken to rest
    }
}
