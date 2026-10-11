package im.flume.hearth.ui.screens.visualiser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Magnetosphere: a couple of thousand motes of light flowing round four invisible magnets that
 * wander the screen. Each magnet belongs to a slice of the spectrum (bass, low-mid, mid, treble):
 * when its slice is loud it pulls and swirls harder, so particles gather and brighten there. Kicks
 * send a shockwave out from the bass magnet; snares make a scatter of motes sparkle.
 *
 * Cheap by design: the particles are drawn as a few batched point lists into a small (360 px wide)
 * bitmap that fades a little each frame for trails, then scaled up to the screen with bilinear
 * filtering, which also gives the soft nebula look.
 */
class MagnetosphereRenderer : VisualiserRenderer() {
    private val maxCount = 2200
    private val field = MagnetField(maxCount, magnets = MAGNETS, seed = 11)
    private val energy = FloatArray(MAGNETS)

    // One point list per magnet × (slow, fast), plus the sparkles.
    private val groups = MAGNETS * 2
    private val pts = Array(groups) { FloatArray(maxCount * 2) }
    private val fill = IntArray(groups)
    private val sparkPts = FloatArray(maxCount * 2)

    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private val canvas = Canvas()
    private var bw = 0
    private var bh = 0

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val fade = Paint()

    private var lastKick = -1
    private var lastSnare = -1
    private var sparkle = 0f
    private var sparkSeed = 0

    override fun draw(scope: DrawScope, f: VisFrame) {
        val sw = scope.size.width
        val sh = scope.size.height
        if (sw <= 0f || sh <= 0f) return
        val targetW = if (quality < 0.75f) 270 else 360
        ensure(targetW, (targetW * sh / sw).roundToInt().coerceAtLeast(16))
        val c = canvas
        val a = f.audio
        val dt = f.dt.coerceIn(0f, 0.1f)

        // ---- audio → magnets
        regionEnergy(a.bands)
        if (a.kickCount != lastKick) {
            if (lastKick >= 0) field.kick(0.5f + 0.6f * a.kick)
            lastKick = a.kickCount
        }
        if (a.snareCount != lastSnare) {
            if (lastSnare >= 0) { sparkle = 1f; sparkSeed++ }
            lastSnare = a.snareCount
        }
        sparkle *= exp(-dt / 0.16f)
        field.aspect = bw / bh.toFloat()
        val active = (maxCount * quality.coerceIn(0.6f, 1f)).toInt()
        field.step(dt, energy, active)

        // ---- trails: fade what's there towards black (time-based, so frame rate doesn't matter)
        val fadeAlpha = ((1f - exp(-dt / 0.24f)) * 255f).roundToInt().coerceIn(12, 255)
        fade.color = fadeAlpha shl 24
        c.drawPaint(fade)

        // ---- sort particles into point batches by nearest magnet and speed
        fill.fill(0)
        var sparks = 0
        val sx = bw / (2f * field.aspect)
        val sy = bh / 2f
        val cx = bw / 2f
        val cy = bh / 2f
        val sparkOn = sparkle > 0.05f
        for (i in 0 until active) {
            val px = cx + field.x[i] * sx
            val py = cy + field.y[i] * sy
            val g = field.owner[i] * 2 + if (field.speed[i] > 0.55f) 1 else 0
            val arr = pts[g]
            var k = fill[g]
            arr[k++] = px; arr[k++] = py
            fill[g] = k
            if (sparkOn && (i * 7 + sparkSeed * 13) % 19 == 0) {
                sparkPts[sparks++] = px; sparkPts[sparks++] = py
            }
        }

        // ---- draw: colour by magnet, brighter and larger where that region is loud
        val pal = f.palette
        for (m in 0 until MAGNETS) {
            val e = energy[m]
            val col = pal.argb[m and 3]
            for (fast in 0..1) {
                val g = m * 2 + fast
                if (fill[g] == 0) continue
                val alpha = (0.5f + 0.45f * e + 0.2f * fast).coerceAtMost(1f)
                dot.color = withAlpha(lerpArgb(col, 0xFFFFFFFF.toInt(), 0.12f * fast + 0.2f * e * e), alpha)
                dot.strokeWidth = 1.7f + 1.1f * e + 0.4f * fast
                c.drawPoints(pts[g], 0, fill[g], dot)
            }
        }
        if (sparks > 0) {
            dot.color = withAlpha(0xFFFFFFFF.toInt(), sparkle * 0.9f)
            dot.strokeWidth = 2f
            c.drawPoints(sparkPts, 0, sparks, dot)
        }

        // ---- up to the screen, smoothly scaled
        scope.drawRect(Color.Black)
        scope.drawImage(
            image!!, IntOffset.Zero, IntSize(bw, bh), IntOffset.Zero,
            IntSize(sw.roundToInt(), sh.roundToInt()), filterQuality = FilterQuality.Low,
        )
    }

    /** Average of each magnet's slice of the spectrum, with a gentle curve so quiet regions stay calm. */
    private fun regionEnergy(bands: FloatArray) {
        val n = bands.size
        for (m in 0 until MAGNETS) {
            val from = n * REGION_EDGES[m] / 64
            val to = (n * REGION_EDGES[m + 1] / 64).coerceAtLeast(from + 1)
            var sum = 0f
            for (b in from until to) sum += bands[b]
            val avg = sum / (to - from)
            energy[m] = (avg * avg * 1.4f).coerceAtMost(1f)
        }
    }

    private fun ensure(w: Int, h: Int) {
        if (bitmap != null && w == bw && h == bh) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF000000.toInt())
        bitmap = bmp
        image = bmp.asImageBitmap()
        canvas.setBitmap(bmp)
        bw = w
        bh = h
    }

    override fun release() {
        canvas.setBitmap(null)
        // Not recycled: the last frame drawn may still reference it on the render thread.
        bitmap = null
        image = null
        bw = 0
        bh = 0
    }

    companion object {
        const val MAGNETS = 4
        /** Band boundaries (out of 64) for bass, low-mid, mid and treble. */
        private val REGION_EDGES = intArrayOf(0, 8, 20, 38, 64)
    }
}
