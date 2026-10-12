package im.flume.hearth.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import im.flume.hearth.core.domain.R
import im.flume.hearth.container
import im.flume.hearth.crashLoggingHandler
import im.flume.hearth.spoken.SpokenSpeed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The background service that owns the player, the MediaSession and the queue state beyond what the
 * player holds. This file is the lifecycle and wiring; the behaviour lives in extensions and helpers
 * beside it: [buildPlayer] (PlayerSetup.kt), QueueEdits.kt, SpokenMode.kt, StreamCaching.kt,
 * QueuePersistence.kt, PlaybackRecovery.kt, SleepTimer.kt, VolumeLevelling.kt, SessionExtras.kt,
 * [SessionCallback], [PlayerListener] and [SpokenAwarePlayer].
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    internal val c by lazy { container }
    internal val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashLoggingHandler(this, "playback")) }

    internal lateinit var player: ExoPlayer
    internal lateinit var session: MediaSession
    internal lateinit var store: QueueStore
    internal lateinit var scrobbler: Scrobbler

    // Queue state beyond what the player holds (see QueuePlanner).
    internal var context: List<String> = emptyList()
    internal var pending: ArrayDeque<String> = ArrayDeque()
    internal var shuffled = false
    internal var sourceLabel = ""
    internal var restoring: Job? = null
    internal lateinit var streamFactory: CacheDataSource.Factory
    internal var prefetchJob: Job? = null
    /** CacheWriter.cache() blocks and ignores coroutine cancellation, so writers are cancelled directly. */
    internal val prefetchWriters = CopyOnWriteArraySet<CacheWriter>()
    /** Queue edits that suspend (database lookups) run one at a time, in the order they arrived. */
    internal val queueOps = Mutex()
    internal var trimmed = 0
    internal var sleepJob: Job? = null
    /** Epoch ms when playback will pause, [SLEEP_END_OF_SONG], or 0 for no timer. */
    internal var sleepAt = 0L
    internal var consecutiveErrors = 0
    internal var recoveryJob: Job? = null
    internal var recoveryAttempts = 0
    internal var ticker: Job? = null
    /** Song whose "now playing" and prefetch wait until playback actually starts (e.g. a restored queue). */
    internal var startPending: MediaItem? = null
    /** Bitrate each song's stream was first opened at, so reconnects resume the same encoding. */
    internal val pinnedBitrates = ConcurrentHashMap<String, Int>()

    /** Set while the queue is a podcast episode or audiobook (a queue is either music or spoken word). */
    internal var spokenNow: SpokenNow? = null
    internal lateinit var tracker: ListeningTracker
    /** A spoken queue restored at start: check the server's position the first time it plays. */
    internal var spokenNeedsServerCheck = false
    /** Follows a playing book's details in the library (see followBookDetails). */
    internal var bookDetailsJob: Job? = null
    /** Wi-Fi lock or CPU-only wake lock, whichever the songs coming up need (see updateWakeMode). */
    internal var wakeMode = C.WAKE_MODE_NETWORK
    /** Song id now playing, for the lock-screen Like button. */
    internal val currentSongId = MutableStateFlow<String?>(null)
    internal var currentLiked = false

    override fun onCreate() {
        super.onCreate()
        store = QueueStore(filesDir)
        scrobbler = Scrobbler(c.api, c.db.library(), c.appScope)
        tracker = ListeningTracker(c.spoken.progress)

        player = buildPlayer()
        player.addListener(PlayerListener(this))

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, c.mainActivity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SpokenAwarePlayer(this, player))
            .setCallback(SessionCallback(this))
            .setSessionActivity(openApp)
            // Artwork URIs are token-free hearth-cover:// / abs-cover:// links; this resolves them in the app.
            .setBitmapLoader(CoverBitmapLoader(this, c.api, scope) { id -> c.spoken.coverUrl(id, 1000) })
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) }
        )

        // Like and Shuffle buttons on the lock screen / notification follow the current song and mode.
        @OptIn(ExperimentalCoroutinesApi::class)
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
            runBlocking { withTimeoutOrNull(2_000) { tracker.pause(pos, send = false) } }
            c.spoken.progress.scheduleFlush()
        }
        saveQueueBlocking()
        c.network.setPlaying(false)
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
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
        internal val CUSTOM_COMMANDS = listOf(
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
        const val MIN_SPEED = SpokenSpeed.MIN
        const val MAX_SPEED = SpokenSpeed.MAX
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
        internal const val TICK_MS = 1000L
        /** Most songs ever prefetched ahead (on Wi-Fi); their pinned bitrates are kept. */
        internal const val PREFETCH_COUNT = PrefetchPolicy.UNMETERED
        internal const val MAX_RECOVERY_ATTEMPTS = 6
    }
}
