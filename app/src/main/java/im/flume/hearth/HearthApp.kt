package im.flume.hearth

import im.flume.hearth.ui.MainActivity
import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import im.flume.hearth.core.network.NavidromeNativeApi
import im.flume.hearth.core.network.SubsonicClient
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.data.LibraryRepository
import im.flume.hearth.data.LyricsRepository
import im.flume.hearth.data.MixRepository
import im.flume.hearth.core.data.NetworkMonitor
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.data.Usage
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.core.playback.CoverCacheKeys
import im.flume.hearth.core.playback.PlayerConnection
import im.flume.hearth.core.playback.PreviewPlayer
import im.flume.hearth.core.playback.QueueInfo
import im.flume.hearth.requests.RequestsRepository
import im.flume.hearth.spoken.SpokenRepository
import im.flume.hearth.sync.LibrarySync
import im.flume.hearth.update.Updater
import im.flume.hearth.core.ui.theme.accentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import im.flume.hearth.core.common.crashLoggingHandler
import im.flume.hearth.core.common.writeCrash
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.core.playback.ContainerOwner
import im.flume.hearth.core.playback.container

class HearthApp : Application(), SingletonImageLoader.Factory, ContainerOwner {
    override lateinit var container: HearthContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Keep the details of a crash so they can be copied from Home next time the app opens.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            writeCrash(this, "thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
        container = HearthContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(CoverCacheKeys)
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("covers"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
}

/** Hand-wired singletons; small enough that a DI framework would be overkill. */
class HearthContainer(context: Context) : AppContainer {
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashLoggingHandler(context, "app"))

    override val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override val session = SessionStore(context)
    override val db = AppDatabase.create(context)
    override val api = SubsonicClient(http) { session.credentials.value }
    override val network = NetworkMonitor(context, api, session, appScope)
    override val downloads = DownloadRepository(context, db, session, appScope)
    override val nativeApi = NavidromeNativeApi(http, { session.credentials.value }, { session.vault.read() })
    override val library = LibraryRepository(db, api, downloads, session, nativeApi)
    init { downloads.onUserDownload = { ids -> library.keepMixSongs(ids) } }
    override val lyrics = LyricsRepository(api, db.lyrics())
    override val mixes = MixRepository(context, db, api, session)
    override val usage = Usage(context)
    override val sync = LibrarySync(api, db, session, onSynced = { removed, complete -> downloads.afterSync(removed, complete) })
    override val player = PlayerConnection(context)
    /** Podcasts and audiobooks from Audiobookshelf (separate login, own catalogue, progress and downloads). */
    override val spoken = SpokenRepository(context, db, http, session, network, appScope)
    override val queueInfo = MutableStateFlow(QueueInfo())
    override val updater = Updater(context, http, appScope)
    override val requests = RequestsRepository(context, db, session, api, library, downloads, sync, http, appScope)
    /** 30 second previews of music that isn't on the server; separate from the main player. */
    override val preview = PreviewPlayer(context, http, { requests.tidarr }, player, appScope)

    /** A page to open, e.g. from a notification tap; AppRoot navigates there and clears it. */
    override val pendingRoute = MutableStateFlow<String?>(null)
    override val mainActivity = MainActivity::class.java

    init {
        appScope.launch(Dispatchers.Main) {
            session.settings.collect { accentState.value = Color(it.accent) }
        }
        saveDownloadedAlbumsOnce()
        requests.start()
        spoken.start()
        spoken.feeds.onReadyToPlay = { id, episodeId -> player.playEpisode(id, episodeId) }
    }

    /**
     * Your Library became personal (saved albums only). Albums people already kept offline are
     * clearly theirs, so save them on the first run; retried next launch if the server can't be reached.
     */
    private fun saveDownloadedAlbumsOnce() {
        if (session.savedDownloadedAlbums || session.credentials.value == null) return
        appScope.launch {
            if (library.saveDownloadedAlbums()) session.savedDownloadedAlbums = true
        }
    }

    /** Recently streamed audio, so replays are instant and work with no signal. One instance per process. */
    @OptIn(UnstableApi::class)
    override val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(session.settings.value.cacheSizeMb * 1024L * 1024L),
            StandaloneDatabaseProvider(context),
        )
    }
}
