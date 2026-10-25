// The requests one hand-over and one look at a speaker are made of. No Android and no state, so it can be
// run against a stand-in speaker in a test.
package im.flume.hearth.core.playback

import im.flume.hearth.core.network.SonosClient
import im.flume.hearth.core.network.SonosPosition
import im.flume.hearth.core.network.SonosTransportState

/** What the speaker said when it was last asked. [volume] is -1 when this round didn't ask for it. */
data class SonosLook(
    val state: SonosTransportState,
    val position: SonosPosition,
    val volume: Int = -1,
)

/** One speaker, driven by [SonosOutput]. Anything that fails throws, and the caller decides what that means. */
internal class SonosSession(private val client: SonosClient, private val uuid: String) {
    /**
     * Empties the speaker's queue, starts the song you were on, then fills the rest in behind it.
     *
     * The order matters more than it looks: every track is its own request, so sending a 200-song queue
     * before pressing play left nineteen seconds of silence after the tap, which reads as nothing having
     * happened at all. The song you are listening to goes over first and starts straight away; the others
     * arrive while it plays. The queue is still the whole point - once the speaker owns it, the phone can
     * lock or leave.
     */
    suspend fun handOver(plan: SonosHandover) {
        client.clearQueue()
        val first = plan.tracks.firstOrNull() ?: return
        client.addToQueue(first.url, first.metadata())
        client.playQueue(uuid)
        if (plan.seekMs > 0) client.seekTo(plan.seekMs)
        for (track in plan.tracks.drop(1)) client.addToQueue(track.url, track.metadata())
    }

    /** Where the speaker is and what it's doing; [withVolume] adds the one extra request for the volume. */
    suspend fun look(withVolume: Boolean): SonosLook = SonosLook(
        state = client.transportState(),
        position = client.position(),
        volume = if (withVolume) client.volume() else -1,
    )

    /** Jumps to a song already on the speaker: [trackNumber] counts from 1. */
    suspend fun playTrack(trackNumber: Int) = client.playTrack(trackNumber)

    suspend fun stop() = client.stop()
}

/** An empty look, for a speaker that hasn't answered yet. */
internal val noLook = SonosLook(SonosTransportState.UNKNOWN, SonosPosition(0, 0, ""))
