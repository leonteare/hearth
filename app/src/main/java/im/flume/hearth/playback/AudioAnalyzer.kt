package im.flume.hearth.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Live audio for the visualiser, without the microphone permission: [tap] sits in the main player's
 * audio pipeline (see PlaybackService) and copies the PCM about to be played, mixed to mono, into a
 * ring buffer. Only while [active] (a visualiser is on screen); otherwise it returns at once.
 *
 * The audio thread only writes samples. The analysis runs on the UI thread in [analyze], once per
 * drawn frame, on the block that's leaving the speaker about now: PCM reaches the tap roughly one
 * AudioTrack buffer ([latencyMs]) before it's heard.
 */
@OptIn(UnstableApi::class)
object AudioAnalyzer {
    private const val RING_SIZE = 1 shl 16
    private const val RING_MASK = RING_SIZE - 1
    private const val FFT = SpectrumAnalyzer.FFT_SIZE

    private val ring = FloatArray(RING_SIZE)
    @Volatile private var written = 0L
    @Volatile private var lastWriteNanos = 0L
    @Volatile private var sampleRate = 44_100
    @Volatile private var channelCount = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT
    /** Samples written before the visualiser opened are stale: analysis waits until it has fresh ones. */
    @Volatile private var startedAt = 0L

    /** True while a visualiser is open. Read by the audio thread for every buffer. */
    @Volatile var active = false
        private set

    /**
     * Time between PCM reaching the tap and coming out of the speaker: Media3's AudioTrack buffer
     * (~250 ms for PCM) plus the device's output latency. Bluetooth adds more on top.
     */
    @Volatile var latencyMs = 270

    private val block = FloatArray(FFT)
    private val pipeline = AudioPipeline()
    private var lastAnalyzeNanos = 0L

    val tap: TeeAudioProcessor.AudioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this@AudioAnalyzer.sampleRate = sampleRateHz
            this@AudioAnalyzer.channelCount = channelCount.coerceAtLeast(1)
            this@AudioAnalyzer.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (active) write(buffer)
        }
    }

    /** Called by the visualiser when it opens (or comes back to the foreground). */
    fun start() {
        pipeline.reset()
        lastAnalyzeNanos = 0L
        startedAt = written
        active = true
    }

    fun stop() {
        active = false
    }

    private fun write(buffer: ByteBuffer) {
        // The tee hands over a read-only duplicate, which is big-endian by default: PCM is native order.
        val b = buffer.order(ByteOrder.nativeOrder())
        val ch = channelCount
        val enc = encoding
        val bytes = when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            else -> return
        }
        val frames = b.remaining() / (bytes * ch)
        var p = b.position()
        var w = written
        val scale = 1f / ch
        for (n in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) {
                sum += when (enc) {
                    C.ENCODING_PCM_16BIT -> b.getShort(p) / 32768f
                    C.ENCODING_PCM_FLOAT -> b.getFloat(p)
                    C.ENCODING_PCM_32BIT -> b.getInt(p) / 2147483648f
                    else -> {
                        val v = (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)
                        v / 8388608f
                    }
                }
                p += bytes
            }
            ring[(w and RING_MASK.toLong()).toInt()] = sum * scale
            w++
        }
        written = w
        lastWriteNanos = System.nanoTime()
    }

    /**
     * Analyses the audio playing now and returns the (reused) frame. UI thread only, once per frame.
     * [playing]: the player says it's playing; when false, or no audio has arrived lately, the values
     * fall away smoothly.
     */
    fun analyze(playing: Boolean): AudioFrame {
        val now = System.nanoTime()
        val dt = if (lastAnalyzeNanos == 0L) 1f / 60f else ((now - lastAnalyzeNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastAnalyzeNanos = now
        val rate = sampleRate
        val w = written
        val since = now - lastWriteNanos
        var fresh = false
        if (playing && since < 250_000_000L) {
            // Between writes the speaker keeps going: move the read point on by the time since the last one.
            val ahead = min(since, 60_000_000L) * rate / 1_000_000_000L
            val end = min(w, w + ahead - latencyMs.toLong() * rate / 1000L)
            if (end - FFT >= startedAt && w - end + FFT < RING_SIZE) {
                var i = end - FFT
                for (k in 0 until FFT) {
                    block[k] = ring[(i and RING_MASK.toLong()).toInt()]
                    i++
                }
                fresh = true
            }
        }
        pipeline.update(if (fresh) block else null, rate, dt)
        return pipeline.frame
    }
}
