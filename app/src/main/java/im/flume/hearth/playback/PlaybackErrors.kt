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

    /** Recovery decision for a failed song; [online] is whether the server is known to be reachable. */
    fun recover(action: ErrorAction, online: Boolean, nextPlayable: Int?): Recovery = when {
        action == ErrorAction.SKIP -> Recovery.SkipNext
        // A "temporary" failure while the server is away is really a connection problem.
        action == ErrorAction.WAIT_FOR_CONNECTION || !online ->
            if (nextPlayable != null) Recovery.JumpTo(nextPlayable) else Recovery.WaitForConnection
        else -> Recovery.RetryLater
    }

    /** First index after [current] (of [count]) that [playable] says can play without the server, or null. */
    fun nextPlayable(current: Int, count: Int, playable: (Int) -> Boolean): Int? =
        (current + 1 until count).firstOrNull(playable)
}

/** What the service does after a failed song (see [PlaybackErrors.recover]). */
sealed interface Recovery {
    /** The song itself is broken: try the next one. */
    data object SkipNext : Recovery

    /** No connection: carry on straight away with the next song that's on the phone (downloaded or fully cached). */
    data class JumpTo(val index: Int) : Recovery

    /** No connection and nothing on the phone left to play: pause, and resume when the server is back. */
    data object WaitForConnection : Recovery

    /** A temporary server problem while still online: keep the song and try again shortly. */
    data object RetryLater : Recovery
}
