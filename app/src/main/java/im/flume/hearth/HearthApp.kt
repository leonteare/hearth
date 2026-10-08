package im.flume.hearth

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.LibraryRepository
import im.flume.hearth.data.LyricsRepository
import im.flume.hearth.data.NetworkMonitor
import im.flume.hearth.data.SessionStore
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.playback.PlayerConnection
import im.flume.hearth.sync.LibrarySync
import im.flume.hearth.update.Updater
import im.flume.hearth.ui.theme.accentState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
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

fun crashFile(context: Context) = java.io.File(context.filesDir, "last-crash.txt")

/** Saves [e] so it can be copied from Home next time the app opens. Never throws. */
fun writeCrash(context: Context, where: String, e: Throwable) {
    runCatching {
        crashFile(context).writeText(
            "Hearth ${BuildConfig.VERSION_NAME} on Android ${android.os.Build.VERSION.RELEASE}, " +
                "${java.util.Date()}, $where\n\n${e.stackTraceToString()}"
        )
    }
}

/**
 * For long-lived scopes: a failed background job (e.g. a database error) is recorded instead of
 * crashing the app. Cancellation never reaches a handler, so it still propagates normally.
 */
fun crashLoggingHandler(context: Context, name: String) = CoroutineExceptionHandler { _, e ->
    writeCrash(context, "caught in $name", e)
}

class HearthApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Keep the details of a crash so they can be copied from Home next time the app opens.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            writeCrash(this, "thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("covers"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
}

/** Hand-wired singletons; small enough that a DI framework would be overkill. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashLoggingHandler(context, "app"))

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val session = SessionStore(context)
    val db = AppDatabase.create(context)
    val api = SubsonicClient(http) { session.credentials.value }
    val network = NetworkMonitor(context, api, session, appScope)
    val downloads = DownloadRepository(context, db, session, appScope)
    val library = LibraryRepository(db, api, downloads)
    val lyrics = LyricsRepository(api, db.lyrics())
    val mixes = im.flume.hearth.data.MixRepository(context, db, api, session)
    val usage = im.flume.hearth.data.Usage(context)
    val sync = LibrarySync(api, db, session, onPlaylistsSynced = { downloads.refreshPinned() })
    val player = PlayerConnection(context)
    val queueInfo = kotlinx.coroutines.flow.MutableStateFlow(im.flume.hearth.playback.QueueInfo())
    val updater = Updater(context, http, appScope)

    init {
        appScope.launch(Dispatchers.Main) {
            session.settings.collect { accentState.value = androidx.compose.ui.graphics.Color(it.accent) }
        }
        saveDownloadedAlbumsOnce()
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
    @androidx.annotation.OptIn(UnstableApi::class)
    val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(session.settings.value.cacheSizeMb * 1024L * 1024L),
            StandaloneDatabaseProvider(context),
        )
    }
}

val Context.container: AppContainer get() = (applicationContext as HearthApp).container
