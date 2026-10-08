package im.flume.hearth.playback

import androidx.media3.common.PlaybackException

/** What to do when a song fails to play. */
enum class ErrorAction {
    /** This song can't be played (gone from the server, unreadable file): move on to the next one. */
    SKIP,

    /** Couldn't reach the server at all: hold position, mark offline and resume when it's back. */
    WAIT_FOR_CONNECTION,

    /** Something temporary (server hiccup, dropped mid-read): hold position and try again shortly. */
    RETRY,
}

object PlaybackErrors {
    /** [httpStatus] is the response code when the error was a bad HTTP status, otherwise null. */
    fun classify(errorCode: Int, httpStatus: Int?): ErrorAction = when {
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> ErrorAction.WAIT_FOR_CONNECTION
        errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            if (httpStatus == 404 || httpStatus == 410) ErrorAction.SKIP else ErrorAction.RETRY
        errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> ErrorAction.SKIP
        // 3xxx: parsing (container malformed/unsupported), 4xxx: decoder and format problems.
        errorCode in 3000..4999 -> ErrorAction.SKIP
        else -> ErrorAction.RETRY
    }
}
