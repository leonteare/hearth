// Looking for Sonos speakers on the network, and holding on to what the last search found.
package im.flume.hearth.core.playback

import android.content.Context
import im.flume.hearth.core.common.logHandledError
import im.flume.hearth.core.common.userMessage
import im.flume.hearth.core.network.SonosDiscovery
import im.flume.hearth.core.network.SonosSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The speaker list behind [SonosOutput]. A search shouts on every network interface and waits a couple of
 * seconds for replies, so it is something the user asks for rather than something that runs on its own; a
 * network with no speakers on it simply comes back empty.
 */
internal class SonosFinder(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val _speakers = MutableStateFlow<List<SonosSpeaker>>(emptyList())
    val speakers: StateFlow<List<SonosSpeaker>> = _speakers.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** Searches once; a second ask while one is already running is ignored. */
    fun search() {
        if (_searching.value) return
        scope.launch {
            _searching.value = true
            try {
                _speakers.value = SonosDiscovery(http).search()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logHandledError(context, "sonos search", e)
                onError(userMessage(e))
            } finally {
                _searching.value = false
            }
        }
    }
}
