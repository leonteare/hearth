@file:OptIn(UnstableApi::class)

package im.flume.hearth.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/*
 * Builds PlaybackService's ExoPlayer and the data source chain behind it: downloaded file, then the
 * streaming cache, then the server, plus spoken word from Audiobookshelf or a folder on the phone.
 */

/** Sets up [PlaybackService.streamFactory] and returns the player; the service adds its listener and session. */
internal fun PlaybackService.buildPlayer(): ExoPlayer {
    streamFactory = CacheDataSource.Factory()
        .setCache(c.mediaCache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(c.http))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    // Reads the streaming cache only (no upstream): used when streaming isn't allowed or possible.
    val cacheOnlyFactory = CacheDataSource.Factory()
        .setCache(c.mediaCache)
        .setUpstreamDataSourceFactory(null)
        .setCacheWriteDataSinkFactory(null)
    val songFactory = SongDataSource.Factory(
        fileFactory = FileDataSource.Factory(),
        streamFactory = streamFactory,
        cacheOnlyFactory = cacheOnlyFactory,
        localFile = c.downloads::localFile,
        streamTarget = this::streamTarget,
        cachedTarget = { id -> cachedBitrate(id)?.let { SongDataSource.StreamTarget("hearth-cache://$id", "$id@$it") } },
        cannotStream = { c.network.cannotStream },
    )
    // Podcast episodes and audiobooks: downloaded file, or the server with the token in a header.
    // Not cached: a long book would push every song out of the streaming cache.
    val spokenFactory = SpokenDataSource.Factory(
        fileFactory = FileDataSource.Factory(),
        httpFactory = OkHttpDataSource.Factory(c.spoken.api.http),
        localFile = c.spoken.downloads::localFile,
        remoteUrl = { item, ino -> runCatching { c.spoken.api.fileUrl(item, ino) }.getOrNull() },
        cannotStream = { c.network.cannotStream },
    )
    // Audiobooks from a folder on the phone play straight from their content:// URIs.
    val routedFactory = SchemeRoutingDataSourceFactory(songFactory, mapOf(SpokenMedia.SCHEME to spokenFactory, "content" to DefaultDataSource.Factory(this)))

    // An offline failure is final for that song: don't retry it, so playback moves on at once.
    val loadErrors = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (generateSequence<Throwable>(info.exception) { it.cause }.any { it is SongDataSource.OfflineException }) C.TIME_UNSET
            else super.getRetryDelayMsFor(info)
    }

    // Same audio sink as Media3's default, wrapped so the visualiser gets the PCM with its
    // presentation times and the sink's playback clock (only copied while one is open). Audio
    // offload stays off (the default): offloaded audio never passes through as PCM.
    val renderers = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            AudioAnalyzer.wrap(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .build()
            )
    }

    return ExoPlayer.Builder(this, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(routedFactory).setLoadErrorHandlingPolicy(loadErrors))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        // Starts with the Wi-Fi lock; updateWakeMode drops it while the music comes from the phone.
        .setWakeMode(wakeMode)
        .build()
}
