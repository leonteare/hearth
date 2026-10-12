@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import im.flume.hearth.core.playback.PlaybackService.Companion.PREFETCH_COUNT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Where each song's audio comes from and what's already on the phone: the bitrate a stream is pinned
 * to, whether a song is downloaded or fully in the streaming cache, prefetching the next few songs
 * into the cache, and holding the Wi-Fi lock only while something actually streams.
 */

internal fun PlaybackService.streamTarget(id: String): SongDataSource.StreamTarget? {
    // A reconnect continues at a byte offset, which is only valid in the same encoding: never switch
    // bitrate mid-song (e.g. Wi-Fi to mobile), and keep the choice for prefetched upcoming songs too.
    // A song already fully cached at some bitrate keeps that one, so it plays without the network.
    val bitrate = pinnedBitrates[id] ?: run {
        val s = c.session.settings.value
        val chosen = cachedBitrate(id) ?: if (c.network.isMetered()) s.mobileBitrate else s.wifiBitrate
        pinnedBitrates.putIfAbsent(id, chosen) ?: chosen
    }
    return c.api.streamUrl(id, bitrate)?.let { SongDataSource.StreamTarget(it, "$id@$bitrate") }
}

/** The bitrate this song is completely in the streaming cache at, or null. */
internal fun PlaybackService.cachedBitrate(id: String): Int? {
    val s = c.session.settings.value
    return listOfNotNull(pinnedBitrates[id], s.wifiBitrate, s.mobileBitrate).distinct()
        .firstOrNull { isFullyCached("$id@$it") }
}

private fun PlaybackService.isFullyCached(key: String): Boolean = runCatching {
    val cache = c.mediaCache
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    length > 0 && cache.isCached(key, 0, length)
}.getOrDefault(false)

/** Can play with no connection: downloaded, or completely in the streaming cache. */
internal fun PlaybackService.isOnPhone(id: String): Boolean {
    if (id.startsWith("abs:")) {
        val item = (0 until player.mediaItemCount).map(player::getMediaItemAt).firstOrNull { it.mediaId == id } ?: return false
        if (item.localConfiguration?.uri?.scheme == "content") return true // a book from a folder on the phone
        return item.localConfiguration?.uri?.let(SpokenMedia::parse)?.let { (itemId, ino) -> c.spoken.downloads.localFile(itemId, ino) } != null
    }
    return c.downloads.isDownloaded(id) || cachedBitrate(id) != null
}

/**
 * Can play this queue item with no connection: a downloaded song or episode file, a book from a
 * folder on the phone, or a song completely in the streaming cache.
 */
private fun PlaybackService.isItemOnPhone(item: MediaItem): Boolean {
    val uri = item.localConfiguration?.uri
    if (uri?.scheme == "content") return true
    if (item.isSpoken) return uri?.let(SpokenMedia::parse)?.let { (itemId, ino) -> c.spoken.downloads.localFile(itemId, ino) } != null
    return c.downloads.isDownloaded(item.mediaId) || cachedBitrate(item.mediaId) != null
}

/**
 * Holds the Wi-Fi lock only when it's needed: while the song playing or the next couple stream.
 * With everything on the phone, a CPU wake lock is enough and the Wi-Fi radio can sleep.
 */
internal fun PlaybackService.updateWakeMode() {
    val cur = player.currentMediaItemIndex
    if (player.mediaItemCount == 0 || cur !in 0 until player.mediaItemCount) return
    val ahead = (cur until minOf(player.mediaItemCount, cur + 1 + WakeModePolicy.LOOKAHEAD))
        .map { isItemOnPhone(player.getMediaItemAt(it)) }
    val mode = if (WakeModePolicy.localOnly(ahead)) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK
    if (mode != wakeMode) {
        wakeMode = mode
        player.setWakeMode(mode)
    }
}

internal fun PlaybackService.cancelPrefetch() {
    prefetchJob?.cancel()
    prefetchWriters.forEach { it.cancel() }
}

/**
 * Saves the next few songs into the streaming cache in the background, so a tunnel or dead spot
 * doesn't interrupt playback. Downloaded songs are skipped; already-cached ones cost nothing.
 * Called on every song change, which first stops the previous round's downloads. Fewer songs on
 * mobile data (see [PrefetchPolicy]).
 */
internal fun PlaybackService.prefetchUpcoming() {
    cancelPrefetch()
    if (!c.network.isOnline.value) return
    val metered = c.network.isMetered()
    val s = c.session.settings.value
    val count = PrefetchPolicy.count(metered, if (metered) s.mobileBitrate else s.wifiBitrate)
    if (count == 0) return
    val next = (player.currentMediaItemIndex + 1 until minOf(player.mediaItemCount, player.currentMediaItemIndex + 1 + count))
        .map { player.getMediaItemAt(it).mediaId }
        .filterNot(c.downloads::isDownloaded)
    if (next.isEmpty()) return
    prefetchJob = scope.launch(Dispatchers.IO) {
        for (id in next) {
            if (!isActive) break
            val target = streamTarget(id) ?: continue
            val spec = DataSpec.Builder().setUri(target.url).setKey(target.cacheKey).build()
            val writer = CacheWriter(streamFactory.createDataSource(), spec, null, null)
            prefetchWriters += writer
            try {
                if (isActive) runCatching { writer.cache() }
            } finally {
                prefetchWriters -= writer
            }
        }
    }
}

/** Forgets pinned bitrates except for the current song and the few prefetched after it. */
internal fun PlaybackService.unpinPassedSongs() {
    val cur = player.currentMediaItemIndex
    val keep = (cur..minOf(player.mediaItemCount - 1, cur + PREFETCH_COUNT)).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
    pinnedBitrates.keys.retainAll(keep)
}
