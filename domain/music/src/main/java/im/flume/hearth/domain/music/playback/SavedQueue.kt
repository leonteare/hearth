package im.flume.hearth.domain.music.playback

import kotlinx.serialization.Serializable

/** Snapshot of the full queue so it survives the app being killed (and so the car can resume it). */
@Serializable
data class SavedQueue(
    val window: List<String>,
    val manual: List<Boolean>,
    val index: Int,
    val positionMs: Long,
    val pending: List<String>,
    val context: List<String>,
    val shuffled: Boolean,
    val label: String,
    val repeatMode: Int,
    /** Set when the queue is a podcast episode or audiobook (then [window] holds its files). */
    val spokenItemId: String? = null,
    val spokenEpisodeId: String? = null,
    /** Position on the whole episode/book, seconds. */
    val spokenPositionSec: Double? = null,
    /** Which full save this is, so a newer position-only save is only ever applied to the queue it was taken from. */
    val revision: Long = 0,
)
