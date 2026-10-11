package im.flume.hearth.ui.screens.visualiser

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import im.flume.hearth.playback.AudioFrame
import kotlin.math.exp
import kotlin.math.floor

/** The looks on offer. [id] is what Settings remembers. */
enum class VisualiserStyle(val id: String, val label: String, val retro: Boolean) {
    AURORA("aurora", "Aurora", false),
    HALO("halo", "Halo", false),
    PARTICLES("particles", "Particles", false),
    RIBBON("ribbon", "Ribbon", false),
    MEMORY_CARD("memory-card", "Memory Card", true),
    TUNNEL("tunnel", "Tunnel '98", true),
    GRID_RUNNER("grid-runner", "Grid Runner", true),
    DISC_PLAYER("disc-player", "Disc Player", true);

    fun create(): VisualiserRenderer = when (this) {
        AURORA -> AuroraRenderer()
        HALO -> HaloRenderer()
        PARTICLES -> ParticlesRenderer()
        RIBBON -> RibbonRenderer()
        MEMORY_CARD -> MemoryCardRenderer()
        TUNNEL -> TunnelRenderer()
        GRID_RUNNER -> GridRunnerRenderer()
        DISC_PLAYER -> DiscPlayerRenderer()
    }

    companion object {
        const val DEFAULT_ID = "aurora"
        fun byId(id: String?): VisualiserStyle = entries.firstOrNull { it.id == id } ?: AURORA
    }
}

/**
 * Everything a style draws from, updated in place once per frame (no allocation): clock, audio,
 * colours, cover and a little track info.
 */
class VisFrame {
    /** Seconds since the visualiser opened; [dt] since the last frame. */
    var time = 0f
    var dt = 1f / 60f
    var audio = AudioFrame()
    val palette = VisPalette()
    /** The current cover, both as a software bitmap (for low-res drawing) and for Compose; null until loaded. */
    var coverBitmap: Bitmap? = null
    var cover: ImageBitmap? = null
    /** Bumped when the cover changes, so styles can rebuild what they made from it. */
    var coverVersion = 0
    var positionMs = 0L
    var durationMs = 0L
    var isPlaying = false
    var title = ""
    var artist = ""
}

/**
 * One visualiser look. [draw] runs every frame on the UI thread and mustn't allocate in its loops:
 * keep Paths, arrays and bitmaps as fields.
 */
abstract class VisualiserRenderer {
    /**
     * Fraction of the screen resolution this style renders at; the result is scaled up. Soft styles
     * (Aurora) look the same at half resolution for a quarter of the work.
     */
    open val renderScale: Float get() = 1f

    /** 1 = full detail; lowered (to 0.5) when frames keep running late. */
    var quality = 1f

    abstract fun draw(scope: DrawScope, f: VisFrame)

    /** Frees bitmaps or shaders when the style is switched away from. */
    open fun release() {}
}

/**
 * Four colours (ARGB) from the cover. Changes ease over about a second so a new song doesn't jump
 * cut; [version] goes up whenever the colours move, for styles that cache gradients.
 */
class VisPalette {
    val argb = IntArray(4)
    private val current = FloatArray(16)
    private val target = FloatArray(16)
    var version = 0
        private set

    init {
        snap(intArrayOf(0xFFFF8A3D.toInt(), 0xFFFF3D7F.toInt(), 0xFF7A5CFF.toInt(), 0xFF3DD9FF.toInt()))
    }

    fun color(i: Int): Color = Color(argb[i and 3])

    /** Cyclic blend through the four colours: 0 → first, 0.25 → second … 1 → back to first. */
    fun argbAt(t: Float): Int {
        val x = (t - floor(t)) * 4f
        val i = x.toInt().coerceIn(0, 3)
        return lerpArgb(argb[i], argb[(i + 1) and 3], x - i)
    }

    fun colorAt(t: Float): Color = Color(argbAt(t))

    fun snap(colors: IntArray) {
        setTarget(colors)
        target.copyInto(current)
        publish()
    }

    fun setTarget(colors: IntArray) {
        for (i in 0 until 4) {
            val c = colors[i % colors.size]
            target[i * 4] = ((c ushr 16) and 0xFF).toFloat()
            target[i * 4 + 1] = ((c ushr 8) and 0xFF).toFloat()
            target[i * 4 + 2] = (c and 0xFF).toFloat()
            target[i * 4 + 3] = 255f
        }
    }

    /** Eases towards the target; call once per frame. */
    fun step(dt: Float) {
        val k = 1f - exp(-dt / 0.3f)
        var moved = false
        for (i in 0 until 16) {
            val d = target[i] - current[i]
            if (d != 0f) {
                current[i] = if (kotlin.math.abs(d) < 0.5f) target[i] else current[i] + d * k
                moved = true
            }
        }
        if (moved) publish()
    }

    private fun publish() {
        for (i in 0 until 4) {
            argb[i] = (0xFF shl 24) or
                (current[i * 4].toInt().coerceIn(0, 255) shl 16) or
                (current[i * 4 + 1].toInt().coerceIn(0, 255) shl 8) or
                current[i * 4 + 2].toInt().coerceIn(0, 255)
        }
        version++
    }

    companion object {
        /**
         * Visualiser-ready colours from cover swatches: vivid and bright enough to glow on black.
         * Near-greys are dropped; missing colours are made by turning the hue of the first one.
         */
        fun vivid(swatches: List<Color>, accent: Color): IntArray {
            val hsv = FloatArray(3)
            val picked = ArrayList<FloatArray>()
            for (c in swatches) {
                android.graphics.Color.colorToHSV(c.toArgb(), hsv)
                if (hsv[1] < 0.12f) continue
                if (picked.any { hueDistance(it[0], hsv[0]) < 18f }) continue
                picked += floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.55f), hsv[2].coerceAtLeast(0.85f))
                if (picked.size == 4) break
            }
            if (picked.isEmpty()) {
                android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
                picked += floatArrayOf(hsv[0], hsv[1].coerceAtLeast(0.55f), hsv[2].coerceAtLeast(0.85f))
            }
            val turns = floatArrayOf(40f, -40f, 160f)
            var n = 0
            while (picked.size < 4) {
                val base = picked[0]
                picked += floatArrayOf((base[0] + turns[n++] + 360f) % 360f, base[1], base[2])
            }
            return IntArray(4) { android.graphics.Color.HSVToColor(picked[it]) }
        }

        private fun hueDistance(a: Float, b: Float): Float {
            val d = kotlin.math.abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }
    }
}
