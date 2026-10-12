package im.flume.hearth.core.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorsTest {
    private fun classify(code: Int, status: Int? = null) = PlaybackErrors.classify(code, status)

    @Test
    fun `missing songs are skipped`() {
        assertEquals(ErrorAction.SKIP, classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(ErrorAction.SKIP, classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 410))
        assertEquals(ErrorAction.SKIP, classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun `unplayable files are skipped`() {
        assertEquals(ErrorAction.SKIP, classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(ErrorAction.SKIP, classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }

    @Test
    fun `connection failures wait for the server`() {
        assertEquals(ErrorAction.WAIT_FOR_CONNECTION, classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(ErrorAction.WAIT_FOR_CONNECTION, classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test
    fun `other failures keep the song and retry`() {
        assertEquals(ErrorAction.RETRY, classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
        assertEquals(ErrorAction.RETRY, classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    private val conn = ErrorAction.WAIT_FOR_CONNECTION

    @Test
    fun `connection error jumps to the next song on the phone`() {
        val downloaded = setOf(4, 7)
        val next = PlaybackErrors.nextPlayable(2, 10) { it in downloaded }
        assertEquals(4, next)
        assertEquals(Recovery.JumpTo(4), PlaybackErrors.recover(conn, online = false, nextPlayable = next))
    }

    @Test
    fun `connection error with nothing on the phone waits for the server`() {
        val next = PlaybackErrors.nextPlayable(2, 10) { false }
        assertEquals(null, next)
        assertEquals(Recovery.WaitForConnection, PlaybackErrors.recover(conn, online = false, nextPlayable = next))
    }

    @Test
    fun `only songs after the current one count`() {
        assertEquals(null, PlaybackErrors.nextPlayable(5, 6) { it <= 5 })
        assertEquals(null, PlaybackErrors.nextPlayable(0, 0) { true })
    }

    @Test
    fun `broken songs are skipped whatever the connection`() {
        assertEquals(Recovery.SkipNext, PlaybackErrors.recover(ErrorAction.SKIP, online = false, nextPlayable = 3))
        assertEquals(Recovery.SkipNext, PlaybackErrors.recover(ErrorAction.SKIP, online = true, nextPlayable = null))
    }

    @Test
    fun `temporary errors retry while online but move on while offline`() {
        assertEquals(Recovery.RetryLater, PlaybackErrors.recover(ErrorAction.RETRY, online = true, nextPlayable = 3))
        assertEquals(Recovery.JumpTo(3), PlaybackErrors.recover(ErrorAction.RETRY, online = false, nextPlayable = 3))
    }

    @Test
    fun `offline mode failures are connection failures`() {
        val code = SongDataSource.OfflineException("x").reason
        assertEquals(ErrorAction.WAIT_FOR_CONNECTION, classify(code))
    }

    @Test
    fun `a broken song moves on to the next one`() {
        assertTrue(PlaybackErrors.keepSkipping(consecutiveErrors = 1, hasNext = true))
        assertTrue(PlaybackErrors.keepSkipping(consecutiveErrors = 2, hasNext = true))
    }

    @Test
    fun `three broken songs in a row stop the skipping`() {
        assertFalse(PlaybackErrors.keepSkipping(consecutiveErrors = 3, hasNext = true))
        assertFalse(PlaybackErrors.keepSkipping(consecutiveErrors = 10, hasNext = true))
    }

    @Test
    fun `a broken last song has nothing to skip to`() {
        assertFalse(PlaybackErrors.keepSkipping(consecutiveErrors = 1, hasNext = false))
    }

    @Test
    fun `the first wait for a connection starts at once`() {
        assertEquals(0L, PlaybackErrors.retryDelayMs(attempt = 0, waitForConnection = true))
    }

    @Test
    fun `the first retry of a server hiccup waits three seconds`() {
        assertEquals(3_000L, PlaybackErrors.retryDelayMs(attempt = 0, waitForConnection = false))
    }

    @Test
    fun `retries back off by doubling up to a minute`() {
        val waits = (0 until 6).map { PlaybackErrors.retryDelayMs(it, waitForConnection = false) }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L), waits)
        assertEquals(6_000L, PlaybackErrors.retryDelayMs(attempt = 1, waitForConnection = true))
    }

    @Test
    fun `after six tries recovery gives up and stays paused`() {
        assertNull(PlaybackErrors.retryDelayMs(attempt = 6, waitForConnection = false))
        assertNull(PlaybackErrors.retryDelayMs(attempt = 6, waitForConnection = true))
        assertNull(PlaybackErrors.retryDelayMs(attempt = 2, waitForConnection = true, maxAttempts = 2))
    }

    @Test
    fun `a failed connection to the music server marks it down`() {
        assertTrue(PlaybackErrors.blamesServer(conn, cannotStream = false, spoken = false))
    }

    @Test
    fun `failures while streaming is off or for spoken word say nothing about the server`() {
        assertFalse(PlaybackErrors.blamesServer(conn, cannotStream = true, spoken = false))
        assertFalse(PlaybackErrors.blamesServer(conn, cannotStream = false, spoken = true))
    }

    @Test
    fun `broken songs and hiccups don't mark the server down`() {
        assertFalse(PlaybackErrors.blamesServer(ErrorAction.SKIP, cannotStream = false, spoken = false))
        assertFalse(PlaybackErrors.blamesServer(ErrorAction.RETRY, cannotStream = false, spoken = false))
    }

    @Test
    fun `offline the player skips past a song it moved on to that isn't on the phone`() {
        assertTrue(PlaybackErrors.skipAheadOffline(spoken = false, automatic = true, online = false) { false })
    }

    @Test
    fun `offline a song that is on the phone just plays`() {
        assertFalse(PlaybackErrors.skipAheadOffline(spoken = false, automatic = true, online = false) { true })
    }

    @Test
    fun `a song picked by hand or played online gets its try`() {
        assertFalse(PlaybackErrors.skipAheadOffline(spoken = false, automatic = false, online = false) { false })
        assertFalse(PlaybackErrors.skipAheadOffline(spoken = false, automatic = true, online = true) { false })
    }

    @Test
    fun `audiobook files are never skipped offline`() {
        assertFalse(PlaybackErrors.skipAheadOffline(spoken = true, automatic = true, online = false) { false })
    }

    @Test
    fun `the phone is only checked when nothing else rules a skip out`() {
        var asked = 0
        PlaybackErrors.skipAheadOffline(spoken = false, automatic = true, online = true) { asked++; false }
        PlaybackErrors.skipAheadOffline(spoken = true, automatic = true, online = false) { asked++; false }
        assertEquals(0, asked)
    }
}
