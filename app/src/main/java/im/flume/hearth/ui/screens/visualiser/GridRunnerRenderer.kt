package im.flume.hearth.ui.screens.visualiser

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Grid Runner: a neon wireframe landscape scrolling towards a striped sunset. Each new row of
 * mountains on the horizon is the spectrum at that moment, so the terrain rolling past is the last
 * few seconds of the song; the road down the middle stays flat.
 */
class GridRunnerRenderer : RetroRenderer() {
    override val bloom: Float get() = 0.45f
    override val ditherLevels: Int get() = 20

    private val rows = 30
    private val cols = 31
    private val spacing = 1f
    private val heights = FloatArray(rows * cols)
    private var newest = 0
    private var scroll = 0f
    private val px = FloatArray(rows * cols)
    private val py = FloatArray(rows * cols)
    private val pz = FloatArray(rows)
    private val valid = BooleanArray(rows)
    private val tmp = FloatArray(3)
    private val cam = RetroCamera()
    private val rnd = Random(9)

    private val stars = 48
    private val starX = FloatArray(stars)
    private val starY = FloatArray(stars)
    private val starPhase = FloatArray(stars)

    private val sky = Paint()
    private var skyKey = 0L
    private var horizon = 0f

    init {
        for (i in 0 until stars) {
            starX[i] = rnd.nextFloat()
            starY[i] = rnd.nextFloat()
            starPhase[i] = rnd.nextFloat() * 6.28f
        }
    }

    private fun fillRow(r: Int, a: FloatArray) {
        val mid = (cols - 1) / 2f
        for (c in 0 until cols) {
            val d = abs(c - mid) / mid
            val h = if (d < 0.14f) 0f else {
                val e = (d - 0.14f) / 0.86f
                val band = a[(2 + e * 42f).toInt().coerceAtMost(a.size - 1)]
                (band.pow(1.2f) * (0.8f + 6.5f * e.pow(1.4f))) + rnd.nextFloat() * 0.35f * e
            }
            heights[r * cols + c] = h
        }
    }

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt

        scroll += dt * (5f + a.bass * 7f + a.beat * 3f)
        while (scroll >= spacing) {
            scroll -= spacing
            newest = (newest + 1) % rows
            fillRow(newest, a.bands)
        }

        cam.x = 0f; cam.y = 1.5f + a.bass * 0.15f; cam.z = 0f
        cam.yaw = 0f; cam.pitch = -0.09f; cam.update()
        cam.focal = w * 0.9f
        cam.cx = w / 2f
        cam.cy = h * 0.47f
        horizon = cam.cy - cam.focal * kotlin.math.tan(0.09f)

        val haze = lerpArgb(pal.argb[2], 0xFFFF4FA0.toInt(), 0.25f)
        val key = (pal.version.toLong() shl 32) xor h.toLong()
        if (skyKey != key) {
            skyKey = key
            sky.shader = LinearGradient(
                0f, 0f, 0f, horizon,
                intArrayOf(0xFF05010C.toInt(), scaleArgb(pal.argb[3], 0.18f), scaleArgb(haze, 0.55f)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sky)

        for (i in 0 until stars) {
            val tw = 0.5f + 0.5f * sin(f.time * 2f + starPhase[i])
            fill.color = withAlpha(0xFFFFFFFF.toInt(), 0.25f + 0.6f * tw * (1f - starY[i] * 0.5f))
            val x = floor(starX[i] * w)
            val y = floor(starY[i] * horizon * 0.8f)
            c.drawRect(x, y, x + 1f, y + 1f, fill)
        }

        drawSun(c, w, f, haze)

        // Project the terrain: row 0 is nearest.
        for (k in 0 until rows) {
            val buf = (newest - (rows - 1 - k) + rows * 2) % rows
            val z = 0.6f + k * spacing - scroll
            pz[k] = z
            valid[k] = z > cam.near + 0.05f
            if (!valid[k]) continue
            for (col in 0 until cols) {
                val x = (col - (cols - 1) / 2f) * spacing * 1.1f
                cam.project(x, heights[buf * cols + col], z, tmp, 0)
                px[k * cols + col] = tmp[0]
                py[k * cols + col] = tmp[1]
            }
        }

        val neon = lerpArgb(pal.argb[0], 0xFFFFFFFF.toInt(), 0.15f)
        val road = lerpArgb(pal.argb[1], 0xFFFFFFFF.toInt(), 0.25f)
        val ground = lerpArgb(0xFF06020E.toInt(), pal.argb[3], 0.06f)
        val farZ = 0.6f + rows * spacing
        val roadL = (cols - 1) / 2 - 2
        val roadR = (cols - 1) / 2 + 2
        for (k in rows - 1 downTo 0) {
            if (!valid[k]) continue
            val fog = fogAmount(pz[k], farZ * 0.35f, farZ)
            // Lines running back to the next row out, then this row's "curtain" hides what's behind it.
            if (k + 1 < rows && valid[k + 1]) {
                val fog2 = fogAmount((pz[k] + pz[k + 1]) / 2f, farZ * 0.35f, farZ)
                for (col in 0 until cols) {
                    val colColor = if (col == roadL || col == roadR) road else neon
                    line(c, px[(k + 1) * cols + col], py[(k + 1) * cols + col], px[k * cols + col], py[k * cols + col], lerpArgb(colColor, haze, fog2))
                }
            }
            path.rewind()
            path.moveTo(px[k * cols], py[k * cols])
            for (col in 1 until cols) path.lineTo(px[k * cols + col], py[k * cols + col])
            path.lineTo(px[k * cols + cols - 1], h.toFloat() + 1f)
            path.lineTo(px[k * cols], h.toFloat() + 1f)
            path.close()
            fill.color = lerpArgb(ground, scaleArgb(haze, 0.35f), fog * fog)
            c.drawPath(path, fill)
            val rowColor = lerpArgb(neon, haze, fog)
            for (col in 0 until cols - 1) {
                line(c, px[k * cols + col], py[k * cols + col], px[k * cols + col + 1], py[k * cols + col + 1], rowColor)
            }
        }
        // A thin glow where the land meets the sky.
        line(c, 0f, horizon, w.toFloat(), horizon, withAlpha(haze, 0.35f + 0.4f * a.beat))
    }

    private fun drawSun(c: Canvas, w: Int, f: VisFrame, haze: Int) {
        val a = f.audio
        val pal = f.palette
        val r = w * 0.3f * (1f + a.bass * 0.06f + a.beat * 0.03f)
        val scx = w / 2f
        val scy = horizon - r * 0.35f
        val top = lerpArgb(pal.argb[1], 0xFFFFF0A0.toInt(), 0.45f)
        val bottom = lerpArgb(pal.argb[0], haze, 0.3f)
        var y = floor(scy - r)
        while (y <= scy + r) {
            val dy = y - scy
            val t = (dy + r) / (2 * r)
            var gap = false
            if (t > 0.45f) {
                val u = (t - 0.45f) / 0.55f
                val phase = u * 6f - f.time * 0.5f
                gap = phase - floor(phase) < 0.15f + u * 0.45f
            }
            if (!gap) {
                val half = sqrt((r * r - dy * dy).coerceAtLeast(0f))
                line(c, floor(scx - half), y + 0.5f, floor(scx + half), y + 0.5f, lerpArgb(top, bottom, t))
            }
            y += 1f
        }
    }
}
