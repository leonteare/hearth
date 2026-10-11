package im.flume.hearth.ui.screens.visualiser

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.exp

/**
 * Mystify: a tribute to the polygon screensaver. Two quadrilaterals whose corners bounce round the
 * screen, each trailed by ten fading copies of where it's been, in slowly cycling cover colours, on a
 * low-res CRT. The corners speed up with the level and tempo; every beat kicks them forward and
 * flashes the newest outline; bass thickens the lines.
 */
class MystifyRenderer : RetroRenderer() {
    override val targetWidth: Int get() = 320
    override val ditherLevels: Int get() = 0
    override val bloom: Float get() = 0.55f

    private val shapes = MystifyShapes(polygons = 2, vertices = 4, trail = 10, seed = 7)
    private val seg = FloatArray(shapes.vertices * 4)
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }

    private var lastBeat = -1
    private var boost = 0f
    private var flash = 0f
    private var sinceRecord = 0f

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val dt = f.dt.coerceIn(0f, 0.1f)
        shapes.resize(w.toFloat(), h.toFloat())
        c.drawColor(0xFF000000.toInt())

        if (a.beatCount != lastBeat) {
            if (lastBeat >= 0) { boost = 1f; flash = 1f }
            lastBeat = a.beatCount
        }
        boost *= exp(-dt / 0.35f)
        flash *= exp(-dt / 0.22f)

        val tempo = if (a.bpm > 0f && a.tempoConfidence > 0.4f) (a.bpm / 120f).coerceIn(0.6f, 1.6f) else 0.8f
        val speed = 0.12f + 0.1f * tempo + a.level * 0.3f + boost * 0.55f
        shapes.step(dt, speed)
        sinceRecord += dt
        if (shapes.stored == 0 || sinceRecord >= 0.06f) {
            shapes.record()
            sinceRecord = 0f
        }

        val width = 1f + a.bass * 1.4f
        val pal = f.palette
        val trail = shapes.trail
        for (p in 0 until shapes.polygons) {
            val col = pal.argbAt(f.time * 0.025f + p * 0.5f)
            // Oldest first so newer outlines land on top.
            for (age in shapes.stored - 1 downTo 0) {
                val fade = 1f - (age + 1f) / (trail + 1f)
                outline(c, p, age, withAlpha(col, fade * 0.85f), width)
            }
            outline(c, p, -1, withAlpha(lerpArgb(col, 0xFFFFFFFF.toInt(), flash * 0.6f), 1f), width + flash)
        }
    }

    /** One polygon's outline: [age] -1 is where it is now, otherwise a trail copy. */
    private fun outline(c: Canvas, p: Int, age: Int, color: Int, width: Float) {
        val v = shapes.vertices
        for (k in 0 until v) {
            val k2 = (k + 1) % v
            val i = k * 4
            if (age < 0) {
                seg[i] = shapes.x[p * v + k]; seg[i + 1] = shapes.y[p * v + k]
                seg[i + 2] = shapes.x[p * v + k2]; seg[i + 3] = shapes.y[p * v + k2]
            } else {
                seg[i] = shapes.trailX(age, p, k); seg[i + 1] = shapes.trailY(age, p, k)
                seg[i + 2] = shapes.trailX(age, p, k2); seg[i + 3] = shapes.trailY(age, p, k2)
            }
        }
        paint.color = color
        paint.strokeWidth = width
        c.drawLines(seg, 0, v * 4, paint)
    }
}
