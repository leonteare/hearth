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
}
