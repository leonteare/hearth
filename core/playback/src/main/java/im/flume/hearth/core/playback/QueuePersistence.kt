@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import im.flume.hearth.core.data.spoken.SpokenProgressEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import im.flume.hearth.domain.music.playback.SavedQueue

/*
 * Saving and restoring the queue on PlaybackService, so music (or the episode or book) picks up where
 * it left off after the app or phone restarts. The files themselves are written by [QueueStore].
 */

internal fun PlaybackService.snapshot(): SavedQueue? {
    if (player.mediaItemCount == 0) return null
    val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    return SavedQueue(
        window = items.map { it.mediaId },
        manual = items.map { it.isManual },
        index = player.currentMediaItemIndex,
        positionMs = player.currentPosition,
        pending = pending.toList(),
        context = context,
        shuffled = shuffled,
        label = sourceLabel,
        repeatMode = player.repeatMode,
        spokenItemId = spokenNow?.libraryItemId,
        spokenEpisodeId = spokenNow?.episodeId,
        spokenPositionSec = spokenNow?.let { spokenPositionSec() },
    )
}

/**
 * A spoken-word queue saved at shutdown: rebuilt from the catalogue (works offline), at this
 * phone's latest recorded position. The server's position is checked when it first plays.
 */
internal suspend fun PlaybackService.loadSavedSpoken(saved: SavedQueue): Triple<SpokenNow, List<MediaItem>, Pair<Double, Boolean>>? {
    val itemId = saved.spokenItemId ?: return null
    val (now, items) = loadSpoken(itemId, saved.spokenEpisodeId) ?: return null
    val local = c.spoken.progress.local(itemId, saved.spokenEpisodeId)
    return Triple(now, items, QueueRestore.spokenStart(local, saved.spokenPositionSec))
}

internal fun PlaybackService.saveQueue() {
    store.offer(snapshot() ?: return)
    c.appScope.launch { store.flush() }
}

internal fun PlaybackService.saveQueueBlocking() {
    val snap = snapshot() ?: return
    runBlocking { store.save(snap) }
}

/** A saved music queue as player items, and where to start in them (index, position in ms). */
internal class LoadedQueue(val saved: SavedQueue, val items: List<MediaItem>, val startIndex: Int, val startMs: Long)

internal suspend fun PlaybackService.loadSavedItems(): LoadedQueue? = loadWindow(store.load() ?: return null)

/** Songs gone from the library are dropped; the start moves with them (see [QueueRestore.start]). */
internal suspend fun PlaybackService.loadWindow(saved: SavedQueue): LoadedQueue? {
    val byId = c.library.songsByIds(saved.window).associateBy { it.id }
    val items = saved.window.mapIndexedNotNull { i, id ->
        byId[id]?.toMediaItem(manual = saved.manual.getOrElse(i) { false })
    }
    if (items.isEmpty()) return null
    val (index, ms) = QueueRestore.start(saved.window.map { it in byId }, saved.index, saved.positionMs)
    return LoadedQueue(saved, items, index, ms)
}

internal suspend fun PlaybackService.restoreQueue(playWhenReady: Boolean) {
    if (player.mediaItemCount > 0) return
    store.load()?.takeIf { it.spokenItemId != null }?.let { saved ->
        val (now, items, start) = loadSavedSpoken(saved) ?: return
        if (player.mediaItemCount > 0) return
        enterSpoken(now, items, start.first, start.second, playWhenReady)
        spokenNeedsServerCheck = true
        return
    }
    val loaded = loadSavedItems() ?: return
    if (player.mediaItemCount > 0) return
    applySavedState(loaded.saved)
    player.setMediaItems(loaded.items, loaded.startIndex, loaded.startMs)
    player.prepare()
    player.playWhenReady = playWhenReady
    publishExtras()
}

internal fun PlaybackService.applySavedState(saved: SavedQueue) {
    context = saved.context
    pending = ArrayDeque(saved.pending)
    shuffled = saved.shuffled
    sourceLabel = saved.label
    player.repeatMode = saved.repeatMode
}

/** Where a saved queue picks up again. */
internal object QueueRestore {
    /**
     * Where to start once songs gone from the library are dropped ([kept] says, per saved song, whether it
     * could be loaded; at least one was): the same song as before, moved up by the songs dropped ahead of
     * it, at the saved position. If that song itself is gone, the next one that's left, from its start (or
     * the last one, if none follow).
     */
    fun start(kept: List<Boolean>, savedIndex: Int, savedPositionMs: Long): Pair<Int, Long> {
        val keptCount = kept.count { it }
        val index = savedIndex.coerceIn(0, kept.lastIndex)
        val before = (0 until index).count { kept[it] }
        return if (kept[index]) before to savedPositionMs else before.coerceAtMost(keptCount - 1) to 0L
    }

    /**
     * Seconds into the episode or book to start at, and whether it was finished. The phone's own record
     * wins unless it says finished; then the position saved with the queue, else the start.
     */
    fun spokenStart(local: SpokenProgressEntity?, savedSec: Double?): Pair<Double, Boolean> {
        val pos = local?.takeIf { !it.isFinished }?.currentTime ?: savedSec ?: 0.0
        return pos to (local?.isFinished == true)
    }
}
