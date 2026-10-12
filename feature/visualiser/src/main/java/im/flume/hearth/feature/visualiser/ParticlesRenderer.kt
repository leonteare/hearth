package im.flume.hearth.feature.visualiser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Particles: a starfield of up to 400 glowing points flying at the viewer, streaking as they speed
 * up with the music. Beats (tempo-tracked, so none are skipped) blow the field outward; colours come
 * from the cover.
 */
class ParticlesRenderer : VisualiserRenderer() {
    private val max = 400
    private val x = FloatArray(max)
    private val y = FloatArray(max)
    private val z = FloatArray(max)
    private val px = FloatArray(max)
    private val py = FloatArray(max)
    private val hasPrev = BooleanArray(max)
    private val tint = FloatArray(max)
    private val rnd = Random(5)
    private var spin = 0f
    private var background: Brush? = null
    private var backgroundKey = 0L

    init {
        for (i in 0 until max) spawn(i, rnd.nextFloat() * 0.95f + 0.05f)
    }

    private fun spawn(i: Int, depth: Float) {
        var sx: Float
        var sy: Float
        do {
            sx = rnd.nextFloat() * 2.4f - 1.2f
            sy = rnd.nextFloat() * 2.4f - 1.2f
        } while (sx * sx + sy * sy < 0.01f)
        x[i] = sx; y[i] = sy; z[i] = depth
        tint[i] = rnd.nextFloat()
        hasPrev[i] = false
    }

    override fun draw(scope: DrawScope, f: VisFrame) = with(scope) {
        val a = f.audio
        val pal = f.palette
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val fov = min(w, h) * 0.55f
        val dp = density

        val key = (pal.version.toLong() shl 32) xor (w.toLong() * 31 + h.toLong())
        if (background == null || backgroundKey != key) {
            backgroundKey = key
            background = Brush.radialGradient(
                listOf(Color(scaleArgb(pal.argb[1], 0.16f)), Color.Black),
                center = Offset(cx, cy), radius = max(w, h) * 0.7f,
            )
        }
        drawRect(background!!)

        val dt = f.dt
        // a.beat is the beat envelope: real kicks, plus the ones the tempo tracker fills in when a beat
        // is too soft to hear clearly, so every beat bursts the field.
        val speed = 0.08f + a.level * 0.45f + a.bass * 0.35f + a.beat * 1.6f + a.snare * 0.3f
        val push = 1f + a.beat * dt * 1.8f
        spin += dt * (0.05f + a.treble * 0.3f)
        val cs = cos(spin)
        val sn = sin(spin)
        val count = (max * quality.coerceIn(0.5f, 1f)).toInt()
        for (i in 0 until count) {
            z[i] -= speed * dt
            x[i] *= push
            y[i] *= push
            if (z[i] < 0.04f) { spawn(i, 1f); continue }
            val rx = x[i] * cs - y[i] * sn
            val ry = x[i] * sn + y[i] * cs
            val sx = cx + rx / z[i] * fov
            val sy = cy + ry / z[i] * fov
            if (sx < -60f || sx > w + 60f || sy < -60f || sy > h + 60f) { spawn(i, 1f); continue }
            val near = 1f - z[i]
            val fadeIn = min(1f, near / 0.25f)
            val r = (0.7f + 3.2f * near * near) * dp * (1f + a.bass * 0.35f)
            val col = pal.colorAt(tint[i])
            val alpha = fadeIn * (0.55f + 0.45f * near)
            val end = Offset(sx, sy)
            val start = if (hasPrev[i]) Offset(px[i], py[i]) else end
            if (near > 0.55f) {
                drawLine(col, start, end, strokeWidth = r * 4f, cap = StrokeCap.Round, alpha = alpha * 0.14f, blendMode = BlendMode.Plus)
            }
            drawLine(col, start, end, strokeWidth = r * 2f, cap = StrokeCap.Round, alpha = alpha, blendMode = BlendMode.Plus)
            px[i] = sx; py[i] = sy; hasPrev[i] = true
        }
        Unit
    }
}
