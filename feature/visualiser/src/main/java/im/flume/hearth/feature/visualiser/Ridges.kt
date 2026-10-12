// The ridge lines and value noise that Unknown Pleasures and Grid Runner draw with.
package im.flume.hearth.feature.visualiser

import kotlin.math.abs
import kotlin.math.floor

/*
 * Pure maths for Unknown Pleasures: the ridge shape, a little value noise for detail, the scroll
 * interval and the ring of past lines. No Android types, so it's unit-tested directly.
 */

/**
 * How tall a ridge may rise at [x] (-1 = left end of the line, 1 = right end): 1 across the middle
 * third, easing smoothly to 0 by about two thirds of the way out, so the ends of every line stay flat.
 */
fun ridgeEnvelope(x: Float): Float {
    val d = abs(x)
    if (d <= RIDGE_CORE) return 1f
    if (d >= RIDGE_EDGE) return 0f
    val t = 1f - (d - RIDGE_CORE) / (RIDGE_EDGE - RIDGE_CORE)
    return t * t * (3f - 2f * t)
}

const val RIDGE_CORE = 1f / 3f
const val RIDGE_EDGE = 0.72f

/** Smooth 1D value noise in 0..1, continuous in [x]; [seed] picks an unrelated sequence. */
fun valueNoise(x: Float, seed: Int = 0): Float {
    val i = floor(x).toInt()
    val f = x - i
    val a = hash01(i, seed)
    val b = hash01(i + 1, seed)
    val s = f * f * (3f - 2f * f)
    return a + (b - a) * s
}

/** A repeatable pseudo-random 0..1 for an integer lattice point. */
fun hash01(i: Int, seed: Int): Float {
    var h = i * 0x27d4eb2d + seed * 0x165667b1
    h = h xor (h ushr 15)
    h *= 0x2c1b3c6d
    h = h xor (h ushr 12)
    h *= 0x297a2d39
    h = h xor (h ushr 15)
    return (h ushr 8) / 16777216f
}

/**
 * Seconds between new lines: a sixteenth note when the tempo is trusted (so the history scrolls in
 * time), otherwise a steady tenth of a second; always kept between 1/14 and 1/6 s.
 */
fun ridgeInterval(bpm: Float, tempoConfidence: Float): Float {
    if (bpm <= 0f || tempoConfidence < 0.5f) return 0.1f
    var step = 60f / bpm / 4f
    // Very fast or slow tempos: halve or double until it's in a comfortable range.
    while (step < 1f / 14f) step *= 2f
    while (step > 1f / 6f) step /= 2f
    return step.coerceIn(1f / 14f, 1f / 6f)
}

/**
 * The last [lines] ridges, [points] heights each, newest first. [push] copies a line in, overwriting
 * the oldest once full; [get] reads line `age` (0 = newest). Allocation-free after construction.
 */
class RidgeHistory(val lines: Int, val points: Int) {
    private val data = FloatArray(lines * points)
    private var head = -1
    var size = 0
        private set

    fun push(src: FloatArray) {
        head = (head + 1) % lines
        src.copyInto(data, head * points, 0, points)
        if (size < lines) size++
    }

    fun get(age: Int, point: Int): Float {
        if (age >= size) return 0f
        val slot = (head - age + lines) % lines
        return data[slot * points + point]
    }

    fun clear() {
        size = 0
        head = -1
    }
}
