package im.flume.hearth.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import im.flume.hearth.R
import im.flume.hearth.container
import im.flume.hearth.data.PlaySource
import im.flume.hearth.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

@UnstableApi
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val c by lazy { container }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var store: QueueStore
    private lateinit var scrobbler: Scrobbler

    // Queue state beyond what the player holds (see QueuePlanner).
    private var context: List<String> = emptyList()
    private var pending: ArrayDeque<String> = ArrayDeque()
    private var shuffled = false
    private var sourceLabel = ""
    private var restoring: Job? = null
    private var consecutiveErrors = 0
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = QueueStore(filesDir)
        scrobbler = Scrobbler(c.api, c.db.library(), c.appScope)

        val streamFactory = CacheDataSource.Factory()
            .setCache(c.mediaCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(c.http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val songFactory = SongDataSource.Factory(
            fileFactory = FileDataSource.Factory(),
            streamFactory = streamFactory,
            localFile = c.downloads::localFile,
            streamTarget = { id ->
                val s = c.session.settings.value
                val bitrate = if (c.network.isMetered()) s.mobileBitrate else s.wifiBitrate
                c.api.streamUrl(id, bitrate)?.let { SongDataSource.StreamTarget(it, "$id@$bitrate") }
            },
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(songFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerListener())

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) }
        )

        restoring = scope.launch { restoreQueue(playWhenReady = false) }
        scope.launch { scrobbler.flushPending() }
        publishExtras()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveQueue()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueueBlocking()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- queue operations

    private suspend fun playSource(source: PlaySource, startId: String?, shuffle: Boolean) {
        restoring?.cancel()
        val offline = !c.network.isOnline.value
        val songs = c.library.resolve(source, offlineOnly = offline)
        if (songs.isEmpty()) return
        val ids = songs.map { it.id }
        val plan = QueuePlanner.initial(ids, startId, shuffle)
        val byId = songs.associateBy { it.id }

        context = ids
        shuffled = shuffle
        sourceLabel = source.label
        pending = ArrayDeque(plan.pending)
        player.setMediaItems(plan.window.mapNotNull { byId[it]?.toMediaItem(c.api) }, plan.startIndex, 0)
        player.prepare()
        player.play()
        publishExtras()
        saveQueue()
    }

    private suspend fun insertSongs(songIds: List<String>, next: Boolean) {
        val songs = c.library.songsByIds(songIds)
        if (songs.isEmpty()) return
        val items = songs.map { it.toMediaItem(c.api, manual = true) }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items)
            player.prepare()
            return
        }
        val index = if (next) {
            player.currentMediaItemIndex + 1
        } else {
            QueuePlanner.addToQueueIndex(player.currentMediaItemIndex, { player.getMediaItemAt(it).isManual }, player.mediaItemCount)
        }
        player.addMediaItems(index, items)
        saveQueue()
    }

    private fun toggleShuffle() {
        shuffled = !shuffled
        val current = player.currentMediaItem?.mediaId
        val cur = player.currentMediaItemIndex
        // Keep songs the user queued by hand; replace the rest of what follows.
        val manualAhead = (cur + 1 until player.mediaItemCount).count { player.getMediaItemAt(it).isManual }
        val firstAuto = cur + 1 + manualAhead
        if (firstAuto < player.mediaItemCount) player.removeMediaItems(firstAuto, player.mediaItemCount)
        pending = ArrayDeque(QueuePlanner.upcomingAfterToggle(context, current, shuffled))
        scope.launch {
            refill(force = true)
            publishExtras()
            saveQueue()
        }
    }

    /** Moves songs from [pending] into the player when it's running low. */
    private suspend fun refill(force: Boolean = false) {
        if (player.mediaItemCount == 0) return
        if (!force && !QueuePlanner.needsRefill(player.mediaItemCount, player.currentMediaItemIndex)) return
        if (pending.isEmpty() && player.repeatMode == Player.REPEAT_MODE_ALL && context.isNotEmpty()) {
            pending = ArrayDeque(QueuePlanner.order(context, null, shuffled, Random.Default))
        }
        if (pending.isEmpty()) return
        val batch = List(minOf(QueuePlanner.REFILL_COUNT, pending.size)) { pending.removeFirst() }
        val songs = c.library.songsByIds(batch)
        player.addMediaItems(songs.map { it.toMediaItem(c.api) })

        val trim = QueuePlanner.trimCount(player.currentMediaItemIndex)
        if (trim > 0) player.removeMediaItems(0, trim)
        publishExtras()
    }

    private fun publishExtras() {
        session.setSessionExtras(Bundle().apply {
            putBoolean(EXTRA_SHUFFLE, shuffled)
            putString(EXTRA_SOURCE_LABEL, sourceLabel)
            putInt(EXTRA_PENDING_COUNT, pending.size)
        })
    }

    // ---------------------------------------------------------------- persistence

    private fun snapshot(): SavedQueue? {
        if (player.mediaItemCount == 0) return null
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        return SavedQueue(
            window = items.map { it.mediaId },
            manual = items.map { it.isManual },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            pending = pending.toList(),
            context = context,
            shuffled = shuffled,
            label = sourceLabel,
            repeatMode = player.repeatMode,
        )
    }

    private fun saveQueue() {
        val snap = snapshot() ?: return
        c.appScope.launch { store.save(snap) }
    }

    private fun saveQueueBlocking() {
        val snap = snapshot() ?: return
        kotlinx.coroutines.runBlocking { store.save(snap) }
    }

    private suspend fun loadSavedItems(): Pair<SavedQueue, List<MediaItem>>? {
        val saved = store.load() ?: return null
        val byId = c.library.songsByIds(saved.window).associateBy { it.id }
        val items = saved.window.mapIndexedNotNull { i, id ->
            byId[id]?.toMediaItem(c.api, manual = saved.manual.getOrElse(i) { false })
        }
        if (items.isEmpty()) return null
        return saved to items
    }

    private suspend fun restoreQueue(playWhenReady: Boolean) {
        if (player.mediaItemCount > 0) return
        val (saved, items) = loadSavedItems() ?: return
        if (player.mediaItemCount > 0) return
        applySavedState(saved)
        player.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
        player.prepare()
        player.playWhenReady = playWhenReady
        publishExtras()
    }

    private fun applySavedState(saved: SavedQueue) {
        context = saved.context
        pending = ArrayDeque(saved.pending)
        shuffled = saved.shuffled
        sourceLabel = saved.label
        player.repeatMode = saved.repeatMode
    }

    // ---------------------------------------------------------------- callbacks

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PLAY_SOURCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_PLAY_NEXT, Bundle.EMPTY))
                .add(SessionCommand(CMD_ADD_TO_QUEUE, Bundle.EMPTY))
                .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_PLAY_SOURCE -> scope.launch {
                    playSource(
                        PlaySource.fromBundle(args.getBundle(ARG_SOURCE) ?: Bundle.EMPTY),
                        args.getString(ARG_START_ID),
                        args.getBoolean(ARG_SHUFFLE),
                    )
                }
                CMD_PLAY_NEXT -> scope.launch { insertSongs(args.getStringArrayList(ARG_SONG_IDS).orEmpty(), next = true) }
                CMD_ADD_TO_QUEUE -> scope.launch { insertSongs(args.getStringArrayList(ARG_SONG_IDS).orEmpty(), next = false) }
                CMD_TOGGLE_SHUFFLE -> toggleShuffle()
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Items added by other controllers (e.g. system UI) carry only an id; look them up locally. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val songs = c.library.songsByIds(mediaItems.map { it.mediaId })
                future.set(songs.map { it.toMediaItem(c.api) }.toMutableList())
            }
            return future
        }

        /** Play pressed on headphones / the car after the app was killed: pick up where we left off. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                restoring?.cancel()
                val loaded = loadSavedItems()
                if (loaded == null) {
                    future.setException(UnsupportedOperationException("Nothing to resume"))
                    return@launch
                }
                val (saved, items) = loaded
                applySavedState(saved)
                publishExtras()
                future.set(MediaSession.MediaItemsWithStartPosition(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs))
            }
            return future
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            scrobbler.onTrackStarted(
                mediaItem.mediaId,
                mediaItem.mediaMetadata.extras?.getString(EXTRA_ALBUM_ID),
                mediaItem.mediaMetadata.durationMs ?: 0L,
            )
            scope.launch {
                refill()
                saveQueue()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ticker?.cancel()
            if (isPlaying) {
                consecutiveErrors = 0
                ticker = scope.launch {
                    var lastSave = System.currentTimeMillis()
                    while (isActive) {
                        delay(TICK_MS)
                        scrobbler.onListened(TICK_MS)
                        if (System.currentTimeMillis() - lastSave > 15_000) {
                            saveQueue()
                            lastSave = System.currentTimeMillis()
                        }
                    }
                }
            } else {
                saveQueue()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            scope.launch { refill() }
        }

        override fun onPlayerError(error: PlaybackException) {
            consecutiveErrors++
            if (error.errorCode in NETWORK_ERRORS) c.network.reportServerFailure()
            // Skip songs that won't load, but don't spin through the whole queue if the server is gone.
            if (consecutiveErrors < 3 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    companion object {
        const val CMD_PLAY_SOURCE = "hearth.PLAY_SOURCE"
        const val CMD_PLAY_NEXT = "hearth.PLAY_NEXT"
        const val CMD_ADD_TO_QUEUE = "hearth.ADD_TO_QUEUE"
        const val CMD_TOGGLE_SHUFFLE = "hearth.TOGGLE_SHUFFLE"
        const val ARG_SOURCE = "source"
        const val ARG_START_ID = "startId"
        const val ARG_SHUFFLE = "shuffle"
        const val ARG_SONG_IDS = "songIds"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_SOURCE_LABEL = "sourceLabel"
        const val EXTRA_PENDING_COUNT = "pendingCount"
        private const val TICK_MS = 1000L

        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
    }
}
