// The spoken-word singletons the background jobs in :domain:spoken reach through the Application.
package im.flume.hearth.domain.spoken

import android.content.Context
import im.flume.hearth.core.data.AppDatabase

/** The spoken-word part of the app's container; [DomainContainer] extends it. */
interface SpokenContainer {
    val db: AppDatabase

    /** Podcasts and audiobooks from Audiobookshelf (separate login, own catalogue, progress and downloads). */
    val spoken: SpokenRepository
}

/** The Application, seen from this module (it really owns a [DomainContainer]). */
interface SpokenContainerOwner {
    val container: SpokenContainer
}

/** For workers in this module. */
internal val Context.spokenContainer: SpokenContainer get() = (applicationContext as SpokenContainerOwner).container
