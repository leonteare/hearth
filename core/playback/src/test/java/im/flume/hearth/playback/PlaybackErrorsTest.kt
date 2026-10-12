package im.flume.hearth.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
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
}
