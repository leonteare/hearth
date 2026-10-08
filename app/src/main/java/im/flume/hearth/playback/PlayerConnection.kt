package im.flume.hearth.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
    val key: String get() = "$index:${item.mediaId}"
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

/** The UI's handle on the playback service. All heavy queue work happens in the service. */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val positionMs: Long get() = controller?.currentPosition ?: 0
    val bufferedMs: Long get() = controller?.bufferedPosition ?: 0

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

    private fun send(action: String, args: Bundle) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    fun play(source: PlaySource, startSongId: String? = null, shuffle: Boolean = false) {
        // Connecting is near-instant; queue the command if the controller isn't ready yet.
        if (controller == null) {
            connect()
            future?.addListener({ play(source, startSongId, shuffle) }, MoreExecutors.directExecutor())
            return
        }
        send(PlaybackService.CMD_PLAY_SOURCE, Bundle().apply {
            putBundle(PlaybackService.ARG_SOURCE, source.toBundle())
            putString(PlaybackService.ARG_START_ID, startSongId)
            putBoolean(PlaybackService.ARG_SHUFFLE, shuffle)
        })
    }

    fun playNext(songIds: List<String>) = send(PlaybackService.CMD_PLAY_NEXT, Bundle().apply {
        putStringArrayList(PlaybackService.ARG_SONG_IDS, ArrayList(songIds))
    })

    fun addToQueue(songIds: List<String>) = send(PlaybackService.CMD_ADD_TO_QUEUE, Bundle().apply {
        putStringArrayList(PlaybackService.ARG_SONG_IDS, ArrayList(songIds))
    })

    fun moveToNext(index: Int) = send(PlaybackService.CMD_MOVE_NEXT, Bundle().apply { putInt(PlaybackService.ARG_INDEX, index) })

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
    fun skipTo(index: Int) = controller?.let { it.seekTo(index, 0); it.play() }
    fun remove(index: Int) = controller?.removeMediaItem(index)
    fun insert(index: Int, item: MediaItem) = controller?.addMediaItem(index.coerceAtMost(controller?.mediaItemCount ?: 0), item)

    /** Undo for "Play next" / "Add to queue": removes the most recently queued copies of these songs. */
    fun removeQueued(songIds: List<String>) {
        val c = controller ?: return
        val remaining = songIds.toMutableList()
        for (i in c.mediaItemCount - 1 downTo c.currentMediaItemIndex + 1) {
            val item = c.getMediaItemAt(i)
            if (item.isManual && remaining.remove(item.mediaId)) c.removeMediaItem(i)
            if (remaining.isEmpty()) break
        }
    }
    fun move(from: Int, to: Int) = controller?.moveMediaItem(from, to)

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
}
