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
     * Empties the speaker's queue, fills it with [plan] in order, starts it and jumps to where the phone
     * had got to. The queue is the whole point: once the speaker owns it, the phone can lock or leave.
     */
    suspend fun handOver(plan: SonosHandover) {
        client.clearQueue()
        for (track in plan.tracks) client.addToQueue(track.url, track.metadata())
        client.playQueue(uuid)
        if (plan.seekMs > 0) client.seekTo(plan.seekMs)
    }

    /** Where the speaker is and what it's doing; [withVolume] adds the one extra request for the volume. */
    suspend fun look(withVolume: Boolean): SonosLook = SonosLook(
        state = client.transportState(),
        position = client.position(),
        volume = if (withVolume) client.volume() else -1,
    )

    suspend fun stop() = client.stop()
}

/** An empty look, for a speaker that hasn't answered yet. */
internal val noLook = SonosLook(SonosTransportState.UNKNOWN, SonosPosition(0, 0, ""))
