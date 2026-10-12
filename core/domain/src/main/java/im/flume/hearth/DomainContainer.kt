package im.flume.hearth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's singletons below the player. HearthApp builds the one real container by hand (no DI
 * framework, see DECISIONS.md). Screens see [AppContainer], which adds the player on top.
 * Each domain module declares the part its own workers need ([MusicContainer], [SpokenContainer],
 * [RequestsContainer], [UpdateContainer]), so they find this container without depending on this module.
 */
interface DomainContainer : MusicContainer, SpokenContainer, RequestsContainer, UpdateContainer {
    /** A page to open, e.g. from a notification tap; AppRoot navigates there and clears it. */
    val pendingRoute: MutableStateFlow<String?>
}

/** The Application, which owns the container; lets workers and receivers below :app find it. */
interface DomainContainerOwner : MusicContainerOwner, SpokenContainerOwner, RequestsContainerOwner, UpdateContainerOwner {
    override val container: DomainContainer
}

/** For workers and receivers; screens use `context.container`, which also has the player. */
internal val Context.domainContainer: DomainContainer get() = (applicationContext as DomainContainerOwner).container
