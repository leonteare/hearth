package im.flume.hearth.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Live audio for the visualiser, without the microphone permission, in sync with what's heard.
 *
 * [wrap] puts a thin layer around the player's audio sink (see PlaybackService). Every PCM buffer
 * going in arrives with its presentation time; while [active] (a visualiser is on screen) it's mixed
 * to mono into a [TimedRing] indexed by that time. A second ring alongside holds the stereo difference
 * (side, (L − R) / 2) at the same times, for the one style that draws left against right; the
 * analysis below only ever reads the mono ring. The same layer notes the sink's own playback
 * position whenever the player asks for it, which already accounts for the AudioTrack's buffer and
 * the output's latency (Bluetooth included, via the track's timestamps).
 *
 * Each drawn frame [analyze] (UI thread) extrapolates that position to now and analyses the audio at
 * exactly that media time: no latency guesses. Seeks flush the sink, which breaks the ring's history;
 * gapless track changes keep one continuous timeline.
 */
@OptIn(UnstableApi::class)
object AudioAnalyzer {
    private val ring = TimedRing()
    /** (L − R) / 2 for every sample in [ring], same indexing; silence for mono sources. */
    private val sideRing = TimedRing()

    /** True while a visualiser is open. Read by the playback thread for every buffer. */
    @Volatile var active = false
        private set

    /** Fine adjustment of the sync, if a device needs it (positive = visuals later). Not exposed yet. */
    @Volatile var offsetMs = 0

    // The sink's last reported position and when it was reported; guarded by [clockLock].
    private val clockLock = Any()
    private var clockUs = C.TIME_UNSET
    private var clockNanos = 0L

    private val block = FloatArray(SpectrumAnalyzer.FFT_SIZE)
    private val hopWindow = FloatArray(BeatTracker.WINDOW)
    private val pipeline = AudioPipeline()
    private var lastAnalyzeNanos = 0L
    private var lastUs = C.TIME_UNSET
    private var hopUs = 0L

    /** The player's audio sink with the visualiser's tap around it. */
    fun wrap(sink: AudioSink): AudioSink = TapSink(sink)

    /** Called by the visualiser when it opens (or comes back to the foreground). */
    fun start() {
        pipeline.reset()
        lastAnalyzeNanos = 0L
        lastUs = C.TIME_UNSET
        ring.discontinuity()
        sideRing.discontinuity()
        active = true
    }

    fun stop() {
        active = false
    }

    /**
     * Analyses the audio playing now and returns the (reused) frame. UI thread only, once per frame.
     * [playing]: the player says it's playing; when false, or the sink's clock has gone quiet, the
     * values fall away smoothly.
     */
    fun analyze(playing: Boolean): AudioFrame {
        val now = System.nanoTime()
        val dt = if (lastAnalyzeNanos == 0L) 1f / 60f else ((now - lastAnalyzeNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastAnalyzeNanos = now
        val posUs: Long
        val posNanos: Long
        synchronized(clockLock) { posUs = clockUs; posNanos = clockNanos }

        var tUs = C.TIME_UNSET
        if (playing && posUs != C.TIME_UNSET && now - posNanos < 300_000_000L) {
            // Between reports the audio keeps going: move on by the time since the last one.
            tUs = posUs + min(now - posNanos, 120_000_000L) / 1000L - offsetMs * 1000L
            val last = lastUs
            if (last != C.TIME_UNSET && tUs < last && last - tUs < 40_000L) tUs = last // tiny clock jitter: hold still
            if (last == C.TIME_UNSET || tUs < last - 40_000L || tUs > last + 600_000L) {
                // First frame, or a jump: start the onset analysis a little way back.
                if (last != C.TIME_UNSET) pipeline.discontinuity()
                hopUs = tUs - 300_000L
            }
            lastUs = tUs
        } else {
            lastUs = C.TIME_UNSET
        }

        var fresh = false
        if (tUs != C.TIME_UNSET) {
            if (tUs - hopUs > 600_000L) hopUs = tUs - 300_000L
            while (hopUs + HOP_US <= tUs) {
                hopUs += HOP_US
                if (ring.read(hopUs, hopWindow)) pipeline.hop(hopWindow, ring.lastRate, hopUs / 1e6)
            }
            fresh = ring.read(tUs, block)
        }
        pipeline.update(if (fresh) block else null, ring.lastRate, dt, if (fresh) tUs / 1e6 else Double.NaN)
        return pipeline.frame
    }

    /**
     * Copies the stereo audio of the moment the last [analyze] showed into [out]: its last
     * [StereoWindow.size] sample pairs as mid and side. UI thread, after [analyze]. When there's
     * nothing to show (paused, seeking, not written yet) [StereoWindow.valid] is false.
     */
    fun readStereo(out: StereoWindow) {
        val t = lastUs
        out.valid = t != C.TIME_UNSET && ring.read(t, out.mid) && sideRing.read(t, out.side)
    }

    private val HOP_US = (BeatTracker.HOP_SEC * 1e6).toLong()

    private class TapSink(sink: AudioSink) : ForwardingAudioSink(sink) {
        private var rate = 44_100
        private var channels = 2
        private var encoding = C.ENCODING_INVALID
        private var lastBuffer: ByteBuffer? = null
        private var lastPts = Long.MIN_VALUE

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            rate = inputFormat.sampleRate
            channels = inputFormat.channelCount.coerceAtLeast(1)
            encoding = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW) inputFormat.pcmEncoding else C.ENCODING_INVALID
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            // A buffer the sink couldn't take in full comes back again: only copy it the first time.
            if (active && (buffer !== lastBuffer || presentationTimeUs != lastPts)) {
                lastBuffer = buffer
                lastPts = presentationTimeUs
                copy(buffer, presentationTimeUs)
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val p = super.getCurrentPositionUs(sourceEnded)
            if (p != AudioSink.CURRENT_POSITION_NOT_SET) {
                val t = System.nanoTime()
                synchronized(clockLock) { clockUs = p; clockNanos = t }
            }
            return p
        }

        override fun flush() {
            super.flush()
            lastBuffer = null
            ring.discontinuity()
            sideRing.discontinuity()
        }

        override fun reset() {
            super.reset()
            lastBuffer = null
            ring.discontinuity()
            sideRing.discontinuity()
        }

        /**
         * Mixes the buffer to mono into the ring, and writes the first two channels' difference into
         * the side ring. Reads by absolute index: the buffer isn't disturbed.
         */
        private fun copy(b: ByteBuffer, ptsUs: Long) {
            val enc = encoding
            val bytes = when (enc) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
                C.ENCODING_PCM_24BIT -> 3
                else -> return
            }
            val ch = channels
            val frames = b.remaining() / (bytes * ch)
            if (frames <= 0) return
            ring.beginChunk(ptsUs, rate)
            sideRing.beginChunk(ptsUs, rate)
            var p = b.position()
            val scale = 1f / ch
            // PCM in the sink is little-endian (native order on Android).
            for (n in 0 until frames) {
                var sum = 0f
                var side = 0f
                for (c in 0 until ch) {
                    val v = when (enc) {
                        C.ENCODING_PCM_16BIT -> ((b.get(p).toInt() and 0xFF) or (b.get(p + 1).toInt() shl 8)) / 32768f
                        C.ENCODING_PCM_FLOAT -> Float.fromBits(int32(b, p))
                        C.ENCODING_PCM_32BIT -> int32(b, p) / 2147483648f
                        else -> ((b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)) / 8388608f
                    }
                    sum += v
                    if (c == 0) side += v else if (c == 1) side -= v
                    p += bytes
                }
                ring.put(sum * scale)
                sideRing.put(if (ch >= 2) side * 0.5f else 0f)
            }
            ring.commit()
            sideRing.commit()
        }

        private fun int32(b: ByteBuffer, p: Int): Int =
            (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or
                ((b.get(p + 2).toInt() and 0xFF) shl 16) or (b.get(p + 3).toInt() shl 24)
    }
}

/**
 * The last moments of stereo audio, as mid ((L + R) / 2) and side ((L − R) / 2), oldest first, so
 * L = mid + side and R = mid − side. Filled in place each frame by [AudioAnalyzer.readStereo].
 */
class StereoWindow(val size: Int = 2048) {
    val mid = FloatArray(size)
    val side = FloatArray(size)
    /** False when there was no audio to read this frame (paused, just seeked); the arrays are stale. */
    var valid = false
}
