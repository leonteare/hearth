package im.flume.hearth.core.playback

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

    /** Broken songs skipped in a row before stopping, so a queue that's all broken isn't run through to the end. */
    const val MAX_SKIPS_IN_A_ROW = 3

    /** After a broken song, whether to move on to the next: [consecutiveErrors] already counts this one. */
    fun keepSkipping(consecutiveErrors: Int, hasNext: Boolean): Boolean =
        consecutiveErrors < MAX_SKIPS_IN_A_ROW && hasNext

    /**
     * How long to wait before retry number [attempt] (from 0), or null to give up and stay paused. The first
     * wait for a connection starts at once (waiting for the server is the pause); after that each retry
     * backs off further, so a half-working server isn't hammered.
     */
    fun retryDelayMs(
        attempt: Int,
        waitForConnection: Boolean,
        maxAttempts: Int = PlaybackService.MAX_RECOVERY_ATTEMPTS,
    ): Long? = when {
        attempt >= maxAttempts -> null
        attempt > 0 || !waitForConnection -> minOf(60_000L, 3_000L shl attempt)
        else -> 0L
    }

    /**
     * Whether a failure says the music server is down. Offline mode or no network fail on purpose, and
     * Audiobookshelf (spoken word) failing says nothing about Navidrome.
     */
    fun blamesServer(action: ErrorAction, cannotStream: Boolean, spoken: Boolean): Boolean =
        action == ErrorAction.WAIT_FOR_CONNECTION && !cannotStream && !spoken

    /**
     * Offline, and the player moved on by itself to a song that isn't on the phone: go straight to one that
     * is rather than wait for it to fail. A song picked by hand gets its try; audiobook files play in order.
     * [onPhone] is only asked when everything else says skip (it looks at downloads and the cache).
     */
    fun skipAheadOffline(spoken: Boolean, automatic: Boolean, online: Boolean, onPhone: () -> Boolean): Boolean =
        !spoken && automatic && !online && !onPhone()
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
