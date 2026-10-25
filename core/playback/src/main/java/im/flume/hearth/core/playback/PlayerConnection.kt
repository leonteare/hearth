package im.flume.hearth.core.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import im.flume.hearth.domain.music.PlaySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueEntry(val index: Int, val item: MediaItem) {
    /** Stable for this entry while it's in the queue, even as songs before it are trimmed or moved. */
    val key: String get() = item.queueKey ?: "$index:${item.mediaId}"
}

data class PlayerUiState(
    val current: MediaItem? = null,
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val sourceLabel: String = "",
    val queue: List<QueueEntry> = emptyList(),
    val pendingCount: Int = 0,
    /** Epoch ms when the sleep timer pauses playback, -1 for end of song, -2 for end of chapter, 0 for none. */
    val sleepAt: Long = 0,
    val error: String? = null,
    /** Set while a podcast episode or audiobook is loaded instead of music. */
    val spoken: SpokenNowPlaying? = null,
    /** Where the sound is coming out: this phone unless a Sonos speaker was chosen. */
    val output: AudioOutput = AudioOutput.Phone,
    /** The chosen speaker's volume, 0..100, or -1 on this phone, where Android owns the volume. */
    val outputVolume: Int = -1,
)

/**
 * The episode or book in the player. Positions are on its whole timeline (a book's files back to back):
 * use [PlayerConnection.spokenPositionMs] and [PlayerConnection.seekSpoken], not the per-file ones.
 */
data class SpokenNowPlaying(
    val libraryItemId: String,
    /** Null for an audiobook. */
    val episodeId: String?,
    val durationMs: Long,
    val speed: Float,
    /** The music queue from before can be brought back with [PlayerConnection.backToMusic]. */
    val canGoBackToMusic: Boolean,
) {
    val isBook: Boolean get() = episodeId == null
}

/**
 * The UI's handle on the playback service. All heavy queue work happens in the service. Queue edits
 * name entries by [QueueEntry.key] + song id, and the service finds them when it runs the command,
 * so trimming or reordering in between can't make an edit hit the wrong song.
 */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Set by [SonosOutput]: while it is active, transport commands and the position come from a speaker. */
    internal var remote: RemoteTransport? = null

    /** What the speaker is doing, laid over the phone's state in [refresh]; null when the phone has the sound. */
    @Volatile private var remoteNow: RemoteOutput? = null

    private val _playing = MutableStateFlow(false)

    /**
     * Just "is something playing", for listeners that run all the time. Collect this and not [state]:
     * [observers] counts whoever is collecting [state] to decide whether a speaker is worth asking, and a
     * listener that never lets go would have a phone in a pocket polling a speaker all day.
     */
    internal val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /**
     * How many screens are collecting [state]. The player screens use `collectAsStateWithLifecycle`, so
     * this falls to zero when the app goes to the background - which is when a speaker stops being asked.
     */
    internal val observers: StateFlow<Int> get() = _state.subscriptionCount

    val positionMs: Long get() = speaking()?.positionMs ?: localPositionMs

    /** Where this phone's own player is, whatever a speaker might be doing. */
    internal val localPositionMs: Long get() = controller?.currentPosition ?: 0

    /** The speaker holding the sound, or null when that is this phone. */
    private fun speaking(): RemoteTransport? = remote?.takeIf { it.active }

    /** Runs [onSpeaker] in place of the phone when a speaker has the sound; true when it did. */
    private inline fun toSpeaker(onSpeaker: RemoteTransport.() -> Unit): Boolean {
        val r = speaking() ?: return false
        r.onSpeaker()
        return true
    }

    /** Called by [SonosOutput] when the speaker has something new to say (or has handed the sound back). */
    internal fun onRemote(now: RemoteOutput?) {
        remoteNow = now
        main.post { refresh() }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = refresh()
            })
            .buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run { future = null; return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    private fun refresh() {
        val c = controller ?: run {
            // Not connected to the service (nothing has played yet): there is still a speaker to show.
            _state.value = PlayerUiState().withRemote(remoteNow)
            _playing.value = _state.value.isPlaying
            return
        }
        val extras = c.sessionExtras
        val count = c.mediaItemCount
        _state.value = PlayerUiState(
            current = c.currentMediaItem,
            currentIndex = c.currentMediaItemIndex,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it > 0 } ?: (c.currentMediaItem?.mediaMetadata?.durationMs ?: 0),
            repeatMode = c.repeatMode,
            shuffle = extras.getBoolean(PlaybackService.EXTRA_SHUFFLE),
            sourceLabel = extras.getString(PlaybackService.EXTRA_SOURCE_LABEL).orEmpty(),
            queue = List(count) { QueueEntry(it, c.getMediaItemAt(it)) },
            pendingCount = extras.getInt(PlaybackService.EXTRA_PENDING_COUNT),
            sleepAt = extras.getLong(PlaybackService.EXTRA_SLEEP_AT),
            error = c.playerError?.let { "Couldn't play this (${it.errorCodeName})" },
            spoken = extras.getString(PlaybackService.EXTRA_SPOKEN_ITEM)?.let { id ->
                SpokenNowPlaying(
                    libraryItemId = id,
                    episodeId = extras.getString(PlaybackService.EXTRA_SPOKEN_EPISODE),
                    durationMs = extras.getLong(PlaybackService.EXTRA_SPOKEN_TOTAL_MS),
                    speed = extras.getFloat(PlaybackService.EXTRA_SPEED, 1f),
                    canGoBackToMusic = extras.getBoolean(PlaybackService.EXTRA_HAS_MUSIC_QUEUE),
                )
            },
        ).withRemote(remoteNow)
        _playing.value = _state.value.isPlaying
    }

    /**
     * Runs [block] on the main thread (MediaController requires it; undo actions arrive from a
     * background scope) once connected. Commands given while connecting wait instead of being dropped.
     */
    private fun withController(block: (MediaController) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { withController(block) }
            return
        }
        controller?.let { block(it); return }
        connect()
        val f = future ?: return
        f.addListener({ controller?.let(block) }, { main.post(it) })
    }

    private fun send(action: String, args: Bundle) = withController {
        it.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    fun play(source: PlaySource, startSongId: String? = null, shuffle: Boolean = false) {
        // A safety net for the Binder limit: big lists should be a PlaySource kind the service resolves.
        val safe = if (source.songIds.size <= MAX_IDS_PER_COMMAND) source else {
            val start = source.songIds.indexOf(startSongId).coerceAtLeast(0)
            source.copy(songIds = source.songIds.drop(start).take(MAX_IDS_PER_COMMAND))
        }
        val was = QueueShape.of(_state.value)
        send(PlaybackService.CMD_PLAY_SOURCE, Bundle().apply {
            putBundle(PlaybackService.ARG_SOURCE, safe.toBundle())
            putString(PlaybackService.ARG_START_ID, startSongId)
            putBoolean(PlaybackService.ARG_SHUFFLE, shuffle)
        })
        // A speaker has the sound: the new queue is built here and then handed straight over.
        speaking()?.requeue(was)
    }

    /** Sent in chunks so a big selection can't exceed the Binder limit; the service applies them in order. */
    fun playNext(songIds: List<String>) {
        // Each chunk goes straight after the current song, so send the last chunk first.
        songIds.chunked(MAX_IDS_PER_COMMAND).asReversed().forEach { chunk ->
            send(PlaybackService.CMD_PLAY_NEXT, Bundle().apply { putStringArrayList(PlaybackService.ARG_SONG_IDS, ArrayList(chunk)) })
        }
    }

    fun addToQueue(songIds: List<String>) {
        songIds.chunked(MAX_IDS_PER_COMMAND).forEach { chunk ->
            send(PlaybackService.CMD_ADD_TO_QUEUE, Bundle().apply { putStringArrayList(PlaybackService.ARG_SONG_IDS, ArrayList(chunk)) })
        }
    }

    private fun entryArgs(e: QueueEntry) = Bundle().apply {
        putString(PlaybackService.ARG_KEY, e.item.queueKey)
        putString(PlaybackService.ARG_SONG_ID, e.item.mediaId)
    }

    /** Swipe right: this entry plays straight after the current song, as a hand-queued song. */
    fun moveToNext(e: QueueEntry) = send(PlaybackService.CMD_MOVE_NEXT, entryArgs(e))

    fun skipTo(e: QueueEntry) = send(PlaybackService.CMD_SKIP_TO, entryArgs(e))

    fun remove(e: QueueEntry) = send(PlaybackService.CMD_REMOVE, entryArgs(e))

    /** Moves [e] to just after [after] (null: straight after the current song). */
    fun moveAfter(e: QueueEntry, after: QueueEntry?) = send(PlaybackService.CMD_MOVE_AFTER, entryArgs(e).apply {
        putString(PlaybackService.ARG_AFTER_KEY, after?.item?.queueKey)
    })

    /** Undo for [remove]: puts [e] back between its old neighbours, keeping its "added by you" flag. */
    fun restore(e: QueueEntry, after: QueueEntry?, before: QueueEntry?) = send(PlaybackService.CMD_RESTORE, entryArgs(e).apply {
        putBoolean(PlaybackService.ARG_MANUAL, e.item.isManual)
        putString(PlaybackService.ARG_AFTER_KEY, after?.item?.queueKey)
        putString(PlaybackService.ARG_BEFORE_KEY, before?.item?.queueKey)
    })

    /** Undo for "Play next" / "Add to queue": removes the most recently queued copies of these songs. */
    fun removeQueued(songIds: List<String>) {
        songIds.chunked(MAX_IDS_PER_COMMAND).forEach { chunk ->
            send(PlaybackService.CMD_REMOVE_QUEUED, Bundle().apply { putStringArrayList(PlaybackService.ARG_SONG_IDS, ArrayList(chunk)) })
        }
    }

    // ------------------------------------------------------------ podcasts and audiobooks

    /**
     * Plays a podcast episode, replacing the queue (a music queue is set aside for [backToMusic]).
     * Starts where this user left off, on this phone or another; [startSec] overrides that.
     */
    fun playEpisode(libraryItemId: String, episodeId: String, startSec: Double? = null) = playSpoken(libraryItemId, episodeId, startSec)

    /** Plays an audiobook (all its files as one timeline); [startSec] e.g. a chapter's start. */
    fun playBook(libraryItemId: String, startSec: Double? = null) = playSpoken(libraryItemId, null, startSec)

    private fun playSpoken(libraryItemId: String, episodeId: String?, startSec: Double?) {
        val was = QueueShape.of(_state.value)
        send(PlaybackService.CMD_PLAY_SPOKEN, Bundle().apply {
            putString(PlaybackService.ARG_ITEM_ID, libraryItemId)
            putString(PlaybackService.ARG_EPISODE_ID, episodeId)
            startSec?.let { putDouble(PlaybackService.ARG_START_SEC, it) }
        })
        speaking()?.requeue(was)
    }

    fun skipBack() = send(PlaybackService.CMD_SKIP_BACK, Bundle.EMPTY)

    fun skipForward() = send(PlaybackService.CMD_SKIP_FORWARD, Bundle.EMPTY)

    /** Seeks on the whole episode/book timeline. */
    fun seekSpoken(positionMs: Long) = send(PlaybackService.CMD_SEEK_SPOKEN, Bundle().apply { putLong(PlaybackService.ARG_POSITION_MS, positionMs) })

    /** 0.8–2.0; remembered per show / book. */
    fun setSpeed(speed: Float) = send(PlaybackService.CMD_SET_SPEED, Bundle().apply { putFloat(PlaybackService.ARG_SPEED, speed) })

    /** Leaves spoken word and brings back the music queue that was playing before. */
    fun backToMusic() {
        val was = QueueShape.of(_state.value)
        send(PlaybackService.CMD_BACK_TO_MUSIC, Bundle.EMPTY)
        speaking()?.requeue(was)
    }

    /** Sleep timer that pauses at the end of the current chapter (spoken word). */
    fun sleepAtEndOfChapter() = setSleepTimer(PlaybackService.SLEEP_END_OF_CHAPTER)

    /** Position on the whole episode/book timeline (the current file's start plus the position in it). */
    val spokenPositionMs: Long
        get() {
            val c = controller ?: return 0
            return (c.currentMediaItem?.spokenTrackStartMs ?: 0) + c.currentPosition
        }

    fun setSleepTimer(ms: Long) = send(PlaybackService.CMD_SLEEP, Bundle().apply { putLong(PlaybackService.ARG_SLEEP_MS, ms) })

    fun toggleShuffle() = send(PlaybackService.CMD_TOGGLE_SHUFFLE, Bundle.EMPTY)

    fun playPause() {
        if (toSpeaker { playPause() }) return
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun pause() {
        if (toSpeaker { pause() }) return
        withController { it.pause() }
    }

    fun resume() {
        if (toSpeaker { resume() }) return
        withController {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun next() {
        if (toSpeaker { next() }) return
        controller?.seekToNext()
    }

    fun previous() {
        if (toSpeaker { previous() }) return
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        if (toSpeaker { seekTo(ms) }) return
        controller?.seekTo(ms)
    }

    /**
     * Hands the sound to a speaker: this phone stops and lets go of the audio focus, so nothing here
     * claims to be playing, but the queue stays put ready for [resumeAt].
     */
    internal fun stopKeepingQueue() = withController {
        it.pause()
        it.stop()
    }

    /** Takes the sound back: carries on from [index] in the queue, [positionMs] into that song. */
    internal fun resumeAt(index: Int, positionMs: Long) = withController {
        if (it.mediaItemCount == 0) return@withController
        it.seekTo(index.coerceIn(0, it.mediaItemCount - 1), positionMs.coerceAtLeast(0))
        it.prepare()
        it.play()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stopAndClear() {
        controller?.stop()
        controller?.clearMediaItems()
    }

    private companion object {
        /** Song ids per command: ~1000 short ids stay far below the 1 MB Binder transaction limit. */
        const val MAX_IDS_PER_COMMAND = 1000
    }
}
