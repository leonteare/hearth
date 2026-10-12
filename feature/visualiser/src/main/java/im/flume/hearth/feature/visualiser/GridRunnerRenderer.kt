// Grid Runner: a retro drive over a wireframe landscape that rises with the music.
package im.flume.hearth.feature.visualiser

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs
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
 * Grid Runner: flying low and fast over a neon wireframe landscape towards a striped synthwave sun,
 * between big mountain ranges. A glowing scanner line sweeps across the land a short way ahead: the
 * spectrum right now is printed into the terrain there (road flat in the middle, bass beside it,
 * treble out at the edges, mirrored), and the printed hills then rush towards and under the camera,
 * so the land just ahead is what you're hearing and the land passing beneath is the last second or
 * two. Beyond the scanner the ground is calm and low, waiting to be written.
 *
 * Speed follows the tempo; the camera bobs and sways, and kicks give it a small jolt and a punch of
 * the field of view. The mountains breathe with the slow bass.
 */
class GridRunnerRenderer : RetroRenderer() {
    override val bloom: Float get() = 0.5f
    override val ditherLevels: Int get() = 20

    /** Spectrum columns: road in the middle, [SIDE] terrain columns each side. */
    private val sc = 2 * (ROAD_HALF + SIDE) + 1
    private val mid = (sc - 1) / 2
    /** All columns per row: [SKIRT] mountain columns each side, then the spectrum columns. */
    private val tc = sc + 2 * SKIRT
    /** Draw rows: the world rows plus the live scanner row. */
    private val dr = ROWS + 1

    // Per spectrum column: bands shown, 0..1 out from the road, terrain or road.
    private val bandLo = IntArray(sc)
    private val bandHi = IntArray(sc)
    private val edge = FloatArray(sc)
    private val terrain = BooleanArray(sc)
    private var laidOutFor = -1

    // World rows, by slot (row id mod ROWS): printed spectrum or still procedural.
    private val printedV = FloatArray(ROWS * sc)
    private val printed = BooleanArray(ROWS)
    private val live = FloatArray(sc)
    private val peak = FloatArray(sc)
    private var nearId = 0L
    private var scroll = 0f
    private var rate = 3f

    // Per draw row (0 = nearest) and point.
    private val rz = FloatArray(dr)
    private val kind = IntArray(dr)
    private val valid = BooleanArray(dr)
    private val xs = FloatArray(tc)
    private val wh = FloatArray(dr * tc)
    private val lv = FloatArray(dr * tc)
    private val px = FloatArray(dr * tc)
    private val py = FloatArray(dr * tc)
    private val tmp = FloatArray(3)
    private val seg = FloatArray(4)
    private val cam = RetroCamera()
    private val rnd = Random(9)

    private val stars = 56
    private val starX = FloatArray(stars)
    private val starY = FloatArray(stars)
    private val starPhase = FloatArray(stars)
    private val ridges = 40
    private val backRange = FloatArray(ridges)
    private val frontRange = FloatArray(ridges)

    private val sky = Paint()
    private var skyKey = 0L
    private var horizon = 0f
    private var unit = 7f
    private var colStep = 0.5f

    init {
        for (i in 0 until stars) {
            starX[i] = rnd.nextFloat()
            starY[i] = rnd.nextFloat()
            starPhase[i] = rnd.nextFloat() * 6.28f
        }
        // Two jagged ranges for the horizon, lower in the middle so the sun shows through.
        for (i in 0 until ridges) {
            val u = i / (ridges - 1f)
            val valley = 0.25f + 0.75f * smoothstep(0.06f, 0.42f, abs(u - 0.5f))
            backRange[i] = valley * (0.45f + 0.55f * rnd.nextFloat())
            frontRange[i] = valley * (0.3f + 0.7f * rnd.nextFloat()) * (if (i % 2 == 0) 1f else 0.6f)
        }
    }

    private fun layoutColumns(bandCount: Int) {
        if (laidOutFor == bandCount) return
        laidOutFor = bandCount
        for (c in 0 until sc) {
            val d = abs(c - mid)
            terrain[c] = d > ROAD_HALF
            val e = (d - ROAD_HALF - 1).coerceAtLeast(0)
            bandLo[c] = GridTerrain.bandLo(e, SIDE, bandCount)
            bandHi[c] = GridTerrain.bandHi(e, SIDE, bandCount)
            edge[c] = if (terrain[c]) e / (SIDE - 1f) else 0f
        }
    }

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt
        val t = f.time
        layoutColumns(a.bands.size)

        // The spectrum right now, per column, and its peak since the last row was printed.
        for (col in 0 until sc) {
            var v = 0f
            if (terrain[col]) for (b in bandLo[col] until bandHi[col]) v = max(v, a.bands[b])
            live[col] = v
            peak[col] = max(peak[col], v)
        }

        // Speed: two rows a beat when the tempo is known (folded into a calm range), else by level.
        val target = if (a.bpm > 0f && a.tempoConfidence > 0.35f) {
            var r = a.bpm / 60f * 2f
            while (r > 6.5f) r /= 2f
            while (r < 2.8f) r *= 2f
            r
        } else {
            2.8f + 2.5f * a.level
        }
        rate += (target - rate) * (1f - exp(-dt / 1.2f))
        scroll += dt * rate
        while (scroll >= 1f) {
            // The nearest row has passed under the camera; its slot becomes a fresh row at the horizon.
            scroll -= 1f
            printed[(nearId % ROWS).toInt()] = false
            nearId++
        }

        // Camera: low, wide, bobbing and swaying; kicks dip it and punch the field of view.
        val punch = a.kick
        val baseFocal = min(w * 0.62f, h * 1.25f)
        val focal = baseFocal * (1f + 0.05f * punch)
        horizon = h * (if (h > w) 0.4f else 0.44f)
        cam.focal = focal
        cam.cx = w / 2f
        cam.cy = horizon + focal * tan(PITCH)
        cam.x = 0.22f * sin(t * 0.27f) + 0.08f * sin(t * 0.71f)
        cam.y = GridTerrain.cameraHeight(t, punch, a.bassSlow)
        cam.z = 0f
        cam.yaw = 0f; cam.pitch = -PITCH; cam.update()
        // The spectrum spans the screen's width at the scanner, whatever the aspect ratio.
        unit = SCAN_Z * (w / 2f) / baseFocal
        colStep = unit * 0.95f / (mid - 0.5f)
        for (i in 0 until tc) xs[i] = columnX(i)

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
        c.drawRect(0f, 0f, w.toFloat(), horizon + 1f, sky)
        val ground = lerpArgb(0xFF06020E.toInt(), pal.argb[3], 0.06f)
        val farGround = lerpArgb(ground, scaleArgb(haze, 0.35f), 0.85f)
        fill.color = farGround
        c.drawRect(0f, horizon, w.toFloat(), h.toFloat(), fill)

        for (i in 0 until stars) {
            val tw = 0.5f + 0.5f * sin(t * 2f + starPhase[i])
            fill.color = withAlpha(0xFFFFFFFF.toInt(), 0.25f + 0.6f * tw * (1f - starY[i] * 0.5f))
            val x = floor(starX[i] * w)
            val y = floor(starY[i] * horizon * 0.75f)
            c.drawRect(x, y, x + 1f, y + 1f, fill)
        }
        drawSun(c, w, h, f, haze)
        val breathe = 0.85f + 0.35f * a.bassSlow
        drawRange(c, w, backRange, h * 0.17f * breathe, -cam.x * 3f, lerpArgb(ground, haze, 0.35f), withAlpha(pal.argb[2], 0.55f))
        drawRange(c, w, frontRange, h * 0.1f * breathe, -cam.x * 6f, lerpArgb(ground, haze, 0.12f), withAlpha(pal.argb[0], 0.7f))
        line(c, 0f, horizon, w.toFloat(), horizon, withAlpha(haze, 0.4f + 0.3f * a.beat))

        buildRows(a.bands.size, a.bassSlow)

        val neon = lerpArgb(pal.argb[0], 0xFFFFFFFF.toInt(), 0.1f)
        val hot = lerpArgb(pal.argb[1], 0xFFFFFFFF.toInt(), 0.3f)
        val calm = scaleArgb(lerpArgb(pal.argb[3], neon, 0.5f), 0.55f)
        val road = lerpArgb(pal.argb[1], 0xFFFFFFFF.toInt(), 0.25f)
        val farZ = Z0 + ROWS - 1f
        val fogStart = SCAN_Z + 3f
        val scan = max(a.kick, a.beat * 0.5f)
        val roadL = SKIRT + mid - ROAD_HALF
        val roadR = SKIRT + mid + ROAD_HALF
        val bottom = h.toFloat() + 1f

        for (d in dr - 1 downTo 0) {
            val z = rz[d]
            val fog = fogAmount(z, fogStart, farZ)
            val r0 = d * tc
            // Lines running back to the next row out, clipped where they pass the camera.
            if (d + 1 < dr) {
                val fog2 = fogAmount((z + rz[d + 1]) / 2f, fogStart, farZ)
                val ahead = kind[d + 1] == KIND_CALM
                for (i in 0 until tc) {
                    val j = r0 + tc + i
                    if (!cam.projectSegment(xs[i], wh[j], rz[d + 1], xs[i], wh[r0 + i], z, seg)) continue
                    val color = when {
                        i == roadL || i == roadR -> road
                        ahead -> calm
                        else -> heightColor(max(lv[j], lv[r0 + i]), neon, hot)
                    }
                    line(c, seg[0], seg[1], seg[2], seg[3], lerpArgb(color, haze, fog2))
                }
            }
            if (valid[d]) {
                // This row's "curtain" hides the land behind it.
                path.rewind()
                path.moveTo(px[r0], bottom)
                for (i in 0 until tc) path.lineTo(px[r0 + i], py[r0 + i])
                path.lineTo(px[r0 + tc - 1], bottom)
                path.close()
                fill.color = lerpArgb(ground, farGround, fog * fog)
                c.drawPath(path, fill)
            }
            val k = kind[d]
            for (i in 0 until tc - 1) {
                val p = r0 + i
                val x0: Float; val y0: Float; val x1: Float; val y1: Float
                if (valid[d]) {
                    x0 = px[p]; y0 = py[p]; x1 = px[p + 1]; y1 = py[p + 1]
                } else {
                    if (!cam.projectSegment(xs[i], wh[p], z, xs[i + 1], wh[p + 1], z, seg)) continue
                    x0 = seg[0]; y0 = seg[1]; x1 = seg[2]; y1 = seg[3]
                }
                val lvl = max(lv[p], lv[p + 1])
                when (k) {
                    KIND_LIVE -> {
                        // The scanner: a bright, glowing line across the whole land, thicker on kicks.
                        val core = lerpArgb(heightColor(lvl, neon, hot), 0xFFFFFFFF.toInt(), 0.45f + 0.4f * scan)
                        line(c, x0, y0, x1, y1, withAlpha(core, 0.3f + 0.3f * scan), 3f + 2f * scan)
                        line(c, x0, y0, x1, y1, core, if (scan > 0.5f) 2f else 1f)
                    }
                    KIND_CALM -> line(c, x0, y0, x1, y1, lerpArgb(calm, haze, fog))
                    else -> {
                        val color = lerpArgb(heightColor(lvl, neon, hot), haze, fog)
                        if (lvl > 0.45f && fog < 0.8f) line(c, x0, y0, x1, y1, withAlpha(color, (lvl - 0.45f) * (1f - fog)), 3f)
                        line(c, x0, y0, x1, y1, color)
                    }
                }
            }
        }
    }

    /**
     * Fills the draw rows, nearest first: the world rows sliding towards the camera, with the live
     * scanner row slotted in at [SCAN_Z]. A row is printed with the spectrum as it crosses the scanner.
     */
    private fun buildRows(bandCount: Int, bassSlow: Float) {
        var stamped = false
        var d = 0
        var liveDone = false
        for (k in 0 until ROWS) {
            val z = Z0 + k - scroll
            if (!liveDone && z > SCAN_Z) {
                fillRow(d++, SCAN_Z, KIND_LIVE, -1, bassSlow)
                liveDone = true
            }
            val slot = ((nearId + k) % ROWS).toInt()
            if (!printed[slot] && z <= SCAN_Z && z > SCAN_Z - 1.5f) {
                peak.copyInto(printedV, slot * sc)
                printed[slot] = true
                stamped = true
            }
            fillRow(d++, z, if (printed[slot]) KIND_PRINTED else KIND_CALM, slot, bassSlow)
        }
        if (!liveDone) fillRow(d, SCAN_Z, KIND_LIVE, -1, bassSlow)
        if (stamped) live.copyInto(peak)
    }

    private fun fillRow(d: Int, z: Float, k: Int, slot: Int, bassSlow: Float) {
        rz[d] = z
        kind[d] = k
        val id = nearId + scroll + (z - Z0)
        val cap = GridTerrain.cap(z, cam.y)
        val r0 = d * tc
        val breathe = 0.85f + 0.35f * bassSlow
        for (i in 0 until tc) {
            val col = i - SKIRT
            var hgt: Float
            var glow: Float
            if (col < 0 || col >= sc) {
                // Mountains flanking the land.
                val j = if (col < 0) -col - 1 else col - sc
                val sideSeed = if (col < 0) 0f else 5.3f
                val m = GridTerrain.mountain(id, j, sideSeed)
                hgt = unit * SKIRT_H[j] * m * breathe
                glow = 0.15f + 0.35f * m * j / (SKIRT - 1f)
            } else if (!terrain[col]) {
                hgt = 0f; glow = 0f
            } else {
                val v = when (k) {
                    KIND_LIVE -> live[col]
                    KIND_PRINTED -> printedV[slot * sc + col]
                    else -> -1f
                }
                if (v < 0f) {
                    val n = GridTerrain.calm(id, col)
                    hgt = unit * (0.02f + 0.06f * edge[col] * edge[col] + 0.08f * edge[col] * n)
                    glow = 0.1f * n
                } else {
                    hgt = GridTerrain.ridgeHeight(v, edge[col], unit)
                    glow = v
                }
            }
            hgt = min(hgt, cap)
            wh[r0 + i] = hgt
            lv[r0 + i] = glow
        }
        // Rows right in front of the camera are drawn only as clipped lines.
        var ok = z >= GridTerrain.ROW_MIN_Z
        if (ok) for (i in 0 until tc) {
            if (cam.project(xs[i], wh[r0 + i], z, tmp, 0)) {
                px[r0 + i] = tmp[0]; py[r0 + i] = tmp[1]
            } else {
                ok = false; break
            }
        }
        valid[d] = ok
    }

    /** World x of column [i]: the spectrum columns evenly spaced, the mountain columns widening out. */
    private fun columnX(i: Int): Float {
        val col = i - SKIRT
        if (col in 0 until sc) return (col - mid) * colStep
        val j = if (col < 0) -col - 1 else col - sc
        val out = mid * colStep + SKIRT_X[j] * unit
        return if (col < 0) -out else out
    }

    /** Low hills in the palette colour, warming through [hot] to near-white at the peaks. */
    private fun heightColor(t: Float, neon: Int, hot: Int): Int {
        val warm = lerpArgb(neon, hot, smoothstep(0.12f, 0.6f, t))
        return lerpArgb(warm, 0xFFFFFFFF.toInt(), smoothstep(0.55f, 1f, t) * 0.85f)
    }

    private fun drawRange(c: Canvas, w: Int, peaks: FloatArray, height: Float, shift: Float, body: Int, rim: Int) {
        val step = (w + 24f) / (ridges - 1)
        path.rewind()
        path.moveTo(-12f + shift, horizon + 1f)
        for (i in 0 until ridges) path.lineTo(-12f + shift + i * step, floor(horizon - peaks[i] * height))
        path.lineTo(w + 12f + shift, horizon + 1f)
        path.close()
        fill.color = body
        c.drawPath(path, fill)
        for (i in 0 until ridges - 1) {
            line(
                c, -12f + shift + i * step, floor(horizon - peaks[i] * height),
                -12f + shift + (i + 1) * step, floor(horizon - peaks[i + 1] * height), rim,
            )
        }
    }

    private fun drawSun(c: Canvas, w: Int, h: Int, f: VisFrame, haze: Int) {
        val a = f.audio
        val pal = f.palette
        val r = min(w * 0.3f, h * 0.3f) * (1f + a.bass * 0.06f + a.beat * 0.03f)
        val scx = w / 2f
        val scy = horizon - r * 0.4f
        val top = lerpArgb(pal.argb[1], 0xFFFFF0A0.toInt(), 0.45f)
        val bottom = lerpArgb(pal.argb[0], haze, 0.3f)
        var y = floor(scy - r)
        while (y <= min(scy + r, horizon)) {
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

    private companion object {
        /** How far the camera looks down. */
        const val PITCH = 0.05f
        /** Where the nearest row sits (it slides from here to just under the camera). */
        const val Z0 = 1f
        const val ROWS = 34
        /** The scanner's distance ahead: about 27% of the way to the far edge of the land. */
        const val SCAN_Z = GridTerrain.SCAN_Z
        const val ROAD_HALF = 2
        const val SIDE = 12
        const val SKIRT = 4
        val SKIRT_X = floatArrayOf(0.12f, 0.35f, 0.8f, 1.7f)
        val SKIRT_H = floatArrayOf(0.35f, 0.65f, 0.95f, 1.25f)
        const val KIND_CALM = 0
        const val KIND_PRINTED = 1
        const val KIND_LIVE = 2
    }
}

/** Grid Runner's terrain maths, kept free of Android types for unit tests. */
internal object GridTerrain {
    /** The scanner's distance ahead of the camera. */
    const val SCAN_Z = 5f // close enough that the hills it prints are big on screen as they rise
    /** The far edge of the land (nearest row 1 ahead, 34 rows). */
    const val FAR_Z = 34f
    /** Rows nearer than this are drawn only as near-plane-clipped lines, never projected as points. */
    const val ROW_MIN_Z = 0.5f

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
     * units of [unit] (the land's half-width at the scanner): a gentle valley when quiet, bass hills
     * low beside the road and treble given more reach at the edges, since it's rarely as loud.
     */
    fun ridgeHeight(v: Float, e01: Float, unit: Float): Float =
        unit * (0.03f + 0.08f * e01 * e01 + v.coerceIn(0f, 1f).pow(1.3f) * (0.24f + 0.46f * e01))

    /**
     * The tallest anything may stand at distance [z] with the camera at [camY]: hills sink as they
     * come close, so nothing can tilt in front of the near plane.
     */
    fun cap(z: Float, camY: Float): Float = camY * 0.8f + max(z, 0f) * 0.9f

    /** Camera height: low, bobbing gently, dipping on kicks ([punch]) and lifting with the slow bass. */
    fun cameraHeight(time: Float, punch: Float, bassSlow: Float): Float =
        1.15f + 0.06f * sin(time * 1.6f) - 0.07f * punch.coerceIn(0f, 1f) + 0.06f * bassSlow.coerceIn(0f, 1f)

    /** Calm terrain ahead of the scanner: smooth 0..1 noise by row position and column. */
    fun calm(id: Float, col: Int): Float =
        (0.5f + 0.28f * sin(id * 0.41f + col * 1.37f) + 0.22f * sin(id * 0.17f - col * 0.73f + 1.3f)).coerceIn(0f, 1f)

    /** Mountain column [j] (0 inner .. 3 outer) at row position [id]: smooth 0.2..1 peaks and saddles. */
    fun mountain(id: Float, j: Int, seed: Float): Float {
        val s = 0.6f + 0.25f * sin(id * 0.19f + j * 2.1f + seed) + 0.15f * sin(id * 0.47f + j * 0.7f + seed * 1.7f)
        return s.coerceIn(0.2f, 1f)
    }
}
