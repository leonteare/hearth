// The music singletons the background jobs in :domain:music reach through the Application.
package im.flume.hearth.domain.music

import android.content.Context
import im.flume.hearth.core.network.NavidromeNativeApi
import im.flume.hearth.core.network.SubsonicClient
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.NetworkMonitor
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.domain.music.download.CoverStore
import im.flume.hearth.domain.music.download.DownloadRepository
import im.flume.hearth.domain.music.sync.LibrarySync
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * The music part of the app's container. [DomainContainer] extends it, so workers in this module can
 * find the one real container without knowing about spoken word, requests or the player.
 */
interface MusicContainer {
    val appScope: CoroutineScope
    val http: OkHttpClient
    val session: SessionStore
    val db: AppDatabase
    val api: SubsonicClient
    val network: NetworkMonitor
    val downloads: DownloadRepository
    val covers: CoverStore
    val nativeApi: NavidromeNativeApi
    val library: LibraryRepository
    val likeOutbox: LikeOutbox
    val lyrics: LyricsRepository
    val mixes: MixRepository
    val usage: Usage
    val sync: LibrarySync
}

/** The Application, seen from this module (it really owns a [DomainContainer]). */
interface MusicContainerOwner {
    val container: MusicContainer
}

/** For workers in this module. */
internal val Context.musicContainer: MusicContainer get() = (applicationContext as MusicContainerOwner).container
