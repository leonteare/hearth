// When to ask a Sonos speaker what it's doing, how to carry the answer forward between asks, and what to do
// when the speaker doesn't answer at all.
package im.flume.hearth.core.playback

import im.flume.hearth.core.common.SonosUpnpException
import im.flume.hearth.core.network.SonosTransportState

/**
 * A speaker has to be asked; it doesn't tell us. Asking costs a request on the network and a wakeup, so it
 * only happens while a player screen is actually on show, and more slowly when there is nothing to watch.
 */
object SonosPolling {
    /** Watching a song play: often enough that the seek bar and the song title keep up. */
    const val WATCHING_MS = 1_000L

    /** Paused: the position isn't moving, but somebody else may press play on the speaker. */
    const val PAUSED_MS = 2_000L

    /** Nothing loaded, or the speaker isn't making sense: back off rather than hammer it. */
    const val IDLE_MS = 5_000L

    /** Volume is asked for every this-many rounds: the user changes it rarely, the position always moves. */
    const val VOLUME_EVERY = 5

    /** Polling runs only while a speaker holds the sound and something is collecting the player's state. */
    fun shouldPoll(output: AudioOutput, observers: Int): Boolean =
        output is AudioOutput.Sonos && observers > 0

    /** How long to wait before asking again, given what the speaker said last time. */
    fun delayMs(state: SonosTransportState): Long = when (state) {
        SonosTransportState.PLAYING, SonosTransportState.TRANSITIONING -> WATCHING_MS
        SonosTransportState.PAUSED -> PAUSED_MS
        SonosTransportState.STOPPED, SonosTransportState.UNKNOWN -> IDLE_MS
    }

    /** True on the first round and every [VOLUME_EVERY] after it. */
    fun volumeDue(round: Int): Boolean = round % VOLUME_EVERY == 0

    /**
     * Where the speaker is now, counted on from the last answer it gave. Without this the seek bar would
     * jump a second at a time; with it the position runs smoothly and is corrected on every answer.
     */
    fun positionNow(
        lastMs: Long,
        measuredAtMs: Long,
        nowMs: Long,
        playing: Boolean,
        durationMs: Long = 0,
    ): Long {
        val base = lastMs.coerceAtLeast(0)
        val moved = if (playing) base + (nowMs - measuredAtMs).coerceAtLeast(0) else base
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

/** What to do about a speaker that just failed. */
enum class SonosFailureAction {
    /** Tell the user and carry on pointed at the speaker: it answered, it just said no. */
    SHOW,

    /** Give up on the speaker and play on the phone instead. */
    BACK_TO_PHONE,
}

/**
 * A speaker is off, moved, or on another network more often than any of this works perfectly, so a failure
 * is ordinary. A refusal is not the same as silence: a speaker that answered is still there and still ours,
 * while one that can't be reached at all has to hand the sound back before the user is left with nothing.
 */
object SonosFailures {
    /** Missed answers in a row before the sound comes back to the phone. */
    const val STRIKES = 3

    fun action(e: Throwable, consecutiveFailures: Int, handingOver: Boolean): SonosFailureAction = when {
        // The speaker answered and refused (a bad URL, a queue that moved under us): nothing to fall back from.
        e is SonosUpnpException -> SonosFailureAction.SHOW
        // Nothing to carry on with: the phone is silent and the speaker never took the queue.
        handingOver -> SonosFailureAction.BACK_TO_PHONE
        consecutiveFailures >= STRIKES -> SonosFailureAction.BACK_TO_PHONE
        else -> SonosFailureAction.SHOW
    }
}
