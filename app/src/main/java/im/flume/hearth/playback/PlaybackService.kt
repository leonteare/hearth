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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import im.flume.hearth.crashLoggingHandler
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@UnstableApi
class PlaybackService : MediaSessionService() {

    private val c by lazy { container }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashLoggingHandler(this, "playback")) }

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
    private lateinit var streamFactory: CacheDataSource.Factory
    private var prefetchJob: Job? = null
    private var trimmed = 0
    private var sleepJob: Job? = null
    /** Epoch ms when playback will pause, [SLEEP_END_OF_SONG], or 0 for no timer. */
    private var sleepAt = 0L
    private var consecutiveErrors = 0
    private var recoveryJob: Job? = null
    private var recoveryAttempts = 0
    private var ticker: Job? = null
    /** Song whose "now playing" and prefetch wait until playback actually starts (e.g. a restored queue). */
    private var startPending: MediaItem? = null
    /** Bitrate each song's stream was first opened at, so reconnects resume the same encoding. */
    private val pinnedBitrates = ConcurrentHashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        store = QueueStore(filesDir)
        scrobbler = Scrobbler(c.api, c.db.library(), c.appScope)

        streamFactory = CacheDataSource.Factory()
            .setCache(c.mediaCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(c.http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val songFactory = SongDataSource.Factory(
            fileFactory = FileDataSource.Factory(),
            streamFactory = streamFactory,
            localFile = c.downloads::localFile,
            streamTarget = ::streamTarget,
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
        scope.launch { c.session.settings.collect { applyVolumeLevelling() } }
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
        val byId = songs.associateBy { it.id }
        val raw = QueuePlanner.initial(ids, startId, shuffle)
        val plan = if (shuffle && c.session.settings.value.smartShuffle) {
            val all = QueuePlanner.spreadArtists(raw.window + raw.pending, { byId[it]?.artistId ?: byId[it]?.artist })
            QueuePlanner.Plan(all.take(raw.window.size), raw.startIndex, all.drop(raw.window.size))
        } else raw
        trimmed = 0

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

    /** Swipe-right in the queue: this song plays straight after the current one, as a hand-queued song. */
    private fun moveToNext(index: Int) {
        val cur = player.currentMediaItemIndex
        if (index <= cur || index >= player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putBoolean(EXTRA_MANUAL, true) }
        val manual = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        player.removeMediaItem(index)
        player.addMediaItem(cur + 1, manual)
        saveQueue()
    }

    private fun streamTarget(id: String): SongDataSource.StreamTarget? {
        // A reconnect continues at a byte offset, which is only valid in the same encoding: never switch
        // bitrate mid-song (e.g. Wi-Fi to mobile), and keep the choice for prefetched upcoming songs too.
        val bitrate = pinnedBitrates.computeIfAbsent(id) {
            val s = c.session.settings.value
            if (c.network.isMetered()) s.mobileBitrate else s.wifiBitrate
        }
        return c.api.streamUrl(id, bitrate)?.let { SongDataSource.StreamTarget(it, "$id@$bitrate") }
    }

    /**
     * Saves the next few songs into the streaming cache in the background, so a tunnel or dead spot
     * doesn't interrupt playback. Downloaded songs are skipped; already-cached ones cost nothing.
     */
    private fun prefetchUpcoming() {
        prefetchJob?.cancel()
        if (!c.network.isOnline.value) return
        val next = (player.currentMediaItemIndex + 1 until minOf(player.mediaItemCount, player.currentMediaItemIndex + 1 + PREFETCH_COUNT))
            .map { player.getMediaItemAt(it).mediaId }
            .filterNot(c.downloads::isDownloaded)
        if (next.isEmpty()) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            for (id in next) {
                val target = streamTarget(id) ?: continue
                val spec = DataSpec.Builder().setUri(target.url).setKey(target.cacheKey).build()
                runCatching { CacheWriter(streamFactory.createDataSource(), spec, null, null).cache() }
                if (!isActive) break
            }
        }
    }

    private fun onTrackStarted(item: MediaItem) {
        prefetchUpcoming()
        scrobbler.onTrackStarted(
            item.mediaId,
            item.mediaMetadata.extras?.getString(EXTRA_ALBUM_ID),
            item.mediaMetadata.durationMs ?: 0L,
        )
    }

    /** Forgets pinned bitrates except for the current song and the few prefetched after it. */
    private fun unpinPassedSongs() {
        val cur = player.currentMediaItemIndex
        val keep = (cur..minOf(player.mediaItemCount - 1, cur + PREFETCH_COUNT)).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
        pinnedBitrates.keys.retainAll(keep)
    }

    /** [ms] > 0: pause after that long; [SLEEP_END_OF_SONG]: pause when this song ends; 0: cancel. */
    private fun setSleepTimer(ms: Long) {
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = false
        sleepAt = when {
            ms == SLEEP_END_OF_SONG -> SLEEP_END_OF_SONG.also { player.pauseAtEndOfMediaItems = true }
            ms > 0 -> (System.currentTimeMillis() + ms).also {
                sleepJob = scope.launch {
                    delay(ms)
                    player.pause()
                    sleepAt = 0
                    publishExtras()
                }
            }
            else -> 0
        }
        publishExtras()
    }

    /**
     * ReplayGain: turn loud tracks down so everything plays at a similar level. Tracks can only be
     * made quieter, never louder, so quiet tracks stay at full volume.
     */
    private fun applyVolumeLevelling() {
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val mode = c.session.settings.value.volumeLevelling
        val gain = when (mode) {
            1 -> extras?.takeIf { it.containsKey(EXTRA_TRACK_GAIN) }?.getDouble(EXTRA_TRACK_GAIN)
            2 -> extras?.takeIf { it.containsKey(EXTRA_ALBUM_GAIN) }?.getDouble(EXTRA_ALBUM_GAIN)
                ?: extras?.takeIf { it.containsKey(EXTRA_TRACK_GAIN) }?.getDouble(EXTRA_TRACK_GAIN)
            else -> null
        }
        player.volume = gain?.let { Math.pow(10.0, it / 20.0).toFloat().coerceIn(0.05f, 1f) } ?: 1f
    }

    private fun toggleShuffle() {
        shuffled = !shuffled
        val current = player.currentMediaItem?.mediaId
        val cur = player.currentMediaItemIndex
        // Keep songs the user queued by hand; replace the rest of what follows.
        val manualAhead = (cur + 1 until player.mediaItemCount).count { player.getMediaItemAt(it).isManual }
        val firstAuto = cur + 1 + manualAhead
        if (firstAuto < player.mediaItemCount) player.removeMediaItems(firstAuto, player.mediaItemCount)
        val upcoming = QueuePlanner.upcomingAfterToggle(context, current, shuffled)
        pending = ArrayDeque(upcoming)
        scope.launch {
            if (shuffled && c.session.settings.value.smartShuffle) {
                val artists = c.library.songsByIds(upcoming).associate { it.id to (it.artistId ?: it.artist) }
                pending = ArrayDeque(QueuePlanner.spreadArtists(upcoming, artists::get))
            }
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
        if (trim > 0) {
            player.removeMediaItems(0, trim)
            trimmed += trim
        }
        publishExtras()
    }

    private fun publishExtras() {
        session.setSessionExtras(Bundle().apply {
            putBoolean(EXTRA_SHUFFLE, shuffled)
            putString(EXTRA_SOURCE_LABEL, sourceLabel)
            putInt(EXTRA_PENDING_COUNT, pending.size)
            putLong(EXTRA_SLEEP_AT, sleepAt)
        })
        c.queueInfo.value = QueueInfo(pending.toList(), trimmed)
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
        store.offer(snapshot() ?: return)
        c.appScope.launch { store.flush() }
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
                .add(SessionCommand(CMD_MOVE_NEXT, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
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
                CMD_MOVE_NEXT -> moveToNext(args.getInt(ARG_INDEX, -1))
                CMD_SLEEP -> setSleepTimer(args.getLong(ARG_SLEEP_MS))
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
            applyVolumeLevelling()
            unpinPassedSongs()
            // A queue restored (or skipped through) while paused isn't "now playing" yet, and shouldn't use data.
            if (player.playWhenReady) {
                startPending = null
                onTrackStarted(mediaItem)
            } else {
                startPending = mediaItem
            }
            scope.launch {
                refill()
                saveQueue()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ticker?.cancel()
            if (isPlaying) {
                consecutiveErrors = 0
                startPending?.let { pending ->
                    startPending = null
                    if (pending.mediaId == player.currentMediaItem?.mediaId) onTrackStarted(pending)
                }
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

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAt == SLEEP_END_OF_SONG) {
                player.pauseAtEndOfMediaItems = false
                sleepAt = 0
                publishExtras()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) recoveryAttempts = 0
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            scope.launch { refill() }
        }

        override fun onPlayerError(error: PlaybackException) {
            val action = PlaybackErrors.classify(error.errorCode, httpStatus(error))
            if (action == ErrorAction.SKIP) {
                consecutiveErrors++
                // Skip songs that won't load, but don't spin through the whole queue if they're all broken.
                if (consecutiveErrors < 3 && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
                return
            }
            // A connection problem isn't the song's fault: keep it and the position, and pick up again later.
            if (action == ErrorAction.WAIT_FOR_CONNECTION) c.network.reportServerFailure()
            val wasPlaying = player.playWhenReady
            player.pause()
            scheduleRecovery(wasPlaying, waitForConnection = action == ErrorAction.WAIT_FOR_CONNECTION)
        }
    }

    private fun httpStatus(error: PlaybackException): Int? =
        generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode

    /**
     * Re-prepares the player after a network failure: once the server is reachable again, or after a
     * short backoff for other temporary errors. Gives up quietly (staying paused) after a few tries.
     */
    private fun scheduleRecovery(wasPlaying: Boolean, waitForConnection: Boolean) {
        recoveryJob?.cancel()
        val attempt = recoveryAttempts++
        if (attempt >= MAX_RECOVERY_ATTEMPTS) return
        recoveryJob = scope.launch {
            // Back off if retrying keeps failing, so a half-working server isn't hammered.
            if (attempt > 0 || !waitForConnection) delay(minOf(60_000L, 3_000L shl attempt))
            while (!c.network.isOnline.value) {
                // The monitor only re-pings every few minutes while it thinks the server is fine.
                c.network.checkServer()
                withTimeoutOrNull(15_000) { c.network.isOnline.first { it } }
            }
            if (player.playerError == null || player.mediaItemCount == 0) return@launch
            player.prepare()
            if (wasPlaying) player.play()
        }
    }

    companion object {
        const val CMD_PLAY_SOURCE = "hearth.PLAY_SOURCE"
        const val CMD_PLAY_NEXT = "hearth.PLAY_NEXT"
        const val CMD_ADD_TO_QUEUE = "hearth.ADD_TO_QUEUE"
        const val CMD_TOGGLE_SHUFFLE = "hearth.TOGGLE_SHUFFLE"
        const val CMD_MOVE_NEXT = "hearth.MOVE_NEXT"
        const val CMD_SLEEP = "hearth.SLEEP"
        const val ARG_SLEEP_MS = "sleepMs"
        const val EXTRA_SLEEP_AT = "sleepAt"
        const val SLEEP_END_OF_SONG = -1L
        const val ARG_INDEX = "index"
        const val ARG_SOURCE = "source"
        const val ARG_START_ID = "startId"
        const val ARG_SHUFFLE = "shuffle"
        const val ARG_SONG_IDS = "songIds"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_SOURCE_LABEL = "sourceLabel"
        const val EXTRA_PENDING_COUNT = "pendingCount"
        private const val TICK_MS = 1000L
        private const val PREFETCH_COUNT = 3
        private const val MAX_RECOVERY_ATTEMPTS = 6
    }
}
