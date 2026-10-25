// Sending the sound to a Sonos speaker instead of this phone: handing the whole queue over, acting as its
// remote control afterwards, and taking the sound back when the user asks or the speaker goes quiet.
package im.flume.hearth.core.playback

import android.content.Context
import im.flume.hearth.core.common.logHandledError
import im.flume.hearth.core.common.userMessage
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.network.SonosClient
import im.flume.hearth.core.network.SonosSpeaker
import im.flume.hearth.core.network.SonosTransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * The one place that knows where the sound is going. While a speaker is chosen it holds the queue and this
 * phone is a remote control: play, pause, skip, seek and volume are sent to the speaker, and what the
 * player screens show comes from asking the speaker what it's doing (see [SonosPolling]).
 *
 * A speaker that is off, moved or on another network is ordinary rather than exceptional, so nothing here
 * throws at its caller: a refusal is shown, and a speaker that can't be reached at all hands the sound
 * back to the phone, which carries on where the speaker had got to (see [SonosFailures]).
 */
class SonosOutput(
    private val context: Context,
    private val session: SessionStore,
    private val http: OkHttpClient,
    private val player: PlayerConnection,
    private val scope: CoroutineScope,
    /** The Audiobookshelf access token, which a speaker needs in the URL because it can't send a header. */
    private val absToken: () -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteTransport {
    private val _output = MutableStateFlow(session.settings.value.chosenOutput())

    /** Where the sound is going. Also in `PlayerUiState.output`, which is what the player screens read. */
    val output: StateFlow<AudioOutput> = _output.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** The last thing that went wrong with the speaker, in plain words. Cleared by [clearError]. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Held while the sound is being handed one way or the other, so two taps can't cross. */
    private val lock = Mutex()
    private var client: SonosClient? = null
    private var clientHost: String? = null

    /** The hand-over the speaker is playing, so its track can be matched back to a song in our queue. */
    private var sent: SonosHandover? = null

    /** Where the phone was when it gave the sound away; where it goes back to if the speaker says nothing. */
    private var handedOverAt: SonosResume? = null

    @Volatile private var look: SonosLook = noLook
    @Volatile private var lookAt: Long = 0
    private var volume: Int = -1
    private var failures = 0

    init {
        // The phone is this speaker's remote from now on; `active` decides where a command goes.
        player.remote = this
        // A restart with a speaker still chosen hands nothing over: the speaker is already playing and the
        // phone simply picks the remote control back up.
        scope.launch { watchWhileWatched() }
    }

    // ------------------------------------------------------------------ choosing where the sound goes

    private val finder = SonosFinder(context, http, scope) { _error.value = it }

    /** What the last search found. Empty until [findSpeakers] has run. */
    val speakers: StateFlow<List<SonosSpeaker>> = finder.speakers

    /** True while a search is running, for a spinner next to the list. */
    val searching: StateFlow<Boolean> = finder.searching

    /** Looks for speakers on this network and fills [speakers]. */
    fun findSpeakers() = finder.search()

    /** Sends the sound to [speaker]: this phone goes quiet and the speaker is given the queue. */
    fun chooseSpeaker(speaker: SonosSpeaker) {
        scope.launch {
            lock.withLock { handOver(AudioOutput.Sonos(speaker.uuid, speaker.roomName, speaker.host)) }
        }
    }

    /**
     * Gives the chosen speaker the queue as it stands now - after the queue has been edited, say. Does
     * nothing while the sound is on this phone.
     */
    fun handOverCurrentQueue() {
        scope.launch {
            lock.withLock { (_output.value as? AudioOutput.Sonos)?.let { forget(); handOver(it) } }
        }
    }

    /** Brings the sound back here, stopping the speaker and carrying on where it had got to. */
    fun backToPhone() {
        scope.launch { lock.withLock { handBack(stopSpeaker = true) } }
    }

    fun clearError() {
        _error.value = null
    }

    /** Gives the queue away. Call with [lock] held. */
    private suspend fun handOver(chosen: AudioOutput.Sonos) {
        val base = session.settings.value.sonosBaseUrl
        val state = player.state.value
        // The phone's own position, not the one a speaker may have been reporting a moment ago. Media3's
        // controller may only be touched from the main thread, and this runs on the app scope.
        val from = withContext(Dispatchers.Main) { player.localPositionMs }
        val plan = SonosPlan.handover(
            state.queue.map { it.item.toSonosTrack(base, session.credentials.value, absToken()) },
            state.currentIndex,
            from,
        )
        handedOverAt = SonosResume(state.currentIndex, from)
        // Quiet here first: two things playing the same song a second apart is worse than a moment's gap.
        player.stopKeepingQueue()
        choose(chosen)
        sent = plan.takeIf { !it.isEmpty }
        if (plan.isEmpty) {
            // Nothing is playing, so there is nothing to push; the speaker takes the next thing tapped.
            publish()
            return
        }
        try {
            sessionFor(chosen).handOver(plan)
            failures = 0
            _error.value = null
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e, handingOver = true)
        }
    }

    /** Takes the sound back. Call with [lock] held. */
    private suspend fun handBack(stopSpeaker: Boolean) {
        val speaker = _output.value as? AudioOutput.Sonos
        var where = look.takeIf { lookAt > 0 }?.position
        if (speaker != null && stopSpeaker) {
            // Both asking where it got to and stopping it can fail; the phone has to carry on regardless.
            runCatching {
                val speaking = sessionFor(speaker)
                where = speaking.look(withVolume = false).position
                speaking.stop()
            }.onFailure { logHandledError(context, "sonos hand back", it) }
        }
        val fallback = handedOverAt ?: SonosResume(player.state.value.currentIndex, 0)
        val resume = SonosPlan.handBack(sent, where, fallback.queueIndex, fallback.positionMs)
        sent = null
        handedOverAt = null
        look = noLook
        lookAt = 0
        volume = -1
        choose(AudioOutput.Phone)
        player.onRemote(null)
        player.resumeAt(resume.queueIndex, resume.positionMs)
    }

    /** Remembers the chosen output, so a restart is still pointed at the speaker. */
    private fun choose(out: AudioOutput) {
        val sonos = out as? AudioOutput.Sonos
        session.updateSettings {
            it.copy(
                sonosUuid = sonos?.uuid.orEmpty(),
                sonosRoom = sonos?.room.orEmpty(),
                sonosHost = sonos?.host.orEmpty(),
            )
        }
        client = null
        clientHost = null
        _output.value = out
    }

    /** Drops what we knew about the speaker, so nothing of the old queue is shown against a new one. */
    private fun forget() {
        sent = null
        look = noLook
        lookAt = 0
        publish()
    }

    /**
     * The phone has just been told to play something new while a speaker holds the sound. The service
     * builds the queue in its own time, so this waits for it to turn up and then hands it over; the phone
     * is stopped again before it has had the chance to make a sound.
     */
    override fun requeue(was: QueueShape) {
        scope.launch {
            lock.withLock {
                val speaker = _output.value as? AudioOutput.Sonos ?: return@withLock
                forget()
                awaitQueue(was)
                handOver(speaker)
            }
        }
    }

    /** Waits for the service to swap the queue, giving up after a moment so a tap is never lost. */
    private suspend fun awaitQueue(was: QueueShape) {
        repeat(QUEUE_TRIES) {
            if (QueueShape.of(player.state.value) != was) return
            delay(QUEUE_WAIT_MS)
        }
    }

    // ------------------------------------------------------------------ asking the speaker

    /**
     * Asks the speaker what it's doing, but only while a speaker holds the sound and a player screen is
     * collecting the state. Nothing watching means nothing to show, and a phone in a pocket must not be
     * woken once a second to find out.
     */
    private suspend fun watchWhileWatched() {
        combine(_output, player.observers) { out, watchers -> out to watchers }
            .collectLatest { (out, watchers) ->
                val speaker = out as? AudioOutput.Sonos
                if (speaker == null || !SonosPolling.shouldPoll(out, watchers)) return@collectLatest
                watch(speaker)
            }
    }

    private suspend fun watch(speaker: AudioOutput.Sonos) {
        var round = 0
        var state = SonosTransportState.UNKNOWN
        while (true) {
            state = try {
                val seen = sessionFor(speaker).look(SonosPolling.volumeDue(round))
                onLook(seen)
                seen.state
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lock.withLock { fail(e, handingOver = false) }
                SonosTransportState.UNKNOWN
            }
            round++
            // A stopped speaker is asked far less often: it has nothing to say until somebody acts.
            delay(SonosPolling.delayMs(state))
        }
    }

    private fun onLook(seen: SonosLook) {
        failures = 0
        look = seen
        lookAt = now()
        if (seen.volume >= 0) volume = seen.volume
        _error.value = null
        publish()
    }

    /** Lays what the speaker is doing over the phone's own player state, for the screens to draw. */
    private fun publish() {
        val out = _output.value
        if (out !is AudioOutput.Sonos) return
        val playing = look.state == SonosTransportState.PLAYING || look.state == SonosTransportState.TRANSITIONING
        player.onRemote(
            RemoteOutput(
                output = out,
                isPlaying = playing,
                queueIndex = SonosPlan.trackIndex(sent, look.position.trackUri),
                positionMs = look.position.elapsedMs,
                durationMs = look.position.durationMs,
                volume = volume,
                error = _error.value,
            ),
        )
    }

    // ------------------------------------------------------------------ the remote control

    override val active: Boolean get() = _output.value is AudioOutput.Sonos

    /** Counted on from the speaker's last answer, so the seek bar moves instead of stepping. */
    override val positionMs: Long
        get() = SonosPolling.positionNow(
            look.position.elapsedMs,
            lookAt,
            now(),
            look.state == SonosTransportState.PLAYING,
            look.position.durationMs,
        )

    override fun playPause() {
        if (look.state == SonosTransportState.PLAYING) pause() else resume()
    }

    override fun pause() = command(SonosTransportState.PAUSED) { it.pause() }

    override fun resume() = command(SonosTransportState.PLAYING) { it.play() }

    override fun next() = command(SonosTransportState.TRANSITIONING) { it.next() }

    override fun previous() = command(SonosTransportState.TRANSITIONING) { it.previous() }

    override fun skipTo(queueIndex: Int): Boolean {
        val number = SonosPlan.trackNumber(sent, queueIndex)
        // The speaker only has the songs from where the hand-over started; anything else stays the phone's job.
        if (number <= 0) return false
        command(SonosTransportState.TRANSITIONING) { it.playTrack(number) }
        return true
    }

    override fun seekTo(ms: Long) {
        val to = ms.coerceAtLeast(0)
        // Show the new place at once; the speaker's next answer confirms or corrects it.
        look = look.copy(position = look.position.copy(elapsedMs = to))
        lookAt = now()
        publish()
        command(null) { it.seekTo(to) }
    }

    /** The speaker's volume as last seen, or -1 when the sound is on this phone or it hasn't answered yet. */
    fun volumeNow(): Int = if (_output.value is AudioOutput.Sonos) volume else -1

    /** The speaker's own volume, 0..100. */
    fun setVolume(level: Int) {
        volume = level.coerceIn(0, 100)
        publish()
        command(null) { it.setVolume(volume) }
    }

    /**
     * Sends one command to the speaker. [expect] is what the speaker will be doing afterwards, shown
     * straight away so a button doesn't wait a second to move.
     */
    private fun command(expect: SonosTransportState?, block: suspend (SonosClient) -> Unit) {
        val speaker = _output.value as? AudioOutput.Sonos ?: return
        scope.launch {
            try {
                block(clientFor(speaker))
                failures = 0
                if (expect != null) {
                    look = look.copy(state = expect)
                    lookAt = now()
                }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lock.withLock { fail(e, handingOver = false) }
            }
        }
    }

    /**
     * What a failure means. Call with [lock] held: falling back to the phone is a hand-back, and two of
     * those at once would fight. A speaker that answered and refused is left alone; one that said nothing
     * often enough gives the sound back, rather than leaving the user with silence and a dead button.
     */
    private suspend fun fail(e: Throwable, handingOver: Boolean) {
        failures++
        val room = (_output.value as? AudioOutput.Sonos)?.room.orEmpty()
        logHandledError(context, "sonos $room", e)
        val message = userMessage(e)
        when (SonosFailures.action(e, failures, handingOver)) {
            SonosFailureAction.SHOW -> {
                _error.value = message
                publish()
            }
            SonosFailureAction.BACK_TO_PHONE -> {
                _error.value = "$message. Playing here instead"
                // It isn't answering, so there is nothing to stop; mid-hand-over it never started either.
                handBack(stopSpeaker = false)
            }
        }
    }

    private fun clientFor(speaker: AudioOutput.Sonos): SonosClient {
        val existing = client
        if (existing != null && clientHost == speaker.host) return existing
        return SonosClient(http, speaker.host).also { client = it; clientHost = speaker.host }
    }

    private fun sessionFor(speaker: AudioOutput.Sonos) = SonosSession(clientFor(speaker), speaker.uuid)

    private companion object {
        /** How long to wait for the service to build a new queue: forty tries of 50 ms, so two seconds. */
        const val QUEUE_TRIES = 40
        const val QUEUE_WAIT_MS = 50L
    }
}
