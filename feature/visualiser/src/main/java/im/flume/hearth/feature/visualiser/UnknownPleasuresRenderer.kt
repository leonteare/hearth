// Unknown Pleasures: stacked ridge lines, drawn one new line per beat.
package im.flume.hearth.feature.visualiser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Unknown Pleasures: a tribute to the stacked pulsar plot. Fifty-odd white ridge lines on black,
 * receding slightly upward and narrowing with distance. The front line is the music right now (the
 * spectrum, bass in the middle, mirrored outwards, with a little noise for grain); every sixteenth note
 * (or tenth of a second without a steady tempo) it's copied into the history, which glides up and
 * back. Each line is filled black underneath so nearer ridges hide the ones behind. Kicks lift the
 * front line.
 */
class UnknownPleasuresRenderer : VisualiserRenderer() {
    private val lines = 56
    private val points = 96
    private val history = RidgeHistory(lines, points)
    private val live = FloatArray(points)
    private val xs = FloatArray(points)
    private val strokePaths = Array(lines + 1) { Path() }
    private val fillPaths = Array(lines + 1) { Path() }

    private var strokes: Array<Stroke> = emptyArray()
    private var strokesDensity = -1f

    private var drift = 0f
    private var since = 0f
    private var lift = 0f

    init {
        for (j in 0 until points) xs[j] = j / (points - 1f) * 2f - 1f
        // Start with the full stack of flat lines, so the plot is there before the music arrives.
        repeat(lines) { history.push(live) }
    }

    override fun draw(scope: DrawScope, f: VisFrame) = with(scope) {
        val a = f.audio
        val dt = f.dt.coerceIn(0f, 0.1f)
        val w = size.width
        val h = size.height
        drawRect(Color.Black)
        if (w <= 0f || h <= 0f) return@with

        // ---- the live line: spectrum mirrored round the centre, shaped by the ridge envelope
        drift += dt * (1.6f + a.level * 1.6f)
        val bands = a.bands
        val usable = bands.size * 0.7f
        val follow = 1f - exp(-dt / 0.035f)
        for (j in 0 until points) {
            val x = xs[j]
            val env = ridgeEnvelope(x)
            val u = (kotlin.math.abs(x) / RIDGE_EDGE).coerceAtMost(1f)
            val bp = u * (usable - 1f)
            val bi = bp.toInt().coerceIn(0, bands.size - 2)
            val sp = bands[bi] + (bands[bi + 1] - bands[bi]) * (bp - bi)
            val n1 = valueNoise(j * 0.42f + drift, 11)
            val n2 = valueNoise(j * 1.15f - drift * 1.4f, 23)
            // Sharp, uneven peaks: the spectrum carries the height, noise breaks it into ridges.
            val peak = sp * (0.3f + 0.95f * n1 * n1) + a.level * 0.12f * n2
            val target = max(0f, env * peak + 0.014f * (n2 - 0.3f))
            live[j] += (target - live[j]) * follow
        }

        // ---- scroll: a new line every interval; frac glides the history between pushes
        val interval = ridgeInterval(a.bpm, a.tempoConfidence)
        since += dt
        if (since >= interval) {
            history.push(live)
            since = if (since > interval * 3f) 0f else since - interval
        }
        val frac = (since / interval).coerceIn(0f, 1f)
        lift += (a.kick - lift) * (1f - exp(-dt / if (a.kick > lift) 0.02f else 0.12f))

        // ---- layout: a plot roughly 4:5, centred, a touch above the middle
        val plotW = min(w * 0.86f, h * 0.62f)
        val plotH = min(h * 0.74f, plotW * 1.25f)
        val cx = w / 2f
        val front = h / 2f + plotH * 0.5f
        val peakMax = plotH * 0.15f
        val back = h / 2f - plotH * 0.5f + peakMax * 0.8f
        val dp = density
        if (strokesDensity != dp) {
            strokesDensity = dp
            strokes = Array(lines + 1) {
                val s = scaleAt(it / (lines - 1f))
                Stroke(width = max(1f, 1.35f * dp * s), cap = StrokeCap.Round, join = StrokeJoin.Round)
            }
        }
        val stride = if (quality < 0.75f) 2 else 1
        val pal = f.palette
        val tint = lerpArgb(0xFFFFFFFF.toInt(), pal.argbAt(f.time * 0.015f), 0.12f)
        val spacing0 = (front - back) / (lines - 1f) * 1.4f

        // ---- back to front: each line's black fill hides what's behind it
        for (age in history.size - 1 downTo 0) {
            val p = age + frac
            if (p > lines - 1f) continue
            val fade = (lines - 1f - p).coerceIn(0f, 1f)
            ridge(age, p, fade, 0f, cx, front, back, plotW, peakMax, stride, tint)
        }
        ridge(-1, 0f, 1f, lift * spacing0 * 0.9f, cx, front, back, plotW, peakMax, stride, tint)
        Unit
    }

    /** Lines shrink a little with distance (0 = front, 1 = back). */
    private fun scaleAt(t: Float) = 1f - 0.2f * t

    /** Baselines bunch up towards the back: spacing at the front is about twice that at the back. */
    private fun depthAt(t: Float): Float {
        val k = 0.6f
        return t * (1f + k) / (1f + k * t)
    }

    /** Draws one line: [age] -1 is the live line, otherwise a history line, at depth position [p]. */
    private fun DrawScope.ridge(
        age: Int, p: Float, alpha: Float, raise: Float,
        cx: Float, front: Float, back: Float, plotW: Float, peakMax: Float, stride: Int, tint: Int,
    ) {
        val t = p / (lines - 1f)
        val s = scaleAt(t)
        val base = front - (front - back) * depthAt(t) - raise
        val half = plotW * 0.5f * s
        val amp = peakMax * s * (1f + 0.25f * raise / (peakMax + 1f))
        val slot = if (age < 0) lines else age % lines
        val sp = strokePaths[slot]
        val fp = fillPaths[slot]
        sp.reset(); fp.reset()
        var j = 0
        var lastX = cx - half
        while (true) {
            val v = if (age < 0) live[j] else history.get(age, j)
            val x = cx + xs[j] * half
            val y = base - v * amp
            if (j == 0) { sp.moveTo(x, y); fp.moveTo(x, y) } else { sp.lineTo(x, y); fp.lineTo(x, y) }
            lastX = x
            if (j == points - 1) break
            j = min(j + stride, points - 1)
        }
        val pad = 2f * density
        fp.lineTo(lastX, base + pad)
        fp.lineTo(cx - half, base + pad)
        fp.close()
        drawPath(fp, Color.Black, style = Fill)
        val bright = (0.95f - 0.35f * t) * alpha
        drawPath(sp, Color(tint), alpha = bright, style = strokes[min(p.toInt(), lines)])
    }
}
