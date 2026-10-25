package im.flume.hearth.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.flow.MutableStateFlow
import im.flume.hearth.core.domain.DomainContainer
import im.flume.hearth.core.domain.DomainContainerOwner

/**
 * Everything screens, components and the playback service reach through `context.container`: the
 * [DomainContainer] singletons plus the player. HearthApp builds the one real container (HearthContainer).
 */
interface AppContainer : DomainContainer {
    val player: PlayerConnection
    val queueInfo: MutableStateFlow<QueueInfo>

    /** Where the sound comes out: this phone, or a Sonos speaker that has been handed the queue. */
    val sonos: SonosOutput

    /** 30 second previews of music that isn't on the server; separate from the main player. */
    val preview: PreviewPlayer

    /** Recently streamed audio, so replays are instant and work with no signal. One instance per process. */
    @get:OptIn(UnstableApi::class)
    val mediaCache: SimpleCache
}

/** The Application, which owns the container. */
interface ContainerOwner : DomainContainerOwner {
    override val container: AppContainer
}

val Context.container: AppContainer get() = (applicationContext as ContainerOwner).container
