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
 * - **Or a slow idle, for a style that keeps moving.** Some styles (Sphere, say) turn or drift on
 *   their own with no music at all. Freezing those looks broken, so once they settle [sleep] keeps
 *   asking for frames but drops the pace to [idleFps]: half the work, and a slow turn at 30 fps
 *   still looks smooth.
 */
class FramePacer(maxFps: Int = 60, idleFps: Int = 30, private val settleSeconds: Float = 1f) {
    private val intervalNanos = 1_000_000_000L / maxFps
    private val idleIntervalNanos = 1_000_000_000L / idleFps
    private var lastVsync = 0L
    private var budget = 0L
    private var stillFor = 0f
    private var idling = false

    /** True while pacing the gentle no-music idle rather than the full frame rate. */
    val isIdling: Boolean get() = idling

    /** True when the vsync at [frameNanos] should be drawn. Call once per vsync. */
    fun shouldDraw(frameNanos: Long): Boolean {
        if (lastVsync == 0L) {
            lastVsync = frameNanos
            return true
        }
        val interval = if (idling) idleIntervalNanos else intervalNanos
        // Vsyncs arrive a little early or late; a frame this close to due still counts.
        val slack = interval / 4
        budget += (frameNanos - lastVsync).coerceAtLeast(0L)
        lastVsync = frameNanos
        if (budget < interval - slack) return false
        // Carry only a little over, so a long gap (a stall) can't bank a burst of frames.
        budget = (budget - interval).coerceIn(0L, slack)
        return true
    }

    /**
     * Call after each drawn frame with how long it covered. True once the picture has stopped
     * changing: time to stop drawing until [wake]. A style that [keepsMoving] with no music never
     * sleeps; it idles at the lower frame rate instead.
     */
    fun sleep(dt: Float, playing: Boolean, still: Boolean, keepsMoving: Boolean = false): Boolean {
        stillFor = if (!playing && still) stillFor + dt else 0f
        val settled = stillFor >= settleSeconds
        setIdling(settled && keepsMoving)
        return settled && !keepsMoving
    }

    /** Back from sleep: the next vsync is drawn straight away and timing starts afresh. */
    fun wake() {
        lastVsync = 0L
        budget = 0L
        stillFor = 0f
        setIdling(false)
    }

    /** Switching pace starts the budget afresh, so the change can't bank or lose frames. */
    private fun setIdling(on: Boolean) {
        if (idling == on) return
        idling = on
        budget = 0L
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
