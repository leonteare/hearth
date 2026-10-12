@file:OptIn(UnstableApi::class)

package im.flume.hearth.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import im.flume.hearth.playback.PlaybackService.Companion.MAX_SPEED
import im.flume.hearth.playback.PlaybackService.Companion.MIN_SPEED
import im.flume.hearth.playback.PlaybackService.Companion.SLEEP_END_OF_CHAPTER
import im.flume.hearth.spoken.LocalBookIds
import im.flume.hearth.spoken.SpokenKey
import im.flume.hearth.spoken.SpokenPlayPrefEntity
import im.flume.hearth.spoken.SpokenTimeline
import im.flume.hearth.spoken.TimelineTrack
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/*
 * Spoken-word mode on PlaybackService: loading a podcast episode or audiobook into the player,
 * entering and leaving that mode (with "Back to music"), keeping a book's details in step with the
 * library, and seeking, skipping and speed on the whole episode or book timeline.
 * State lives on the service; these are extensions so the service file stays about wiring.
 */

/** The episode's or book's audio files as player items, with its timeline. Null if it can't be played. */
internal suspend fun PlaybackService.loadSpoken(itemId: String, episodeId: String?): Pair<SpokenNow, List<MediaItem>>? {
    val dao = c.db.spoken()
    if (LocalBookIds.isLocal(itemId)) {
        val (book, files, chapters) = c.spoken.local.playable(itemId) ?: return null
        val timeline = SpokenTimeline(files.map { TimelineTrack(it.fileIndex.toString(), it.durationSec) }, chapters)
        return SpokenNow(itemId, null, "", book.title, book.author.orEmpty(), timeline) to SpokenMedia.localBookItems(book, files)
    }
    if (episodeId != null) {
        val row = dao.episodeOnce(episodeId) ?: run { runCatching { c.spoken.fetchItem(itemId) }; dao.episodeOnce(episodeId) } ?: return null
        val items = SpokenMedia.episodeItems(row).takeIf { it.isNotEmpty() } ?: return null
        val ino = row.episode.ino ?: return null
        val podcast = dao.podcastOnce(itemId)
        val timeline = SpokenTimeline(listOf(TimelineTrack(ino, row.episode.durationSec)), SpokenNow.chaptersOf(dao.chapters(itemId, episodeId)))
        return SpokenNow(itemId, episodeId, podcast?.libraryId.orEmpty(), row.episode.title, row.podcastAuthor ?: row.podcastTitle, timeline) to items
    }
    var book = dao.bookOnce(itemId)
    if (book == null || dao.tracks(itemId).isEmpty()) {
        runCatching { c.spoken.fetchItem(itemId) }
        book = dao.bookOnce(itemId) ?: return null
    }
    val tracks = dao.tracks(itemId).takeIf { it.isNotEmpty() } ?: return null
    val timeline = SpokenTimeline(tracks.map { TimelineTrack(it.ino, it.durationSec) }, SpokenNow.chaptersOf(dao.chapters(itemId, "")))
    return SpokenNow(itemId, null, book.libraryId, book.title, book.author.orEmpty(), timeline) to SpokenMedia.bookItems(book, tracks)
}

/**
 * Plays an episode ([episodeId]) or audiobook from where this user left off (newest of this phone
 * and the server), or from [startSec]. Replaces the queue; a music queue is kept for "Back to music".
 */
internal suspend fun PlaybackService.playSpoken(itemId: String, episodeId: String?, startSec: Double?) = queueOps.withLock {
    restoring?.cancel()
    val (now, items) = loadSpoken(itemId, episodeId) ?: return@withLock
    if (spokenNow != null) exitSpoken()
    else if (player.mediaItemCount > 0) snapshot()?.let { store.saveMusic(it) }
    val start = c.spoken.progress.resolveStart(itemId, episodeId, now.title, online = !c.network.cannotStream)
    enterSpoken(now, items, startSec ?: start.positionSec, start.finished, playWhenReady = true)
}

/**
 * While a book plays, keeps its title, writer and cover in the player, notification and lock screen
 * in step with the library (a phone book's proper details can arrive after it started playing).
 */
internal fun PlaybackService.followBookDetails(now: SpokenNow?) {
    bookDetailsJob?.cancel()
    if (now == null || !now.isBook) return
    val id = now.libraryItemId
    bookDetailsJob = scope.launch {
        c.spoken.bookDetails(id).collect { d ->
            val current = spokenNow?.takeIf { it.libraryItemId == id } ?: return@collect
            if (d == null) return@collect
            val version = d.coverUrl?.hashCode()?.let { Integer.toHexString(it) }
            var changed = false
            for (i in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(i)
                if (item.mediaMetadata.extras?.getString(SpokenMedia.EXTRA_ITEM_ID) != id) continue
                SpokenMedia.withDetails(item, d.title, d.author, version)?.let { player.replaceMediaItem(i, it); changed = true }
            }
            if (current.title != d.title || current.author != d.author.orEmpty()) {
                spokenNow = current.copy(title = d.title, author = d.author.orEmpty())
                sourceLabel = d.title
                changed = true
            }
            if (changed) { publishExtras(); saveQueue() }
        }
    }
}

internal suspend fun PlaybackService.enterSpoken(now: SpokenNow, items: List<MediaItem>, startSec: Double, finished: Boolean, playWhenReady: Boolean) {
    spokenNow = now
    followBookDetails(now)
    c.spoken.downloads.current = SpokenKey(now.libraryItemId, now.episodeId.orEmpty())
    context = emptyList()
    pending = ArrayDeque()
    shuffled = false
    trimmed = 0
    sourceLabel = if (now.isBook) now.title else c.db.spoken().podcastOnce(now.libraryItemId)?.title ?: now.author
    scrobbler.reset()
    val (index, offsetMs) = now.timeline.locate(startSec)
    player.repeatMode = Player.REPEAT_MODE_OFF
    player.pauseAtEndOfMediaItems = false
    player.setMediaItems(items, index, offsetMs)
    player.setPlaybackSpeed(c.db.spoken().speed(now.libraryItemId) ?: 1f)
    player.prepare()
    player.playWhenReady = playWhenReady
    tracker.begin(now, startSec, finished)
    publishExtras()
    saveQueue()
}

/** Leaves spoken-word mode, saving where we got to. The player's items are left for the caller to replace. */
internal suspend fun PlaybackService.exitSpoken() {
    if (spokenNow == null) return
    // Saved on the phone now; sent in the background so starting the next thing never waits on the network.
    tracker.pause(spokenPositionSec(), send = false)
    c.spoken.progress.flushSoon()
    tracker.end()
    spokenNow = null
    followBookDetails(null)
    spokenNeedsServerCheck = false
    c.spoken.downloads.current = null
    player.setPlaybackSpeed(1f)
    if (sleepAt == SLEEP_END_OF_CHAPTER) setSleepTimer(0)
    c.appScope.launch { runCatching { c.spoken.downloads.autoDeleteFinished() } }
}

/** "Back to music": the music queue that was playing before the episode or book started. */
internal suspend fun PlaybackService.backToMusic() = queueOps.withLock {
    val saved = store.loadMusic() ?: return@withLock
    exitSpoken()
    val byId = c.library.songsByIds(saved.window).associateBy { it.id }
    val items = saved.window.mapIndexedNotNull { i, id -> byId[id]?.toMediaItem(manual = saved.manual.getOrElse(i) { false }) }
    store.clearMusic()
    if (items.isEmpty()) { publishExtras(); return@withLock }
    applySavedState(saved)
    player.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
    player.prepare()
    player.play()
    publishExtras()
    saveQueue()
}

/** Position on the whole episode/book timeline. */
internal fun PlaybackService.spokenPositionSec(): Double =
    spokenNow?.timeline?.globalSec(player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0)) ?: 0.0

internal fun PlaybackService.seekSpokenTo(sec: Double) {
    val now = spokenNow ?: return
    val (index, ms) = now.timeline.locate(sec.coerceIn(0.0, now.timeline.durationSec))
    player.seekTo(index, ms)
}

internal fun PlaybackService.skipSpokenBy(deltaSec: Double) = seekSpokenTo(spokenPositionSec() + deltaSec)

internal fun PlaybackService.setSpeed(speed: Float) {
    val now = spokenNow ?: return
    val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    player.setPlaybackSpeed(s)
    publishExtras()
    c.appScope.launch { c.db.spoken().setSpeed(SpokenPlayPrefEntity(now.libraryItemId, s)) }
}

/**
 * A spoken queue restored from the last run started playing: if the server has a newer position
 * (another device), jump there; the UI hears about it through SpokenProgress.resumeNotices.
 */
internal suspend fun PlaybackService.checkServerPosition() {
    val now = spokenNow ?: return
    val start = c.spoken.progress.resolveStart(now.libraryItemId, now.episodeId, now.title, online = !c.network.cannotStream)
    if (spokenNow !== now || start.finished) return
    if (start.notice != null || abs(start.positionSec - spokenPositionSec()) > 2 && start.serverWon) seekSpokenTo(start.positionSec)
}
