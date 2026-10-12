@file:OptIn(UnstableApi::class)

package im.flume.hearth.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/*
 * Loudness levelling (ReplayGain) on PlaybackService, applied whenever the song or the setting changes.
 */

/**
 * ReplayGain: turn loud tracks down so everything plays at a similar level. Tracks can only be
 * made quieter, never louder, so quiet tracks stay at full volume.
 */
internal fun PlaybackService.applyVolumeLevelling() {
    val extras = player.currentMediaItem?.mediaMetadata?.extras
    val mode = c.session.settings.value.volumeLevelling
    val gain = VolumeLevellingPolicy.gainDb(
        mode,
        trackGain = { extras?.takeIf { it.containsKey(EXTRA_TRACK_GAIN) }?.getDouble(EXTRA_TRACK_GAIN) },
        albumGain = { extras?.takeIf { it.containsKey(EXTRA_ALBUM_GAIN) }?.getDouble(EXTRA_ALBUM_GAIN) },
    )
    player.volume = VolumeLevellingPolicy.volume(gain)
}

internal object VolumeLevellingPolicy {
    /** The `volumeLevelling` setting: 0 off, 1 per track, 2 per album (falling back to the track's gain). */
    const val OFF = 0
    const val TRACK = 1
    const val ALBUM = 2

    /** The ReplayGain (dB) to apply in [mode], or null for none. Tags are read only when the mode needs them. */
    fun gainDb(mode: Int, trackGain: () -> Double?, albumGain: () -> Double?): Double? = when (mode) {
        TRACK -> trackGain()
        ALBUM -> albumGain() ?: trackGain()
        else -> null
    }

    /** Player volume for [gainDb]: never above full, and never so low a badly tagged song goes silent. */
    fun volume(gainDb: Double?): Float =
        gainDb?.let { Math.pow(10.0, it / 20.0).toFloat().coerceIn(0.05f, 1f) } ?: 1f
}
