package im.flume.hearth.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place radix-2 complex FFT of a fixed power-of-two [size]. Twiddles and the bit-reversal table
 * are built once, so [transform] allocates nothing.
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    private val bitReverse = IntArray(size)
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)

    init {
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in 0 until size / 2) {
            val a = -2.0 * PI * i / size
            cosTable[i] = cos(a).toFloat()
            sinTable[i] = sin(a).toFloat()
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += len
            }
            len *= 2
        }
    }
}

/**
 * Spectrum of one block of mono samples: Hann window, FFT, then [bandCount] log-spaced bands between
 * [minHz] and [maxHz], each mapped from decibels to 0..1. Allocation-free after construction (band
 * edges are rebuilt only when the sample rate changes).
 */
class SpectrumAnalyzer(
    val fftSize: Int = FFT_SIZE,
    val bandCount: Int = BAND_COUNT,
    val minHz: Float = 32f,
    val maxHz: Float = 16_000f,
) {
    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    /** Magnitude per bin 0..fftSize/2, scaled so a full-scale sine peaks at about 1. */
    val magnitudes = FloatArray(fftSize / 2 + 1)
    /** Latest band values, 0..1, unsmoothed. */
    val bands = FloatArray(bandCount)
    /** RMS of the block (linear, full scale = 1 for a square wave). */
    var rms = 0f
        private set
    /** Low-frequency (40–160 Hz) amplitude, linear: what the beat detector listens to. */
    var bassEnergy = 0f
        private set

    private val edgeBins = FloatArray(bandCount + 1)
    private val tilt = FloatArray(bandCount)
    private val centreHz = FloatArray(bandCount)
    private var rate = 0

    /** Index of the band whose range holds [hz] at [sampleRate] (clamped to the ends). */
    fun bandForHz(hz: Float, sampleRate: Int): Int {
        ensureRate(sampleRate)
        val top = topHz(sampleRate)
        if (hz <= minHz) return 0
        if (hz >= top) return bandCount - 1
        val t = ln(hz / minHz) / ln(top / minHz)
        return (t * bandCount).toInt().coerceIn(0, bandCount - 1)
    }

    /** Centre frequency of band [i] at the last sample rate processed. */
    fun centreHz(i: Int): Float = centreHz[i]

    private fun topHz(sampleRate: Int) = min(maxHz, sampleRate * 0.5f * 0.95f)

    private fun ensureRate(sampleRate: Int) {
        if (sampleRate == rate) return
        rate = sampleRate
        val top = topHz(sampleRate)
        val binHz = sampleRate.toFloat() / fftSize
        for (i in 0..bandCount) {
            val hz = minHz * (top / minHz).pow(i.toFloat() / bandCount)
            edgeBins[i] = hz / binHz
        }
        for (i in 0 until bandCount) {
            val c = minHz * (top / minHz).pow((i + 0.5f) / bandCount)
            centreHz[i] = c
            // Music gets quieter towards the treble: lift it ~3 dB per octave around 1 kHz so every band moves.
            tilt[i] = 3f * (ln(c / 1000f) / ln(2f))
        }
    }

    /** Analyses the first [fftSize] samples of [samples] (mono, -1..1). */
    fun process(samples: FloatArray, sampleRate: Int) {
        ensureRate(sampleRate)
        var sq = 0.0
        for (i in 0 until fftSize) {
            val s = samples[i]
            sq += s * s
            re[i] = s * window[i]
            im[i] = 0f
        }
        rms = sqrt(sq / fftSize).toFloat()
        fft.transform(re, im)
        val norm = 4f / fftSize
        for (i in magnitudes.indices) magnitudes[i] = sqrt(re[i] * re[i] + im[i] * im[i]) * norm

        val binHz = sampleRate.toFloat() / fftSize
        val lo = max(1, (40f / binHz).toInt())
        val hi = max(lo, (160f / binHz).toInt())
        var e = 0f
        for (i in lo..hi) e += magnitudes[i] * magnitudes[i]
        bassEnergy = sqrt(e / (hi - lo + 1))

        val last = magnitudes.size - 1
        for (b in 0 until bandCount) {
            val a = edgeBins[b]
            val z = edgeBins[b + 1]
            val mag = if (z - a < 1.5f) {
                // Narrow (bass) band: read between bins so neighbouring bands don't step together.
                val pos = ((a + z) * 0.5f).coerceIn(0f, last.toFloat())
                val i0 = pos.toInt().coerceAtMost(last - 1)
                val f = pos - i0
                magnitudes[i0] * (1 - f) + magnitudes[i0 + 1] * f
            } else {
                var m = 0f
                val from = a.toInt().coerceIn(1, last)
                val to = z.toInt().coerceIn(from, last)
                for (i in from..to) if (magnitudes[i] > m) m = magnitudes[i]
                m
            }
            val db = 20f * log10(mag + 1e-7f) + tilt[b]
            bands[b] = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)
        }
    }

    companion object {
        const val FFT_SIZE = 2048
        const val BAND_COUNT = 64
        const val FLOOR_DB = -62f
        const val CEIL_DB = -12f
    }
}

/**
 * Finds beats in a stream of bass energy values: a beat is energy well above its recent running
 * average, at most once per [cooldownSec]. Time-based (not frame-based), so it behaves the same at
 * 60 or 120 fps.
 */
class BeatDetector(
    private val sensitivity: Float = 1.45f,
    private val cooldownSec: Float = 0.28f,
    private val averageSec: Float = 1.0f,
    private val minEnergy: Float = 0.004f,
) {
    private var average = 0f
    private var started = false
    private var lastBeat = Double.NEGATIVE_INFINITY

    /** The running average the next value is compared with. */
    val runningAverage: Float get() = average

    fun reset() {
        started = false
        average = 0f
        lastBeat = Double.NEGATIVE_INFINITY
    }

    /** Feeds one value taken at [timeSec], [dt] seconds after the previous one; true if it's a beat. */
    fun process(energy: Float, timeSec: Double, dt: Float): Boolean {
        if (!started) {
            started = true
            average = energy
            return false
        }
        val beat = energy > minEnergy && energy > average * sensitivity && timeSec - lastBeat >= cooldownSec
        average += (energy - average) * (1f - exp(-dt / averageSec))
        if (beat) lastBeat = timeSec
        return beat
    }
}

/** What a visualiser draws from, refreshed every frame. All values 0..1 unless noted. */
class AudioFrame(bandCount: Int = SpectrumAnalyzer.BAND_COUNT, waveSize: Int = WAVE_SIZE) {
    /** Log-spaced spectrum, bass first; fast attack, slow decay. */
    val bands = FloatArray(bandCount)
    /** Recent waveform, about -1..1 (normalised to the block's peak, scaled down when quiet). */
    val waveform = FloatArray(waveSize)
    var bass = 0f
    var mid = 0f
    var treble = 0f
    var level = 0f
    /** 1 on a beat, decaying towards 0. */
    var beat = 0f
    /** Counts beats, so renderers can spot a new one by comparing with the last value they saw. */
    var beatCount = 0
    /** True while live audio is coming in. */
    var live = false

    companion object {
        const val WAVE_SIZE = 256
    }
}

/**
 * Turns blocks of samples into a smoothed [AudioFrame]: spectrum bands, bass/mid/treble, level, beat
 * pulse and waveform. Passing null (paused, nothing playing) lets everything fall away smoothly.
 */
class AudioPipeline(private val spectrum: SpectrumAnalyzer = SpectrumAnalyzer()) {
    val frame = AudioFrame(spectrum.bandCount)
    private val beats = BeatDetector()
    private var time = 0.0
    private var bassTo = 0
    private var midTo = 0
    private var rate = 0

    fun reset() {
        beats.reset()
        frame.bands.fill(0f)
        frame.waveform.fill(0f)
        frame.bass = 0f; frame.mid = 0f; frame.treble = 0f; frame.level = 0f; frame.beat = 0f
        frame.live = false
    }

    fun update(samples: FloatArray?, sampleRate: Int, dt: Float) {
        time += dt
        val f = frame
        val attack = 1f - exp(-dt / 0.03f)
        val release = 1f - exp(-dt / 0.28f)
        f.live = samples != null
        if (samples != null) {
            spectrum.process(samples, sampleRate)
            if (sampleRate != rate) {
                rate = sampleRate
                bassTo = spectrum.bandForHz(250f, sampleRate)
                midTo = spectrum.bandForHz(4000f, sampleRate)
            }
        }
        var bass = 0f; var mid = 0f; var treble = 0f
        val n = f.bands.size
        for (b in 0 until n) {
            val raw = if (samples != null) spectrum.bands[b] else 0f
            f.bands[b] = smooth(f.bands[b], raw, attack, release)
            when {
                b < bassTo -> bass += raw
                b < midTo -> mid += raw
                else -> treble += raw
            }
        }
        bass /= max(1, bassTo)
        mid /= max(1, midTo - bassTo)
        treble /= max(1, n - midTo)
        f.bass = smooth(f.bass, bass, attack, release)
        f.mid = smooth(f.mid, mid, attack, release)
        f.treble = smooth(f.treble, treble, attack, release)
        val rmsDb = if (samples != null) 20f * log10(spectrum.rms + 1e-6f) else -100f
        f.level = smooth(f.level, ((rmsDb + 50f) / 44f).coerceIn(0f, 1f), attack, release)

        f.beat *= exp(-dt * 5f)
        if (beats.process(if (samples != null) spectrum.bassEnergy else 0f, time, dt)) {
            f.beat = 1f
            f.beatCount++
        }

        if (samples != null) {
            fillWaveform(samples, f.waveform, min(1f, f.level * 1.4f + 0.1f))
        } else {
            val k = exp(-dt * 6f)
            for (i in f.waveform.indices) f.waveform[i] *= k
        }
    }

    private fun fillWaveform(samples: FloatArray, out: FloatArray, loudness: Float) {
        val n = spectrum.fftSize
        val span = out.size * WAVE_STEP
        // Start at a rising zero crossing so the shape holds still instead of sliding sideways.
        val searchFrom = max(1, n - span - 512)
        var start = n - span
        for (i in searchFrom until n - span) {
            if (samples[i - 1] <= 0f && samples[i] > 0f) { start = i; break }
        }
        var peak = 0f
        for (i in 0 until span) peak = max(peak, abs(samples[start + i]))
        val gain = loudness / max(peak, 0.2f)
        for (j in out.indices) {
            val v = samples[start + j * WAVE_STEP] * gain
            out[j] += (v - out[j]) * 0.6f
        }
    }

    private fun smooth(current: Float, target: Float, attack: Float, release: Float): Float =
        current + (target - current) * (if (target > current) attack else release)

    private companion object {
        const val WAVE_STEP = 4
    }
}
