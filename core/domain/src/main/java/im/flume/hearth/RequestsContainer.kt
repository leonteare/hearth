// The request singletons the background jobs in :domain:requests reach through the Application.
package im.flume.hearth

import android.app.Activity
import android.content.Context
import im.flume.hearth.requests.RequestsRepository

/** The Tidarr-requests part of the app's container; [DomainContainer] extends it. */
interface RequestsContainer {
    val requests: RequestsRepository

    /** The screen a notification tap opens. It lives in :app, which this layer can't name. */
    val mainActivity: Class<out Activity>
}

/** The Application, seen from this module (it really owns a [DomainContainer]). */
interface RequestsContainerOwner {
    val container: RequestsContainer
}

/** For the worker and notifications in this module. */
internal val Context.requestsContainer: RequestsContainer get() = (applicationContext as RequestsContainerOwner).container
