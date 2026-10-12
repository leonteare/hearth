package im.flume.hearth.spoken

import im.flume.hearth.core.network.AbsBookSearchResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import im.flume.hearth.core.data.spoken.LocalBookEntity
import im.flume.hearth.core.data.spoken.LocalBookMerge
import im.flume.hearth.core.data.spoken.LocalMatchState

class BookMatchTest {
    // What the server's audible.uk search answered for "Goldfinch" (lengths in minutes).
    private val goldfinch = listOf(
        BookCandidate("The Secret History", author = "Donna Tartt", durationSec = 1324 * 60.0, asin = "A1", provider = "audible.uk"),
        BookCandidate("The Goldfinch", author = "Donna Tartt", narrator = "David Pittu", durationSec = 1945 * 60.0, asin = "A2", provider = "audible.uk"),
        BookCandidate("Goldfinch", author = "Raven Kennedy", durationSec = 1302 * 60.0, asin = "A3", provider = "audible.uk"),
        BookCandidate("Study Guide: The Goldfinch by Donna Tartt", author = "SuperSummary", durationSec = 135 * 60.0, asin = "A4", provider = "audible.uk"),
    )

    private fun book(
        title: String = "Goldfinch_NTG", author: String? = null, matchState: String? = null, coverPath: String? = null,
    ) = LocalBookEntity(
        id = "phone-1", treeUri = "content://tree", relPath = "Goldfinch_NTG", title = title, author = author, durationSec = 115_200.0,
        sizeBytes = 1, numFiles = 1, coverPath = coverPath, addedAt = 1, scannedAt = 1, hidden = false, serverItemId = null,
        uploadState = null, uploadError = null, fileTitle = "Goldfinch_NTG", fileAuthor = null, matchState = matchState,
    )

    // ---------------------------------------------------------------- names

    @Test
    fun `scene style names lose their release tag`() {
        assertEquals("Goldfinch", BookMatch.cleanName("Goldfinch_NTG"))
        assertEquals("The Goldfinch", BookMatch.cleanName("The.Goldfinch.MP3"))
        assertEquals("Project Hail Mary", BookMatch.cleanName("Project_Hail_Mary_64k_M4B"))
        assertEquals("Dune", BookMatch.cleanName("Dune [Unabridged] (2019)"))
        assertEquals("Dune", BookMatch.cleanName("Dune - Audiobook"))
        assertEquals("Dune", BookMatch.cleanName("Dune Unabridged"))
    }

    @Test
    fun `ordinary titles are left alone`() {
        assertEquals("Stephen King - IT", BookMatch.cleanName("Stephen King - IT"))
        assertEquals("Mr. Mercedes", BookMatch.cleanName("Mr. Mercedes"))
        assertEquals("1984", BookMatch.cleanName("1984"))
        assertEquals("NTG", BookMatch.cleanName("NTG"))
    }

    @Test
    fun `author and title are split when the first part looks like a name`() {
        assertEquals(BookQuery("The Goldfinch", "Donna Tartt"), BookMatch.query("Donna_Tartt_-_The_Goldfinch", null))
        assertEquals(BookQuery("IT", "Stephen King"), BookMatch.query("Stephen King - IT", null))
        // A known author is taken out whichever side it's on.
        assertEquals(BookQuery("Dune", "Frank Herbert"), BookMatch.query("Dune - Frank Herbert", "Frank Herbert"))
        // Not a name: left as it is.
        assertEquals(BookQuery("The Hobbit - Part One", null), BookMatch.query("The Hobbit - Part One", null))
    }

    @Test
    fun `book and part numbers are left out of the search`() {
        assertEquals("Mistborn", BookMatch.query("Mistborn Book 1", null).title)
        assertEquals("The Wheel of Time", BookMatch.query("The Wheel of Time, Vol. 3", null).title)
        assertEquals("Goldfinch", BookMatch.query("Goldfinch_NTG", null).title)
    }

    @Test
    fun `titles compare without case, punctuation or a leading The`() {
        assertEquals(1.0, BookMatch.titleScore("Goldfinch", BookCandidate("The Goldfinch")), 0.0)
        assertEquals(1.0, BookMatch.titleScore("harry potter & the philosopher's stone", BookCandidate("Harry Potter and the Philosopher’s Stone")), 0.0)
        assertEquals(1.0, BookMatch.titleScore("Dune", BookCandidate("Dune: Book One in the Dune Chronicles")), 0.0)
        assertEquals(0.7, BookMatch.titleScore("Goldfinch", BookCandidate("Study Guide: The Goldfinch by Donna Tartt")), 0.0)
        assertEquals(0.0, BookMatch.titleScore("Goldfinch", BookCandidate("The Secret History")), 0.0)
    }

    // ---------------------------------------------------------------- scoring

    @Test
    fun `a 32 hour book is Donna Tartt's The Goldfinch`() {
        val result = BookMatch.rank(BookMatch.query("Goldfinch_NTG", null), 32 * 3600.0, goldfinch)
        assertEquals("The Goldfinch", result.best!!.candidate.title)
        assertEquals("Donna Tartt", result.best!!.candidate.author)
        assertEquals(MatchConfidence.HIGH, result.confidence)
    }

    @Test
    fun `a 21 hour book is Raven Kennedy's Goldfinch`() {
        val result = BookMatch.rank(BookMatch.query("Goldfinch_NTG", null), 21.7 * 3600, goldfinch)
        assertEquals("Goldfinch", result.best!!.candidate.title)
        assertEquals("Raven Kennedy", result.best!!.candidate.author)
        assertEquals(MatchConfidence.HIGH, result.confidence)
    }

    @Test
    fun `the study guide never wins for a long book`() {
        val result = BookMatch.rank(BookMatch.query("Goldfinch", null), 32 * 3600.0, goldfinch)
        assertEquals("Study Guide: The Goldfinch by Donna Tartt", result.ranked.last().candidate.title)
    }

    @Test
    fun `a length far from every result is offered but not applied`() {
        val result = BookMatch.rank(BookMatch.query("Goldfinch", null), 10 * 3600.0, goldfinch)
        assertTrue(result.confidence != MatchConfidence.HIGH)
        assertEquals(goldfinch.size, result.ranked.size)
    }

    @Test
    fun `an unknown length is never applied by itself`() {
        assertEquals(MatchConfidence.LOW, BookMatch.rank(BookMatch.query("Goldfinch", null), 0.0, goldfinch).confidence)
    }

    @Test
    fun `two different books with the same title and length are left for the user`() {
        val twins = listOf(
            BookCandidate("Echo", author = "Alice Smith", durationSec = 600 * 60.0, asin = "E1"),
            BookCandidate("Echo", author = "Bob Jones", durationSec = 605 * 60.0, asin = "E2"),
        )
        assertEquals(MatchConfidence.LOW, BookMatch.rank(BookMatch.query("Echo", null), 600 * 60.0, twins).confidence)
        // The author from the file's tags settles it.
        val withAuthor = BookMatch.rank(BookMatch.query("Echo", "Bob Jones"), 600 * 60.0, twins)
        assertEquals("Bob Jones", withAuthor.best!!.candidate.author)
        assertEquals(MatchConfidence.HIGH, withAuthor.confidence)
    }

    @Test
    fun `nothing related is no match`() {
        val result = BookMatch.rank(BookMatch.query("Goldfinch", null), 1324 * 60.0, goldfinch.take(1))
        assertEquals(MatchConfidence.NONE, result.confidence)
    }

    @Test
    fun `the same edition from both Audible stores counts once`() {
        val both = goldfinch + goldfinch[1].copy(provider = "audible")
        assertEquals(goldfinch.size, BookMatch.rank(BookMatch.query("Goldfinch", null), 32 * 3600.0, both).ranked.size)
    }

    // ---------------------------------------------------------------- server results

    @Test
    fun `search results are read with lengths in minutes and series as a label`() {
        val body = """[
            {"title":"The Goldfinch","author":"Donna Tartt","narrator":"David Pittu","publishedYear":"2013","duration":1945,
             "cover":"https://m.media-amazon.com/images/I/x.jpg","asin":"B00GFCRTPI","descriptionPlain":"A boy in New York.",
             "series":[{"series":"Standalone","sequence":"1"}],"abridged":false,"genres":["Fiction"]},
            {"title":"Goldfinch","author":"Raven Kennedy","publishedYear":2023,"duration":"1302","series":null}
        ]"""
        val parsed = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
            .decodeFromString(ListSerializer(AbsBookSearchResult.serializer()), body).map { it.toCandidate("audible.uk") }
        assertEquals(1945 * 60.0, parsed[0].durationSec, 0.0)
        assertEquals("Standalone, book 1", parsed[0].series)
        assertEquals("A boy in New York.", parsed[0].description)
        assertEquals("2013", parsed[0].publishedYear)
        assertEquals("audible.uk:B00GFCRTPI", parsed[0].source)
        assertFalse(parsed[0].abridged)
        assertEquals("2023", parsed[1].publishedYear)
        assertEquals(1302 * 60.0, parsed[1].durationSec, 0.0)
        assertNull(parsed[1].series)
    }

    // ---------------------------------------------------------------- applying and rescans

    @Test
    fun `applying a match fills the details and remembers where they came from`() {
        val applied = BookMatch.applied(book(), goldfinch[1], LocalMatchState.AUTO, "/covers/phone-1-online-ab.img", 5)
        assertEquals("The Goldfinch", applied.title)
        assertEquals("Donna Tartt", applied.author)
        assertEquals("David Pittu", applied.narrator)
        assertEquals("AUTO", applied.matchState)
        assertEquals("audible.uk:A2", applied.matchSource)
        assertEquals("Goldfinch_NTG", applied.fileTitle)
        assertEquals("Details from Audible", "Details from " + BookMatch.providerName(applied.matchSource))
    }

    @Test
    fun `using the file's own name undoes the match`() {
        val matched = BookMatch.applied(book(), goldfinch[1], LocalMatchState.AUTO, null, 5)
        val declined = BookMatch.declined(matched, null, 9)
        assertEquals("Goldfinch_NTG", declined.title)
        assertNull(declined.author)
        assertNull(declined.narrator)
        assertEquals("DECLINED", declined.matchState)
        assertEquals(9, declined.matchCheckedAt)
    }

    @Test
    fun `an online cover is only used when the book has none of its own`() {
        assertTrue(BookMatch.wantsOnlineCover(null))
        assertTrue(BookMatch.wantsOnlineCover("/data/local-covers/phone-1-online-1f.img"))
        assertFalse(BookMatch.wantsOnlineCover("/data/local-covers/phone-1.img"))
    }

    @Test
    fun `a rescan keeps matched details even when the files changed`() {
        val current = BookMatch.applied(book(), goldfinch[1], LocalMatchState.CONFIRMED, "/c/phone-1-online-ab.img", 5)
        val scanned = book(title = "Goldfinch (new rip)").copy(fileTitle = "Goldfinch (new rip)", coverPath = null, matchState = null)
        val kept = LocalBookMerge.keepUserState(scanned, current)
        assertEquals("The Goldfinch", kept.title)
        assertEquals("Donna Tartt", kept.author)
        assertEquals("David Pittu", kept.narrator)
        assertEquals("CONFIRMED", kept.matchState)
        assertEquals("Goldfinch (new rip)", kept.fileTitle)
        assertEquals("/c/phone-1-online-ab.img", kept.coverPath)
    }

    @Test
    fun `a rescan of an unmatched or declined book takes the files' names, and the files' own cover wins`() {
        val declined = BookMatch.declined(book(), null, 9)
        val scanned = book(title = "Renamed").copy(fileTitle = "Renamed", coverPath = "/c/phone-1.img")
        val kept = LocalBookMerge.keepUserState(scanned, declined)
        assertEquals("Renamed", kept.title)
        assertEquals("DECLINED", kept.matchState)
        assertEquals(9, kept.matchCheckedAt)
        assertEquals("/c/phone-1.img", kept.coverPath)
        // Not removed or uploaded meanwhile, so those stay as they were.
        assertFalse(kept.hidden)
    }

    @Test
    fun `the same edition from the UK and US stores is listed once`() {
        val uk = BookCandidate("The Goldfinch", author = "Donna Tartt", durationSec = 1945 * 60.0, asin = "UK1", provider = "audible.uk")
        val us = uk.copy(asin = "US1", provider = "audible")
        val other = BookCandidate("Goldfinch", author = "Raven Kennedy", durationSec = 1302 * 60.0, asin = "A3", provider = "audible.uk")
        assertEquals(listOf(uk, other), BookMatch.dedupe(listOf(uk, us, other)))
    }
}
