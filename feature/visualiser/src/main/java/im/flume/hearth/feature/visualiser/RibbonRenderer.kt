package im.flume.hearth.feature.visualiser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ribbon: four glowing silk ribbons woven from the waveform and the spectrum, each a little lazier
 * than the one in front, mirrored top to bottom like a reflection on water. Kicks bow them apart,
 * snares ripple them; with no music they lie almost still.
 */
class RibbonRenderer : VisualiserRenderer() {
    private val ribbons = 4
    private val points = 64
    private val ys = FloatArray(ribbons * points)
    private val path = Path()
    private val brushes = arrayOfNulls<Brush>(ribbons)
    private var brushKey = 0L
    private var glow = Stroke(1f)
    private var mid = Stroke(1f)
    private var core = Stroke(1f)
    private var strokesDensity = 0f

    override fun draw(scope: DrawScope, f: VisFrame) = with(scope) {
        val a = f.audio
        val pal = f.palette
        val w = size.width
        val h = size.height
        val cy = h / 2

        val key = (pal.version.toLong() shl 32) xor w.toLong()
        if (brushKey != key || brushes[0] == null) {
            brushKey = key
            for (r in 0 until ribbons) {
                brushes[r] = Brush.horizontalGradient(
                    listOf(pal.color(r), pal.color(r + 1), pal.color(r + 2), pal.color(r)),
                    startX = 0f, endX = w,
                )
            }
        }
        if (strokesDensity != density) {
            strokesDensity = density
            glow = Stroke(width = 16f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            mid = Stroke(width = 5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            core = Stroke(width = 1.8f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        }

        drawRect(Color.Black)
        // Horizon haze along the mirror line.
        drawRect(
            pal.color(0), topLeft = Offset(0f, cy - h * 0.002f), size = Size(w, h * 0.004f),
            alpha = 0.15f + 0.3f * a.beat, blendMode = BlendMode.Plus,
        )

        val amp = h * 0.2f
        val pi = PI.toFloat()
        val t = f.time
        for (r in ribbons - 1 downTo 0) {
            val k = 1f - exp(-f.dt / (0.04f + r * 0.07f))
            val base = r * points
            // Alternate ribbons bow opposite ways, so a kick opens them like a breath.
            val side = if (r % 2 == 0) 1f else -1f
            for (i in 0 until points) {
                val u = i / (points - 1f)
                val wave = a.waveform[(u * (a.waveform.size - 1)).toInt()]
                // The spectrum mirrored about the middle: bass at the centre, treble at the ends.
                val band = a.bands[((kotlin.math.abs(u - 0.5f) * 2f) * 44f).toInt().coerceAtMost(a.bands.size - 1)]
                // Only a faint breathing of their own (a fifth of what it was), so in silence they rest.
                val idle = 0.07f * sin(u * pi * (1.2f + r * 0.55f) + t * (0.25f + r * 0.08f) + r * 1.7f)
                // Everything else is the music: the synced waveform, the spectrum's shape, a bow on
                // each kick and a ripple on each snare.
                val bow = sin(u * pi)
                val audio = wave * (0.42f + 0.25f * a.kick) * (1f - r * 0.15f) +
                    side * band * (0.35f + 0.2f * a.bassSlow) +
                    side * bow * a.kick * 0.45f +
                    a.snare * 0.22f * sin(u * pi * (5f + r) + r * 1.1f)
                // sin(π) is a hair below 0 in floats, and a negative to a fractional power is NaN, which hides the whole path.
                val target = (idle + audio) * sin(u * pi).coerceAtLeast(0f).pow(1.3f)
                ys[base + i] += (target - ys[base + i]) * k
            }
            path.rewind()
            val scale = amp * (1f - r * 0.13f)
            var prevX = 0f
            var prevY = cy - ys[base] * scale
            path.moveTo(prevX, prevY)
            for (i in 1 until points) {
                val x = w * i / (points - 1f)
                val y = cy - ys[base + i] * scale
                path.quadraticTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
                prevX = x; prevY = y
            }
            path.lineTo(prevX, prevY)

            val brush = brushes[r]!!
            val depthFade = 1f - r * 0.18f
            val lift = 0.75f + 0.5f * a.level
            drawPath(path, brush, alpha = 0.14f * depthFade * lift, style = glow, blendMode = BlendMode.Plus)
            drawPath(path, brush, alpha = 0.35f * depthFade * lift, style = mid, blendMode = BlendMode.Plus)
            drawPath(path, brush, alpha = 0.95f * depthFade, style = core, blendMode = BlendMode.Plus)
            withTransform({ scale(1f, -1f, pivot = Offset(w / 2, cy)) }) {
                drawPath(path, brush, alpha = 0.09f * depthFade * lift, style = glow, blendMode = BlendMode.Plus)
                drawPath(path, brush, alpha = 0.40f * depthFade, style = core, blendMode = BlendMode.Plus)
            }
        }
        Unit
    }
}
