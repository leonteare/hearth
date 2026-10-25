// What a Sonos speaker is and the little bits of state it reports: which speaker, playing or paused, and where in the track.
package im.flume.hearth.core.network

/**
 * One speaker found on the network.
 *
 * [uuid] is the speaker's own identifier (`RINCON_7828CA301BFC01400`), which also names its queue.
 * [host] is the base address every control call hangs off (`http://192.168.1.121:1400`), and [ip] is
 * just the address part, for showing the user which box this is.
 */
data class SonosSpeaker(
    val uuid: String,
    val roomName: String,
    val model: String,
    val host: String,
    val ip: String,
)

/** What the speaker says it is doing with its transport. */
enum class SonosTransportState {
    PLAYING,
    PAUSED,
    STOPPED,
    TRANSITIONING,

    /** The speaker said something we don't know about. */
    UNKNOWN,
    ;

    companion object {
        /** Reads the speaker's wording, which calls a pause `PAUSED_PLAYBACK` and an empty queue `NO_MEDIA_PRESENT`. */
        fun from(raw: String?): SonosTransportState = when (raw?.trim()?.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK", "PAUSED" -> PAUSED
            // Nothing loaded plays the same as stopped as far as anyone looking at the app is concerned.
            "STOPPED", "NO_MEDIA_PRESENT" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            else -> UNKNOWN
        }
    }
}

/**
 * Where the speaker is in the current track. [durationMs] is 0 for a live stream, and [trackUri] is
 * what is actually loaded, which lets the phone tell whether the speaker is still on our queue.
 */
data class SonosPosition(
    val elapsedMs: Long,
    val durationMs: Long,
    val trackUri: String,
)

/** The content types Hearth sends to a speaker; the speaker refuses a stream it can't type (see [SonosXml.didl]). */
object SonosMime {
    /** Music from Navidrome, which Hearth asks to be transcoded to mp3. */
    const val MP3 = "audio/mpeg"

    /** Audiobookshelf books, which are usually m4b. */
    const val MP4 = "audio/mp4"
}
