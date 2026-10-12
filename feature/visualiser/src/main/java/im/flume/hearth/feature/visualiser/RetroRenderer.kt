package im.flume.hearth.feature.visualiser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Base for the late-90s styles: [render] draws into a small bitmap (about 270 px across, a whole
 * fraction of the screen) with plain android.graphics, no anti-aliasing. The frame is then ordered-
 * dithered to a short palette, blown up with nearest-neighbour filtering, given an optional cheap
 * bloom (a tiny blurred copy added on top) and finished with scanlines and a vignette.
 */
abstract class RetroRenderer : VisualiserRenderer() {
    /** Low-res pixels across the screen at full quality. */
    protected open val targetWidth: Int get() = 270
    /** Levels per colour channel after dithering; 0 turns dithering off. */
    protected open val ditherLevels: Int get() = 20
    /** Strength of the bloom pass, 0 = none. */
    protected open val bloom: Float get() = 0f

    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    protected val canvas = Canvas()
    protected var lw = 0
        private set
    protected var lh = 0
        private set
    private var pixelScale = 1
    private var pixels = IntArray(0)
    private var lut = IntArray(0)
    private var lutLevels = -1

    private var bloomBitmap: Bitmap? = null
    private var bloomImage: ImageBitmap? = null
    private val bloomCanvas = Canvas()
    private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bloomRect = Rect()

    private var scanlines: ShaderBrush? = null
    private var scanlineScale = 0
    private var vignette: Brush? = null
    private var vignetteSize = Size.Zero

    /** Shared scratch for subclasses: an unfilled path and flat, aliased paints. */
    protected val path = Path()
    protected val fill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    protected val stroke = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 1f }

    /** Draws one frame into [canvas], which is [w] × [h] low-res pixels. */
    protected abstract fun render(c: Canvas, w: Int, h: Int, f: VisFrame)

    override fun draw(scope: DrawScope, f: VisFrame) {
        val sw = scope.size.width.toInt()
        val sh = scope.size.height.toInt()
        if (sw <= 0 || sh <= 0) return
        // Whole-number upscaling keeps every big pixel the same size. Lower quality = chunkier pixels.
        val scale = max(2, (sw / (targetWidth * quality.coerceIn(0.5f, 1f))).roundToInt())
        ensure(ceil(sw / scale.toFloat()).toInt(), ceil(sh / scale.toFloat()).toInt(), scale)
        val bmp = bitmap ?: return

        render(canvas, lw, lh, f)
        if (ditherLevels > 0) dither(bmp)

        val dst = IntSize(lw * scale, lh * scale)
        scope.drawImage(image!!, IntOffset.Zero, IntSize(lw, lh), IntOffset.Zero, dst, filterQuality = FilterQuality.None)
        if (bloom > 0f) {
            val bb = bloomBitmap!!
            bloomRect.set(0, 0, bb.width, bb.height)
            bloomCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            bloomCanvas.drawBitmap(bmp, null, bloomRect, bloomPaint)
            scope.drawImage(
                bloomImage!!, IntOffset.Zero, IntSize(bb.width, bb.height), IntOffset.Zero, dst,
                alpha = bloom, blendMode = BlendMode.Plus, filterQuality = FilterQuality.Low,
            )
        }
        scope.drawRect(scanlineBrush(scale), size = scope.size, alpha = 0.32f)
        scope.drawRect(vignetteBrush(scope.size), size = scope.size)
    }

    private fun ensure(w: Int, h: Int, scale: Int) {
        if (bitmap != null && w == lw && h == lh) return
        release()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        image = bmp.asImageBitmap()
        canvas.setBitmap(bmp)
        lw = w; lh = h; pixelScale = scale
        pixels = IntArray(w * h)
        val bw = max(8, w / 6)
        val bh = max(8, h / 6)
        val bb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bloomBitmap = bb
        bloomImage = bb.asImageBitmap()
        bloomCanvas.setBitmap(bb)
        onResize(w, h)
    }

    /** The low-res size changed (first frame, rotation, quality drop): rebuild size-based caches. */
    protected open fun onResize(w: Int, h: Int) {}

    private fun dither(bmp: Bitmap) {
        val levels = ditherLevels
        if (levels != lutLevels) { lut = Bayer.table(levels); lutLevels = levels }
        val px = pixels
        val w = lw
        bmp.getPixels(px, 0, w, 0, 0, w, lh)
        val t = lut
        for (y in 0 until lh) {
            val row = (y and 3) shl 2
            var i = y * w
            for (x in 0 until w) {
                val c = px[i]
                val cell = (row or (x and 3)) shl 8
                px[i] = (c and 0xFF000000.toInt()) or
                    (t[cell or ((c ushr 16) and 0xFF)] shl 16) or
                    (t[cell or ((c ushr 8) and 0xFF)] shl 8) or
                    t[cell or (c and 0xFF)]
                i++
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, lh)
    }

    private fun scanlineBrush(scale: Int): ShaderBrush {
        scanlines?.takeIf { scanlineScale == scale }?.let { return it }
        // One dark screen-pixel row at the bottom of every low-res row.
        val tile = Bitmap.createBitmap(1, scale, Bitmap.Config.ARGB_8888)
        tile.setPixel(0, scale - 1, 0xFF000000.toInt())
        if (scale >= 4) tile.setPixel(0, scale - 2, 0x66000000)
        val brush = ShaderBrush(ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        scanlines = brush
        scanlineScale = scale
        return brush
    }

    private fun vignetteBrush(size: Size): Brush {
        vignette?.takeIf { vignetteSize == size }?.let { return it }
        val brush = Brush.radialGradient(
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.6f),
            center = Offset(size.width / 2, size.height / 2),
            radius = max(size.width, size.height) * 0.62f,
        )
        vignette = brush
        vignetteSize = size
        return brush
    }

    override fun release() {
        canvas.setBitmap(null)
        bloomCanvas.setBitmap(null)
        // Not recycled: the last frame drawn may still reference them on the render thread.
        bitmap = null; image = null
        bloomBitmap = null; bloomImage = null
        lw = 0; lh = 0
    }

    // ---------------------------------------------------------------- helpers for subclasses

    protected fun fillQuad(c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float, color: Int) {
        path.rewind()
        path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.lineTo(dx, dy); path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    protected fun fillTri(c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, color: Int) {
        path.rewind()
        path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    protected fun line(c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, color: Int, width: Float = 1f) {
        stroke.color = color
        stroke.strokeWidth = width
        c.drawLine(ax, ay, bx, by, stroke)
    }
}
