package im.flume.hearth.ui.screens.visualiser

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Grid Runner: a neon wireframe landscape under a striped synthwave sun, drawn by the music like a
 * 3D spectrogram. The nearest ridge, just in front of the camera, is the spectrum right now (a
 * bright equaliser line); every fraction of a second it's printed into the land, and the printed
 * rows roll away to the horizon, so the hills are the last few seconds of the song.
 *
 * The road down the middle stays flat; bass sits right beside it and the bands rise to treble at the
 * outer edges (mirrored left and right), so kicks make the inner hills jump. Hills are coloured by
 * height (palette colour low down, glowing towards white at the peaks) and fade as they recede;
 * kicks flash the road edges, and the flash travels away with the land.
 */
class GridRunnerRenderer : RetroRenderer() {
    override val bloom: Float get() = 0.5f
    override val ditherLevels: Int get() = 20

    private val cols = 31
    private val mid = (cols - 1) / 2
    private val roadHalf = 2
    private val side = mid - roadHalf
    /** Rows printed into the land; plus the live row in front of them. */
    private val history = 30
    private val rows = history + 1

    /** Per column: which bands it shows (lo inclusive, hi exclusive), and 0..1 out from the road. */
    private val bandLo = IntArray(cols)
    private val bandHi = IntArray(cols)
    private val edge = FloatArray(cols)
    private val terrain = BooleanArray(cols)

    private val live = FloatArray(cols)
    private val hist = FloatArray(history * cols)
    private val histKick = FloatArray(history)
    private var newest = 0
    private var kickPeak = 0f
    private var scroll = 0f
    private var rate = 4f

    private val px = FloatArray(rows * cols)
    private val py = FloatArray(rows * cols)
    private val wh = FloatArray(rows * cols)
    private val lv = FloatArray(rows * cols)
    private val pz = FloatArray(rows)
    private val rowKick = FloatArray(rows)
    private val valid = BooleanArray(rows)
    private val tmp = FloatArray(3)
    private val seg = FloatArray(4)
    private val cam = RetroCamera()
    private val rnd = Random(9)

    private val stars = 48
    private val starX = FloatArray(stars)
    private val starY = FloatArray(stars)
    private val starPhase = FloatArray(stars)

    private val sky = Paint()
    private var skyKey = 0L
    private var horizon = 0f
    private var colStep = 0.2f
    private var unit = 2f
    private var front = 4f
    private var laidOutFor = -1

    init {
        for (i in 0 until stars) {
            starX[i] = rnd.nextFloat()
            starY[i] = rnd.nextFloat()
            starPhase[i] = rnd.nextFloat() * 6.28f
        }
    }

    private fun layoutColumns(bandCount: Int) {
        if (laidOutFor == bandCount) return
        laidOutFor = bandCount
        for (c in 0 until cols) {
            val d = abs(c - mid)
            terrain[c] = d > roadHalf
            val e = (d - roadHalf - 1).coerceAtLeast(0)
            bandLo[c] = GridTerrain.bandLo(e, side, bandCount)
            bandHi[c] = GridTerrain.bandHi(e, side, bandCount)
            edge[c] = if (terrain[c]) e / (side - 1f) else 0f
        }
    }

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt
        layoutColumns(a.bands.size)

        // The live row: each column's loudest band, right now.
        for (col in 0 until cols) {
            var v = 0f
            if (terrain[col]) for (b in bandLo[col] until bandHi[col]) v = max(v, a.bands[b])
            live[col] = v
        }
        kickPeak = max(kickPeak, a.kick)

        // A steady scroll, two rows a beat when the tempo is known (folded into a calm range).
        var target = 4f
        if (a.bpm > 0f && a.tempoConfidence > 0.35f) {
            target = a.bpm / 60f * 2f
            while (target > 6.5f) target /= 2f
            while (target < 2.5f) target *= 2f
        }
        rate += (target - rate) * (1f - exp(-dt / 1f))
        scroll += dt * rate
        while (scroll >= 1f) {
            scroll -= 1f
            newest = (newest + 1) % history
            live.copyInto(hist, newest * cols)
            histKick[newest] = kickPeak
            kickPeak = 0f
        }

        // Camera: the horizon sits at 42% down, and the live row's foot near the bottom of the screen.
        val focal = w * 0.9f
        cam.focal = focal
        cam.cx = w / 2f
        horizon = h * 0.42f
        cam.cy = horizon + focal * tan(PITCH)
        val below = max(0.08f, PITCH + atan((h * 0.88f - cam.cy) / focal))
        val camY = (FRONT * tan(below)).coerceIn(2.2f, 6f)
        front = (camY / tan(below)).coerceIn(2.5f, 12f)
        cam.x = 0f; cam.z = 0f; cam.y = camY
        cam.yaw = 0f; cam.pitch = -PITCH; cam.update()
        // The land is as wide as the screen at the live row, whatever the aspect ratio; heights scale with it.
        val depth0 = camY * sin(PITCH) + front * cos(PITCH)
        unit = depth0 * (w / 2f) / focal
        colStep = unit * 1.1f / mid
        val cap = GridTerrain.cap(camY)

        val haze = lerpArgb(pal.argb[2], 0xFFFF4FA0.toInt(), 0.25f)
        val key = (pal.version.toLong() shl 32) xor (h.toLong() * 31 + w)
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

        // Project the land: row 0 is live, at the front; printed rows slide smoothly away from it.
        // Everything is at least [front] ahead and capped below the camera, so no point comes near
        // the near plane.
        for (k in 0 until rows) {
            val z = if (k == 0) front else front + (k - 1 + scroll) * SPACING
            pz[k] = z
            val buf = (newest - (k - 1) + history) % history
            rowKick[k] = if (k == 0) a.kick else histKick[buf]
            var ok = true
            for (col in 0 until cols) {
                val i = k * cols + col
                val v = if (k == 0) live[col] else hist[buf * cols + col]
                val hgt = if (terrain[col]) min(GridTerrain.ridgeHeight(v, edge[col], unit), cap) else 0f
                wh[i] = hgt
                lv[i] = v
                if (ok && cam.project(colX(col), hgt, z, tmp, 0)) {
                    px[i] = tmp[0]
                    py[i] = tmp[1]
                } else {
                    ok = false
                }
            }
            valid[k] = ok
        }

        val neon = lerpArgb(pal.argb[0], 0xFFFFFFFF.toInt(), 0.1f)
        val hot = lerpArgb(pal.argb[1], 0xFFFFFFFF.toInt(), 0.3f)
        val road = lerpArgb(pal.argb[1], 0xFFFFFFFF.toInt(), 0.25f)
        val ground = lerpArgb(0xFF06020E.toInt(), pal.argb[3], 0.06f)
        val farZ = front + rows * SPACING
        val fogStart = front + (farZ - front) * 0.3f
        for (k in rows - 1 downTo 0) {
            val fog = fogAmount(pz[k], fogStart, farZ)
            // Older rows glow less: the peaks cool as they recede.
            val fade = 1f - 0.5f * k / history
            // Lines running back to the next row out, then this row's "curtain" hides what's behind.
            if (k + 1 < rows && valid[k + 1] && valid[k]) {
                val fog2 = fogAmount((pz[k] + pz[k + 1]) / 2f, fogStart, farZ)
                for (col in 0 until cols) {
                    val i = k * cols + col
                    val j = i + cols
                    val color = when {
                        abs(col - mid) == roadHalf -> lerpArgb(road, 0xFFFFFFFF.toInt(), rowKick[k] * 0.9f * (1f - fog2))
                        terrain[col] -> heightColor(max(lv[i], lv[j]) * fade, neon, hot)
                        else -> neon
                    }
                    line(c, px[j], py[j], px[i], py[i], lerpArgb(color, haze, fog2))
                }
            }
            if (!valid[k]) continue
            val r0 = k * cols
            path.rewind()
            path.moveTo(px[r0], py[r0])
            for (col in 1 until cols) path.lineTo(px[r0 + col], py[r0 + col])
            path.lineTo(px[r0 + cols - 1], h.toFloat() + 1f)
            path.lineTo(px[r0], h.toFloat() + 1f)
            path.close()
            fill.color = lerpArgb(ground, scaleArgb(haze, 0.35f), fog * fog)
            c.drawPath(path, fill)

            val isLive = k == 0
            for (col in 0 until cols - 1) {
                val i = r0 + col
                val t = max(lv[i], lv[i + 1]) * fade
                var color = lerpArgb(heightColor(t, neon, hot), haze, fog)
                if (isLive) {
                    // The equaliser: a bright, thick ridge line with a soft glow under it.
                    color = lerpArgb(color, 0xFFFFFFFF.toInt(), 0.3f + 0.3f * a.level)
                    line(c, px[i], py[i], px[i + 1], py[i + 1], withAlpha(color, 0.25f + 0.45f * t), 4f)
                    line(c, px[i], py[i], px[i + 1], py[i + 1], color, 2f)
                } else {
                    if (t > 0.45f && fog < 0.8f) {
                        line(c, px[i], py[i], px[i + 1], py[i + 1], withAlpha(color, (t - 0.45f) * 0.9f * (1f - fog)), 3f)
                    }
                    line(c, px[i], py[i], px[i + 1], py[i + 1], color)
                }
            }
        }

        // Flat ground in front of the live row: the grid runs on under the camera, and the road
        // edges flash with each kick.
        val floorColor = scaleArgb(neon, 0.55f)
        val roadFlash = lerpArgb(road, 0xFFFFFFFF.toInt(), a.kick * 0.9f)
        for (col in 0 until cols step 2) {
            val x = colX(col)
            if (cam.projectSegment(x, 0f, front, x, 0f, 0.2f, seg)) line(c, seg[0], seg[1], seg[2], seg[3], floorColor)
        }
        for (s in intArrayOf(-roadHalf, roadHalf)) {
            val x = colX(mid + s)
            if (cam.projectSegment(x, 0f, front, x, 0f, 0.2f, seg)) line(c, seg[0], seg[1], seg[2], seg[3], roadFlash, 1f + a.kick)
        }
        for (m in 1..4) {
            val z = front - (m - scroll) * SPACING
            if (z <= 0.3f) break
            if (cam.projectSegment(colX(0), 0f, z, colX(cols - 1), 0f, z, seg)) line(c, seg[0], seg[1], seg[2], seg[3], floorColor)
        }
        // A thin glow where the land meets the sky.
        line(c, 0f, horizon, w.toFloat(), horizon, withAlpha(haze, 0.35f + 0.4f * a.beat))
    }

    /** Low hills in the palette colour, warming through [hot] to near-white at the peaks. */
    private fun heightColor(t: Float, neon: Int, hot: Int): Int {
        val warm = lerpArgb(neon, hot, smoothstep(0.12f, 0.6f, t))
        return lerpArgb(warm, 0xFFFFFFFF.toInt(), smoothstep(0.55f, 1f, t) * 0.85f)
    }

    private fun colX(col: Int) = (col - mid) * colStep

    private companion object {
        /** How far the camera looks down. */
        const val PITCH = 0.12f
        /** Preferred distance to the live row (the camera's height follows from it). */
        const val FRONT = 4f
        const val SPACING = 1f
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

/** Grid Runner's terrain maths, kept free of Android types for unit tests. */
internal object GridTerrain {
    /**
     * First band shown by terrain column [e] (0 = right beside the road, [side] - 1 = the outer edge):
     * the spectrum is split evenly (it's already log-spaced), skipping the lowest band and the top
     * few, which are mostly rumble and silence.
     */
    fun bandLo(e: Int, side: Int, bandCount: Int): Int = 1 + e * (bandCount - 5) / side

    /** One past the last band shown by column [e]; always at least one band. */
    fun bandHi(e: Int, side: Int, bandCount: Int): Int =
        max(bandLo(e, side, bandCount) + 1, bandLo(e + 1, side, bandCount)).coerceAtMost(bandCount)

    /**
     * A hill's height for band level [v] (0..1) at [e01] (0 beside the road .. 1 at the edge), in
     * units of [unit] (the land's half-width): a gentle valley when quiet, with outer (treble)
     * columns given a little more reach, since treble is rarely as loud as bass.
     */
    fun ridgeHeight(v: Float, e01: Float, unit: Float): Float =
        unit * (0.04f + 0.1f * e01 * e01 + v.coerceIn(0f, 1f).pow(1.3f) * (0.55f + 0.4f * e01))

    /** The tallest a hill may stand: well below the camera, so it can't reach the near plane. */
    fun cap(camY: Float): Float = camY * 0.8f
}
