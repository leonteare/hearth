package im.flume.hearth.data

import im.flume.hearth.core.network.TidalType
import im.flume.hearth.data.RequestIntent.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.RequestStatus

class RequestIntentTest {
    private fun req(type: String = "track", like: Boolean, toPhone: Boolean, status: RequestStatus = RequestStatus.REQUESTED) = RequestEntity(
        tidalId = "1", type = type, title = "T", artist = "A", coverUuid = null, isrc = null, albumTitle = "Album",
        durationSec = null, requestedAt = 0, requestedBy = "leon", status = status, errorMessage = null, matchedIds = null,
        updatedAt = 0, seenInQueue = false, finishedAt = null, lookupAttempts = 0, like = like, toPhone = toPhone,
    )

    @Test
    fun `asking again adds to what a request wants, never takes away`() {
        val serverOnly = req(like = false, toPhone = false)
        assertEquals(req(like = true, toPhone = false), RequestIntent.merge(serverOnly, like = true, toPhone = false))
        assertEquals(req(like = true, toPhone = true), RequestIntent.merge(req(like = true, toPhone = false), like = false, toPhone = true))
        assertEquals(req(like = true, toPhone = true), RequestIntent.merge(req(like = true, toPhone = true), like = false, toPhone = false))
        assertEquals(serverOnly, RequestIntent.merge(serverOnly, like = false, toPhone = false))
    }

    @Test
    fun `taking back the last wish cancels, otherwise the rest stays`() {
        assertEquals(req(like = false, toPhone = true), RequestIntent.without(req(like = true, toPhone = true), dropLike = true))
        assertEquals(req(like = true, toPhone = false), RequestIntent.without(req(like = true, toPhone = true), dropToPhone = true))
        assertNull(RequestIntent.without(req(like = true, toPhone = false), dropLike = true))
        assertNull(RequestIntent.without(req(like = false, toPhone = true), dropToPhone = true))
    }

    @Test
    fun `arrival does only what was asked`() {
        assertEquals(setOf(Action.LIKE_SONGS), RequestIntent.onArrival(TidalType.TRACK, like = true, toPhone = false))
        assertEquals(setOf(Action.DOWNLOAD_SONGS), RequestIntent.onArrival(TidalType.TRACK, like = false, toPhone = true))
        assertEquals(emptySet<Action>(), RequestIntent.onArrival(TidalType.TRACK, like = false, toPhone = false))
        assertEquals(setOf(Action.LIKE_SONGS, Action.DOWNLOAD_SONGS), RequestIntent.onArrival(TidalType.TRACK, like = true, toPhone = true))
        assertEquals(setOf(Action.SAVE_ALBUM), RequestIntent.onArrival(TidalType.ALBUM, like = true, toPhone = false))
        assertEquals(setOf(Action.DOWNLOAD_ALBUM), RequestIntent.onArrival(TidalType.ALBUM, like = false, toPhone = true))
        assertEquals(emptySet<Action>(), RequestIntent.onArrival(TidalType.ALBUM, like = false, toPhone = false))
        // Artists are followed whatever was asked.
        assertEquals(setOf(Action.FOLLOW_ARTIST), RequestIntent.onArrival(TidalType.ARTIST, like = false, toPhone = false))
    }

    private fun arrival(title: String, like: Boolean, toPhone: Boolean, album: String? = "Album", type: TidalType = TidalType.TRACK) =
        RequestIntent.Arrival(type, title, "A", album, like, toPhone, null, "${type.api}:$title")

    @Test
    fun `notifications say where it went, and group the rest of an album`() {
        val notes = RequestIntent.notifications(
            listOf(
                arrival("One", like = false, toPhone = true),
                arrival("Two", like = true, toPhone = false),
                arrival("Three", like = false, toPhone = false, album = "Solo"),
                arrival("Four", like = false, toPhone = false),
                arrival("Five", like = false, toPhone = false),
                arrival("LP", like = true, toPhone = false, album = null, type = TidalType.ALBUM),
            )
        ).map { it.title }
        assertEquals(
            listOf(
                "Added to your phone: One", "Added to Liked Songs: Two", "Now on your server: Three",
                "Saved to Your Library: LP", "2 songs added to Album",
            ),
            notes,
        )
    }

    @Test
    fun `labels follow what it will do`() {
        assertEquals("Coming to your server", RequestIntent.comingLabel(req(like = false, toPhone = false)))
        assertEquals("Coming to your phone", RequestIntent.comingLabel(req(like = false, toPhone = true)))
        assertEquals("Coming · will be liked", RequestIntent.comingLabel(req(like = true, toPhone = false)))
        assertEquals("Coming · will be saved", RequestIntent.comingLabel(req(type = "album", like = true, toPhone = false)))
        assertEquals("Couldn't get it", RequestIntent.comingLabel(req(like = true, toPhone = false, status = RequestStatus.FAILED)))
        assertNull(RequestIntent.comingLabel(req(like = true, toPhone = false, status = RequestStatus.DONE)))
        assertEquals("Coming to your server · will be liked · will download", RequestIntent.summary(req(like = true, toPhone = true)))
    }
}
