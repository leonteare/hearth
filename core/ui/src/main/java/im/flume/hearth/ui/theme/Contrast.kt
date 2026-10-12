package im.flume.hearth.ui.theme

import kotlin.math.pow

/**
 * WCAG contrast checks on plain ARGB ints, so the dimmed text colours can be tested on the JVM.
 * Body text needs at least 4.5:1 to stay readable for people with low vision.
 */
object Contrast {
    const val MIN_TEXT = 4.5

    /** [argb] drawn at [alpha] on top of the opaque [background], as the colour people actually see. */
    fun over(argb: Int, alpha: Float, background: Int): Int {
        fun mix(shift: Int): Int {
            val fg = (argb shr shift) and 0xFF
            val bg = (background shr shift) and 0xFF
            return Math.round(bg + (fg - bg) * alpha)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Relative luminance of an opaque colour, 0 (black) to 1 (white). */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Contrast ratio between two opaque colours, from 1:1 to 21:1. */
    fun ratio(a: Int, b: Int): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (hi + 0.05) / (lo + 0.05)
    }
}
