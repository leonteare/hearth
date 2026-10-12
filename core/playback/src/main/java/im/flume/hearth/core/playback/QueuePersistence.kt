@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import im.flume.hearth.playback.SavedQueue

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
    val pos = local?.takeIf { !it.isFinished }?.currentTime ?: saved.spokenPositionSec ?: 0.0
    return Triple(now, items, pos to (local?.isFinished == true))
}

internal fun PlaybackService.saveQueue() {
    store.offer(snapshot() ?: return)
    c.appScope.launch { store.flush() }
}

internal fun PlaybackService.saveQueueBlocking() {
    val snap = snapshot() ?: return
    runBlocking { store.save(snap) }
}

internal suspend fun PlaybackService.loadSavedItems(): Pair<SavedQueue, List<MediaItem>>? {
    val saved = store.load() ?: return null
    val byId = c.library.songsByIds(saved.window).associateBy { it.id }
    val items = saved.window.mapIndexedNotNull { i, id ->
        byId[id]?.toMediaItem(manual = saved.manual.getOrElse(i) { false })
    }
    if (items.isEmpty()) return null
    return saved to items
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
    val (saved, items) = loadSavedItems() ?: return
    if (player.mediaItemCount > 0) return
    applySavedState(saved)
    player.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
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
