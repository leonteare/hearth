package im.flume.hearth.spoken

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

// Chapters stored inside an audio file: a single .m4b usually carries its own chapter list, which
// Android's metadata reader can't see. Plain Kotlin over a random-access byte source, so it's unit
// tested on byte arrays and reads only the few boxes/frames it needs from a large file on the phone.

/** Random access to a file's bytes. [read] may return fewer bytes than asked for at the end of the file. */
interface ByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

/** A file on the phone, read in place (a large .m4b is never loaded whole). */
class FileChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(minOf(length.toLong(), size - offset).toInt())
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }
}

/** A chapter as the file states it: where it starts within that file, and its title (may be blank). */
data class EmbeddedChapter(val startSec: Double, val title: String)

object EmbeddedChapters {
    private val MP4 = setOf("m4b", "m4a", "mp4")
    private val MP3 = setOf("mp3")

    /** Whether a file of this name might hold chapters we can read (so other files aren't opened for nothing). */
    fun mayHaveChapters(fileName: String): Boolean = LocalBookScan.extension(fileName).let { it in MP4 || it in MP3 }

    /** The file's own chapters, or an empty list when it has none or they can't be read. Never throws on bad data. */
    fun read(fileName: String, source: ByteSource): List<EmbeddedChapter> = try {
        // Sniff rather than trust the extension: some ".mp3"s are really MP4 and vice versa.
        val head = source.read(0, 8)
        when {
            head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> Id3Chapters.read(source)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES -> Mp4Chapters.read(source)
            LocalBookScan.extension(fileName) in MP4 -> Mp4Chapters.read(source)
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "free", "skip", "mdat", "wide")

    /**
     * Chapters for the whole book. Each file that names at least two chapters of its own is split into
     * them (moved to where that file starts in the book); other files stay one chapter each. When no file
     * has chapters of its own this is exactly the old one-chapter-per-file list (none for a lone file).
     * Blank titles become "Chapter N".
     */
    fun timeline(durations: List<Double>, fileTitles: List<String>, embedded: List<List<EmbeddedChapter>>): List<TimelineChapter> {
        val inside = durations.indices.map { i -> clean(embedded.getOrElse(i) { emptyList() }, durations[i]) }
        if (inside.none { it.size >= 2 }) return LocalBookNaming.chapters(durations, fileTitles)
        val out = ArrayList<TimelineChapter>()
        var fileStart = 0.0
        durations.forEachIndexed { i, d ->
            val chapters = inside[i]
            if (chapters.size >= 2) {
                chapters.forEachIndexed { j, c ->
                    val end = chapters.getOrNull(j + 1)?.startSec ?: d
                    out += TimelineChapter(fileStart + c.startSec, fileStart + end, c.title)
                }
            } else {
                out += TimelineChapter(fileStart, fileStart + d, chapters.singleOrNull()?.title?.takeIf { it.isNotBlank() } ?: fileTitles.getOrElse(i) { "" })
            }
            fileStart += d
        }
        return out.mapIndexed { n, c -> if (c.title.isBlank()) c.copy(title = "Chapter ${n + 1}") else c }
    }

    /** In order, inside the file, one per start time; the first starts at 0 so the file is fully covered. */
    private fun clean(chapters: List<EmbeddedChapter>, durationSec: Double): List<EmbeddedChapter> {
        val sorted = chapters
            .filter { it.startSec.isFinite() && it.startSec >= 0 && (durationSec <= 0 || it.startSec < durationSec) }
            .sortedBy { it.startSec }
            .distinctBy { it.startSec }
            .map { it.copy(title = it.title.trim()) }
        if (sorted.isEmpty()) return sorted
        return listOf(sorted.first().copy(startSec = 0.0)) + sorted.drop(1)
    }
}

/** Big-endian reads within a byte array, returning null instead of throwing when the data runs out. */
private class Bytes(val b: ByteArray) {
    fun u8(at: Int): Int? = if (at in b.indices) b[at].toInt() and 0xFF else null
    fun u16(at: Int): Int? = if (at >= 0 && at + 2 <= b.size) ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF) else null
    fun u32(at: Int): Long? = if (at >= 0 && at + 4 <= b.size) (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xFF) } else null
    fun u64(at: Int): Long? = if (at >= 0 && at + 8 <= b.size) (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xFF) } else null
}

/**
 * MP4/M4B chapters, in either of the two common forms: a QuickTime chapter track (a text track the
 * audio track points at with `tref/chap`; usually the better titles, so it wins) or Nero's
 * `moov/udta/chpl` list.
 */
object Mp4Chapters {
    /** Biggest box read whole; chapter tables are tiny, so anything larger is damage, not chapters. */
    private const val MAX_BOX = 8 * 1024 * 1024
    private const val MAX_CHAPTERS = 5_000
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "tref", "edts")

    private class Box(val type: String, val start: Long, val dataStart: Long, val end: Long) {
        val dataSize: Long get() = end - dataStart
    }

    fun read(source: ByteSource): List<EmbeddedChapter> {
        val moov = children(source, 0, source.size).firstOrNull { it.type == "moov" } ?: return emptyList()
        val quickTime = runCatching { quickTimeChapters(source, moov) }.getOrDefault(emptyList())
        if (quickTime.isNotEmpty()) return quickTime
        return runCatching { neroChapters(source, moov) }.getOrDefault(emptyList())
    }

    /** The boxes directly inside [from, to), by header only. Stops quietly at the first broken header. */
    private fun children(source: ByteSource, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to && out.size < 10_000) {
            val h = Bytes(source.read(at, 16))
            val size32 = h.u32(0) ?: break
            val type = String(h.b, 4, 4, Charsets.ISO_8859_1)
            val (headerSize, size) = when (size32) {
                0L -> 8L to (to - at) // runs to the end of its parent (or the file)
                1L -> 16L to (h.u64(8) ?: break)
                else -> 8L to size32
            }
            if (size < headerSize || at + size > to) break
            out += Box(type, at, at + headerSize, at + size)
            at += size
        }
        return out
    }

    private fun child(source: ByteSource, parent: Box, type: String): Box? =
        children(source, parent.dataStart, parent.end).firstOrNull { it.type == type }

    private fun path(source: ByteSource, parent: Box, vararg types: String): Box? =
        types.fold(parent as Box?) { box, t -> box?.let { child(source, it, t) } }

    private fun payload(source: ByteSource, box: Box): Bytes? {
        if (box.dataSize > MAX_BOX) return null
        val data = source.read(box.dataStart, box.dataSize.toInt())
        return if (data.size.toLong() == box.dataSize) Bytes(data) else null
    }

    // ---------------------------------------------------------------- Nero chpl

    private fun neroChapters(source: ByteSource, moov: Box): List<EmbeddedChapter> {
        val chpl = path(source, moov, "udta", "chpl") ?: return emptyList()
        val p = payload(source, chpl) ?: return emptyList()
        val version = p.u8(0) ?: return emptyList()
        // Version 1: 4 more bytes before the count. ffmpeg writes 4 zero bytes and a 1-byte count;
        // mp4v2 writes a reserved byte and a 4-byte count. Reading bytes 5..8 as a number fits both.
        val (count, first) = if (version >= 1) (p.u32(5) ?: return emptyList()) to 9 else (p.u8(4)?.toLong() ?: return emptyList()) to 5
        val out = ArrayList<EmbeddedChapter>()
        var at = first
        while (out.size < minOf(count, MAX_CHAPTERS.toLong())) {
            val start = p.u64(at) ?: break
            val len = p.u8(at + 8) ?: break
            if (at + 9 + len > p.b.size) break
            out += EmbeddedChapter(start / 10_000_000.0, String(p.b, at + 9, len, Charsets.UTF_8))
            at += 9 + len
        }
        return out
    }

    // ---------------------------------------------------------------- QuickTime chapter track

    private class Track(val id: Long, val chapterRefs: List<Long>, val handler: String?, val box: Box)

    private fun quickTimeChapters(source: ByteSource, moov: Box): List<EmbeddedChapter> {
        val tracks = children(source, moov.dataStart, moov.end).filter { it.type == "trak" }.mapNotNull { trak -> track(source, trak) }
        val referenced = tracks.flatMap { it.chapterRefs }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in referenced && it.handler in TEXT_HANDLERS }
            ?: tracks.firstOrNull { it.id in referenced }
            ?: return emptyList()
        return readTextTrack(source, chapterTrack.box)
    }

    private val TEXT_HANDLERS = setOf("text", "sbtl")

    private fun track(source: ByteSource, trak: Box): Track? {
        val tkhd = child(source, trak, "tkhd")?.let { payload(source, it) } ?: return null
        val id = (if (tkhd.u8(0) == 1) tkhd.u32(20) else tkhd.u32(12)) ?: return null
        val chap = path(source, trak, "tref", "chap")?.let { payload(source, it) }
        val refs = chap?.let { c -> (0 until c.b.size / 4).mapNotNull { c.u32(it * 4) } }.orEmpty()
        val handler = path(source, trak, "mdia", "hdlr")?.let { payload(source, it) }?.takeIf { it.b.size >= 12 }?.let { String(it.b, 8, 4, Charsets.ISO_8859_1) }
        return Track(id, refs, handler, trak)
    }

    private fun readTextTrack(source: ByteSource, trak: Box): List<EmbeddedChapter> {
        val mdia = child(source, trak, "mdia") ?: return emptyList()
        val mdhd = child(source, mdia, "mdhd")?.let { payload(source, it) } ?: return emptyList()
        val timescale = (if (mdhd.u8(0) == 1) mdhd.u32(20) else mdhd.u32(12))?.takeIf { it > 0 } ?: return emptyList()
        val stbl = path(source, mdia, "minf", "stbl") ?: return emptyList()
        val tables = children(source, stbl.dataStart, stbl.end).associateBy { it.type }
        fun table(type: String) = tables[type]?.let { payload(source, it) }

        // Sample durations (stts): runs of (count, delta).
        val stts = table("stts") ?: return emptyList()
        val durations = ArrayList<Long>()
        for (e in 0 until (stts.u32(4) ?: 0L).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()) {
            val n = stts.u32(8 + e * 8) ?: break
            val delta = stts.u32(12 + e * 8) ?: break
            repeat(n.coerceAtMost((MAX_CHAPTERS - durations.size).toLong()).toInt()) { durations += delta }
        }
        // Sample sizes (stsz): one size for all, or a list.
        val stsz = table("stsz") ?: return emptyList()
        val fixed = stsz.u32(4) ?: return emptyList()
        val sampleCount = (stsz.u32(8) ?: return emptyList()).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()
        val sizes = (0 until sampleCount).map { i -> if (fixed != 0L) fixed else stsz.u32(12 + i * 4) ?: return emptyList() }
        // Chunk offsets (stco, or co64 for files over 4 GB).
        val chunkOffsets = table("stco")?.let { t -> (0 until (t.u32(4) ?: 0L).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()).mapNotNull { t.u32(8 + it * 4) } }
            ?: table("co64")?.let { t -> (0 until (t.u32(4) ?: 0L).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()).mapNotNull { t.u64(8 + it * 8) } }
            ?: return emptyList()
        // Samples per chunk (stsc): runs starting at a 1-based chunk number.
        val stsc = table("stsc") ?: return emptyList()
        val runs = (0 until (stsc.u32(4) ?: 0L).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()).mapNotNull { e ->
            val first = stsc.u32(8 + e * 12) ?: return@mapNotNull null
            val per = stsc.u32(12 + e * 12) ?: return@mapNotNull null
            first to per
        }
        if (runs.isEmpty()) return emptyList()

        val offsets = ArrayList<Long>()
        chunkOffsets.forEachIndexed { c, chunkStart ->
            val chunk = c + 1L
            val per = runs.lastOrNull { it.first <= chunk }?.second ?: runs.first().second
            var at = chunkStart
            for (k in 0 until per.coerceAtMost(MAX_CHAPTERS.toLong())) {
                if (offsets.size >= sizes.size) return@forEachIndexed
                offsets += at
                at += sizes[offsets.size - 1]
            }
        }

        val out = ArrayList<EmbeddedChapter>()
        var time = 0L
        for (i in offsets.indices) {
            out += EmbeddedChapter(time.toDouble() / timescale, sampleText(source, offsets[i], sizes[i]))
            time += durations.getOrElse(i) { 0L }
        }
        return out
    }

    /** A text sample: a 2-byte length, then the title in UTF-8, or UTF-16 when it starts with a byte-order mark. */
    private fun sampleText(source: ByteSource, offset: Long, size: Long): String {
        if (size < 2) return ""
        val head = Bytes(source.read(offset, 2))
        val len = (head.u16(0) ?: return "").coerceAtMost((size - 2).toInt().coerceAtLeast(0))
        if (len == 0) return ""
        val text = source.read(offset + 2, len)
        return decodeWithBom(text, Charsets.UTF_8)
    }
}

/** UTF-16 when a byte-order mark says so, otherwise [fallback]. Trailing NULs are dropped. */
private fun decodeWithBom(b: ByteArray, fallback: Charset): String {
    val s = when {
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> String(b, fallback)
    }
    return s.trimEnd('\u0000')
}

/** MP3 chapters: ID3v2.3/2.4 `CHAP` frames (start time in ms, title in an inner `TIT2`). */
object Id3Chapters {
    private const val MAX_TAG = 64 * 1024 * 1024
    private const val MAX_CHAPTERS = 5_000

    fun read(source: ByteSource): List<EmbeddedChapter> {
        val h = Bytes(source.read(0, 10))
        if (h.b.size < 10 || h.b[0] != 'I'.code.toByte() || h.b[1] != 'D'.code.toByte() || h.b[2] != '3'.code.toByte()) return emptyList()
        val major = h.u8(3) ?: return emptyList()
        if (major != 3 && major != 4) return emptyList() // v2.2 uses 3-letter frames and has no CHAP
        val flags = h.u8(5) ?: return emptyList()
        val tagSize = synchsafe(h, 6) ?: return emptyList()
        if (tagSize <= 0 || tagSize > MAX_TAG) return emptyList()
        val tagUnsync = flags and 0x80 != 0

        // A v2.3 tag-wide "unsynchronised" tag has to be undone as a whole before frames can be found.
        val tagSource: ByteSource
        var at: Long
        if (tagUnsync && major == 3) {
            tagSource = ByteArraySource(resync(source.read(10, tagSize)))
            at = 0
        } else {
            tagSource = OffsetSource(source, 10, tagSize.toLong())
            at = 0
        }
        if (flags and 0x40 != 0) { // extended header
            val e = Bytes(tagSource.read(at, 4))
            val extSize = (if (major == 4) synchsafe(e, 0) else e.u32(0)?.toInt()?.plus(4)) ?: return emptyList()
            if (extSize < 4) return emptyList()
            at += extSize
        }

        val out = ArrayList<EmbeddedChapter>()
        while (at + 10 <= tagSource.size && out.size < MAX_CHAPTERS) {
            val fh = Bytes(tagSource.read(at, 10))
            if (fh.b.size < 10 || fh.b[0] == 0.toByte()) break // padding
            val id = String(fh.b, 0, 4, Charsets.ISO_8859_1)
            val size = (if (major == 4) synchsafe(fh, 4) else fh.u32(4)?.toInt()) ?: break
            if (size < 0 || at + 10 + size > tagSource.size) break
            val formatFlags = fh.u8(9) ?: 0
            if (id == "CHAP") {
                var data = tagSource.read(at + 10, size)
                if (major == 4 && formatFlags and 0x02 != 0) data = resync(data)
                chapter(data, major)?.let { out += it }
            }
            at += 10 + size
        }
        return out.sortedBy { it.startSec }
    }

    /** One CHAP frame: element id (NUL-terminated), start ms, end ms, two byte offsets, then sub-frames. */
    private fun chapter(data: ByteArray, major: Int): EmbeddedChapter? {
        val b = Bytes(data)
        val nul = data.indexOf(0.toByte()).takeIf { it >= 0 } ?: return null
        val startMs = b.u32(nul + 1) ?: return null
        var at = nul + 17
        var title = ""
        while (at + 10 <= data.size) {
            val id = String(data, at, 4, Charsets.ISO_8859_1)
            val size = (if (major == 4) synchsafe(b, at + 4) else b.u32(at + 4)?.toInt()) ?: break
            if (size < 0 || at + 10 + size > data.size) break
            if (id == "TIT2" && size >= 1) title = text(data.copyOfRange(at + 10, at + 10 + size))
            at += 10 + size
        }
        return EmbeddedChapter(startMs / 1000.0, title)
    }

    /** A text frame: an encoding byte, then the text. */
    private fun text(frame: ByteArray): String {
        val body = frame.copyOfRange(1, frame.size)
        return when (frame[0].toInt()) {
            0 -> String(body, Charsets.ISO_8859_1).trimEnd('\u0000')
            1 -> decodeWithBom(body, Charsets.UTF_16LE).trimEnd('\u0000')
            2 -> String(body, Charsets.UTF_16BE).trimEnd('\u0000')
            else -> decodeWithBom(body, Charsets.UTF_8)
        }.substringBefore('\u0000')
    }

    private fun synchsafe(b: Bytes, at: Int): Int? {
        val raw = (0 until 4).map { b.u8(at + it) ?: return null }
        return raw.fold(0) { acc, v -> (acc shl 7) or (v and 0x7F) }
    }

    /** Undoes ID3 unsynchronisation: every 0xFF 0x00 pair was a lone 0xFF. */
    private fun resync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private class OffsetSource(val inner: ByteSource, val base: Long, length: Long) : ByteSource {
        override val size: Long = minOf(length, (inner.size - base).coerceAtLeast(0))
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= size) return ByteArray(0)
            return inner.read(base + offset, minOf(length.toLong(), size - offset).toInt())
        }
    }
}
