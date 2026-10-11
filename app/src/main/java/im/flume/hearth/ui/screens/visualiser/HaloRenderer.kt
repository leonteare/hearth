package im.flume.hearth.ui.screens.visualiser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Halo: the album cover in a circle, breathing with the bass and kicking on beats, ringed by a
 * mirrored circular spectrum of glowing rounded bars that slowly turns. Faint motes drift outward.
 */
class HaloRenderer : VisualiserRenderer() {
    private val clip = Path()
    private val motes = 90
    private val moteAngle = FloatArray(motes)
    private val motePhase = FloatArray(motes)
    private val moteSpeed = FloatArray(motes)
    private val moteSize = FloatArray(motes)
    private var spin = 0f
    private var background: Brush? = null
    private var backgroundKey = 0L
    private var ringStroke = Stroke(1f)

    init {
        val rnd = Random(11)
        for (i in 0 until motes) {
            moteAngle[i] = rnd.nextFloat() * 2f * PI.toFloat()
            motePhase[i] = rnd.nextFloat()
            moteSpeed[i] = 0.04f + rnd.nextFloat() * 0.08f
            moteSize[i] = 0.6f + rnd.nextFloat() * 1.6f
        }
    }

    override fun draw(scope: DrawScope, f: VisFrame) = with(scope) {
        val a = f.audio
        val pal = f.palette
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val minDim = min(w, h)
        val maxDim = max(w, h)
        val dp = density

        val key = (pal.version.toLong() shl 32) xor (w.toLong() * 31 + h.toLong())
        if (background == null || backgroundKey != key) {
            backgroundKey = key
            background = Brush.radialGradient(
                listOf(Color(scaleArgb(pal.argb[0], 0.28f)), Color(scaleArgb(pal.argb[2], 0.08f)), Color.Black),
                center = Offset(cx, cy), radius = maxDim * 0.65f,
            )
            ringStroke = Stroke(width = 1.5f * dp)
        }
        drawRect(background!!)

        spin += f.dt * (0.12f + a.mid * 0.25f)
        val radius = minDim * 0.24f * (1f + a.bass * 0.05f + a.beat * 0.045f)

        // Motes drifting out from the ring.
        for (i in 0 until motes) {
            val p = (motePhase[i] + f.time * moteSpeed[i] * (1f + a.level)) % 1f
            val d = radius * 1.25f + p * maxDim * 0.55f
            val ang = moteAngle[i] + spin * 0.3f
            val alpha = (1f - p) * (0.15f + 0.55f * a.level)
            drawCircle(pal.colorAt(i / motes.toFloat()), moteSize[i] * dp * (1f + a.treble), Offset(cx + cos(ang) * d, cy + sin(ang) * d), alpha = alpha)
        }

        // Glow behind the cover.
        drawCircle(pal.color(0), radius * (1.25f + a.bass * 0.15f), Offset(cx, cy), alpha = 0.10f + 0.25f * a.beat + 0.15f * a.bass, blendMode = BlendMode.Plus)

        // Mirrored circular spectrum.
        val bars = if (quality >= 1f) 120 else 72
        val half = bars / 2
        val barWidth = (2f * PI.toFloat() * radius * 1.1f / bars) * 0.55f
        val inner = radius * 1.1f
        for (i in 0 until bars) {
            val k = if (i < half) i else bars - 1 - i
            // Skip the topmost bands, which are mostly empty: 0..52 of 64.
            val band = a.bands[(k * 52 / half).coerceAtMost(a.bands.size - 1)]
            val v = band.pow(1.4f)
            val len = minDim * (0.012f + 0.2f * v)
            val ang = spin + i * (2f * PI.toFloat() / bars) - PI.toFloat() / 2f
            val dx = cos(ang)
            val dy = sin(ang)
            val start = Offset(cx + dx * inner, cy + dy * inner)
            val end = Offset(cx + dx * (inner + len), cy + dy * (inner + len))
            val col = pal.colorAt(k / half.toFloat() * 0.75f)
            drawLine(col, start, end, strokeWidth = barWidth * 2.8f, cap = StrokeCap.Round, alpha = 0.10f + 0.25f * v, blendMode = BlendMode.Plus)
            drawLine(col, start, end, strokeWidth = barWidth, cap = StrokeCap.Round, alpha = 0.75f + 0.25f * v)
        }

        drawCircle(Color.White, radius * 1.035f, Offset(cx, cy), alpha = 0.12f + 0.5f * a.beat, style = ringStroke)

        // The cover, cropped to a circle.
        val cover = f.cover
        if (cover != null) {
            clip.rewind()
            clip.addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
            val side = min(cover.width, cover.height)
            clipPath(clip) {
                drawImage(
                    cover,
                    srcOffset = IntOffset((cover.width - side) / 2, (cover.height - side) / 2),
                    srcSize = IntSize(side, side),
                    dstOffset = IntOffset((cx - radius).toInt(), (cy - radius).toInt()),
                    dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        } else {
            drawCircle(pal.color(2), radius, Offset(cx, cy))
            drawCircle(pal.color(0), radius * 0.7f, Offset(cx - radius * 0.15f, cy - radius * 0.15f), alpha = 0.8f)
        }
        // A light sheen that flashes on beats.
        drawCircle(Color.White, radius, Offset(cx, cy), alpha = 0.10f * a.beat)
        Unit
    }
}
