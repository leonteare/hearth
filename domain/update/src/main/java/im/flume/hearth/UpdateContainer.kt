// The self-updater, as the install-result receiver in :domain:update reaches it through the Application.
package im.flume.hearth

import android.content.Context
import im.flume.hearth.update.Updater

/** The self-update part of the app's container; [DomainContainer] extends it. */
interface UpdateContainer {
    val updater: Updater
}

/** The Application, seen from this module (it really owns a [DomainContainer]). */
interface UpdateContainerOwner {
    val container: UpdateContainer
}

/** For the install-result receiver. */
internal val Context.updateContainer: UpdateContainer get() = (applicationContext as UpdateContainerOwner).container
