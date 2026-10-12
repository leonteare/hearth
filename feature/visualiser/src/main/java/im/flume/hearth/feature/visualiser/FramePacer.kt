// Keeps the visualiser at a steady frame rate, and eases it up to speed when it starts.
package im.flume.hearth.feature.visualiser

import im.flume.hearth.core.playback.AudioFrame
import kotlin.math.abs

/**
 * Decides which display frames the visualiser draws, so it costs less battery without looking any
 * different while music plays.
 *
 * - **At most [maxFps].** On a 90 or 120 Hz screen it skips vsyncs instead of drawing every one:
 *   the styles all move by elapsed time, so 60 fps looks the same and takes half the work at 120 Hz.
 *   Skipping is used rather than a frame-rate hint because the hint APIs (Surface / View frame rate)
 *   are only requests that many phones ignore, and they'd slow the whole window, controls included.
 *   A running budget (not "16.7 ms since the last one") keeps 90 Hz at 60 fps (two of every three
 *   vsyncs) instead of dropping to 45.
 * - **Nothing while paused and still.** Once playback is paused and the picture has settled for
 *   [settleSeconds] (levels faded to nothing, colours done blending, no style crossfade), [sleep]
 *   says to stop asking for frames; the last one stays on screen until something changes.
 */
class FramePacer(maxFps: Int = 60, private val settleSeconds: Float = 1f) {
    private val intervalNanos = 1_000_000_000L / maxFps
    /** Vsyncs arrive a little early or late; a frame this close to due still counts. */
    private val slackNanos = intervalNanos / 4
    private var lastVsync = 0L
    private var budget = 0L
    private var stillFor = 0f

    /** True when the vsync at [frameNanos] should be drawn. Call once per vsync. */
    fun shouldDraw(frameNanos: Long): Boolean {
        if (lastVsync == 0L) {
            lastVsync = frameNanos
            return true
        }
        budget += (frameNanos - lastVsync).coerceAtLeast(0L)
        lastVsync = frameNanos
        if (budget < intervalNanos - slackNanos) return false
        // Carry only a little over, so a long gap (a stall) can't bank a burst of frames.
        budget = (budget - intervalNanos).coerceIn(0L, slackNanos)
        return true
    }

    /**
     * Call after each drawn frame with how long it covered. True once the picture has stopped
     * changing: time to stop drawing until [wake].
     */
    fun sleep(dt: Float, playing: Boolean, still: Boolean): Boolean {
        stillFor = if (!playing && still) stillFor + dt else 0f
        return stillFor >= settleSeconds
    }

    /** Back from sleep: the next vsync is drawn straight away and timing starts afresh. */
    fun wake() {
        lastVsync = 0L
        budget = 0L
        stillFor = 0f
    }

    companion object {
        /** Below this the levels are invisible on every style. */
        const val QUIET = 0.005f

        /** True when nothing in [audio] would move anything on screen. */
        fun isQuiet(audio: AudioFrame): Boolean {
            if (audio.level > QUIET || audio.bass > QUIET || audio.mid > QUIET || audio.treble > QUIET) return false
            if (audio.bassSlow > QUIET || audio.beat > QUIET || audio.kick > QUIET || audio.snare > QUIET || audio.hat > QUIET) return false
            for (v in audio.bands) if (v > QUIET) return false
            for (v in audio.waveform) if (abs(v) > QUIET) return false
            return true
        }
    }
}
