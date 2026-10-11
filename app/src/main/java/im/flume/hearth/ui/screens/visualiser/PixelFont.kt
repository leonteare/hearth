package im.flume.hearth.ui.screens.visualiser

import android.graphics.Canvas
import android.graphics.Paint

/** A chunky 3×5 bitmap font for the retro styles: digits, A–Z and a little punctuation. */
object PixelFont {
    const val WIDTH = 3
    const val HEIGHT = 5

    private val glyphs = IntArray(128)

    init {
        fun def(ch: Char, vararg rows: String) {
            var bits = 0
            for (r in 0 until HEIGHT) for (c in 0 until WIDTH) if (rows[r][c] == '1') bits = bits or (1 shl (r * WIDTH + c))
            glyphs[ch.code] = bits
        }
        def('0', "111", "101", "101", "101", "111"); def('1', "010", "110", "010", "010", "111")
        def('2', "111", "001", "111", "100", "111"); def('3', "111", "001", "111", "001", "111")
        def('4', "101", "101", "111", "001", "001"); def('5', "111", "100", "111", "001", "111")
        def('6', "111", "100", "111", "101", "111"); def('7', "111", "001", "010", "010", "010")
        def('8', "111", "101", "111", "101", "111"); def('9', "111", "101", "111", "001", "111")
        def('A', "010", "101", "111", "101", "101"); def('B', "110", "101", "110", "101", "110")
        def('C', "011", "100", "100", "100", "011"); def('D', "110", "101", "101", "101", "110")
        def('E', "111", "100", "110", "100", "111"); def('F', "111", "100", "110", "100", "100")
        def('G', "011", "100", "101", "101", "011"); def('H', "101", "101", "111", "101", "101")
        def('I', "111", "010", "010", "010", "111"); def('J', "001", "001", "001", "101", "010")
        def('K', "101", "101", "110", "101", "101"); def('L', "100", "100", "100", "100", "111")
        def('M', "101", "111", "111", "101", "101"); def('N', "110", "101", "101", "101", "101")
        def('O', "010", "101", "101", "101", "010"); def('P', "110", "101", "110", "100", "100")
        def('Q', "010", "101", "101", "110", "011"); def('R', "110", "101", "110", "101", "101")
        def('S', "011", "100", "010", "001", "110"); def('T', "111", "010", "010", "010", "010")
        def('U', "101", "101", "101", "101", "111"); def('V', "101", "101", "101", "101", "010")
        def('W', "101", "101", "111", "111", "101"); def('X', "101", "101", "010", "101", "101")
        def('Y', "101", "101", "010", "010", "010"); def('Z', "111", "001", "010", "100", "111")
        def(':', "000", "010", "000", "010", "000"); def('/', "001", "001", "010", "100", "100")
        def('-', "000", "000", "111", "000", "000"); def('.', "000", "000", "000", "000", "010")
        def(',', "000", "000", "000", "010", "100"); def('\'', "010", "010", "000", "000", "000")
        def('!', "010", "010", "010", "000", "010"); def('?', "111", "001", "010", "000", "010")
        def('&', "010", "101", "010", "101", "011"); def('(', "001", "010", "010", "010", "001")
        def(')', "100", "010", "010", "010", "100"); def('>', "100", "110", "111", "110", "100")
        def('|', "101", "101", "101", "101", "101"); def('+', "000", "010", "111", "010", "000")
    }

    /** Upper-cases [text] and swaps characters the font doesn't have for spaces. */
    fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val u = ch.uppercaseChar()
            sb.append(if (u.code < 128 && (glyphs[u.code] != 0 || u == ' ')) u else if (u.isLetterOrDigit()) '?' else ' ')
        }
        return sb.toString()
    }

    fun width(chars: Int, scale: Int): Int = if (chars == 0) 0 else chars * (WIDTH + 1) * scale - scale

    fun draw(c: Canvas, text: CharSequence, x: Float, y: Float, scale: Int, paint: Paint) {
        drawChars(c, text, text.length, x, y, scale, paint)
    }

    fun drawChars(c: Canvas, text: CharSequence, length: Int, x: Float, y: Float, scale: Int, paint: Paint) {
        var cx = x
        val s = scale.toFloat()
        for (i in 0 until length) {
            val code = text[i].code
            val bits = if (code < 128) glyphs[code] else 0
            if (bits != 0) {
                for (r in 0 until HEIGHT) for (col in 0 until WIDTH) {
                    if (bits and (1 shl (r * WIDTH + col)) != 0) {
                        val px = cx + col * s
                        val py = y + r * s
                        c.drawRect(px, py, px + s, py + s, paint)
                    }
                }
            }
            cx += (WIDTH + 1) * s
        }
    }
}
