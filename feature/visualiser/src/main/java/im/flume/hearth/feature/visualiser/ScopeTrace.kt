package im.flume.hearth.feature.visualiser

import im.flume.hearth.core.playback.AutoGain
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The points of an XY oscilloscope (a goniometer): each stereo sample pair becomes one point, left
 * channel across and right channel up. Mono audio (L = R) lies on the rising diagonal, a left-only
 * sound on the horizontal axis, a right-only one on the vertical, and silence collapses to the centre.
 *
 * A slow automatic gain keeps quiet tracks about as big as loud ones, so the trace reaches roughly
 * [FILL] of the way to the edge either way. A light three-sample blur takes the stair-steps out of the
 * line without changing its shape. Points come out in -1..1 with y up; allocation-free after building.
 */
class ScopeTrace(val capacity: Int = 2048) {
    /** Point positions, -1..1; [y] grows upwards. Valid up to [count]. */
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    var count = 0
        private set

    private val autoGain = AutoGain(target = 1f, maxGain = MAX_GAIN, riseSec = 0.12f, fallSec = 2.5f, floor = 0.02f)

    /** The overall scale applied last time: the automatic gain times [FILL]. */
    val scale: Float get() = autoGain.gain * FILL

    fun reset() {
        autoGain.reset()
        count = 0
    }

    /**
     * Builds the trace from the last [n] samples of [mid] ((L + R) / 2) and [side] ((L − R) / 2),
     * using at most [capacity] of the newest. [dt]: seconds since the last call, for the gain.
     */
    fun build(mid: FloatArray, side: FloatArray, dt: Float, n: Int = min(mid.size, side.size)) {
        val m = min(n, capacity)
        val from = n - m
        if (m <= 0) { count = 0; return }
        var peak = 0f
        for (i in from until n) {
            peak = max(peak, max(abs(mid[i] + side[i]), abs(mid[i] - side[i])))
        }
        val g = autoGain.update(peak, dt) * FILL
        val last = n - 1
        for (k in 0 until m) {
            val i = from + k
            val a = max(from, i - 1)
            val b = min(last, i + 1)
            val l = (mid[a] + side[a]) * 0.25f + (mid[i] + side[i]) * 0.5f + (mid[b] + side[b]) * 0.25f
            val r = (mid[a] - side[a]) * 0.25f + (mid[i] - side[i]) * 0.5f + (mid[b] - side[b]) * 0.25f
            x[k] = (l * g).coerceIn(-1f, 1f)
            y[k] = (r * g).coerceIn(-1f, 1f)
        }
        count = m
    }

    companion object {
        /** How far towards the edge the loudest moments reach once the gain has settled. */
        const val FILL = 0.8f
        /** The most a quiet track is boosted; any quieter and it simply draws smaller. */
        const val MAX_GAIN = 8f
    }
}
