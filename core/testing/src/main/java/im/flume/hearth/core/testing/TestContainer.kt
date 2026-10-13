// A stand-in for HearthContainer under Robolectric: real classes, an in-memory database, no server and no player service.
package im.flume.hearth.core.testing

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.core.playback.ContainerOwner
import im.flume.hearth.core.network.NavidromeNativeApi
import im.flume.hearth.core.network.SubsonicClient
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.domain.music.LibraryRepository
import im.flume.hearth.domain.music.LyricsRepository
import im.flume.hearth.domain.music.MixRepository
import im.flume.hearth.core.data.NetworkMonitor
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.domain.music.Usage
import im.flume.hearth.domain.music.download.DownloadRepository
import im.flume.hearth.core.playback.PlayerConnection
import im.flume.hearth.core.playback.PreviewPlayer
import im.flume.hearth.core.playback.QueueInfo
import im.flume.hearth.domain.requests.RequestsRepository
import im.flume.hearth.domain.spoken.SpokenRepository
import im.flume.hearth.domain.music.sync.LibrarySync
import im.flume.hearth.domain.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.File

/**
 * What `LocalContext.current.container` returns in Robolectric tests. Everything is built on first use,
 * like HearthContainer but signed out, with an in-memory database and nothing started (no sync, no
 * request polling, no connection to the playback service). So a screen that reads the container renders
 * its signed-out, empty state, and tests put rows straight into [db] when they need data.
 */
class TestContainer(private val context: Context) : AppContainer {
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    override val http: OkHttpClient by lazy { OkHttpClient() }
    override val session by lazy { SessionStore(context) }
    override val db: AppDatabase by lazy {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
    override val api by lazy { SubsonicClient(http) { session.credentials.value } }
    override val network by lazy { NetworkMonitor(context, api, session, appScope) }
    override val downloads by lazy { DownloadRepository(context, db, session, appScope) }
    override val nativeApi by lazy { NavidromeNativeApi(http, { session.credentials.value }, { null }) }
    override val library by lazy { LibraryRepository(db, api, downloads, session, nativeApi) }
    override val lyrics by lazy { LyricsRepository(api, db.lyrics()) }
    override val mixes by lazy { MixRepository(context, db, api, session) }
    override val usage by lazy { Usage(context) }
    override val sync by lazy { LibrarySync(api, db, session, onSynced = { _, _ -> }) }
    override val spoken by lazy { SpokenRepository(context, db, http, session, network, appScope) }
    // A fixed version: the settings page shows it, and a screenshot must not change at every release.
    override val updater by lazy { Updater(context, http, appScope, currentVersion = "2.0.0") }
    override val requests by lazy { RequestsRepository(context, db, session, api, library, downloads, sync, http, appScope) }
    override val pendingRoute = MutableStateFlow<String?>(null)
    override val mainActivity: Class<out Activity> = Activity::class.java
    override val player by lazy { PlayerConnection(context) }
    override val queueInfo = MutableStateFlow(QueueInfo())
    override val preview by lazy { PreviewPlayer(context, http, { requests.tidarr }, player, appScope) }

    @get:OptIn(UnstableApi::class)
    override val mediaCache: SimpleCache by lazy {
        SimpleCache(File(context.cacheDir, "media-test"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }
}

/**
 * The Application for Robolectric tests that render screens reading `context.container`. [ScreenshotTest]
 * uses it; other Robolectric tests can opt in with `@Config(application = TestApplication::class)`.
 */
class TestApplication : Application(), ContainerOwner {
    override val container: TestContainer by lazy { TestContainer(this) }
}
