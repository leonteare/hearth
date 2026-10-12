package im.flume.hearth.ui.screens.visualiser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sphere: a glowing ball bristling with a few hundred spikes, slowly turning in 3D. Every spike
 * belongs to a frequency (bass round the bottom, treble towards the top), so the spikes' peaks and
 * troughs draw the whole spectrum across the sphere. The ball swells on kicks; snares glint the tips.
 */
class SphereRenderer : VisualiserRenderer() {
    private val count = 420

    // Unit directions spread evenly over the sphere (Fibonacci lattice), and each spike's band.
    private val dx = FloatArray(count)
    private val dy = FloatArray(count)
    private val dz = FloatArray(count)
    private val band = IntArray(count)
    private val bandFrac = FloatArray(count)

    // Smoothed spike lengths (0..1) and per-frame projected points, reused every frame.
    private val len = FloatArray(count)
    private val bx = FloatArray(count)
    private val by = FloatArray(count)
    private val tx = FloatArray(count)
    private val ty = FloatArray(count)
    private val depth = FloatArray(count)

    private var yaw = 0f
    private var pulse = 0f
    private var shimmer = 0f
    private var bandsKnown = -1

    private var background: Brush? = null
    private var backgroundKey = 0L

    init {
        val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
        for (i in 0 until count) {
            val y = 1f - 2f * (i + 0.5f) / count
            val r = sqrt(1f - y * y)
            val theta = golden * i
            dx[i] = cos(theta) * r
            dy[i] = y
            dz[i] = sin(theta) * r
        }
    }

    /** Bass at the bottom of the sphere, treble at the top, with a little spread so neighbours differ. */
    private fun assignBands(bandCount: Int) {
        bandsKnown = bandCount
        for (i in 0 until count) {
            val up = (1f - dy[i]) * 0.5f // 0 at the top, 1 at the bottom
            val wobble = sin(i * 2.399f) * 1.5f
            val b = ((1f - up) * (bandCount - 1) + wobble).toInt().coerceIn(0, bandCount - 1)
            band[i] = b
            bandFrac[i] = b / (bandCount - 1f).coerceAtLeast(1f)
        }
    }

    override fun draw(scope: DrawScope, f: VisFrame) = with(scope) {
        val a = f.audio
        val pal = f.palette
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val dt = f.dt
        if (bandsKnown != a.bands.size) assignBands(a.bands.size)

        // Ball swells on kicks and with the slow bass, then settles back.
        val pulseTarget = a.kick * 0.9f + a.bassSlow * 0.35f
        pulse += (pulseTarget - pulse) * (1f - exp(-dt / if (pulseTarget > pulse) 0.03f else 0.22f))
        shimmer += (a.snare - shimmer) * (1f - exp(-dt / if (a.snare > shimmer) 0.02f else 0.18f))
        yaw += dt * (0.18f + a.level * 0.35f)
        val tilt = 0.42f + 0.06f * sin(f.time * 0.21f)

        val base = min(w, h) * 0.23f
        val radius = base * (1f + 0.13f * pulse)
        val maxSpike = base * 1.05f
        val camera = base * 4.2f
        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosT = cos(tilt); val sinT = sin(tilt)

        // Background: near-black with a faint glow of the cover's first colour behind the ball.
        val key = (pal.version.toLong() shl 32) xor (w.toLong() * 31 + h.toLong())
        if (backgroundKey != key || background == null) {
            backgroundKey = key
            background = Brush.radialGradient(
                listOf(pal.color(0).copy(alpha = 0.22f), pal.color(2).copy(alpha = 0.06f), Color.Black),
                center = Offset(cx, cy), radius = min(w, h) * 0.85f,
            )
        }
        drawRect(Color.Black)
        drawRect(background!!)

        // Rotate, extend each spike by its band, project.
        for (i in 0 until count) {
            val target = a.bands[band[i]]
            len[i] += (target - len[i]) * (1f - exp(-dt / if (target > len[i]) 0.025f else 0.16f))
            // Yaw around the vertical axis, then tilt towards the viewer.
            val x1 = dx[i] * cosY + dz[i] * sinY
            val z1 = -dx[i] * sinY + dz[i] * cosY
            val y2 = dy[i] * cosT - z1 * sinT
            val z2 = dy[i] * sinT + z1 * cosT
            depth[i] = z2 // +1 = facing us, -1 = far side
            val spike = radius + maxSpike * (0.09f + len[i])
            val sb = camera / (camera - z2 * radius)
            val st = camera / (camera - z2 * spike)
            bx[i] = cx + x1 * radius * sb
            by[i] = cy - y2 * radius * sb
            tx[i] = cx + x1 * spike * st
            ty[i] = cy - y2 * spike * st
        }

        val stroke = 1.6f * density
        // Far side first, dim, so the ball reads as solid and 3D.
        for (i in 0 until count) if (depth[i] < 0f) spike(i, pal, stroke, 0.18f + 0.12f * (1f + depth[i]))

        // The ball: a dark core with a coloured rim that brightens as it swells.
        val ballR = radius * (camera / (camera - radius * 0.2f))
        drawCircle(
            Brush.radialGradient(
                listOf(Color.Black, pal.color(1).copy(alpha = 0.35f + 0.35f * pulse), pal.color(0).copy(alpha = 0f)),
                center = Offset(cx, cy), radius = ballR * 1.18f,
            ),
            radius = ballR * 1.18f, center = Offset(cx, cy),
        )
        drawCircle(Color.Black.copy(alpha = 0.85f), radius = ballR * 0.92f, center = Offset(cx, cy))
        drawCircle(
            pal.color(0), radius = ballR * 0.92f, center = Offset(cx, cy),
            alpha = 0.25f + 0.45f * pulse, style = Stroke(width = 1.5f * density),
            blendMode = BlendMode.Plus,
        )

        // Near side on top, bright, with glinting tips.
        for (i in 0 until count) if (depth[i] >= 0f) spike(i, pal, stroke, 0.55f + 0.45f * depth[i])
        Unit
    }

    private fun DrawScope.spike(i: Int, pal: VisPalette, stroke: Float, light: Float) {
        val c = pal.colorAt(bandFrac[i] * 0.75f)
        val strength = (0.35f + len[i] * 0.9f).coerceAtMost(1f)
        drawLine(
            c, Offset(bx[i], by[i]), Offset(tx[i], ty[i]),
            strokeWidth = stroke, cap = StrokeCap.Round, alpha = (light * strength).coerceIn(0f, 1f), blendMode = BlendMode.Plus,
        )
        // Tips of the taller spikes glint, more so on snares.
        if (len[i] > 0.35f) {
            val glint = ((len[i] - 0.35f) * 1.4f + shimmer * 0.6f).coerceIn(0f, 1f) * light
            drawCircle(Color.White, radius = stroke * (1.2f + shimmer), center = Offset(tx[i], ty[i]), alpha = glint * 0.7f, blendMode = BlendMode.Plus)
        }
    }
}
