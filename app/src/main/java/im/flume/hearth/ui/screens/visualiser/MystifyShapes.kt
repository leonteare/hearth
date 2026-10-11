package im.flume.hearth.ui.screens.visualiser

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The moving part of Mystify: [polygons] shapes of [vertices] corners, each corner bouncing round a
 * [width] × [height] box with its own velocity, plus a ring of the last [trail] outlines. Pure maths
 * and allocation-free after construction, so it's unit-tested directly.
 */
class MystifyShapes(
    val polygons: Int = 2,
    val vertices: Int = 4,
    val trail: Int = 10,
    seed: Int = 1,
) {
    private val n = polygons * vertices
    val x = FloatArray(n)
    val y = FloatArray(n)
    val vx = FloatArray(n)
    val vy = FloatArray(n)
    var width = 1f
        private set
    var height = 1f
        private set

    private val histX = FloatArray(trail * n)
    private val histY = FloatArray(trail * n)
    private var head = -1
    /** Outlines stored so far (up to [trail]). */
    var stored = 0
        private set

    init {
        val rnd = Random(seed)
        for (i in 0 until n) {
            x[i] = rnd.nextFloat()
            y[i] = rnd.nextFloat()
            val a = rnd.nextFloat() * 6.2832f
            val s = 0.55f + rnd.nextFloat() * 0.45f
            // Not too horizontal or vertical, or a corner just slides along an edge.
            vx[i] = cos(a) * s
            vy[i] = sin(a) * s
            if (abs(vx[i]) < 0.2f) vx[i] = if (vx[i] < 0f) -0.2f else 0.2f
            if (abs(vy[i]) < 0.2f) vy[i] = if (vy[i] < 0f) -0.2f else 0.2f
        }
    }

    /** Sets the box, rescaling positions (and the trail) so the shapes keep their place on a resize. */
    fun resize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f || (w == width && h == height)) return
        val kx = w / width
        val ky = h / height
        for (i in 0 until n) { x[i] *= kx; y[i] *= ky }
        for (i in histX.indices) { histX[i] *= kx; histY[i] *= ky }
        width = w
        height = h
    }

    /**
     * Moves every corner by its velocity × [speed] (box heights per second) × [dt], bouncing off the
     * walls: a corner that would leave is reflected back in and its velocity turned inward.
     */
    fun step(dt: Float, speed: Float) {
        val k = speed * height * dt
        for (i in 0 until n) {
            var nx = x[i] + vx[i] * k
            var ny = y[i] + vy[i] * k
            if (nx < 0f) { nx = -nx; vx[i] = abs(vx[i]) }
            if (nx > width) { nx = 2f * width - nx; vx[i] = -abs(vx[i]) }
            if (ny < 0f) { ny = -ny; vy[i] = abs(vy[i]) }
            if (ny > height) { ny = 2f * height - ny; vy[i] = -abs(vy[i]) }
            x[i] = nx.coerceIn(0f, width)
            y[i] = ny.coerceIn(0f, height)
        }
    }

    /** Copies the current outlines into the trail, overwriting the oldest once it's full. */
    fun record() {
        head = (head + 1) % trail
        x.copyInto(histX, head * n)
        y.copyInto(histY, head * n)
        if (stored < trail) stored++
    }

    /** A corner of a stored outline: [age] 0 = newest record. */
    fun trailX(age: Int, polygon: Int, vertex: Int): Float = histX[slot(age) * n + polygon * vertices + vertex]
    fun trailY(age: Int, polygon: Int, vertex: Int): Float = histY[slot(age) * n + polygon * vertices + vertex]

    private fun slot(age: Int): Int {
        require(age in 0 until stored)
        return (head - age + trail) % trail
    }
}
