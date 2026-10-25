// Where the sound comes out (this phone or a named Sonos speaker), and the sums behind handing the queue
// to a speaker and taking it back again. No Android, no sockets: plain values in, plain values out.
package im.flume.hearth.core.playback

import im.flume.hearth.core.data.Settings
import im.flume.hearth.core.network.SonosPosition
import im.flume.hearth.core.network.SonosXml

/** Where Hearth is sending sound. */
sealed interface AudioOutput {
    /** This phone: its own speaker, headphones, Bluetooth - whatever Android is playing through. */
    data object Phone : AudioOutput

    /** One Sonos speaker, which fetches the music itself and keeps playing when the phone doesn't. */
    data class Sonos(val uuid: String, val room: String, val host: String) : AudioOutput

    /** The room to show, or null on this phone. */
    val roomName: String? get() = (this as? Sonos)?.room
}

/** The speaker chosen last time the app ran, so a restart doesn't quietly pull the sound back. */
fun Settings.chosenOutput(): AudioOutput =
    if (sonosUuid.isBlank() || sonosHost.isBlank()) AudioOutput.Phone
    else AudioOutput.Sonos(sonosUuid, sonosRoom, sonosHost)

/**
 * One queue entry as a speaker needs it: where to fetch it from and what to call it. [mime] has to match
 * what the server will actually send, or the speaker refuses the track (UPnP error 714).
 */
data class SonosTrack(
    val url: String,
    val mime: String,
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val artUrl: String? = null,
)

/** The DIDL-Lite document that has to travel with [SonosTrack.url]. */
fun SonosTrack.metadata(): String = SonosXml.didl(url, mime, title, artist, album, artUrl)

/**
 * What to send a speaker so it carries on from where the phone was. [queueIndexes] is where each sent
 * track sits in the phone's own queue, so taking the sound back lands on the right song even when a
 * track in the middle couldn't be given a URL.
 */
data class SonosHandover(
    val tracks: List<SonosTrack>,
    val queueIndexes: List<Int>,
    val seekMs: Long,
) {
    val isEmpty: Boolean get() = tracks.isEmpty()
}

/** Where the phone picks up again when the sound comes back to it. */
data class SonosResume(val queueIndex: Int, val positionMs: Long)

/** The decisions behind a hand-over: which tracks, in what order, and where in them. */
object SonosPlan {
    /**
     * Tracks sent in one hand-over. Each one is its own request to the speaker, so a shuffle of the whole
     * library would take minutes to push; two hundred songs is over ten hours of listening.
     */
    const val MAX_TRACKS = 200

    /**
     * The speaker gets the song playing now and everything after it, in queue order. Songs already played
     * are left behind (the speaker starts its queue at the first track), and a track nothing could build a
     * URL for - a book kept only on the phone, say - is skipped rather than ending the hand-over.
     */
    fun handover(
        tracks: List<SonosTrack?>,
        currentIndex: Int,
        positionMs: Long,
        max: Int = MAX_TRACKS,
    ): SonosHandover {
        if (tracks.isEmpty() || max <= 0) return SonosHandover(emptyList(), emptyList(), 0)
        val from = currentIndex.coerceIn(0, tracks.lastIndex)
        val sending = (from..tracks.lastIndex).mapNotNull { i -> tracks[i]?.let { i to it } }.take(max)
        // The speaker starts at the first track it was given, so the position only belongs to it when that
        // really is the song the phone had been playing.
        val seek = if (sending.firstOrNull()?.first == from) positionMs.coerceAtLeast(0) else 0L
        return SonosHandover(sending.map { it.second }, sending.map { it.first }, seek)
    }

    /**
     * Where the phone should carry on from. The speaker says which URL it has loaded, which names one of
     * the tracks we sent it and so a song in the phone's queue. Anything we can't place - the speaker was
     * never given a queue by us, it couldn't be asked, or somebody started something else on it - leaves
     * the phone where it already thought it was.
     */
    fun handBack(
        sent: SonosHandover?,
        where: SonosPosition?,
        phoneIndex: Int,
        phonePositionMs: Long,
    ): SonosResume {
        val at = trackIndex(sent, where?.trackUri)
        if (where == null || at < 0) return SonosResume(phoneIndex.coerceAtLeast(0), phonePositionMs.coerceAtLeast(0))
        return SonosResume(at, where.elapsedMs.coerceAtLeast(0))
    }

    /**
     * Which song in the phone's queue the speaker has loaded, or -1 when it can't be told: we never gave it
     * a queue, it hasn't answered yet, or somebody started something else on it from the Sonos app.
     */
    fun trackIndex(sent: SonosHandover?, trackUri: String?): Int {
        if (sent == null || trackUri.isNullOrBlank()) return -1
        val at = sent.tracks.indexOfFirst { it.url == trackUri }
        return if (at < 0 || at > sent.queueIndexes.lastIndex) -1 else sent.queueIndexes[at]
    }
}

/**
 * What a speaker is doing, laid over the phone's own [PlayerUiState] so the player screens need no new
 * wiring: the queue, titles and covers are still the phone's, while playing, position and volume are the
 * speaker's. [queueIndex] is -1 while the speaker's track can't be placed in our queue.
 */
data class RemoteOutput(
    val output: AudioOutput,
    val isPlaying: Boolean = false,
    val queueIndex: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0..100, or -1 before the speaker has been asked. */
    val volume: Int = -1,
    val error: String? = null,
)

/** The phone's own player state with [remote] laid over it; unchanged when the phone has the sound. */
fun PlayerUiState.withRemote(remote: RemoteOutput?): PlayerUiState {
    if (remote == null) return this
    val index = remote.queueIndex.takeIf { it in queue.indices } ?: currentIndex
    return copy(
        current = queue.getOrNull(index)?.item ?: current,
        currentIndex = index,
        isPlaying = remote.isPlaying,
        isBuffering = false,
        durationMs = remote.durationMs.takeIf { it > 0 } ?: durationMs,
        output = remote.output,
        outputVolume = remote.volume,
        error = remote.error ?: error,
    )
}

/**
 * The transport commands another box answers instead of this phone, so the player screens keep calling
 * [PlayerConnection] and don't need to know where the sound is. Implemented by [SonosOutput].
 */
internal interface RemoteTransport {
    /** False when the sound is on this phone, and every command then stays here. */
    val active: Boolean
    val positionMs: Long
    fun playPause()
    fun pause()
    fun resume()
    fun next()
    fun previous()
    fun seekTo(ms: Long)

    /** The phone was told to play something new: whatever queue it ends up with belongs on the speaker. */
    fun requeue(was: QueueShape)
}

/** Just enough of the queue to tell when the playback service has swapped it for another one. */
data class QueueShape(val size: Int, val currentId: String?) {
    companion object {
        fun of(state: PlayerUiState): QueueShape = QueueShape(state.queue.size, state.current?.mediaId)
    }
}
