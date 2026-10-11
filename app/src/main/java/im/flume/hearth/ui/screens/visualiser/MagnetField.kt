package im.flume.hearth.ui.screens.visualiser

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The physics behind Magnetosphere: [count] particles drifting round [magnets] invisible attractors
 * that wander on slow Lissajous paths. Each magnet pulls and swirls particles in proportion to its
 * energy (0..1, one frequency region each), and a kick sends a ring-shaped shockwave out from the
 * first (bass) magnet. The field is [aspect] × 1 in half-extents: x in -aspect..aspect, y in -1..1.
 * Pure maths, allocation-free after construction, so it's unit-tested directly.
 */
class MagnetField(val count: Int, val magnets: Int = 4, seed: Int = 7) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    /** Index of the nearest magnet after the last [step]. */
    val owner = IntArray(count)
    /** Speed after the last [step], in field units per second. */
    val speed = FloatArray(count)
    private val life = FloatArray(count)

    val mx = FloatArray(magnets)
    val my = FloatArray(magnets)
    private val fx = FloatArray(magnets)
    private val fy = FloatArray(magnets)
    private val px = FloatArray(magnets)
    private val py = FloatArray(magnets)
    private val spin = FloatArray(magnets)

    var aspect = 1f
    var time = 0f
        private set

    var rippleX = 0f
        private set
    var rippleY = 0f
        private set
    var rippleR = 0f
        private set
    var ripplePower = 0f
        private set

    private val rnd = Random(seed)

    init {
        for (m in 0 until magnets) {
            fx[m] = 0.07f + 0.045f * m + rnd.nextFloat() * 0.03f
            fy[m] = 0.09f + 0.035f * m + rnd.nextFloat() * 0.03f
            px[m] = rnd.nextFloat() * 6.2832f
            py[m] = rnd.nextFloat() * 6.2832f
            spin[m] = if (m % 2 == 0) 1f else -1f
        }
        placeMagnets()
        for (i in 0 until count) respawn(i, initial = true)
    }

    private fun placeMagnets() {
        for (m in 0 until magnets) {
            mx[m] = aspect * 0.62f * sin(time * fx[m] * 6.2832f + px[m])
            my[m] = 0.62f * sin(time * fy[m] * 6.2832f + py[m])
        }
    }

    private fun respawn(i: Int, initial: Boolean = false) {
        x[i] = (rnd.nextFloat() * 2f - 1f) * aspect
        y[i] = rnd.nextFloat() * 2f - 1f
        vx[i] = 0f
        vy[i] = 0f
        life[i] = if (initial) rnd.nextFloat() * 6f else 2f + rnd.nextFloat() * 5f
    }

    /** Starts a shockwave from the bass magnet; [power] about 0..1. */
    fun kick(power: Float) {
        rippleX = mx[0]
        rippleY = my[0]
        rippleR = 0f
        ripplePower = power.coerceIn(0f, 1.5f)
    }

    /**
     * Advances [dt] seconds with each magnet's [energy] (0..1). Only the first [active] particles
     * move (fewer when frames run late); the rest are left where they are.
     */
    fun step(dt: Float, energy: FloatArray, active: Int = count) {
        val d = dt.coerceIn(0f, 0.05f)
        time += d
        placeMagnets()
        val drag = exp(-d * 1.8f)
        val rippling = ripplePower > 0.02f
        if (rippling) {
            rippleR += d * 1.5f
            ripplePower *= exp(-d / 0.45f)
        }
        val xMax = aspect + 0.08f
        val n = active.coerceIn(0, count)
        for (i in 0 until n) {
            var ax = 0f
            var ay = 0f
            var best = Float.MAX_VALUE
            var bestM = 0
            for (m in 0 until magnets) {
                val e = energy[m].coerceIn(0f, 1f)
                val dx = mx[m] - x[i]
                val dy = my[m] - y[i]
                val r2 = dx * dx + dy * dy
                if (r2 < best) { best = r2; bestM = m }
                val inv = 1f / (r2 + SOFTEN)
                val pull = (0.08f + 1.5f * e) * PULL * inv
                val swirl = (0.3f + 0.7f * e) * SWIRL * spin[m] * inv
                ax += dx * pull - dy * swirl
                ay += dy * pull + dx * swirl
            }
            // A little turbulence keeps it soft and nebula-like rather than clockwork.
            ax += (rnd.nextFloat() - 0.5f) * JITTER
            ay += (rnd.nextFloat() - 0.5f) * JITTER
            if (rippling) {
                val rx = x[i] - rippleX
                val ry = y[i] - rippleY
                val dist = sqrt(rx * rx + ry * ry) + 1e-4f
                val band = 1f - kotlin.math.abs(dist - rippleR) / RIPPLE_WIDTH
                if (band > 0f) {
                    val push = band * ripplePower * RIPPLE_PUSH / dist
                    ax += rx * push
                    ay += ry * push
                }
            }
            var nvx = (vx[i] + ax * d) * drag
            var nvy = (vy[i] + ay * d) * drag
            val s = sqrt(nvx * nvx + nvy * nvy)
            if (s > MAX_SPEED) { val k = MAX_SPEED / s; nvx *= k; nvy *= k }
            vx[i] = nvx
            vy[i] = nvy
            x[i] += nvx * d
            y[i] += nvy * d
            owner[i] = bestM
            speed[i] = if (s > MAX_SPEED) MAX_SPEED else s
            life[i] -= d
            if (life[i] <= 0f || x[i] < -xMax || x[i] > xMax || y[i] < -1.08f || y[i] > 1.08f || x[i].isNaN() || y[i].isNaN()) {
                respawn(i)
                speed[i] = 0f
            }
        }
    }

    companion object {
        const val SOFTEN = 0.015f
        const val PULL = 0.55f
        const val SWIRL = 0.5f
        const val JITTER = 0.6f
        const val RIPPLE_WIDTH = 0.14f
        const val RIPPLE_PUSH = 9f
        const val MAX_SPEED = 1.6f
    }
}
