package im.flume.hearth.ui.screens.visualiser

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Disc Player: the screen of an imaginary turn-of-the-century CD player. A spinning disc with the
 * cover as its label, a ring of spectrum bars and sweeping lines around it, starbursts on beats,
 * and a chunky pixel readout of the track, time and a segmented level meter.
 */
class DiscPlayerRenderer : RetroRenderer() {
    override val bloom: Float get() = 0.4f
    override val ditherLevels: Int get() = 20

    private var spin = 0f
    private val bg = Paint()
    private var bgKey = 0L
    private val oval = RectF()
    private val ring = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(5f, 3f), 0f) }
    private val label = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val labelMatrix = Matrix()
    private var labelVersion = -1
    private var labelBitmap: Bitmap? = null
    private val text = Paint().apply { isAntiAlias = false }

    private val bursts = 6
    private val bx = FloatArray(bursts)
    private val by = FloatArray(bursts)
    private val bAge = FloatArray(bursts) { 9f }
    private val bRot = FloatArray(bursts)
    private var nextBurst = 0
    private var lastBeat = -1
    private val rnd = Random(21)

    private val meterCols = 16
    private val peaks = FloatArray(meterCols)
    private val peakAge = FloatArray(meterCols)

    private var titleSource = ""
    private var titleText = ""
    private var artistSource = ""
    private var artistText = ""
    private val clock = StringBuilder(8)

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt
        val cx = w / 2f
        val cy = h * 0.43f
        val discR = w * 0.34f

        val key = (pal.version.toLong() shl 32) xor (w.toLong() * 31 + h)
        if (bgKey != key) {
            bgKey = key
            bg.shader = RadialGradient(
                cx, cy, max(w, h) * 0.75f,
                intArrayOf(scaleArgb(pal.argb[0], 0.22f), scaleArgb(pal.argb[2], 0.07f), 0xFF000000.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val playingSpeed = if (f.isPlaying) 1f else 0f
        spin += dt * playingSpeed * (2.2f + a.level * 1.5f)

        // Sweeping lines behind everything.
        val lineCol = withAlpha(pal.argb[2], 0.16f + 0.2f * a.treble)
        for (i in 0 until 32) {
            val ang = -spin * 0.05f + i * (2f * PI.toFloat() / 32)
            val r0 = discR * 1.05f
            val r1 = max(w, h).toFloat()
            line(c, cx + cos(ang) * r0, cy + sin(ang) * r0, cx + cos(ang) * r1, cy + sin(ang) * r1, lineCol)
        }

        // Spectrum ring.
        val bars = 48
        for (i in 0 until bars) {
            val band = a.bands[(i * 52 / bars).coerceAtMost(a.bands.size - 1)]
            val len = 2f + band.pow(1.2f) * w * 0.2f
            val ang = spin * -0.12f + i * (2f * PI.toFloat() / bars) - PI.toFloat() / 2
            val r0 = discR * 1.08f
            val dx = cos(ang)
            val dy = sin(ang)
            line(
                c, snapToGrid(cx + dx * r0), snapToGrid(cy + dy * r0), snapToGrid(cx + dx * (r0 + len)), snapToGrid(cy + dy * (r0 + len)),
                lerpArgb(pal.argbAt(i / bars.toFloat()), 0xFFFFFFFF.toInt(), band * 0.3f), 3f,
            )
        }

        drawDisc(c, cx, cy, discR, f)
        drawBursts(c, w, h, cx, cy, f)
        drawReadout(c, w, h, f)
    }

    private fun drawDisc(c: Canvas, cx: Float, cy: Float, r: Float, f: VisFrame) {
        val pal = f.palette
        val a = f.audio
        val deg = spin * 180f / PI.toFloat()
        fill.color = 0xFF15171E.toInt()
        c.drawCircle(cx, cy, r, fill)
        // Grooves.
        var gr = r * 0.46f
        var n = 0
        while (gr < r * 0.98f) {
            stroke.color = if (n % 2 == 0) 0xFF242833.toInt() else 0xFF1B1E27.toInt()
            stroke.strokeWidth = 1f
            c.drawCircle(cx, cy, gr, stroke)
            gr += 3f; n++
        }
        // Rainbow sheen: two opposite wedges turning with the disc.
        oval.set(cx - r, cy - r, cx + r, cy + r)
        fill.color = withAlpha(pal.argb[1], 0.28f + 0.2f * a.treble)
        c.drawArc(oval, deg * 0.35f, 28f, true, fill)
        c.drawArc(oval, deg * 0.35f + 180f, 28f, true, fill)
        fill.color = withAlpha(pal.argb[3], 0.22f + 0.2f * a.mid)
        c.drawArc(oval, deg * 0.35f + 28f, 18f, true, fill)
        c.drawArc(oval, deg * 0.35f + 208f, 18f, true, fill)

        // Dashed rim, spinning.
        ring.color = lerpArgb(pal.argb[0], 0xFFFFFFFF.toInt(), 0.2f + 0.5f * a.beat)
        ring.strokeWidth = 2f
        c.save()
        c.rotate(deg, cx, cy)
        c.drawCircle(cx, cy, r - 1f, ring)
        c.restore()

        // Label: the cover, pixelated, spinning.
        val labelR = r * 0.42f
        val bmp = f.coverBitmap
        if (labelVersion != f.coverVersion) {
            labelVersion = f.coverVersion
            labelBitmap = bmp
            label.shader = bmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
        val lb = labelBitmap
        if (lb != null && label.shader != null) {
            val side = min(lb.width, lb.height).toFloat()
            val s = labelR * 2f / side
            labelMatrix.reset()
            labelMatrix.setTranslate(-(lb.width - side) / 2f, -(lb.height - side) / 2f)
            labelMatrix.postScale(s, s)
            labelMatrix.postTranslate(cx - labelR, cy - labelR)
            labelMatrix.postRotate(deg, cx, cy)
            label.shader.setLocalMatrix(labelMatrix)
            c.drawCircle(cx, cy, labelR, label)
        } else {
            fill.color = pal.argb[0]
            c.drawCircle(cx, cy, labelR, fill)
            fill.color = pal.argb[2]
            c.drawArc(cx - labelR, cy - labelR, cx + labelR, cy + labelR, deg, 90f, true, fill)
            c.drawArc(cx - labelR, cy - labelR, cx + labelR, cy + labelR, deg + 180f, 90f, true, fill)
        }
        fill.color = 0xFF000000.toInt()
        c.drawCircle(cx, cy, labelR * 0.14f, fill)
        stroke.color = 0xFF5A5F6E.toInt()
        c.drawCircle(cx, cy, labelR * 0.22f, stroke)
    }

    private fun drawBursts(c: Canvas, w: Int, h: Int, cx: Float, cy: Float, f: VisFrame) {
        val a = f.audio
        if (a.beatCount != lastBeat) {
            if (lastBeat >= 0) {
                val k = nextBurst
                nextBurst = (nextBurst + 1) % bursts
                val ang = rnd.nextFloat() * 2f * PI.toFloat()
                val d = w * (0.42f + rnd.nextFloat() * 0.12f)
                bx[k] = cx + cos(ang) * d
                by[k] = cy + sin(ang) * d * 1.2f
                bAge[k] = 0f
                bRot[k] = rnd.nextFloat()
            }
            lastBeat = a.beatCount
        }
        for (k in 0 until bursts) {
            if (bAge[k] > 0.7f) continue
            bAge[k] += f.dt
            val t = bAge[k] / 0.7f
            val len = w * 0.13f * (1f - (1f - t).pow(3))
            val alpha = (1f - t).coerceIn(0f, 1f)
            val col = withAlpha(lerpArgb(f.palette.argb[(k % 3) + 1], 0xFFFFFFFF.toInt(), 0.5f), alpha)
            for (i in 0 until 10) {
                val ang = bRot[k] * 6.28f + i * (2f * PI.toFloat() / 10)
                val r0 = len * 0.25f
                val r1 = if (i % 2 == 0) len else len * 0.6f
                line(c, snapToGrid(bx[k] + cos(ang) * r0), snapToGrid(by[k] + sin(ang) * r0), snapToGrid(bx[k] + cos(ang) * r1), snapToGrid(by[k] + sin(ang) * r1), col)
            }
            fill.color = withAlpha(0xFFFFFFFF.toInt(), alpha)
            c.drawRect(bx[k] - 1f, by[k] - 1f, bx[k] + 2f, by[k] + 2f, fill)
        }
    }

    private fun drawReadout(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val pal = f.palette
        val a = f.audio
        if (f.title != titleSource) { titleSource = f.title; titleText = PixelFont.sanitize(f.title) }
        if (f.artist != artistSource) { artistSource = f.artist; artistText = PixelFont.sanitize(f.artist) }
        val bright = lerpArgb(pal.argb[0], 0xFFFFFFFF.toInt(), 0.55f)
        val dim = scaleArgb(bright, 0.55f)

        // Status line.
        text.color = bright
        PixelFont.draw(c, if (f.isPlaying) "> PLAY" else "| PAUSE", 8f, 10f, 2, text)
        text.color = dim
        val trackLabel = "DISC 1"
        PixelFont.draw(c, trackLabel, w - 8f - PixelFont.width(trackLabel.length, 2), 10f, 2, text)

        // Title (scrolling when long) and artist.
        marquee(c, titleText, h * 0.74f, 2, w, bright, f.time)
        marquee(c, artistText, h * 0.74f + 16f, 1, w, dim, f.time)

        // Big clock.
        clock.setLength(0)
        appendTime(clock, f.positionMs)
        val big = 4
        val bw = PixelFont.width(clock.length, big)
        text.color = bright
        PixelFont.draw(c, clock, (w - bw) / 2f, h * 0.80f, big, text)
        clock.setLength(0)
        clock.append('/'); clock.append(' ')
        appendTime(clock, f.durationMs)
        text.color = dim
        PixelFont.draw(c, clock, (w - PixelFont.width(clock.length, 1)) / 2f, h * 0.80f + 24f, 1, text)

        // Segmented level meter with peak hold.
        val segs = 10
        val colW = (w - 32f) / meterCols
        val segH = 3f
        val baseY = h - 14f
        for (col in 0 until meterCols) {
            var v = 0f
            for (b in 0 until 3) v = max(v, a.bands[(col * 3 + b).coerceAtMost(a.bands.size - 1)])
            val lit = (v * segs).toInt()
            if (v >= peaks[col]) { peaks[col] = v; peakAge[col] = 0f } else {
                peakAge[col] += f.dt
                if (peakAge[col] > 0.5f) peaks[col] = max(0f, peaks[col] - f.dt * 0.6f)
            }
            val x = 16f + col * colW
            for (s in 0 until segs) {
                val on = s < lit
                val colr = when {
                    s >= segs - 2 -> pal.argb[1]
                    s >= segs - 5 -> pal.argb[3]
                    else -> pal.argb[0]
                }
                fill.color = if (on) colr else withAlpha(scaleArgb(colr, 0.3f), 0.5f)
                val y = baseY - s * (segH + 1f)
                c.drawRect(x, y - segH, x + colW - 2f, y, fill)
            }
            val ps = (peaks[col] * segs).toInt().coerceIn(0, segs - 1)
            fill.color = 0xFFFFFFFF.toInt()
            val py = baseY - ps * (segH + 1f)
            c.drawRect(x, py - segH, x + colW - 2f, py - segH + 1f, fill)
        }
    }

    private fun marquee(c: Canvas, s: String, y: Float, scale: Int, w: Int, color: Int, time: Float) {
        if (s.isEmpty()) return
        text.color = color
        val tw = PixelFont.width(s.length, scale)
        val room = w - 16
        if (tw <= room) {
            PixelFont.draw(c, s, (w - tw) / 2f, y, scale, text)
            return
        }
        val gap = 24f * scale
        val period = tw + gap
        val off = (time * 18f * scale) % period
        c.save()
        c.clipRect(8f, y - 1f, w - 8f, y + PixelFont.HEIGHT * scale + 1f)
        val x = snapToGrid(8f - off)
        PixelFont.draw(c, s, x, y, scale, text)
        PixelFont.draw(c, s, x + period, y, scale, text)
        c.restore()
    }

    private fun appendTime(sb: StringBuilder, ms: Long) {
        val total = (ms / 1000).coerceIn(0, 99 * 60 + 59)
        val m = (total / 60).toInt()
        val s = (total % 60).toInt()
        sb.append('0' + m / 10); sb.append('0' + m % 10); sb.append(':')
        sb.append('0' + s / 10); sb.append('0' + s % 10)
    }
}
