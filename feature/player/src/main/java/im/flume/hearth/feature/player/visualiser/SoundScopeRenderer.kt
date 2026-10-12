package im.flume.hearth.feature.player.visualiser

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.max
import kotlin.math.min

/**
 * Sound Scope: the original PlayStation CD player's XY scope. A thin violet line on black traces the
 * left channel against the right: bass, which is mostly mono, stretches it into a long diagonal from
 * bottom left to top right, wide stereo scribbles it everywhere, and silence leaves a dot. The last
 * few frames linger as a fading, bluer afterglow, like phosphor.
 *
 * Only the newest frame's path is rebuilt; the afterglow reuses the paths of earlier frames as they
 * were, so a frame costs one path build and a handful of strokes.
 */
class SoundScopeRenderer : VisualiserRenderer() {
    override val usesStereo: Boolean get() = true

    private val trace = ScopeTrace()
    private val paths = Array(FRAMES) { Path() }
    private val drawn = BooleanArray(FRAMES)
    private var head = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        // Additive, so where the line crosses itself it glows brighter, as on a CRT.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private var vignette: Brush? = null
    private var vignetteSize = Size.Zero

    override fun draw(scope: DrawScope, f: VisFrame) = with(scope) {
        val w = size.width
        val h = size.height
        drawRect(Background)

        head = (head + 1) % FRAMES
        val path = paths[head]
        path.rewind()
        val st = f.stereo
        drawn[head] = false
        if (st.valid) {
            trace.build(st.mid, st.side, f.dt)
            val n = trace.count
            if (n > 0) {
                val cx = w / 2f
                val cy = h / 2f
                val r = min(w, h) / 2f
                path.moveTo(cx + trace.x[0] * r, cy - trace.y[0] * r)
                for (i in 1 until n) path.lineTo(cx + trace.x[i] * r, cy - trace.y[i] * r)
                // A lone point (silence) still needs a segment for its round cap to show as a dot.
                if (n == 1) path.lineTo(cx + trace.x[0] * r, cy - trace.y[0] * r)
                drawn[head] = true
            }
        }

        val canvas = drawContext.canvas.nativeCanvas
        val core = CORE_DP * density
        // Afterglow: oldest first, each fainter, thinner-feeling and bluer than the one after it.
        for (age in FRAMES - 1 downTo 1) {
            val i = (head - age + FRAMES) % FRAMES
            if (!drawn[i]) continue
            val t = age / (FRAMES - 1f)
            paint.color = withAlpha(lerpArgb(CORE_COLOR, TRAIL_COLOR, t), AFTERGLOW_ALPHA * (1f - t) + 0.05f)
            paint.strokeWidth = core
            canvas.drawPath(paths[i], paint)
        }
        if (drawn[head]) {
            // A soft halo (skipped when frames run late), then the sharp line on top.
            if (quality >= 1f) {
                paint.color = withAlpha(GLOW_COLOR, 0.10f)
                paint.strokeWidth = core * 5f
                canvas.drawPath(path, paint)
                paint.color = withAlpha(GLOW_COLOR, 0.22f)
                paint.strokeWidth = core * 2.4f
                canvas.drawPath(path, paint)
            }
            paint.color = CORE_COLOR
            paint.strokeWidth = core
            canvas.drawPath(path, paint)
        }

        drawRect(vignetteBrush(size), size = size)
    }

    private fun vignetteBrush(size: Size): Brush {
        vignette?.takeIf { vignetteSize == size }?.let { return it }
        val brush = Brush.radialGradient(
            0.6f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.45f),
            center = Offset(size.width / 2, size.height / 2),
            radius = max(size.width, size.height) * 0.65f,
        )
        vignette = brush
        vignetteSize = size
        return brush
    }

    override fun release() {
        paths.forEach { it.rewind() }
        drawn.fill(false)
        trace.reset()
    }

    private companion object {
        /** The current frame plus four of afterglow. */
        const val FRAMES = 5
        const val CORE_DP = 1.6f
        const val AFTERGLOW_ALPHA = 0.5f
        val Background = Color(0xFF030208)
        val CORE_COLOR = 0xFFA99CFF.toInt()
        val GLOW_COLOR = 0xFF7A66FF.toInt()
        val TRAIL_COLOR = 0xFF4F66FF.toInt()
    }
}
