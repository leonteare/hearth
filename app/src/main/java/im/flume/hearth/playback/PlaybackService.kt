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
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import android.content.Context
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import im.flume.hearth.R
import im.flume.hearth.container
import im.flume.hearth.data.PlaySource
import im.flume.hearth.spoken.SpokenTimeline
import im.flume.hearth.spoken.TimelineTrack
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.media3.session.CommandButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import im.flume.hearth.crashLoggingHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
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
    /** CacheWriter.cache() blocks and ignores coroutine cancellation, so writers are cancelled directly. */
    private val prefetchWriters = CopyOnWriteArraySet<CacheWriter>()
    /** Queue edits that suspend (database lookups) run one at a time, in the order they arrived. */
    private val queueOps = Mutex()
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

    /** Set while the queue is a podcast episode or audiobook (a queue is either music or spoken word). */
    private var spokenNow: SpokenNow? = null
    private lateinit var tracker: ListeningTracker
    /** A spoken queue restored at start: check the server's position the first time it plays. */
    private var spokenNeedsServerCheck = false

    override fun onCreate() {
        super.onCreate()
        store = QueueStore(filesDir)
        scrobbler = Scrobbler(c.api, c.db.library(), c.appScope)

        streamFactory = CacheDataSource.Factory()
            .setCache(c.mediaCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(c.http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Reads the streaming cache only (no upstream): used when streaming isn't allowed or possible.
        val cacheOnlyFactory = CacheDataSource.Factory()
            .setCache(c.mediaCache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
        val songFactory = SongDataSource.Factory(
            fileFactory = FileDataSource.Factory(),
            streamFactory = streamFactory,
            cacheOnlyFactory = cacheOnlyFactory,
            localFile = c.downloads::localFile,
            streamTarget = ::streamTarget,
            cachedTarget = { id -> cachedBitrate(id)?.let { SongDataSource.StreamTarget("hearth-cache://$id", "$id@$it") } },
            cannotStream = { c.network.cannotStream },
        )
        // Podcast episodes and audiobooks: downloaded file, or the server with the token in a header.
        // Not cached: a long book would push every song out of the streaming cache.
        val spokenFactory = SpokenDataSource.Factory(
            fileFactory = FileDataSource.Factory(),
            httpFactory = OkHttpDataSource.Factory(c.spoken.api.http),
            localFile = c.spoken.downloads::localFile,
            remoteUrl = { item, ino -> runCatching { c.spoken.api.fileUrl(item, ino) }.getOrNull() },
            cannotStream = { c.network.cannotStream },
        )
        val routedFactory = SchemeRoutingDataSourceFactory(songFactory, mapOf(SpokenMedia.SCHEME to spokenFactory))
        tracker = ListeningTracker(c.spoken.progress)
        // An offline failure is final for that song: don't retry it, so playback moves on at once.
        val loadErrors = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (generateSequence<Throwable>(info.exception) { it.cause }.any { it is SongDataSource.OfflineException }) C.TIME_UNSET
                else super.getRetryDelayMsFor(info)
        }

        // Same audio sink as Media3's default, wrapped so the visualiser gets the PCM with its
        // presentation times and the sink's playback clock (only copied while one is open). Audio
        // offload stays off (the default): offloaded audio never passes through as PCM.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                AudioAnalyzer.wrap(
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                        .build()
                )
        }

        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(routedFactory).setLoadErrorHandlingPolicy(loadErrors))
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
        session = MediaSession.Builder(this, SpokenAwarePlayer(player))
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            // Artwork URIs are token-free hearth-cover:// / abs-cover:// links; this resolves them in the app.
            .setBitmapLoader(CoverBitmapLoader(this, c.api, scope) { id -> c.spoken.api.coverUrl(id, 1000) })
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) }
        )

        // Like and Shuffle buttons on the lock screen / notification follow the current song and mode.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        scope.launch {
            currentSongId.flatMapLatest { id -> if (id == null) flowOf(null) else c.db.library().songFlow(id) }
                .collect { song -> currentLiked = song?.starred == true; updateMediaButtons() }
        }

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
        cancelPrefetch()
        if (spokenNow != null) {
            // Keep where we got to on the phone; the outbox worker sends it once there's a connection.
            val pos = spokenPositionSec()
            kotlinx.coroutines.runBlocking { withTimeoutOrNull(2_000) { tracker.pause(pos, send = false) } }
            c.spoken.progress.scheduleFlush()
        }
        saveQueueBlocking()
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- queue operations

    // ---------------------------------------------------------------- spoken word (podcasts, audiobooks)

    /** The episode's or book's audio files as player items, with its timeline. Null if it can't be played. */
    private suspend fun loadSpoken(itemId: String, episodeId: String?): Pair<SpokenNow, List<MediaItem>>? {
        val dao = c.db.spoken()
        if (episodeId != null) {
            val row = dao.episodeOnce(episodeId) ?: run { runCatching { c.spoken.fetchItem(itemId) }; dao.episodeOnce(episodeId) } ?: return null
            val items = SpokenMedia.episodeItems(row).takeIf { it.isNotEmpty() } ?: return null
            val ino = row.episode.ino ?: return null
            val podcast = dao.podcastOnce(itemId)
            val timeline = SpokenTimeline(listOf(TimelineTrack(ino, row.episode.durationSec)), SpokenNow.chaptersOf(dao.chapters(itemId, episodeId)))
            return SpokenNow(itemId, episodeId, podcast?.libraryId.orEmpty(), row.episode.title, row.podcastAuthor ?: row.podcastTitle, timeline) to items
        }
        var book = dao.bookOnce(itemId)
        if (book == null || dao.tracks(itemId).isEmpty()) {
            runCatching { c.spoken.fetchItem(itemId) }
            book = dao.bookOnce(itemId) ?: return null
        }
        val tracks = dao.tracks(itemId).takeIf { it.isNotEmpty() } ?: return null
        val timeline = SpokenTimeline(tracks.map { TimelineTrack(it.ino, it.durationSec) }, SpokenNow.chaptersOf(dao.chapters(itemId, "")))
        return SpokenNow(itemId, null, book.libraryId, book.title, book.author.orEmpty(), timeline) to SpokenMedia.bookItems(book, tracks)
    }

    /**
     * Plays an episode ([episodeId]) or audiobook from where this user left off (newest of this phone
     * and the server), or from [startSec]. Replaces the queue; a music queue is kept for "Back to music".
     */
    private suspend fun playSpoken(itemId: String, episodeId: String?, startSec: Double?) = queueOps.withLock {
        restoring?.cancel()
        val (now, items) = loadSpoken(itemId, episodeId) ?: return@withLock
        if (spokenNow != null) exitSpoken()
        else if (player.mediaItemCount > 0) snapshot()?.let { store.saveMusic(it) }
        val start = c.spoken.progress.resolveStart(itemId, episodeId, now.title, online = !c.network.cannotStream)
        enterSpoken(now, items, startSec ?: start.positionSec, start.finished, playWhenReady = true)
    }

    private suspend fun enterSpoken(now: SpokenNow, items: List<MediaItem>, startSec: Double, finished: Boolean, playWhenReady: Boolean) {
        spokenNow = now
        c.spoken.downloads.current = im.flume.hearth.spoken.SpokenKey(now.libraryItemId, now.episodeId.orEmpty())
        context = emptyList()
        pending = ArrayDeque()
        shuffled = false
        trimmed = 0
        sourceLabel = if (now.isBook) now.title else c.db.spoken().podcastOnce(now.libraryItemId)?.title ?: now.author
        scrobbler.reset()
        val (index, offsetMs) = now.timeline.locate(startSec)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.pauseAtEndOfMediaItems = false
        player.setMediaItems(items, index, offsetMs)
        player.setPlaybackSpeed(c.db.spoken().speed(now.libraryItemId) ?: 1f)
        player.prepare()
        player.playWhenReady = playWhenReady
        tracker.begin(now, startSec, finished)
        publishExtras()
        saveQueue()
    }

    /** Leaves spoken-word mode, saving where we got to. The player's items are left for the caller to replace. */
    private suspend fun exitSpoken() {
        if (spokenNow == null) return
        // Saved on the phone now; sent in the background so starting the next thing never waits on the network.
        tracker.pause(spokenPositionSec(), send = false)
        c.spoken.progress.flushSoon()
        tracker.end()
        spokenNow = null
        spokenNeedsServerCheck = false
        c.spoken.downloads.current = null
        player.setPlaybackSpeed(1f)
        if (sleepAt == SLEEP_END_OF_CHAPTER) setSleepTimer(0)
        c.appScope.launch { runCatching { c.spoken.downloads.autoDeleteFinished() } }
    }

    /** "Back to music": the music queue that was playing before the episode or book started. */
    private suspend fun backToMusic() = queueOps.withLock {
        val saved = store.loadMusic() ?: return@withLock
        exitSpoken()
        val byId = c.library.songsByIds(saved.window).associateBy { it.id }
        val items = saved.window.mapIndexedNotNull { i, id -> byId[id]?.toMediaItem(manual = saved.manual.getOrElse(i) { false }) }
        store.clearMusic()
        if (items.isEmpty()) { publishExtras(); return@withLock }
        applySavedState(saved)
        player.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
        player.prepare()
        player.play()
        publishExtras()
        saveQueue()
    }

    /** Position on the whole episode/book timeline. */
    private fun spokenPositionSec(): Double =
        spokenNow?.timeline?.globalSec(player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0)) ?: 0.0

    private fun seekSpokenTo(sec: Double) {
        val now = spokenNow ?: return
        val (index, ms) = now.timeline.locate(sec.coerceIn(0.0, now.timeline.durationSec))
        player.seekTo(index, ms)
    }

    private fun skipSpokenBy(deltaSec: Double) = seekSpokenTo(spokenPositionSec() + deltaSec)

    private fun setSpeed(speed: Float) {
        val now = spokenNow ?: return
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        player.setPlaybackSpeed(s)
        publishExtras()
        c.appScope.launch { c.db.spoken().setSpeed(im.flume.hearth.spoken.SpokenPlayPrefEntity(now.libraryItemId, s)) }
    }

    /** Session player: in spoken mode, next/previous (headphones, Bluetooth, car) skip +30 s / −15 s. */
    private inner class SpokenAwarePlayer(p: Player) : androidx.media3.common.ForwardingPlayer(p) {
        override fun seekToNext() = if (spokenNow != null) skipSpokenBy(SKIP_FORWARD_SEC) else super.seekToNext()
        override fun seekToNextMediaItem() = if (spokenNow != null) skipSpokenBy(SKIP_FORWARD_SEC) else super.seekToNextMediaItem()
        override fun seekToPrevious() = if (spokenNow != null) skipSpokenBy(-SKIP_BACK_SEC) else super.seekToPrevious()
        override fun seekToPreviousMediaItem() = if (spokenNow != null) skipSpokenBy(-SKIP_BACK_SEC) else super.seekToPreviousMediaItem()
        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            if (spokenNow == null) return base
            return base.buildUpon().addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            ).build()
        }
        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    }

    private suspend fun playSource(source: PlaySource, startId: String?, shuffle: Boolean) {
        restoring?.cancel()
        if (spokenNow != null) { exitSpoken(); store.clearMusic() }
        // Right after start the server's state isn't known yet: ask first rather than shuffle songs that can't stream.
        val offline = !c.network.awaitServerKnown()
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
        player.setMediaItems(plan.window.mapNotNull { byId[it]?.toMediaItem() }, plan.startIndex, 0)
        player.prepare()
        player.play()
        publishExtras()
        saveQueue()
    }

    private suspend fun insertSongs(songIds: List<String>, next: Boolean) = queueOps.withLock {
        val songs = c.library.songsByIds(songIds)
        if (songs.isEmpty()) return@withLock
        val items = songs.map { it.toMediaItem(manual = true) }
        if (spokenNow != null) {
            // Songs can't join a spoken-word queue, and shouldn't cut the episode off: they go into the
            // music queue set aside for "Back to music" (or start one), where they play when you go back.
            store.saveMusic(QueuePlanner.insertIntoSaved(store.loadMusic(), songs.map { it.id }, next))
            publishExtras()
            return@withLock
        }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items)
            player.prepare()
            return@withLock
        }
        val index = if (next) {
            player.currentMediaItemIndex + 1
        } else {
            QueuePlanner.addToQueueIndex(player.currentMediaItemIndex, { player.getMediaItemAt(it).isManual }, player.mediaItemCount)
        }
        player.addMediaItems(index, items)
        saveQueue()
    }

    // ---------------------------------------------------------------- queue edits from the queue screen
    // Entries are found by queue key + song id when the command runs, never by a position the UI saw:
    // played songs get trimmed from the front, so positions shift under the UI.

    private fun indexOfEntry(key: String?, songId: String?): Int {
        if (key == null) return -1
        return (0 until player.mediaItemCount).firstOrNull {
            val item = player.getMediaItemAt(it)
            item.queueKey == key && (songId == null || item.mediaId == songId)
        } ?: -1
    }

    /** Swipe-right in the queue: this song plays straight after the current one, as a hand-queued song. */
    private fun moveToNext(key: String?, songId: String?) {
        val index = indexOfEntry(key, songId)
        val cur = player.currentMediaItemIndex
        if (index <= cur) return
        val manual = player.getMediaItemAt(index).withManual(true)
        player.removeMediaItem(index)
        player.addMediaItem(cur + 1, manual)
        saveQueue()
    }

    private fun skipTo(key: String?, songId: String?) {
        val index = indexOfEntry(key, songId)
        if (index < 0) return
        player.seekTo(index, 0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    private fun removeEntry(key: String?, songId: String?) {
        val index = indexOfEntry(key, songId)
        if (index < 0) return
        player.removeMediaItem(index)
        saveQueue()
    }

    /** Drag-to-reorder: puts the entry straight after [afterKey] (null or gone: straight after the current song). */
    private fun moveAfter(key: String?, songId: String?, afterKey: String?) {
        val from = indexOfEntry(key, songId)
        val cur = player.currentMediaItemIndex
        if (from <= cur) return
        val after = indexOfEntry(afterKey, null).takeIf { it >= cur } ?: cur
        // moveMediaItem's target is the index in the list after removal.
        val to = if (from <= after) after else after + 1
        if (to != from) player.moveMediaItem(from, to)
        saveQueue()
    }

    /** Undo of a removal: back between its old neighbours, with its "added by you" flag as it was. */
    private suspend fun restoreEntry(songId: String, key: String?, manual: Boolean, afterKey: String?, beforeKey: String?) = queueOps.withLock {
        val song = c.library.songsByIds(listOf(songId)).firstOrNull() ?: return@withLock
        val item = song.toMediaItem(manual = manual, queueKey = key?.takeIf { indexOfEntry(it, null) < 0 } ?: newQueueKey())
        if (player.mediaItemCount == 0) {
            player.setMediaItems(listOf(item))
            player.prepare()
            return@withLock
        }
        val cur = player.currentMediaItemIndex
        val index = indexOfEntry(beforeKey, null).takeIf { it > cur }
            ?: indexOfEntry(afterKey, null).takeIf { it >= cur }?.plus(1)
            ?: (cur + 1)
        player.addMediaItem(index, item)
        saveQueue()
    }

    /** Undo for "Play next" / "Add to queue": removes the most recently queued copies of these songs. */
    private suspend fun removeQueued(songIds: List<String>) = queueOps.withLock {
        val remaining = songIds.toMutableList()
        for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            val item = player.getMediaItemAt(i)
            if (item.isManual && remaining.remove(item.mediaId)) player.removeMediaItem(i)
            if (remaining.isEmpty()) break
        }
        saveQueue()
    }

    private fun streamTarget(id: String): SongDataSource.StreamTarget? {
        // A reconnect continues at a byte offset, which is only valid in the same encoding: never switch
        // bitrate mid-song (e.g. Wi-Fi to mobile), and keep the choice for prefetched upcoming songs too.
        // A song already fully cached at some bitrate keeps that one, so it plays without the network.
        val bitrate = pinnedBitrates[id] ?: run {
            val s = c.session.settings.value
            val chosen = cachedBitrate(id) ?: if (c.network.isMetered()) s.mobileBitrate else s.wifiBitrate
            pinnedBitrates.putIfAbsent(id, chosen) ?: chosen
        }
        return c.api.streamUrl(id, bitrate)?.let { SongDataSource.StreamTarget(it, "$id@$bitrate") }
    }

    /** The bitrate this song is completely in the streaming cache at, or null. */
    private fun cachedBitrate(id: String): Int? {
        val s = c.session.settings.value
        return listOfNotNull(pinnedBitrates[id], s.wifiBitrate, s.mobileBitrate).distinct()
            .firstOrNull { isFullyCached("$id@$it") }
    }

    private fun isFullyCached(key: String): Boolean = runCatching {
        val cache = c.mediaCache
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length > 0 && cache.isCached(key, 0, length)
    }.getOrDefault(false)

    /** Can play with no connection: downloaded, or completely in the streaming cache. */
    private fun isOnPhone(id: String): Boolean {
        if (id.startsWith("abs:")) {
            val item = (0 until player.mediaItemCount).map(player::getMediaItemAt).firstOrNull { it.mediaId == id } ?: return false
            return item.localConfiguration?.uri?.let(SpokenMedia::parse)?.let { (itemId, ino) -> c.spoken.downloads.localFile(itemId, ino) } != null
        }
        return c.downloads.isDownloaded(id) || cachedBitrate(id) != null
    }

    private fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchWriters.forEach { it.cancel() }
    }

    /**
     * Saves the next few songs into the streaming cache in the background, so a tunnel or dead spot
     * doesn't interrupt playback. Downloaded songs are skipped; already-cached ones cost nothing.
     * Called on every song change, which first stops the previous round's downloads.
     */
    private fun prefetchUpcoming() {
        cancelPrefetch()
        if (!c.network.isOnline.value) return
        val next = (player.currentMediaItemIndex + 1 until minOf(player.mediaItemCount, player.currentMediaItemIndex + 1 + PREFETCH_COUNT))
            .map { player.getMediaItemAt(it).mediaId }
            .filterNot(c.downloads::isDownloaded)
        if (next.isEmpty()) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            for (id in next) {
                if (!isActive) break
                val target = streamTarget(id) ?: continue
                val spec = DataSpec.Builder().setUri(target.url).setKey(target.cacheKey).build()
                val writer = CacheWriter(streamFactory.createDataSource(), spec, null, null)
                prefetchWriters += writer
                try {
                    if (isActive) runCatching { writer.cache() }
                } finally {
                    prefetchWriters -= writer
                }
            }
        }
    }

    private fun onTrackStarted(item: MediaItem) {
        if (item.isSpoken) return // not a song: nothing to prefetch from Navidrome or scrobble
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

    /**
     * [ms] > 0: pause after that long; [SLEEP_END_OF_SONG]: pause when this song ends;
     * [SLEEP_END_OF_CHAPTER]: pause at the end of the chapter playing (spoken word; the end of the
     * episode or file when there are no chapters); 0: cancel.
     */
    private fun setSleepTimer(ms: Long) {
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = false
        val spoken = spokenNow
        if (ms == SLEEP_END_OF_CHAPTER && spoken != null) {
            val end = spoken.timeline.chapterEndSec(spokenPositionSec())
            sleepAt = SLEEP_END_OF_CHAPTER
            sleepJob = scope.launch {
                while (isActive) {
                    val left = end - spokenPositionSec()
                    if (left <= 0.05) break
                    // Wake often enough to stop within a fraction of a second, at any speed.
                    delay(((left / player.playbackParameters.speed.coerceAtLeast(0.1f)) * 1000).toLong().coerceIn(20, 500))
                }
                player.pause()
                sleepAt = 0
                publishExtras()
            }
            publishExtras()
            return
        }
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
        if (spokenNow != null) return // episodes and books play in order
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
        val batch = if (c.network.isOnline.value) {
            List(minOf(QueuePlanner.REFILL_COUNT, pending.size)) { pending.removeFirst() }
        } else {
            // Offline: bring forward only songs on the phone; the rest stay in line for when the server's back.
            takePending(QueuePlanner.REFILL_COUNT, ::isOnPhone)
        }
        if (batch.isEmpty()) return
        val songs = c.library.songsByIds(batch)
        player.addMediaItems(songs.map { it.toMediaItem() })

        val trim = QueuePlanner.trimCount(player.currentMediaItemIndex)
        if (trim > 0) {
            player.removeMediaItems(0, trim)
            trimmed += trim
        }
        publishExtras()
    }

    /** Removes and returns up to [max] songs from [pending] that match [keep], leaving the others in order. */
    private fun takePending(max: Int, keep: (String) -> Boolean): List<String> {
        val taken = ArrayList<String>()
        val rest = ArrayDeque<String>(pending.size)
        for (id in pending) if (taken.size < max && keep(id)) taken += id else rest += id
        if (taken.isNotEmpty()) pending = rest
        return taken
    }

    /** Song id now playing, for the lock-screen Like button. */
    private val currentSongId = MutableStateFlow<String?>(null)
    private var currentLiked = false

    /** Two extra buttons next to play/skip on the lock screen and notification: Like and Shuffle. */
    private fun updateMediaButtons() {
        if (spokenNow != null) {
            // Spoken word: back 15 s / forward 30 s in place of previous / next.
            val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setDisplayName("Back 15 seconds")
                .setSessionCommand(SessionCommand(CMD_SKIP_BACK, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_BACK)
                .build()
            val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setDisplayName("Forward 30 seconds")
                .setSessionCommand(SessionCommand(CMD_SKIP_FORWARD, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build()
            session.setMediaButtonPreferences(listOf(back, forward))
            return
        }
        val like = CommandButton.Builder(if (currentLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (currentLiked) "Unlike" else "Like")
            .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
            .build()
        val shuffle = CommandButton.Builder(if (shuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (shuffled) "Shuffle off" else "Shuffle on")
            .setSessionCommand(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .build()
        session.setMediaButtonPreferences(listOf(like, shuffle))
    }

    private fun toggleLike() {
        val id = player.currentMediaItem?.mediaId ?: return
        val liked = !currentLiked
        currentLiked = liked
        updateMediaButtons()
        scope.launch { runCatching { c.library.setStarred(id, liked) } }
    }

    private fun publishExtras() {
        updateMediaButtons()
        session.setSessionExtras(Bundle().apply {
            putBoolean(EXTRA_SHUFFLE, shuffled)
            putString(EXTRA_SOURCE_LABEL, sourceLabel)
            putInt(EXTRA_PENDING_COUNT, pending.size)
            putLong(EXTRA_SLEEP_AT, sleepAt)
            spokenNow?.let { s ->
                putString(EXTRA_SPOKEN_ITEM, s.libraryItemId)
                putString(EXTRA_SPOKEN_EPISODE, s.episodeId)
                putLong(EXTRA_SPOKEN_TOTAL_MS, (s.timeline.durationSec * 1000).toLong())
                putFloat(EXTRA_SPEED, player.playbackParameters.speed)
            }
            putBoolean(EXTRA_HAS_MUSIC_QUEUE, spokenNow != null && store.hasMusic())
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
            spokenItemId = spokenNow?.libraryItemId,
            spokenEpisodeId = spokenNow?.episodeId,
            spokenPositionSec = spokenNow?.let { spokenPositionSec() },
        )
    }

    /**
     * A spoken-word queue saved at shutdown: rebuilt from the catalogue (works offline), at this
     * phone's latest recorded position. The server's position is checked when it first plays.
     */
    private suspend fun loadSavedSpoken(saved: SavedQueue): Triple<SpokenNow, List<MediaItem>, Pair<Double, Boolean>>? {
        val itemId = saved.spokenItemId ?: return null
        val (now, items) = loadSpoken(itemId, saved.spokenEpisodeId) ?: return null
        val local = c.spoken.progress.local(itemId, saved.spokenEpisodeId)
        val pos = local?.takeIf { !it.isFinished }?.currentTime ?: saved.spokenPositionSec ?: 0.0
        return Triple(now, items, pos to (local?.isFinished == true))
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
            byId[id]?.toMediaItem(manual = saved.manual.getOrElse(i) { false })
        }
        if (items.isEmpty()) return null
        return saved to items
    }

    private suspend fun restoreQueue(playWhenReady: Boolean) {
        if (player.mediaItemCount > 0) return
        store.load()?.takeIf { it.spokenItemId != null }?.let { saved ->
            val (now, items, start) = loadSavedSpoken(saved) ?: return
            if (player.mediaItemCount > 0) return
            enterSpoken(now, items, start.first, start.second, playWhenReady)
            spokenNeedsServerCheck = true
            return
        }
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
            // System UI, Bluetooth and other apps get the standard player controls only; Hearth's own
            // queue and playback commands are for this app.
            if (controller.packageName != packageName) {
                // Only the Like and Shuffle buttons, so the lock screen and notification can use them.
                val buttons = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                    .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                    .add(SessionCommand(CMD_SKIP_BACK, Bundle.EMPTY))
                    .add(SessionCommand(CMD_SKIP_FORWARD, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(buttons).build()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                CUSTOM_COMMANDS.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
            }.build()
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
            if (controller.packageName != packageName && customCommand.customAction !in BUTTON_COMMANDS) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
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
                CMD_LIKE -> toggleLike()
                CMD_MOVE_NEXT -> moveToNext(args.getString(ARG_KEY), args.getString(ARG_SONG_ID))
                CMD_SKIP_TO -> skipTo(args.getString(ARG_KEY), args.getString(ARG_SONG_ID))
                CMD_REMOVE -> removeEntry(args.getString(ARG_KEY), args.getString(ARG_SONG_ID))
                CMD_MOVE_AFTER -> moveAfter(args.getString(ARG_KEY), args.getString(ARG_SONG_ID), args.getString(ARG_AFTER_KEY))
                CMD_RESTORE -> args.getString(ARG_SONG_ID)?.let { songId ->
                    scope.launch {
                        restoreEntry(songId, args.getString(ARG_KEY), args.getBoolean(ARG_MANUAL), args.getString(ARG_AFTER_KEY), args.getString(ARG_BEFORE_KEY))
                    }
                }
                CMD_REMOVE_QUEUED -> scope.launch { removeQueued(args.getStringArrayList(ARG_SONG_IDS).orEmpty()) }
                CMD_SLEEP -> setSleepTimer(args.getLong(ARG_SLEEP_MS))
                CMD_PLAY_SPOKEN -> args.getString(ARG_ITEM_ID)?.let { itemId ->
                    scope.launch {
                        playSpoken(itemId, args.getString(ARG_EPISODE_ID), args.getDouble(ARG_START_SEC, Double.NaN).takeUnless { it.isNaN() })
                    }
                }
                CMD_SKIP_BACK -> skipSpokenBy(-SKIP_BACK_SEC)
                CMD_SKIP_FORWARD -> skipSpokenBy(SKIP_FORWARD_SEC)
                CMD_SEEK_SPOKEN -> seekSpokenTo(args.getLong(ARG_POSITION_MS) / 1000.0)
                CMD_SET_SPEED -> setSpeed(args.getFloat(ARG_SPEED, 1f))
                CMD_BACK_TO_MUSIC -> scope.launch { backToMusic() }
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
                future.set(songs.map { it.toMediaItem() }.toMutableList())
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
                store.load()?.takeIf { it.spokenItemId != null }?.let { saved ->
                    val spoken = loadSavedSpoken(saved)
                    if (spoken == null) {
                        future.setException(UnsupportedOperationException("Nothing to resume"))
                        return@launch
                    }
                    val (now, items, start) = spoken
                    spokenNow = now
                    c.spoken.downloads.current = im.flume.hearth.spoken.SpokenKey(now.libraryItemId, now.episodeId.orEmpty())
                    context = emptyList(); pending = ArrayDeque(); shuffled = false; sourceLabel = saved.label
                    tracker.begin(now, start.first, start.second)
                    spokenNeedsServerCheck = true
                    player.setPlaybackSpeed(c.db.spoken().speed(now.libraryItemId) ?: 1f)
                    publishExtras()
                    val (index, ms) = now.timeline.locate(start.first)
                    future.set(MediaSession.MediaItemsWithStartPosition(items, index, ms))
                    return@launch
                }
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
            currentSongId.value = mediaItem?.mediaId
            mediaItem ?: return
            // Offline and the next song isn't on the phone: go straight to one that is, rather than
            // waiting for it to fail (only for automatic advances; a song picked by hand gets its try).
            // Not for audiobooks: their files must play in order.
            if (!mediaItem.isSpoken && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !c.network.isOnline.value && !isOnPhone(mediaItem.mediaId)) {
                scope.launch { jumpToPlayable() }
            }
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
                if (spokenNow != null && spokenNeedsServerCheck) {
                    spokenNeedsServerCheck = false
                    scope.launch { checkServerPosition() }
                }
                ticker = scope.launch {
                    var lastSave = System.currentTimeMillis()
                    while (isActive) {
                        delay(TICK_MS)
                        if (spokenNow != null) tracker.tick(spokenPositionSec(), TICK_MS) else scrobbler.onListened(TICK_MS)
                        if (System.currentTimeMillis() - lastSave > 15_000) {
                            saveQueue()
                            lastSave = System.currentTimeMillis()
                        }
                    }
                }
            } else {
                saveQueue()
                if (spokenNow != null) {
                    val pos = spokenPositionSec()
                    scope.launch { tracker.pause(pos) }
                }
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A seek while paused (e.g. scrubbing a chapter) is remembered too.
            if (spokenNow != null && reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) {
                val pos = spokenPositionSec()
                scope.launch { tracker.seeked(pos) }
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
            // Played to the very end: record the end (marks it finished) and send it.
            if (playbackState == Player.STATE_ENDED && spokenNow != null) {
                val now = spokenNow
                val end = now?.timeline?.durationSec ?: 0.0
                scope.launch {
                    tracker.pause(end)
                    // A finished episode leaves this phone straight away rather than when the next thing
                    // plays: it's no longer held by the player, so deleting it is safe. Playing it again
                    // just streams it from the server.
                    if (now != null && !now.isBook) {
                        c.spoken.downloads.current = null
                        runCatching { c.spoken.downloads.autoDeleteFinished() }
                    }
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            scope.launch { refill() }
        }

        override fun onPlayerError(error: PlaybackException) {
            val action = PlaybackErrors.classify(error.errorCode, httpStatus(error))
            // Offline mode / no network fail on purpose; only a real failure says anything about the server.
            // (Audiobookshelf failing says nothing about Navidrome.)
            if (action == ErrorAction.WAIT_FOR_CONNECTION && !c.network.cannotStream && spokenNow == null) c.network.reportServerFailure()
            scope.launch { recover(action) }
        }
    }

    /**
     * A spoken queue restored from the last run started playing: if the server has a newer position
     * (another device), jump there; the UI hears about it through SpokenProgress.resumeNotices.
     */
    private suspend fun checkServerPosition() {
        val now = spokenNow ?: return
        val start = c.spoken.progress.resolveStart(now.libraryItemId, now.episodeId, now.title, online = !c.network.cannotStream)
        if (spokenNow !== now || start.finished) return
        if (start.notice != null || kotlin.math.abs(start.positionSec - spokenPositionSec()) > 2 && start.serverWon) seekSpokenTo(start.positionSec)
    }

    private suspend fun recover(action: ErrorAction) {
        val wasPlaying = player.playWhenReady
        val online = c.network.isOnline.value
        val next = if (action == ErrorAction.SKIP) null else nextPlayableIndex()
        when (val r = PlaybackErrors.recover(action, online, next)) {
            Recovery.SkipNext -> {
                consecutiveErrors++
                // Skip songs that won't load, but don't spin through the whole queue if they're all broken.
                if (consecutiveErrors < 3 && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
            // In the car with patchy signal: keep the music going with what's on the phone.
            is Recovery.JumpTo -> {
                recoveryJob?.cancel()
                player.seekTo(r.index, 0)
                player.prepare()
                if (wasPlaying) player.play()
            }
            // Nothing on the phone left to play: keep the song and position, and pick up when the server's back.
            Recovery.WaitForConnection -> {
                player.pause()
                scheduleRecovery(wasPlaying, waitForConnection = true)
            }
            Recovery.RetryLater -> {
                player.pause()
                scheduleRecovery(wasPlaying, waitForConnection = false)
            }
        }
    }

    /**
     * Index of the next queued song that plays without a connection. When none is left in the player,
     * the first such song still waiting in [pending] is brought in at the end and its index returned.
     */
    private suspend fun nextPlayableIndex(): Int? {
        PlaybackErrors.nextPlayable(player.currentMediaItemIndex, player.mediaItemCount) {
            isOnPhone(player.getMediaItemAt(it).mediaId)
        }?.let { return it }
        val id = takePending(1, ::isOnPhone).firstOrNull() ?: return null
        val song = c.library.songsByIds(listOf(id)).firstOrNull() ?: return null
        player.addMediaItem(song.toMediaItem())
        publishExtras()
        return player.mediaItemCount - 1
    }

    /** Moves on to the next song that's on the phone, if there is one (used while offline). */
    private suspend fun jumpToPlayable() {
        if (isOnPhone(player.currentMediaItem?.mediaId ?: return)) return
        val index = nextPlayableIndex() ?: return
        player.seekTo(index, 0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
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
            // Spoken word comes from Audiobookshelf, not Navidrome: just retry after the backoff.
            while (spokenNow == null && !c.network.isOnline.value) {
                if (c.network.offlineMode) {
                    // Offline mode is the user's choice: no pinging, just wait for it to be switched off.
                    c.network.isOnline.first { it }
                    break
                }
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
        const val CMD_LIKE = "hearth.LIKE"
        const val CMD_PLAY_SPOKEN = "hearth.PLAY_SPOKEN"
        const val CMD_SKIP_BACK = "hearth.SKIP_BACK"
        const val CMD_SKIP_FORWARD = "hearth.SKIP_FORWARD"
        const val CMD_SEEK_SPOKEN = "hearth.SEEK_SPOKEN"
        const val CMD_SET_SPEED = "hearth.SET_SPEED"
        const val CMD_BACK_TO_MUSIC = "hearth.BACK_TO_MUSIC"
        /** Commands any controller may send: the lock-screen / notification buttons. */
        val BUTTON_COMMANDS = setOf(CMD_LIKE, CMD_TOGGLE_SHUFFLE, CMD_SKIP_BACK, CMD_SKIP_FORWARD)
        const val CMD_MOVE_NEXT = "hearth.MOVE_NEXT"
        const val CMD_SLEEP = "hearth.SLEEP"
        const val CMD_SKIP_TO = "hearth.SKIP_TO"
        const val CMD_REMOVE = "hearth.REMOVE"
        const val CMD_MOVE_AFTER = "hearth.MOVE_AFTER"
        const val CMD_RESTORE = "hearth.RESTORE"
        const val CMD_REMOVE_QUEUED = "hearth.REMOVE_QUEUED"
        /** Only this app's own controllers may send these. */
        private val CUSTOM_COMMANDS = listOf(
            CMD_PLAY_SOURCE, CMD_PLAY_NEXT, CMD_ADD_TO_QUEUE, CMD_TOGGLE_SHUFFLE, CMD_LIKE, CMD_MOVE_NEXT, CMD_SLEEP,
            CMD_SKIP_TO, CMD_REMOVE, CMD_MOVE_AFTER, CMD_RESTORE, CMD_REMOVE_QUEUED,
            CMD_PLAY_SPOKEN, CMD_SKIP_BACK, CMD_SKIP_FORWARD, CMD_SEEK_SPOKEN, CMD_SET_SPEED, CMD_BACK_TO_MUSIC,
        )
        const val ARG_ITEM_ID = "absItemId"
        const val ARG_EPISODE_ID = "absEpisodeId"
        /** Seconds on the whole episode/book; absent = resume where this user left off. */
        const val ARG_START_SEC = "startSec"
        /** Milliseconds on the whole episode/book timeline. */
        const val ARG_POSITION_MS = "positionMs"
        const val ARG_SPEED = "speed"
        const val EXTRA_SPOKEN_ITEM = "spokenItemId"
        const val EXTRA_SPOKEN_EPISODE = "spokenEpisodeId"
        const val EXTRA_SPOKEN_TOTAL_MS = "spokenTotalMs"
        const val EXTRA_SPEED = "speed"
        /** True while spoken word plays and the music queue from before can be brought back. */
        const val EXTRA_HAS_MUSIC_QUEUE = "hasMusicQueue"
        const val SLEEP_END_OF_CHAPTER = -2L
        const val SKIP_BACK_SEC = 15.0
        const val SKIP_FORWARD_SEC = 30.0
        const val MIN_SPEED = 0.8f
        const val MAX_SPEED = 2.0f
        const val ARG_SLEEP_MS = "sleepMs"
        const val EXTRA_SLEEP_AT = "sleepAt"
        const val SLEEP_END_OF_SONG = -1L
        /** Queue entry commands: the entry's queue key and song id, checked together when the command runs. */
        const val ARG_KEY = "key"
        const val ARG_SONG_ID = "songId"
        const val ARG_AFTER_KEY = "afterKey"
        const val ARG_BEFORE_KEY = "beforeKey"
        const val ARG_MANUAL = "manual"
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
