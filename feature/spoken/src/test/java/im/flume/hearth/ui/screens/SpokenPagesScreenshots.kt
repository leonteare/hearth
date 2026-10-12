package im.flume.hearth.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import im.flume.hearth.core.data.spoken.ChapterEntity
import im.flume.hearth.core.data.spoken.LocalBookEntity
import im.flume.hearth.core.data.spoken.LocalBookFileEntity
import im.flume.hearth.spoken.LocalBookIds
import im.flume.hearth.core.data.spoken.LocalBookProgressEntity
import im.flume.hearth.core.data.spoken.LocalMatchState
import im.flume.hearth.core.data.spoken.SpokenProgressEntity
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** The show page, the server book page, the phone book page and its Find details sheet, from the in-memory database. */
class SpokenPagesScreenshots : ScreenshotTest() {
    private val localId = LocalBookIds.PREFIX + "0011223344"

    private fun localBook(matched: Boolean = false) = LocalBookEntity(
        id = localId, treeUri = "content://tree/books", relPath = "Harbour Lights", title = "Harbour Lights", author = "June Okafor",
        durationSec = 3 * 3_600.0, sizeBytes = 180_000_000, numFiles = 3, coverPath = null, addedAt = Samples.NOW_MS,
        scannedAt = Samples.NOW_MS, hidden = false, serverItemId = null, uploadState = null, uploadError = null,
        fileTitle = "Harbour_Lights_Unabridged", fileAuthor = "June Okafor",
        matchState = if (matched) LocalMatchState.AUTO.name else null, matchSource = if (matched) "audible.uk:B000000001" else null,
    )

    private fun seedLocalBook(matched: Boolean = false) = runBlocking {
        container.db.localBooks().insertBooks(listOf(localBook(matched)))
        container.db.localBooks().insertFiles(
            List(3) { i ->
                LocalBookFileEntity(
                    bookId = localId, fileIndex = i, uri = "content://tree/books/$i", relPath = "Harbour Lights/Part ${i + 1}.mp3",
                    sizeBytes = 60_000_000, lastModified = 0, durationSec = 3_600.0, mimeType = "audio/mpeg", title = "Part ${i + 1}",
                )
            }
        )
    }

    @Test
    fun `a show page with its header, buttons and episodes`() {
        runBlocking {
            val dao = container.db.spoken()
            dao.upsertPodcasts(listOf(Samples.podcast(numEpisodes = 3)))
            dao.upsertEpisodes(
                listOf(
                    Samples.episode(id = "ep1", title = "The town that forgot to sleep", publishedAt = Samples.PUBLISHED_MS),
                    Samples.episode(id = "ep2", title = "A river with two names", publishedAt = Samples.PUBLISHED_MS - 7 * 86_400_000L),
                    Samples.episode(id = "ep3", title = "Letters from the lighthouse", publishedAt = Samples.PUBLISHED_MS - 14 * 86_400_000L),
                )
            )
            dao.upsertProgress(SpokenProgressEntity("pod1", "ep2", 1_260.0, 2_520.0, false, Samples.NOW_MS, null, dirty = false, markedUnplayed = false))
            dao.upsertProgress(SpokenProgressEntity("pod1", "ep3", 2_520.0, 2_520.0, true, Samples.NOW_MS, Samples.NOW_MS, dirty = false, markedUnplayed = false))
        }
        snapScreen("show_page") { Settled { PodcastScreen("pod1") } }
    }

    @Test
    fun `a book page half way through, with its chapters`() {
        runBlocking {
            val dao = container.db.spoken()
            dao.upsertBooks(listOf(Samples.book()))
            dao.insertChapters(
                listOf("Arrival", "The keeper's log", "Storm season", "A light in the fog", "Low tide", "Departure").mapIndexed { i, t ->
                    ChapterEntity("bk1", "", i, i * 6_000.0, (i + 1) * 6_000.0, t)
                }
            )
            dao.upsertProgress(SpokenProgressEntity("bk1", "", 14_000.0, 36_000.0, false, Samples.NOW_MS, null, dirty = false, markedUnplayed = false))
        }
        snapScreen("book_page") { Settled { BookScreen("bk1") } }
    }

    @Test
    fun `a phone book page with chapters from its files`() {
        seedLocalBook()
        runBlocking {
            container.db.localBooks().upsertProgress(LocalBookProgressEntity(localId, 4_000.0, 3 * 3_600.0, false, null, Samples.NOW_MS))
        }
        snapScreen("local_book_page") { Settled { BookScreen(localId) } }
    }

    @Test
    fun `find details, signed in, with the results found earlier`() {
        seedLocalBook(matched = true)
        // As LocalBookDetails saves a search, so the sheet opens on it without asking the server.
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "local-book-matches").apply { mkdirs() }
            .resolve("$localId.json").writeText(
                """{"title":"Harbour Lights","candidates":[
                  {"title":"Harbour Lights","author":"June Okafor","narrator":"Sam Rowe","publishedYear":"2020","durationSec":10800.0,"asin":"B000000001","provider":"audible.uk"},
                  {"title":"Harbour Lights: A Novel","author":"J. Okafor","publishedYear":"2021","durationSec":11400.0,"asin":"B000000002","provider":"audible.uk"},
                  {"title":"The Harbour","author":"Peter Lane","durationSec":7200.0,"asin":"B000000003","provider":"audible"}
                ]}""".trimIndent()
            )
        // Read it once here, so the sheet's own read (classes loaded, file parsed) lands within the first frames.
        check(runBlocking { container.spoken.local.details.saved(localId) }?.candidates?.size == 3)
        snapScreen("find_details") { Settled { FindDetailsSheet(true, localBook(matched = true), signedIn = true, onDismiss = {}) } }
    }

    @Test
    fun `find details when signed out`() {
        snapScreen("find_details_signed_out") { FindDetailsSheet(true, localBook(), signedIn = false, onDismiss = {}) }
    }
}

/**
 * These pages read Room flows and files on background threads, one after another (the show, then its
 * episodes). The test clock runs in no real time, so each early frame waits a little real time for that
 * work to land, and the shot shows the loaded page every run.
 */
@Composable
private fun Settled(content: @Composable () -> Unit) {
    LaunchedEffect(Unit) {
        repeat(40) {
            withFrameNanos { }
            Thread.sleep(25)
        }
    }
    content()
}
