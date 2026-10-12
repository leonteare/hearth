package im.flume.hearth

import android.app.Activity
import android.content.Context
import im.flume.hearth.api.NavidromeNativeApi
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.LibraryRepository
import im.flume.hearth.data.LyricsRepository
import im.flume.hearth.data.MixRepository
import im.flume.hearth.data.NetworkMonitor
import im.flume.hearth.data.SessionStore
import im.flume.hearth.data.Usage
import im.flume.hearth.download.DownloadRepository
import im.flume.hearth.requests.RequestsRepository
import im.flume.hearth.spoken.SpokenRepository
import im.flume.hearth.sync.LibrarySync
import im.flume.hearth.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

/**
 * The app's singletons below the player. HearthApp builds the one real container by hand (no DI
 * framework, see DECISIONS.md). Screens see [AppContainer], which adds the player on top.
 */
interface DomainContainer {
    val appScope: CoroutineScope
    val http: OkHttpClient
    val session: SessionStore
    val db: AppDatabase
    val api: SubsonicClient
    val network: NetworkMonitor
    val downloads: DownloadRepository
    val nativeApi: NavidromeNativeApi
    val library: LibraryRepository
    val lyrics: LyricsRepository
    val mixes: MixRepository
    val usage: Usage
    val sync: LibrarySync

    /** Podcasts and audiobooks from Audiobookshelf (separate login, own catalogue, progress and downloads). */
    val spoken: SpokenRepository
    val updater: Updater
    val requests: RequestsRepository

    /** A page to open, e.g. from a notification tap; AppRoot navigates there and clears it. */
    val pendingRoute: MutableStateFlow<String?>

    /** The screen a notification tap opens. It lives in :app, which this layer can't name. */
    val mainActivity: Class<out Activity>
}

/** The Application, which owns the container; lets workers and receivers below :app find it. */
interface DomainContainerOwner {
    val container: DomainContainer
}

/** Intent extra naming a page to open (see [DomainContainer.pendingRoute]), e.g. from a notification. */
const val EXTRA_ROUTE = "im.flume.hearth.ROUTE"

/** For workers and receivers in this module; screens use `context.container`, which also has the player. */
internal val Context.domainContainer: DomainContainer get() = (applicationContext as DomainContainerOwner).container
