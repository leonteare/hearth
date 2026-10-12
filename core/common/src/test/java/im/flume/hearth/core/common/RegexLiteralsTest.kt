package im.flume.hearth.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Android compiles regexes with ICU, which throws on some patterns the JVM accepts, so a pattern that
 * passes every JVM test can still crash the app the first time it runs on a phone. This scans every
 * literal `Regex("…")` / `"…".toRegex()` in every module's source for those patterns.
 */
class RegexLiteralsTest {
    private data class Literal(val file: File, val line: Int, val pattern: String)

    @Test
    fun `every regex literal in the app compiles on the JVM and on Android`() {
        // Tests run from their module's folder; the repo root is the nearest folder with settings.gradle.kts.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        assertTrue("No settings.gradle.kts above ${File("").absolutePath}", root != null)
        val sources = root!!.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("/src/main/java") }
            .toList()
        assertTrue("Found no module sources under $root", sources.isNotEmpty())
        val literals = sources.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.flatMap { regexLiterals(it) } }
        assertTrue("Found no regex literals at all; the scanner is broken", literals.size >= 10)
        val problems = literals.flatMap { lit ->
            val jvm = try {
                Pattern.compile(lit.pattern); emptyList()
            } catch (e: PatternSyntaxException) {
                listOf("doesn't compile: ${e.description}")
            }
            (jvm + icuProblems(lit.pattern)).map { "${lit.file.relativeTo(root).invariantSeparatorsPath}:${lit.line}  /${lit.pattern}/  $it" }
        }
        assertTrue("Regex literals Android would reject:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the checker flags what ICU rejects and passes what it accepts`() {
        assertEquals(1, icuProblems("""a]""").size)
        assertEquals(1, icuProblems("""a}""").size)
        assertEquals(1, icuProblems("""x{2,3}}""").size)
        assertEquals(0, icuProblems("""\s*[(\[][^)\]]*[)\]]""").size)
        assertEquals(0, icuProblems("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""").size)
        assertEquals(0, icuProblems("""\d{2,3}k\p{L}\x{41}a{2}b{1,}\Q]}\E""").size)
    }

    @Test
    fun `the scanner finds both literal forms and unescapes Kotlin strings`() {
        val src = "val a = Regex(\"\\\\s+\\\"\")\nval b = \"\"\"x]\"\"\".toRegex()\nval c = Regex(\"a\" + d)\n// Regex(\"ignored\")\n"
        val file = File.createTempFile("regex", ".kt").apply { writeText(src); deleteOnExit() }
        assertEquals(listOf(1 to "\\s+\"", 2 to "x]"), regexLiterals(file).map { it.line to it.pattern })
    }

    /**
     * Problems ICU has with [pattern] that java.util.regex doesn't: a bare `]` outside a character
     * class, and a bare `}` that doesn't close a `{n}`, `{n,}` or `{n,m}` quantifier.
     */
    private fun icuProblems(pattern: String): List<String> {
        val problems = mutableListOf<String>()
        var depth = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1)
                    i += when {
                        next == 'Q' -> pattern.indexOf("\\E", i + 2).let { if (it < 0) pattern.length else it + 2 } - i
                        next != null && next in "pPxN" && pattern.getOrNull(i + 2) == '{' ->
                            pattern.indexOf('}', i + 2).let { if (it < 0) pattern.length else it + 1 } - i
                        else -> 2
                    }
                    continue
                }
                depth > 0 && c == '[' -> depth++
                depth > 0 && c == ']' -> depth--
                depth > 0 -> {}
                c == '[' -> depth = 1
                c == ']' -> problems += "unescaped ] outside a character class at index $i (write \\])"
                c == '{' -> {
                    val m = Regex("""\{\d+(,\d*)?\}""").matchAt(pattern, i)
                    if (m != null) {
                        i += m.value.length
                        continue
                    }
                    problems += "unescaped { that isn't a {n,m} quantifier at index $i (write \\{)"
                }
                c == '}' -> problems += "unescaped } that doesn't close a quantifier at index $i (write \\})"
            }
            i++
        }
        return problems
    }

    /** String literals passed straight to `Regex(` or followed by `.toRegex()`, with templates skipped. */
    private fun regexLiterals(file: File): List<Literal> {
        val text = file.readText()
        val out = mutableListOf<Literal>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                text[i] == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '\'') j += if (text[j] == '\\') 2 else 1
                    i = j + 1
                }
                text[i] == '"' -> {
                    val raw = text.startsWith("\"\"\"", i)
                    val (end, value) = if (raw) readRaw(text, i + 3) else readQuoted(text, i + 1)
                    val before = text.substring(0, i).trimEnd()
                    val after = text.substring(end).trimStart()
                    val passed = before.endsWith("Regex(") && (after.startsWith(")") || after.startsWith(","))
                    if (value != null && (passed || text.startsWith(".toRegex(", end))) {
                        out += Literal(file, text.substring(0, i).count { it == '\n' } + 1, value)
                    }
                    i = end
                }
                else -> i++
            }
        }
        return out
    }

    /** Reads a raw string starting after its opening quotes. The value is null if it holds a template. */
    private fun readRaw(text: String, start: Int): Pair<Int, String?> {
        var close = text.indexOf("\"\"\"", start)
        if (close < 0) return text.length to null
        while (text.getOrNull(close + 3) == '"') close++ // """a"""" ends with a quote in the value
        val body = text.substring(start, close).replace("\${'$'}", "\u0000")
        val templated = Regex("""\$[A-Za-z_{]""").containsMatchIn(body)
        return close + 3 to if (templated) null else body.replace('\u0000', '$')
    }

    /** Reads a "…" string starting after its opening quote, unescaping it. The value is null if it holds a template. */
    private fun readQuoted(text: String, start: Int): Pair<Int, String?> {
        val sb = StringBuilder()
        var templated = false
        var i = start
        while (i < text.length && text[i] != '"' && text[i] != '\n') {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val e = text[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'u' -> { sb.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
                i += 2
                continue
            }
            if (c == '$' && text.getOrNull(i + 1)?.let { it == '{' || it.isLetter() || it == '_' } == true) {
                templated = true
                if (text[i + 1] == '{') {
                    var depth = 0
                    while (i < text.length) {
                        if (text[i] == '{') depth++
                        if (text[i] == '}' && --depth == 0) break
                        i++
                    }
                }
            }
            sb.append(c)
            i++
        }
        return i + 1 to if (templated) null else sb.toString()
    }
}
