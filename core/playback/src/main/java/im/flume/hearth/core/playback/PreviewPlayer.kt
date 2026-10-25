package im.flume.hearth.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import im.flume.hearth.core.network.TidalTrack
import im.flume.hearth.core.network.TidarrClient
import im.flume.hearth.domain.music.PreviewWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 30 second previews of songs that aren't on the server, streamed at low quality straight from the
 * CDN. Its own small ExoPlayer: no MediaSession, so previews never touch the queue, history,
 * scrobbles, stats, downloads or the lock screen. The main player is paused while a preview plays
 * and carries on afterwards if it was playing. One preview at a time.
 */
@OptIn(UnstableApi::class)
class PreviewPlayer(
    private val context: Context,
    private val http: OkHttpClient,
    private val tidarr: () -> TidarrClient,
    private val main: PlayerConnection,
    private val scope: CoroutineScope,
) {
    data class Preview(val track: TidalTrack, val loading: Boolean)

    private val _current = MutableStateFlow<Preview?>(null)
    /** The preview playing (or loading), or null. */
    val current: StateFlow<Preview?> = _current.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    /** How far through the 30 seconds the current preview is, 0..1. */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short messages for a snackbar, e.g. "Preview unavailable". */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var exo: ExoPlayer? = null
    private var job: Job? = null
    /** True when the main player was playing before the first of a run of previews. */
    private var resumeMain = false

    init {
        // Starting the main player (a song tapped, headphones' play button) ends the preview.
        scope.launch(Dispatchers.Main) {
            // `playing` rather than the whole state: this listener never stops, and collecting the state
            // forever would keep a chosen Sonos speaker being asked what it's doing all day.
            var wasPlaying = main.playing.value
            main.playing.collect { isPlaying ->
                if (isPlaying && !wasPlaying && _current.value != null) finish(resume = false)
                wasPlaying = isPlaying
            }
        }
    }

    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .also { exo = it }

    /** Plays a preview of [track]; the same one again stops it, another one switches. */
    fun toggle(track: TidalTrack) {
        scope.launch(Dispatchers.Main) {
            if (_current.value?.track?.id == track.id) finish(resume = true) else start(track)
        }
    }

    fun stop() {
        scope.launch(Dispatchers.Main) { finish(resume = true) }
    }

    private fun start(track: TidalTrack) {
        job?.cancel()
        if (_current.value == null) {
            resumeMain = main.state.value.isPlaying
            if (resumeMain) main.pause()
        }
        _current.value = Preview(track, loading = true)
        _progress.value = 0f
        job = scope.launch(Dispatchers.Main) {
            try {
                play(track)
                finish(resume = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Preview unavailable")
                finish(resume = true)
            }
        }
    }

    private suspend fun play(track: TidalTrack) {
        val stream = withContext(Dispatchers.IO) { tidarr().previewStream(track.id) }
        val durationMs = (track.durationSec ?: 0) * 1000L
        val (start, length) = PreviewWindow.of(durationMs)
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { if (stream.mimeType == "audio/mp4") setMimeType(MimeTypes.AUDIO_MP4) }
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(start)
                    .apply { if (durationMs > 0) setEndPositionMs(start + length) }
                    .build()
            )
            .build()
        val p = player()
        var error: PlaybackException? = null
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = e }
        }
        p.addListener(listener)
        try {
            p.volume = 1f
            p.setMediaItem(item)
            p.prepare()
            p.play()
            val startedAt = System.currentTimeMillis()
            var playing = false
            while (true) {
                error?.let { throw it }
                if (p.playbackState == Player.STATE_ENDED) return
                // Audio focus lost (a call, another app): the preview is over.
                if (!p.playWhenReady) return
                if (p.isPlaying && !playing) {
                    playing = true
                    _current.value = Preview(track, loading = false)
                }
                if (!playing && System.currentTimeMillis() - startedAt > LOAD_TIMEOUT_MS) throw IllegalStateException("Preview took too long")
                val pos = p.currentPosition
                if (pos >= length) return
                _progress.value = (pos.toFloat() / length).coerceIn(0f, 1f)
                p.volume = PreviewWindow.volume(pos, length)
                delay(50)
            }
        } finally {
            p.removeListener(listener)
        }
    }

    private fun finish(resume: Boolean) {
        val wasPreviewing = _current.value != null
        job?.cancel()
        job = null
        exo?.let { it.stop(); it.clearMediaItems() }
        _current.value = null
        _progress.value = 0f
        if (wasPreviewing && resume && resumeMain && !main.state.value.isPlaying) main.resume()
        resumeMain = false
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 15_000L
    }
}
