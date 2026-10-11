package im.flume.hearth.spoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBookLogicTest {
    private fun f(path: String, size: Long = 100, modified: Long = 1) = LocalFileEntry(path, "content://x/$path", size, modified)

    // ---------------------------------------------------------------- grouping and order

    @Test
    fun `each folder of audio is one book with its files in natural order`() {
        val books = LocalBookScan.group(
            listOf(f("Dune/Chapter 10.mp3"), f("Dune/Chapter 2.mp3"), f("Dune/chapter 1.mp3"), f("Dune/notes.txt"), f("Dune/Chapter 11.mp3"))
        )
        assertEquals(1, books.size)
        assertEquals("Dune", books[0].key)
        assertEquals("Dune", books[0].name)
        assertEquals(listOf("chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3", "Chapter 11.mp3"), books[0].files.map { it.name })
    }

    @Test
    fun `a lone audio file at the top is a book of its own`() {
        val books = LocalBookScan.group(listOf(f("Project Hail Mary.m4b"), f("Another One.MP3"), f("cover.jpg")))
        assertEquals(listOf("Another One", "Project Hail Mary"), books.map { it.name })
        assertTrue(books.all { it.files.size == 1 && it.cover == null })
    }

    @Test
    fun `author folders hold books, and disc folders join their book`() {
        val books = LocalBookScan.group(
            listOf(
                f("Tolkien/The Hobbit/CD 2/01.mp3"), f("Tolkien/The Hobbit/CD 1/02.mp3"), f("Tolkien/The Hobbit/CD 1/01.mp3"),
                f("Tolkien/The Hobbit/CD 10/01.mp3"), f("Pratchett/Mort/Mort.m4b"),
            )
        )
        assertEquals(listOf("Pratchett/Mort", "Tolkien/The Hobbit"), books.map { it.key })
        assertEquals(
            listOf("CD 1/01.mp3", "CD 1/02.mp3", "CD 2/01.mp3", "CD 10/01.mp3"),
            books[1].files.map { it.relPath.removePrefix("Tolkien/The Hobbit/") },
        )
    }

    @Test
    fun `only the listed audio types count, and hidden files are skipped`() {
        val types = listOf("m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac").map { f("B/a.$it") }
        val books = LocalBookScan.group(types + listOf(f("B/a.wav"), f("B/.trashed-a.mp3"), f("B/a.pdf")))
        assertEquals(7, books.single().files.size)
    }

    @Test
    fun `the cover is cover or folder art in the book's folder, then any image there`() {
        assertEquals("Cover.png", LocalBookScan.group(listOf(f("B/1.mp3"), f("B/folder.jpg"), f("B/Cover.png"), f("B/zz.jpg"))).single().cover?.name)
        assertEquals("cover.jpg", LocalBookScan.group(listOf(f("B/1.mp3"), f("B/cover.png"), f("B/cover.jpg"))).single().cover?.name)
        assertEquals("folder.jpg", LocalBookScan.group(listOf(f("B/1.mp3"), f("B/folder.jpg"), f("B/back.jpg"))).single().cover?.name)
        assertEquals("art 1.jpeg", LocalBookScan.group(listOf(f("B/1.mp3"), f("B/art 10.jpeg"), f("B/art 1.jpeg"))).single().cover?.name)
        assertNull(LocalBookScan.group(listOf(f("B/1.mp3"), f("Other/cover.jpg"))).single().cover)
    }

    @Test
    fun `natural order compares numbers as numbers and ignores case`() {
        val sorted = listOf("Part 10", "part 2", "Part 1", "Part 02b", "Part 002a", "Intro").sortedWith(LocalBookScan::naturalCompare)
        assertEquals(listOf("Intro", "Part 1", "part 2", "Part 002a", "Part 02b", "Part 10"), sorted)
        assertTrue(LocalBookScan.naturalCompare("1", "01") != 0) // still a total order
    }

    // ---------------------------------------------------------------- naming and chapters

    @Test
    fun `a folder book is named by its album tag, falling back to the folder name`() {
        val g = LocalBookScan.group(listOf(f("Some Folder/01.mp3"), f("Some Folder/02.mp3"))).single()
        assertEquals("Dune" to "Frank Herbert", LocalBookNaming.describe(g, LocalTags(title = "Chapter 1", album = "Dune", artist = "Frank Herbert")))
        assertEquals("Some Folder" to null, LocalBookNaming.describe(g, LocalTags(title = "Chapter 1")))
        assertEquals("Some Folder" to null, LocalBookNaming.describe(g, null))
    }

    @Test
    fun `a lone file is named by its title, and album artist beats artist`() {
        val g = LocalBookScan.group(listOf(f("mort_v2.m4b"))).single()
        assertEquals("Mort" to "Terry Pratchett", LocalBookNaming.describe(g, LocalTags(title = "Mort", album = "Discworld", artist = "Narrator", albumArtist = "Terry Pratchett")))
        assertEquals("mort_v2" to null, LocalBookNaming.describe(g, LocalTags(title = "  ")))
    }

    @Test
    fun `chapters are one per file, named by their tags when those differ`() {
        assertEquals(listOf("One", "Two"), LocalBookNaming.chapterTitles(listOf("01.mp3", "02.mp3"), listOf("One", "Two")))
        assertEquals(listOf("01 Start", "02 Middle"), LocalBookNaming.chapterTitles(listOf("01 Start.mp3", "02 Middle.mp3"), listOf("Book", "Book")))
        assertEquals(listOf("01", "02"), LocalBookNaming.chapterTitles(listOf("01.mp3", "02.mp3"), listOf("One", null)))
        val ch = LocalBookNaming.chapters(listOf(60.0, 30.0, 10.0), listOf("a", "b", "c"))
        assertEquals(listOf(0.0 to 60.0, 60.0 to 90.0, 90.0 to 100.0), ch.map { it.startSec to it.endSec })
        assertTrue(LocalBookNaming.chapters(listOf(3600.0), listOf("only")).isEmpty())
    }

    @Test
    fun `book ids are stable, distinct and marked as phone books`() {
        val a = LocalBookIds.idFor("content://tree/1", "Dune")
        assertEquals(a, LocalBookIds.idFor("content://tree/1", "Dune"))
        assertNotEquals(a, LocalBookIds.idFor("content://tree/1", "Mort"))
        assertNotEquals(a, LocalBookIds.idFor("content://tree/2", "Dune"))
        assertTrue(LocalBookIds.isLocal(a))
        assertFalse(LocalBookIds.isLocal("a4c1b7e2-5d1f-4c34-9a3f-2b7f0c1d9e88"))
        assertFalse(LocalBookIds.isLocal(null))
    }

    // ---------------------------------------------------------------- finding the uploaded book

    @Test
    fun `the uploaded book is found by the folder the server put it in`() {
        val candidates = listOf(
            ServerBookRef("old", "Dune", "Frank Herbert", "/audiobooks/Frank Herbert/Dune (1965)", 1),
            ServerBookRef("new", "Dune: Book One", "Herbert", "/audiobooks/Frank Herbert/Dune", 2),
        )
        assertEquals("new", UploadMatch.find("Dune", "Frank Herbert", candidates)?.id)
        // The server cleans the folder name: "Mort: A Novel" goes in "Mort - A Novel".
        val cleaned = listOf(ServerBookRef("m", "Mort", null, "C:\\books\\Terry Pratchett\\Mort - A Novel\\", 5))
        assertEquals("m", UploadMatch.find("Mort: A Novel", "Terry Pratchett", cleaned)?.id)
        assertEquals("Terry Pratchett/Mort - A Novel", UploadMatch.expectedFolder("Mort: A Novel", "Terry Pratchett"))
    }

    @Test
    fun `without a folder match, title and author have to agree`() {
        val candidates = listOf(
            ServerBookRef("a", "The Hobbit", "J. R. R. Tolkien", "/x/1", 1),
            ServerBookRef("b", "the hobbit!", "Someone Else", "/x/2", 2),
        )
        assertEquals("a", UploadMatch.find("The Hobbit", "j r r tolkien", candidates)?.id)
        assertNull(UploadMatch.find("The Hobbit", "Nobody", candidates))
        assertNull(UploadMatch.find("Unknown", "J. R. R. Tolkien", candidates))
    }

    @Test
    fun `without an author, a title only counts when one book has it`() {
        val one = listOf(ServerBookRef("a", "Mort", "Pratchett", "/x/1"))
        assertEquals("a", UploadMatch.find("Mort", null, one)?.id)
        assertNull(UploadMatch.find("Mort", null, one + ServerBookRef("b", "MORT", "Other", "/x/2")))
        // The folder still finds it: with no author the upload goes straight under the library folder.
        assertEquals("c", UploadMatch.find("Mort", null, one + ServerBookRef("c", "Mort", null, "/books/Mort")).let { it?.id })
    }

    // ---------------------------------------------------------------- carrying progress over

    private fun p(pos: Double, at: Long, finished: Boolean = false, dur: Double = 1000.0) = ProgressSnapshot(pos, dur, finished, at)

    @Test
    fun `listening on the phone carries over to a server copy nobody has started`() {
        assertEquals(p(300.0, 50, dur = 990.0), ProgressCarry.decide(p(300.0, 50), null, 990.0))
        assertEquals(true, ProgressCarry.decide(p(0.0, 50, finished = true), null, 1000.0)?.isFinished)
    }

    @Test
    fun `nothing is carried over when nothing was heard or the server is newer`() {
        assertNull(ProgressCarry.decide(null, null, 1000.0))
        assertNull(ProgressCarry.decide(p(0.4, 50), null, 1000.0))
        assertNull(ProgressCarry.decide(p(300.0, 50), p(10.0, 60), 1000.0))
        assertNull(ProgressCarry.decide(p(300.0, 50), p(300.5, 40), 1000.0))
    }

    @Test
    fun `newer phone progress replaces older server progress, kept within the server's length`() {
        assertEquals(p(500.0, 70, dur = 900.0).currentTime, ProgressCarry.decide(p(500.0, 70), p(10.0, 60), 900.0)?.currentTime)
        assertEquals(900.0, ProgressCarry.decide(p(950.0, 70), null, 900.0)?.currentTime)
        assertEquals(1000.0, ProgressCarry.decide(p(500.0, 70), null, 0.0)?.duration)
    }

    @Test
    fun `a scan explains why nothing turned up`() {
        assertTrue(LocalBookScan.scanProblem("Books", 0, emptyList())!!.startsWith("No audiobook files in \"Books\""))
        assertEquals(
            "Found 2 audio files in \"Books\" but couldn't read any of them (for example \"a.m4b\"). They may still be downloading, or be damaged.",
            LocalBookScan.scanProblem("Books", 2, listOf("a.m4b", "b.m4b")),
        )
        assertEquals(
            "Couldn't read 1 of 3 audio files in \"Books\" (for example \"a.m4b\"). The rest are in your library.",
            LocalBookScan.scanProblem("Books", 3, listOf("a.m4b")),
        )
        assertNull(LocalBookScan.scanProblem("Books", 3, emptyList()))
    }
}
