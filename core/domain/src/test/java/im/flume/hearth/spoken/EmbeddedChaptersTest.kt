package im.flume.hearth.spoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class EmbeddedChaptersTest {

    // ---------------------------------------------------------------- byte builders

    private class W {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v shr 8); u8(v) }
        fun u32(v: Long) = apply { for (s in 24 downTo 0 step 8) u8((v shr s).toInt()) }
        fun u32(v: Int) = u32(v.toLong())
        fun u64(v: Long) = apply { for (s in 56 downTo 0 step 8) u8((v shr s).toInt()) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun ascii(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))
        fun build(): ByteArray = out.toByteArray()
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return W().u32(8 + body.size).ascii(type).bytes(body).build()
    }

    private fun largeBox(type: String, body: ByteArray): ByteArray = W().u32(1).ascii(type).u64(16L + body.size).bytes(body).build()

    private fun fullBox(type: String, body: ByteArray, version: Int = 0) = box(type, W().u8(version).u8(0).u16(0).build() + body)

    private val ftyp = box("ftyp", "M4B ".toByteArray(), W().u32(0).build(), "M4B mp42isom".toByteArray())

    private fun chpl(chapters: List<Pair<Long, String>>, version: Int = 1): ByteArray {
        val w = W()
        if (version == 1) w.u32(0).u8(chapters.size) else w.u8(chapters.size)
        chapters.forEach { (start, title) -> val t = title.toByteArray(); w.u64(start).u8(t.size).bytes(t) }
        return fullBox("chpl", w.build(), version)
    }

    private fun tkhd(id: Int) = fullBox("tkhd", W().u32(0).u32(0).u32(id).u32(0).u32(0).build())
    private fun hdlr(type: String) = fullBox("hdlr", W().u32(0).ascii(type).u32(0).u32(0).u32(0).u8(0).build())
    private fun mdhd(timescale: Int) = fullBox("mdhd", W().u32(0).u32(0).u32(timescale).u32(0).u16(0).u16(0).build())

    private fun audioTrack(id: Int, chapterTrack: Int) = box(
        "trak", tkhd(id), box("tref", box("chap", W().u32(chapterTrack).build())), box("mdia", mdhd(44100), hdlr("soun")),
    )

    private fun sample(title: String, utf16: Boolean = false): ByteArray {
        val t = if (utf16) byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + title.toByteArray(Charsets.UTF_16BE) else title.toByteArray()
        return W().u16(t.size).bytes(t).bytes(box("encd", W().u32(256).build())).build()
    }

    /**
     * A file with an audio track pointing at a text chapter track. The samples sit in an mdat after moov;
     * [samplesPerChunk] splits them over chunks to exercise stsc, [co64] uses 64-bit chunk offsets.
     */
    private fun quickTimeFile(titles: List<String>, durations: List<Int>, timescale: Int, samplesPerChunk: Int = 1, co64: Boolean = false, utf16: Boolean = false, extraMoov: ByteArray = ByteArray(0)): ByteArray {
        val samples = titles.map { sample(it, utf16) }
        val chunks = samples.chunked(samplesPerChunk)
        fun moov(mdatDataStart: Long): ByteArray {
            var at = mdatDataStart
            val offsets = chunks.map { c -> at.also { at += c.sumOf { it.size } } }
            val stts = fullBox("stts", W().u32(durations.size).apply { durations.forEach { u32(1).u32(it) } }.build())
            val stsz = fullBox("stsz", W().u32(0).u32(samples.size).apply { samples.forEach { u32(it.size) } }.build())
            val stsc = fullBox("stsc", W().u32(1).u32(1).u32(samplesPerChunk).u32(1).build())
            val stco = if (co64) fullBox("co64", W().u32(offsets.size).apply { offsets.forEach { u64(it) } }.build())
            else fullBox("stco", W().u32(offsets.size).apply { offsets.forEach { u32(it) } }.build())
            val textTrack = box("trak", tkhd(2), box("mdia", mdhd(timescale), hdlr("text"), box("minf", box("stbl", fullBox("stsd", W().u32(0).build()), stts, stsz, stsc, stco))))
            return box("moov", audioTrack(1, 2), textTrack, extraMoov)
        }
        val size = ftyp.size + moov(0).size
        val moov = moov(size + 8L)
        val mdat = box("mdat", *samples.toTypedArray())
        return ftyp + moov + mdat
    }

    private fun read(bytes: ByteArray, name: String = "book.m4b") = EmbeddedChapters.read(name, ByteArraySource(bytes))

    // ---------------------------------------------------------------- MP4

    @Test
    fun `nero chapters are read with times in seconds`() {
        val file = ftyp + box("moov", box("udta", chpl(listOf(0L to "Opening", 600_000_000L to "The Middle", 12_345_000_000L to "Ünd")))) + box("mdat", ByteArray(32))
        assertEquals(
            listOf(EmbeddedChapter(0.0, "Opening"), EmbeddedChapter(60.0, "The Middle"), EmbeddedChapter(1234.5, "Ünd")),
            read(file),
        )
    }

    @Test
    fun `version 0 nero chapters are read too`() {
        val file = ftyp + box("moov", box("udta", chpl(listOf(0L to "A", 10_000_000L to "B"), version = 0)))
        assertEquals(listOf(EmbeddedChapter(0.0, "A"), EmbeddedChapter(1.0, "B")), read(file))
    }

    @Test
    fun `a quicktime chapter track gives each sample's start and title`() {
        val file = quickTimeFile(listOf("Prologue", "Part One", "Part Two"), listOf(30_000, 90_000, 45_000), timescale = 1000)
        assertEquals(listOf(EmbeddedChapter(0.0, "Prologue"), EmbeddedChapter(30.0, "Part One"), EmbeddedChapter(120.0, "Part Two")), read(file))
    }

    @Test
    fun `a quicktime chapter track with 64-bit offsets and several samples per chunk is read`() {
        val file = quickTimeFile(listOf("One", "Two", "Three", "Four", "Five"), listOf(600, 600, 300, 300, 600), timescale = 600, samplesPerChunk = 2, co64 = true)
        assertEquals(listOf(0.0, 1.0, 2.0, 2.5, 3.0), read(file).map { it.startSec })
        assertEquals(listOf("One", "Two", "Three", "Four", "Five"), read(file).map { it.title })
    }

    @Test
    fun `utf-16 chapter titles with a byte order mark are decoded`() {
        val file = quickTimeFile(listOf("Kapitel Ä", "第二章"), listOf(10, 10), timescale = 1, utf16 = true)
        assertEquals(listOf("Kapitel Ä", "第二章"), read(file).map { it.title })
    }

    @Test
    fun `the quicktime track wins over nero chapters`() {
        val nero = box("udta", chpl(listOf(0L to "chpl one", 10_000_000L to "chpl two")))
        val file = quickTimeFile(listOf("Track one", "Track two"), listOf(5, 5), timescale = 1, extraMoov = nero)
        assertEquals(listOf("Track one", "Track two"), read(file).map { it.title })
    }

    @Test
    fun `nero chapters are used when the chapter track is broken`() {
        val nero = box("udta", chpl(listOf(0L to "chpl one", 10_000_000L to "chpl two")))
        val brokenTrack = box("trak", tkhd(2), box("mdia", mdhd(1000), hdlr("text")))
        val file = ftyp + box("moov", audioTrack(1, 2), brokenTrack, nero)
        assertEquals(listOf("chpl one", "chpl two"), read(file).map { it.title })
    }

    @Test
    fun `boxes with 64-bit sizes and a last box running to the end are walked`() {
        // moov written with a largesize header; mdat with size 0 ("to the end of the file").
        val moovBody = box("udta", chpl(listOf(0L to "A", 20_000_000L to "B")))
        val file = ftyp + largeBox("moov", moovBody) + W().u32(0).ascii("mdat").bytes(ByteArray(100)).build()
        assertEquals(listOf(EmbeddedChapter(0.0, "A"), EmbeddedChapter(2.0, "B")), read(file))
        val moovLast = ftyp + box("free", ByteArray(4)) + W().u32(0).ascii("moov").bytes(moovBody).build()
        assertEquals(listOf("A", "B"), read(moovLast).map { it.title })
    }

    @Test
    fun `a file without chapters has none`() {
        val file = ftyp + box("moov", audioTrack(1, 9)) + box("mdat", ByteArray(64))
        assertEquals(emptyList<EmbeddedChapter>(), read(file))
    }

    @Test
    fun `garbage, truncated and empty files give no chapters and never throw`() {
        val rnd = Random(42)
        repeat(200) { assertEquals(emptyList<EmbeddedChapter>(), read(rnd.nextBytes(rnd.nextInt(0, 400)))) }
        val good = quickTimeFile(listOf("One", "Two"), listOf(5, 5), timescale = 1)
        for (cut in good.indices) read(good.copyOf(cut)) // must not throw
        // Lies about sizes and counts.
        val hugeCount = ftyp + box("moov", box("udta", fullBox("chpl", W().u32(0).u8(255).u64(0).u8(200).build(), 1)))
        assertEquals(emptyList<EmbeddedChapter>(), read(hugeCount))
        val badSize = ftyp + W().u32(4).ascii("moov").build()
        assertEquals(emptyList<EmbeddedChapter>(), read(badSize))
        assertEquals(emptyList<EmbeddedChapter>(), read(ByteArray(0)))
        // Random bytes flipped inside a real file.
        repeat(300) {
            val bent = good.copyOf()
            repeat(3) { bent[rnd.nextInt(bent.size)] = rnd.nextInt(256).toByte() }
            read(bent)
        }
    }

    @Test
    fun `a file on disk is read through a channel`() {
        val bytes = quickTimeFile(listOf("One", "Two"), listOf(5, 7), timescale = 1)
        val f = File.createTempFile("chapters", ".m4b")
        try {
            f.writeBytes(bytes)
            RandomAccessFile(f, "r").use { raf -> assertEquals(listOf(0.0, 5.0), EmbeddedChapters.read(f.name, FileChannelSource(raf.channel)).map { it.startSec }) }
        } finally {
            f.delete()
        }
    }

    // ---------------------------------------------------------------- ID3 CHAP

    private fun synchsafe(v: Int) = W().u8((v shr 21) and 0x7F).u8((v shr 14) and 0x7F).u8((v shr 7) and 0x7F).u8(v and 0x7F).build()

    private fun frame(id: String, body: ByteArray, major: Int) =
        W().ascii(id).bytes(if (major == 4) synchsafe(body.size) else W().u32(body.size).build()).u16(0).bytes(body).build()

    private fun chap(element: String, startMs: Int, endMs: Int, title: String?, major: Int, encoding: Int = 3): ByteArray {
        val w = W().ascii(element).u8(0).u32(startMs).u32(endMs).u32(-1L and 0xFFFFFFFFL).u32(-1L and 0xFFFFFFFFL)
        if (title != null) {
            val text = when (encoding) {
                1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + title.toByteArray(Charsets.UTF_16LE)
                0 -> title.toByteArray(Charsets.ISO_8859_1)
                else -> title.toByteArray()
            }
            w.bytes(frame("TIT2", byteArrayOf(encoding.toByte()) + text, major))
        }
        return frame("CHAP", w.build(), major)
    }

    private fun id3(major: Int, vararg frames: ByteArray, padding: Int = 64): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(padding)
        return W().ascii("ID3").u8(major).u8(0).u8(0).bytes(synchsafe(body.size)).bytes(body).build() + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    }

    @Test
    fun `id3v2_4 chapter frames give starts and titles in time order`() {
        val tag = id3(
            4,
            frame("TIT2", byteArrayOf(3) + "The Book".toByteArray(), 4),
            chap("ch1", 90_000, 200_000, "Second", 4),
            chap("ch0", 0, 90_000, "First", 4),
            chap("ch2", 200_000, 300_000, null, 4),
        )
        assertEquals(listOf(EmbeddedChapter(0.0, "First"), EmbeddedChapter(90.0, "Second"), EmbeddedChapter(200.0, "")), read(tag, "book.mp3"))
    }

    @Test
    fun `id3v2_3 chapter frames with utf-16 and latin-1 titles are read`() {
        val tag = id3(3, chap("a", 0, 1000, "Café", 3, encoding = 1), chap("b", 1500, 3000, "Señor", 3, encoding = 0))
        assertEquals(listOf(EmbeddedChapter(0.0, "Café"), EmbeddedChapter(1.5, "Señor")), read(tag, "book.mp3"))
    }

    @Test
    fun `an mp3 without chapters or with a broken tag has none`() {
        assertEquals(emptyList<EmbeddedChapter>(), read(id3(4, frame("TIT2", byteArrayOf(3) + "x".toByteArray(), 4)), "a.mp3"))
        assertEquals(emptyList<EmbeddedChapter>(), read(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3), "a.mp3"))
        val broken = id3(4, chap("c", 0, 10, "x", 4)).copyOf(30)
        read(broken, "a.mp3") // must not throw
        val rnd = Random(7)
        repeat(200) { read("ID3".toByteArray() + byteArrayOf(4, 0, 0) + rnd.nextBytes(rnd.nextInt(0, 200)), "a.mp3") }
    }

    // ---------------------------------------------------------------- the book's chapter list

    @Test
    fun `a lone file's own chapters become the book's chapters, ending where the next starts`() {
        val chapters = EmbeddedChapters.timeline(listOf(300.0), listOf("Book"), listOf(listOf(EmbeddedChapter(0.0, "Intro"), EmbeddedChapter(100.0, " "), EmbeddedChapter(250.0, "End"))))
        assertEquals(
            listOf(TimelineChapter(0.0, 100.0, "Intro"), TimelineChapter(100.0, 250.0, "Chapter 2"), TimelineChapter(250.0, 300.0, "End")),
            chapters,
        )
    }

    @Test
    fun `a lone file without chapters of its own still has none`() {
        assertTrue(EmbeddedChapters.timeline(listOf(300.0), listOf("Book"), listOf(emptyList())).isEmpty())
        assertTrue(EmbeddedChapters.timeline(listOf(300.0), listOf("Book"), listOf(listOf(EmbeddedChapter(0.0, "Only")))).isEmpty())
    }

    @Test
    fun `several files with no chapters inside keep one chapter per file`() {
        assertEquals(
            LocalBookNaming.chapters(listOf(10.0, 20.0), listOf("a", "b")),
            EmbeddedChapters.timeline(listOf(10.0, 20.0), listOf("a", "b"), listOf(emptyList(), emptyList())),
        )
    }

    @Test
    fun `chapters inside each file are moved to where that file starts in the book`() {
        val chapters = EmbeddedChapters.timeline(
            listOf(100.0, 50.0, 80.0),
            listOf("Part 1", "Interlude", "Part 2"),
            listOf(
                listOf(EmbeddedChapter(0.0, "1a"), EmbeddedChapter(40.0, "1b")),
                emptyList(),
                listOf(EmbeddedChapter(0.0, "2a"), EmbeddedChapter(30.0, "2b")),
            ),
        )
        assertEquals(
            listOf(
                TimelineChapter(0.0, 40.0, "1a"), TimelineChapter(40.0, 100.0, "1b"),
                TimelineChapter(100.0, 150.0, "Interlude"),
                TimelineChapter(150.0, 180.0, "2a"), TimelineChapter(180.0, 230.0, "2b"),
            ),
            chapters,
        )
    }

    @Test
    fun `chapters past the end, out of order or repeated are tidied`() {
        val chapters = EmbeddedChapters.timeline(
            listOf(100.0), listOf("Book"),
            listOf(listOf(EmbeddedChapter(60.0, "B"), EmbeddedChapter(2.0, "A"), EmbeddedChapter(60.0, "B again"), EmbeddedChapter(150.0, "Beyond"))),
        )
        assertEquals(listOf(TimelineChapter(0.0, 60.0, "A"), TimelineChapter(60.0, 100.0, "B")), chapters)
    }

    @Test
    fun `only mp4 and mp3 files are opened to look for chapters`() {
        assertTrue(EmbeddedChapters.mayHaveChapters("Book.M4B"))
        assertTrue(EmbeddedChapters.mayHaveChapters("x.mp3"))
        assertTrue(!EmbeddedChapters.mayHaveChapters("x.flac"))
    }
}
