package im.flume.hearth.feature.player.visualiser

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Memory Card: a slow camera orbit over a grid of glowing, translucent towers in a blue-black fog,
 * like a late-90s console's system menu. Each tower is a spectrum band (bass in the middle), softened
 * by a bloom, with fine dust drifting up through the light.
 */
class MemoryCardRenderer : RetroRenderer() {
    override val bloom: Float get() = 0.65f
    override val ditherLevels: Int get() = 24

    private val cam = RetroCamera()
    private val grid = 8
    private val count = grid * grid
    private val spacing = 1.5f
    private val half = 0.48f
    private val tx = FloatArray(count)
    private val tz = FloatArray(count)
    private val bandOf = IntArray(count)
    private val rank = IntArray(count)
    private val heights = FloatArray(count)
    private val order = IntArray(count) { it }
    private val dist = FloatArray(count)
    private val pts = FloatArray(8 * 3)
    private val seg = FloatArray(6)

    private val motes = 50
    private val mx = FloatArray(motes)
    private val my = FloatArray(motes)
    private val mz = FloatArray(motes)
    private val rnd = Random(3)

    private val sky = Paint()
    private var skyKey = 0L

    init {
        for (i in 0 until count) {
            tx[i] = (i % grid - (grid - 1) / 2f) * spacing
            tz[i] = (i / grid - (grid - 1) / 2f) * spacing
        }
        // Bass in the middle of the grid, treble at the edges.
        val byDistance = (0 until count).sortedBy { tx[it] * tx[it] + tz[it] * tz[it] + it * 1e-4f }
        byDistance.forEachIndexed { r, i -> rank[i] = r; bandOf[i] = r * 54 / count }
        for (i in 0 until motes) {
            mx[i] = rnd.nextFloat() * 16f - 8f
            my[i] = rnd.nextFloat() * 7f
            mz[i] = rnd.nextFloat() * 16f - 8f
        }
    }

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt
        val fogColor = lerpArgb(0xFF071330.toInt(), pal.argb[0], 0.12f)

        val key = (pal.version.toLong() shl 32) xor h.toLong()
        if (skyKey != key) {
            skyKey = key
            sky.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF010208.toInt(), fogColor, lerpArgb(0xFF040A1C.toInt(), pal.argb[2], 0.08f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sky)

        val ang = f.time * 0.08f
        cam.x = sin(ang) * 12f
        cam.z = cos(ang) * 12f
        cam.y = 6.2f + sin(f.time * 0.11f) * 1.1f
        cam.focal = w * 1.15f
        cam.cx = w / 2f
        cam.cy = h * 0.56f
        cam.lookAt(0f, 0.6f, 0f)

        // Floor grid, fading into the fog.
        val gridColor = lerpArgb(0xFF0E2E66.toInt(), pal.argb[0], 0.3f)
        val extent = grid / 2f * spacing
        for (i in 0..grid) {
            val g = (i - grid / 2f) * spacing
            for (s in 0 until 6) {
                val t0 = -extent + s * (2 * extent / 6)
                val t1 = t0 + 2 * extent / 6
                floorLine(c, g, t0, g, t1, gridColor, fogColor)
                floorLine(c, t0, g, t1, g, gridColor, fogColor)
            }
        }

        // Towers follow the spectrum: up quickly, down slowly (on top of the bands' own smoothing).
        val up = 1f - exp(-dt / 0.06f)
        val down = 1f - exp(-dt / 0.35f)
        for (i in 0 until count) {
            val target = 0.12f + a.bands[bandOf[i]].pow(1.3f) * 4.6f
            heights[i] += (target - heights[i]) * (if (target > heights[i]) up else down)
            val dx = tx[i] - cam.x
            val dz = tz[i] - cam.z
            dist[i] = dx * dx + dz * dz
        }
        // Farthest first (insertion sort: nearly sorted from the last frame).
        for (i in 1 until count) {
            val v = order[i]
            var j = i - 1
            while (j >= 0 && dist[order[j]] < dist[v]) { order[j + 1] = order[j]; j-- }
            order[j + 1] = v
        }
        for (n in 0 until count) drawTower(c, order[n], a.bands[bandOf[order[n]]], pal, fogColor)

        // Dust motes drifting up through the light.
        val moteColor = lerpArgb(0xFF8FB8FF.toInt(), pal.argb[1], 0.3f)
        for (i in 0 until motes) {
            my[i] += dt * (0.12f + a.level * 0.2f)
            if (my[i] > 7f) my[i] = 0f
            if (!cam.project(mx[i], my[i], mz[i], pts, 0)) continue
            val fog = fogAmount(pts[2], 6f, 24f)
            fill.color = withAlpha(moteColor, (0.5f + 0.4f * a.treble) * (1f - fog))
            c.drawRect(pts[0], pts[1], pts[0] + 1f, pts[1] + 1f, fill)
        }
    }

    private fun floorLine(c: Canvas, x0: Float, z0: Float, x1: Float, z1: Float, color: Int, fogColor: Int) {
        if (!cam.project(x0, 0f, z0, seg, 0) || !cam.project(x1, 0f, z1, seg, 3)) return
        val fog = fogAmount((seg[2] + seg[5]) / 2f, 6f, 24f)
        line(c, seg[0], seg[1], seg[3], seg[4], withAlpha(lerpArgb(color, fogColor, fog), 0.7f * (1f - fog * 0.6f)))
    }

    private fun drawTower(c: Canvas, i: Int, v: Float, pal: VisPalette, fogColor: Int) {
        val x0 = tx[i] - half
        val x1 = tx[i] + half
        val z0 = tz[i] - half
        val z1 = tz[i] + half
        val top = heights[i]
        if (!cam.project(x0, 0f, z0, pts, 0) || !cam.project(x1, 0f, z0, pts, 3) ||
            !cam.project(x1, 0f, z1, pts, 6) || !cam.project(x0, 0f, z1, pts, 9) ||
            !cam.project(x0, top, z0, pts, 12) || !cam.project(x1, top, z0, pts, 15) ||
            !cam.project(x1, top, z1, pts, 18) || !cam.project(x0, top, z1, pts, 21)
        ) return

        val fog = fogAmount(kotlin.math.sqrt(dist[i]), 8f, 22f) * 0.85f
        val base = pal.argbAt(rank[i] / count.toFloat() * 0.8f)
        val lit = 0.35f + 0.75f * v
        fun shade(k: Float, alpha: Float) = withAlpha(lerpArgb(scaleArgb(base, lit * k), fogColor, fog), alpha)

        if (cam.x > x1) face(c, 1, 2, 6, 5, shade(0.75f, 0.55f))
        if (cam.x < x0) face(c, 0, 3, 7, 4, shade(0.75f, 0.55f))
        if (cam.z > z1) face(c, 3, 2, 6, 7, shade(0.6f, 0.55f))
        if (cam.z < z0) face(c, 0, 1, 5, 4, shade(0.6f, 0.55f))
        if (cam.y > top) face(c, 4, 5, 6, 7, shade(1.1f, 0.8f))

        val edge = withAlpha(lerpArgb(lerpArgb(base, 0xFFFFFFFF.toInt(), 0.3f + 0.4f * v), fogColor, fog), 0.9f)
        edgeLine(c, 4, 5, edge); edgeLine(c, 5, 6, edge); edgeLine(c, 6, 7, edge); edgeLine(c, 7, 4, edge)
        for (k in 0 until 4) edgeLine(c, k, k + 4, withAlpha(edge, 0.45f))
    }

    private fun face(c: Canvas, a: Int, b: Int, d: Int, e: Int, color: Int) =
        fillQuad(c, pts[a * 3], pts[a * 3 + 1], pts[b * 3], pts[b * 3 + 1], pts[d * 3], pts[d * 3 + 1], pts[e * 3], pts[e * 3 + 1], color)

    private fun edgeLine(c: Canvas, a: Int, b: Int, color: Int) =
        line(c, pts[a * 3], pts[a * 3 + 1], pts[b * 3], pts[b * 3 + 1], color)
}
