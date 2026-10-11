package im.flume.hearth.ui.screens.visualiser

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Pure maths for the retro styles: a tiny perspective camera, the whole-pixel vertex snapping that
 * gives late-90s 3D its wobble, fog, colour helpers and ordered dithering. No Android types, so it's
 * unit-tested directly.
 */

/** Rounds to the nearest multiple of [grid]: projected vertices land on whole low-res pixels. */
fun snapToGrid(v: Float, grid: Float = 1f): Float = floor(v / grid + 0.5f) * grid

/** 0 before [start], 1 after [end], linear between. */
fun fogAmount(depth: Float, start: Float, end: Float): Float = ((depth - start) / (end - start)).coerceIn(0f, 1f)

fun lerpArgb(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val x = (a ushr shift) and 0xFF
        val y = (b ushr shift) and 0xFF
        return (x + (y - x) * u + 0.5f).toInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Multiplies the RGB of [c] by [k] (clamped), keeping alpha. */
fun scaleArgb(c: Int, k: Float): Int {
    fun ch(shift: Int) = (((c ushr shift) and 0xFF) * k).toInt().coerceIn(0, 255)
    return (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun withAlpha(c: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (c and 0xFFFFFF)

/**
 * A pinhole camera at ([x], [y], [z]) turned by [yaw] (around Y; 0 looks down +Z) and [pitch]
 * (positive looks up). Projects onto a screen centred at ([cx], [cy]) with focal length [focal] px.
 */
class RetroCamera {
    var x = 0f
    var y = 0f
    var z = 0f
    var yaw = 0f
    var pitch = 0f
    var focal = 200f
    var cx = 0f
    var cy = 0f
    var near = 0.1f
    private var cosYaw = 1f
    private var sinYaw = 0f
    private var cosPitch = 1f
    private var sinPitch = 0f

    /** Call after changing [yaw] or [pitch]. */
    fun update() {
        cosYaw = cos(yaw); sinYaw = sin(yaw)
        cosPitch = cos(pitch); sinPitch = sin(pitch)
    }

    fun lookAt(tx: Float, ty: Float, tz: Float) {
        val dx = tx - x
        val dy = ty - y
        val dz = tz - z
        yaw = atan2(dx, dz)
        pitch = atan2(dy, sqrt(dx * dx + dz * dz))
        update()
    }

    /**
     * Writes screen x, y and view depth of a world point into [out] at [o]. False (and nothing
     * written) when it's behind the near plane. With [snap], screen coordinates are whole pixels.
     */
    fun project(wx: Float, wy: Float, wz: Float, out: FloatArray, o: Int, snap: Boolean = true): Boolean {
        val dx = wx - x
        val dy = wy - y
        val dz = wz - z
        val rx = dx * cosYaw - dz * sinYaw
        val rz = dx * sinYaw + dz * cosYaw
        val ry = dy * cosPitch - rz * sinPitch
        val depth = dy * sinPitch + rz * cosPitch
        if (depth < near) return false
        var sx = cx + rx / depth * focal
        var sy = cy - ry / depth * focal
        if (snap) { sx = snapToGrid(sx); sy = snapToGrid(sy) }
        out[o] = sx
        out[o + 1] = sy
        out[o + 2] = depth
        return true
    }
}

/** Ordered (Bayer 4×4) dithering down to a few levels per channel, as a lookup table. */
object Bayer {
    val MATRIX = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** Quantises one 0..255 channel to [levels] steps, nudged by the threshold for matrix cell [cell]. */
    fun quantize(value: Int, cell: Int, levels: Int): Int {
        val step = 255f / (levels - 1)
        val threshold = (MATRIX[cell] + 0.5f) / 16f - 0.5f
        val q = floor((value + threshold * step) / step + 0.5f)
        return (q * step + 0.5f).toInt().coerceIn(0, 255)
    }

    /** lut[cell * 256 + value] = [quantize] (value, cell, levels). */
    fun table(levels: Int): IntArray = IntArray(16 * 256) { quantize(it and 0xFF, it ushr 8, levels) }
}
