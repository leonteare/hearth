package im.flume.hearth.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import im.flume.hearth.container
import im.flume.hearth.playback.EXTRA_COVER_ART
import im.flume.hearth.playback.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which queue positions to warm, as plain decisions so they can be tested off the device. */
internal object CoverPrefetch {
    /**
     * Queue indices whose covers to load ahead: the next [ahead] then the previous [behind], nearest
     * first. With repeat-all the queue wraps round, so the end leads back to the start.
     */
    fun neighbours(current: Int, count: Int, repeatAll: Boolean, ahead: Int = 2, behind: Int = 1): List<Int> {
        if (count <= 1 || current !in 0 until count) return emptyList()
        val out = ArrayList<Int>(ahead + behind)
        fun add(i: Int) {
            val j = if (repeatAll) Math.floorMod(i, count) else i
            if (j in 0 until count && j != current && j !in out) out += j
        }
        for (k in 1..ahead) add(current + k)
        for (k in 1..behind) add(current - k)
        return out
    }

    /**
     * The distinct covers to fetch for those neighbours, leaving out the cover already showing (an
     * album played in order shares one cover, so there's often nothing to do).
     */
    fun covers(coverArtIds: List<String?>, current: Int, repeatAll: Boolean): List<String> {
        val showing = coverArtIds.getOrNull(current)
        return neighbours(current, coverArtIds.size, repeatAll)
            .mapNotNull { coverArtIds[it]?.takeIf { id -> id.isNotEmpty() && id != showing } }
            .distinct()
    }
}

/**
 * Loads the covers of the songs either side of the current one before they're needed, so a skip
 * or swipe shows the new cover straight away instead of after a second's download.
 *
 * Coil's memory cache only matches a request of the same pixel size, so the player covers report
 * the size they're drawn at ([noteSong] / [noteSpoken], keyed by the cover size asked of the
 * server) and the prefetch asks for exactly that. Until a player cover has been shown once there's
 * no size to match, and nothing is fetched for it.
 */
object CoverPrefetcher {
    data class Slots(val song: Map<Int, IntSize> = emptyMap(), val spoken: Map<Int, IntSize> = emptyMap())

    private val _slots = MutableStateFlow(Slots())
    val slots = _slots.asStateFlow()

    fun noteSong(requestSize: Int, px: IntSize) {
        if (px.width > 0 && px.height > 0) _slots.update { if (it.song[requestSize] == px) it else it.copy(song = it.song + (requestSize to px)) }
    }

    fun noteSpoken(requestSize: Int, px: IntSize) {
        if (px.width > 0 && px.height > 0) _slots.update { if (it.spoken[requestSize] == px) it else it.copy(spoken = it.spoken + (requestSize to px)) }
    }
}

/**
 * Keeps the neighbouring covers warm whenever the current item or queue changes. Lives at the app
 * root, so it works with the player closed (the mini player) and open alike.
 */
@Composable
fun PrefetchPlayerCovers(state: PlayerUiState) {
    val context = LocalContext.current
    val c = context.container
    val slots by CoverPrefetcher.slots.collectAsStateWithLifecycle()
    val spokenId = state.spoken?.libraryItemId
    val repeatAll = state.repeatMode == Player.REPEAT_MODE_ALL
    val covers = remember(state.queue, state.currentIndex, repeatAll, spokenId) {
        if (spokenId != null) emptyList()
        else CoverPrefetch.covers(state.queue.map { it.item.mediaMetadata.extras?.getString(EXTRA_COVER_ART) }, state.currentIndex, repeatAll)
    }
    LaunchedEffect(covers, slots, spokenId) {
        val loader = SingletonImageLoader.get(context)
        fun enqueue(url: String?, px: IntSize) {
            url ?: return
            loader.enqueue(ImageRequest.Builder(context).data(url).size(px.width, px.height).build())
        }
        if (spokenId != null) {
            // An episode or a book keeps one cover throughout: warm it for each player that shows it.
            for ((requestSize, px) in slots.spoken) enqueue(c.spoken.coverUrl(spokenId, requestSize), px)
        } else {
            for (id in covers) for ((requestSize, px) in slots.song) enqueue(c.api.coverArtUrl(id, requestSize), px)
        }
    }
}
