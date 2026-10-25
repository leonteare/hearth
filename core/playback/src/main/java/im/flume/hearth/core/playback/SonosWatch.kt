// Asking a speaker what it is doing, on a loop, but only while somebody is looking at the answer.
package im.flume.hearth.core.playback

import im.flume.hearth.core.network.SonosTransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The polling behind [SonosOutput], kept here so that class stays about where the sound is going.
 *
 * It runs only while a speaker holds the sound *and* a player screen is collecting the state: nothing
 * watching means nothing to show, and a phone in a pocket must not be woken once a second to find out.
 */
internal class SonosWatch(
    private val scope: CoroutineScope,
    private val output: StateFlow<AudioOutput>,
    private val observers: StateFlow<Int>,
    /** Asks the speaker; [withVolume] costs one extra request, so it isn't asked every round. */
    private val look: suspend (AudioOutput.Sonos, Boolean) -> SonosLook,
    private val onSeen: (SonosLook) -> Unit,
    private val onFailed: suspend (Exception) -> Unit,
) {
    fun start() {
        scope.launch {
            combine(output, observers) { out, watchers -> out to watchers }
                .collectLatest { (out, watchers) ->
                    val speaker = out as? AudioOutput.Sonos
                    if (speaker == null || !SonosPolling.shouldPoll(out, watchers)) return@collectLatest
                    watch(speaker)
                }
        }
    }

    private suspend fun watch(speaker: AudioOutput.Sonos) {
        var round = 0
        var state = SonosTransportState.UNKNOWN
        while (true) {
            state = try {
                val seen = look(speaker, SonosPolling.volumeDue(round))
                onSeen(seen)
                seen.state
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed(e)
                SonosTransportState.UNKNOWN
            }
            round++
            // A stopped speaker is asked far less often: it has nothing to say until somebody acts.
            delay(SonosPolling.delayMs(state))
        }
    }
}
