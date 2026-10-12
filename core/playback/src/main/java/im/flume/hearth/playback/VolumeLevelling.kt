@file:OptIn(UnstableApi::class)

package im.flume.hearth.playback

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
    val gain = when (mode) {
        1 -> extras?.takeIf { it.containsKey(EXTRA_TRACK_GAIN) }?.getDouble(EXTRA_TRACK_GAIN)
        2 -> extras?.takeIf { it.containsKey(EXTRA_ALBUM_GAIN) }?.getDouble(EXTRA_ALBUM_GAIN)
            ?: extras?.takeIf { it.containsKey(EXTRA_TRACK_GAIN) }?.getDouble(EXTRA_TRACK_GAIN)
        else -> null
    }
    player.volume = gain?.let { Math.pow(10.0, it / 20.0).toFloat().coerceIn(0.05f, 1f) } ?: 1f
}
