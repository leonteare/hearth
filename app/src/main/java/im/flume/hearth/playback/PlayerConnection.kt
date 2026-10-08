package im.flume.hearth.playback

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
import im.flume.hearth.data.PlaySource
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
    /** Epoch ms when the sleep timer pauses playback, -1 for end of song, 0 for none. */
    val sleepAt: Long = 0,
    val error: String? = null,
)

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

    val positionMs: Long get() = controller?.currentPosition ?: 0

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
        val c = controller ?: return
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
            error = c.playerError?.let { "Couldn't play this song (${it.errorCodeName})" },
        )
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
        send(PlaybackService.CMD_PLAY_SOURCE, Bundle().apply {
            putBundle(PlaybackService.ARG_SOURCE, safe.toBundle())
            putString(PlaybackService.ARG_START_ID, startSongId)
            putBoolean(PlaybackService.ARG_SHUFFLE, shuffle)
        })
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

    fun setSleepTimer(ms: Long) = send(PlaybackService.CMD_SLEEP, Bundle().apply { putLong(PlaybackService.ARG_SLEEP_MS, ms) })

    fun toggleShuffle() = send(PlaybackService.CMD_TOGGLE_SHUFFLE, Bundle.EMPTY)

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)

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
