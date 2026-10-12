package im.flume.hearth.core.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Onsets, beats and tempo from audio fed at a fixed hop ([HOP_SEC] of media time), so it behaves the
 * same whatever the screen's frame rate.
 *
 * Onsets: three regions (kick 40–150 Hz, snare/body 150–2500 Hz, hats 5–16 kHz). For each, the
 * log of its energy now minus [LAG] hops ago (positive part only: spectral flux of the region), against
 * an adaptive threshold (running mean + k·std over about a second), with a refractory period. Each
 * onset sets an envelope that attacks instantly and releases exponentially.
 *
 * Tempo: the autocorrelation of the onset strength over the last ~6 s gives the beat period
 * (70–180 BPM, gently biased to ~120 against octave errors); a comb over the same history gives the
 * phase. Predicted beats are nudged towards real kicks (a small phase-locked loop), and when the
 * tempo is clear and most predictions are being hit, a beat the music doesn't mark loudly enough is
 * filled in, so the visuals don't skip.
 *
 * Allocation-free after construction.
 */
class BeatTracker {
    private val longFft = Fft(WINDOW)
    private val shortFft = Fft(SHORT)
    private val longWin = hann(WINDOW)
    private val shortWin = hann(SHORT)
    private val re = FloatArray(WINDOW)
    private val im = FloatArray(WINDOW)
    private var rate = 0
    private val binFrom = IntArray(REGIONS)
    private val binTo = IntArray(REGIONS)

    /** Per region: log power of each of its bins for the last [MAX_LAG] + 1 hops. */
    private val logHist = arrayOfNulls<FloatArray>(REGIONS)
    private var hops = 0L
    private val odfMean = FloatArray(REGIONS)
    private val odfVar = FloatArray(REGIONS)
    private val lastOnset = DoubleArray(REGIONS)
    /** Kick, snare and hat envelopes as of [time]. */
    val env = FloatArray(REGIONS)
    /** Each region's latest onset function value (rectified log-energy rise) and its threshold. */
    val flux = FloatArray(REGIONS)
    val threshold = FloatArray(REGIONS)
    /** Onsets seen per region, so a renderer can spot a new one. */
    val counts = IntArray(REGIONS)

    /** Media time (seconds) of the last hop processed. */
    var time = Double.NaN
        private set
    var beatEnv = 0f
        private set
    var beatCount = 0
        private set
    private var lastBeatFired = NEG
    private var lastRealBeat = NEG

    // Tempo.
    private val onsets = FloatArray(HISTORY)
    /** Beat period in seconds; NaN until known. */
    var period = Double.NaN
        private set
    /** 0..1: how clearly periodic the recent onsets are. */
    var confidence = 0f
        private set
    private var nextBeat = Double.NaN
    private var lastPredicted = Double.NaN
    private var lastPredictedHit = false
    private var nextPredictedHit = false
    private var lastPredictedOpen = false
    private var matchRate = 0f
    private val acf = FloatArray(MAX_PERIOD_HOPS + 2)

    val bpm: Float get() = if (period.isNaN()) 0f else (60.0 / period).toFloat()

    /** 0 at a (predicted) beat, rising to 1 just before the next; 0 when there's no tempo. */
    fun phaseAt(t: Double): Float {
        val p = period
        val last = lastPredicted
        if (p.isNaN() || last.isNaN() || confidence <= 0f) return 0f
        val x = (t - last) / p
        return (x - kotlin.math.floor(x)).toFloat()
    }

    fun reset() {
        hops = 0
        for (h in logHist) h?.fill(0f)
        odfMean.fill(0f)
        odfVar.fill(0f)
        lastOnset.fill(NEG)
        onsets.fill(0f)
        time = Double.NaN
        lastBeatFired = NEG
        lastRealBeat = NEG
        period = Double.NaN
        confidence = 0f
        nextBeat = Double.NaN
        lastPredicted = Double.NaN
        lastPredictedHit = false
        nextPredictedHit = false
        lastPredictedOpen = false
        matchRate = 0f
    }

    /** Envelopes fall away by [dt] seconds (nothing playing). */
    fun decay(dt: Float) {
        for (r in 0 until REGIONS) env[r] *= exp(-dt / RELEASE[r])
        beatEnv *= exp(-dt / BEAT_RELEASE)
    }

    /** An envelope's value [age] seconds after [time]. */
    fun envAfter(region: Int, age: Float) = env[region] * exp(-age / RELEASE[region])
    fun beatAfter(age: Float) = beatEnv * exp(-age / BEAT_RELEASE)

    /**
     * Analyses one hop: [window] holds at least [WINDOW] mono samples, the newest of which plays at
     * [t] seconds of media time. Call once per [HOP_SEC] of audio, in order.
     */
    fun process(window: FloatArray, sampleRate: Int, t: Double) {
        if (sampleRate != rate) setRate(sampleRate)
        // A jump in the audio (seek, or a long pause then a seek): the history no longer applies.
        // (Envelopes carry on falling away; reset leaves them alone.)
        if (!time.isNaN() && (t < time - 0.05 || t > time + 1.0)) reset()
        val dtHop = if (time.isNaN()) HOP_SEC.toFloat() else (t - time).toFloat().coerceIn(0.001f, 0.1f)
        time = t
        decay(dtHop)

        // Long window (kick region) and short window (the rest).
        val slot = (hops % (MAX_LAG + 1)).toInt()
        for (i in 0 until WINDOW) { re[i] = window[i] * longWin[i]; im[i] = 0f }
        longFft.transform(re, im)
        flux[KICK] = regionFlux(KICK, WINDOW, slot)
        val off = WINDOW - SHORT
        for (i in 0 until SHORT) { re[i] = window[off + i] * shortWin[i]; im[i] = 0f }
        shortFft.transform(re, im)
        flux[SNARE] = regionFlux(SNARE, SHORT, slot)
        flux[HAT] = regionFlux(HAT, SHORT, slot)

        val alpha = 1f - exp(-dtHop / THRESHOLD_SEC)
        var strength = 0f
        for (r in 0 until REGIONS) {
            if (hops < LAG[r]) continue
            val d = flux[r]
            val mean = odfMean[r]
            val std = sqrt(odfVar[r])
            val threshold = max(max(MIN_FLUX[r], mean * RATIO), mean + K[r] * std)
            this.threshold[r] = threshold
            if (hops > WARMUP && d > threshold && t - lastOnset[r] >= REFRACTORY[r]) {
                lastOnset[r] = t
                counts[r]++
                val s = (0.55f + 0.45f * (d - threshold) / threshold).coerceIn(0.55f, 1f)
                env[r] = max(env[r], s)
                onOnset(r, s, t)
            }
            // The threshold mostly learns the level between onsets: onsets themselves count for little, so
            // one huge kick doesn't hide the softer ones after it, while a busy passage still raises it.
            val a = if (d > threshold) alpha * 0.1f else alpha
            val dev = d - mean
            odfMean[r] = mean + a * dev
            odfVar[r] = (1f - a) * (odfVar[r] + a * dev * dev)
            strength += WEIGHT[r] * max(0f, d - mean)
        }

        onsets[(hops % HISTORY).toInt()] = strength
        hops++
        if (hops >= TEMPO_MIN_HOPS && hops % TEMPO_EVERY == 0L) estimateTempo(t)
        predict(t)
    }

    private fun onOnset(region: Int, strength: Float, t: Double) {
        val isBeat = region == 0 || (region == 1 && t - lastOnset[0] > 2.0)
        if (!isBeat) return
        lastRealBeat = t
        val p = period
        // Lock the predictions onto real beats.
        if (!p.isNaN() && !nextBeat.isNaN()) {
            val eNext = t - nextBeat
            val eLast = if (lastPredicted.isNaN()) Double.MAX_VALUE else t - lastPredicted
            val e = if (kotlin.math.abs(eNext) < kotlin.math.abs(eLast)) eNext else eLast
            if (kotlin.math.abs(e) < 0.2 * p) {
                if (e == eNext) nextPredictedHit = true else lastPredictedHit = true
                nextBeat += 0.25 * e
            }
        }
        val gap = if (p.isNaN()) 0.25 else max(0.12, 0.45 * p)
        if (t - lastBeatFired >= gap) fire(strength, t) else beatEnv = max(beatEnv, strength)
    }

    private fun fire(strength: Float, t: Double) {
        beatCount++
        lastBeatFired = t
        beatEnv = max(beatEnv, strength)
    }

    private fun predict(t: Double) {
        val p = period
        if (p.isNaN() || nextBeat.isNaN()) return
        // Score the last prediction once its window has passed.
        if (lastPredictedOpen && t > lastPredicted + 0.2 * p) {
            matchRate += ((if (lastPredictedHit) 1f else 0f) - matchRate) * 0.2f
            lastPredictedOpen = false
        }
        if (t < nextBeat) return
        lastPredicted = nextBeat
        lastPredictedHit = nextPredictedHit
        nextPredictedHit = false
        lastPredictedOpen = true
        nextBeat += p
        if (nextBeat < t) nextBeat = t + p // fell behind (a gap in the hops)
        val fill = confidence >= FILL_CONFIDENCE && matchRate >= FILL_MATCH_RATE &&
            t - lastRealBeat < 4 * p && t - lastBeatFired > 0.5 * p
        if (fill) fire(0.7f, t)
    }

    private fun estimateTempo(t: Double) {
        val n = min(hops, HISTORY.toLong()).toInt()
        var mean = 0f
        for (k in 0 until n) mean += onsetAgo(k)
        mean /= n
        var r0 = 0f
        for (k in 0 until n) { val v = onsetAgo(k) - mean; r0 += v * v }
        if (r0 < 1e-6f) { confidence = 0f; return }
        var best = -1
        var bestScore = 0f
        for (lag in MIN_PERIOD_HOPS..MAX_PERIOD_HOPS) {
            var s = 0f
            for (k in 0 until n - lag) s += (onsetAgo(k) - mean) * (onsetAgo(k + lag) - mean)
            s *= n.toFloat() / (n - lag)
            acf[lag] = s
            val bpm = 60f / (lag * HOP_SEC.toFloat())
            val octaves = ln(bpm / 120f) / ln(2f)
            val score = s * exp(-0.5f * octaves * octaves / (PRIOR_OCTAVES * PRIOR_OCTAVES))
            if (best < 0 || score > bestScore) { best = lag; bestScore = score }
        }
        val c = ((acf[best] / r0 - 0.05f) / 0.25f).coerceIn(0f, 1f)
        confidence += (c - confidence) * 0.5f
        if (acf[best] <= 0f) return
        // Parabolic interpolation for a fractional period.
        var lagF = best.toDouble()
        if (best > MIN_PERIOD_HOPS && best < MAX_PERIOD_HOPS) {
            val a = acf[best - 1]; val b = acf[best]; val cc = acf[best + 1]
            val den = a - 2 * b + cc
            if (den < 0f) lagF += (0.5f * (a - cc) / den).coerceIn(-0.5f, 0.5f)
        }
        val newPeriod = lagF * HOP_SEC
        val old = period
        val changed = old.isNaN() || kotlin.math.abs(newPeriod - old) / old > 0.08
        period = if (changed) newPeriod else old + (newPeriod - old) * 0.3

        // Phase: the offset whose comb of beats (one per period, going back) holds the most onset energy.
        val pHops = period / HOP_SEC
        val span = pHops.roundToInt().coerceAtLeast(1)
        var bestPhase = 0
        var bestSum = -1f
        for (phi in 0 until span) {
            var sum = 0f
            var w = 1f
            var k = 0
            while (true) {
                val at = (phi + k * pHops).roundToInt()
                if (at >= n) break
                // Neighbours count a little: detection jitters by a hop.
                sum += w * (onsetAgo(at) + 0.5f * (if (at > 0) onsetAgo(at - 1) else 0f) + 0.5f * (if (at + 1 < n) onsetAgo(at + 1) else 0f))
                w *= 0.85f
                k++
            }
            if (sum > bestSum) { bestSum = sum; bestPhase = phi }
        }
        var candidate = t - bestPhase * HOP_SEC + period
        while (candidate <= t) candidate += period
        if (changed || nextBeat.isNaN()) {
            nextBeat = candidate
        } else {
            var e = candidate - nextBeat
            e -= kotlin.math.round(e / period) * period
            nextBeat += 0.35 * e
        }
    }

    private fun onsetAgo(k: Int): Float = onsets[((hops - 1 - k) % HISTORY).toInt()]

    /**
     * Spectral flux of region [r] from the spectrum in [re]/[im]: the mean over its bins of the rise in
     * log-compressed magnitude since [LAG] hops ago, counting rises only. Averaging many bins makes noise's flux
     * steady (a high but flat line the threshold learns), while a hit lifts every bin at once.
     */
    private fun regionFlux(r: Int, size: Int, slot: Int): Float {
        val from = binFrom[r]
        val bins = binTo[r] - from + 1
        val hist = logHist[r]!!
        val cur = slot * bins
        val prev = (((hops - LAG[r]) % (MAX_LAG + 1)).toInt()) * bins
        val have = hops >= LAG[r]
        val g = COMPRESSION * 4f / size
        var sum = 0f
        for (k in 0 until bins) {
            val i = from + k
            // log(1 + γ·magnitude): near-silent bins sit at ~0 instead of jittering wildly in log space.
            val l = ln(1f + g * sqrt(re[i] * re[i] + im[i] * im[i]))
            if (have) {
                val rise = l - hist[prev + k]
                if (rise > 0f) sum += rise
            }
            hist[cur + k] = l
        }
        return if (have) sum / bins else 0f
    }

    private fun setRate(sampleRate: Int) {
        rate = sampleRate
        for (r in 0 until REGIONS) {
            val size = if (r == 0) WINDOW else SHORT
            val binHz = sampleRate.toFloat() / size
            val top = min(HI_HZ[r], sampleRate * 0.475f)
            val from = (LO_HZ[r] / binHz).roundToInt().coerceIn(1, size / 2 - 1)
            binFrom[r] = from
            binTo[r] = (top / binHz).roundToInt().coerceIn(from, size / 2 - 1)
            logHist[r] = FloatArray((MAX_LAG + 1) * (binTo[r] - from + 1))
        }
        hops = 0
    }

    companion object {
        const val WINDOW = 4096
        const val SHORT = 1024
        const val HOP_SEC = 0.01
        const val KICK = 0
        const val SNARE = 1
        const val HAT = 2
        private const val REGIONS = 3
        private const val HISTORY = 600
        private const val TEMPO_MIN_HOPS = 250L
        private const val TEMPO_EVERY = 25L
        private const val WARMUP = 30L
        private const val THRESHOLD_SEC = 1.2f
        private const val COMPRESSION = 100f
        private const val BEAT_RELEASE = 0.25f
        private const val PRIOR_OCTAVES = 0.9f
        private const val FILL_CONFIDENCE = 0.45f
        private const val FILL_MATCH_RATE = 0.55f
        private const val MAX_LAG = 3
        private val MIN_PERIOD_HOPS = (60.0 / 180.0 / HOP_SEC).toInt()
        private val MAX_PERIOD_HOPS = (60.0 / 70.0 / HOP_SEC).toInt() + 1
        private val LO_HZ = floatArrayOf(40f, 150f, 5000f)
        private val HI_HZ = floatArrayOf(150f, 2500f, 16000f)
        private val LAG = intArrayOf(3, 2, 2)
        private val K = floatArrayOf(3.5f, 3.5f, 3.5f)
        private const val RATIO = 1.8f
        private val MIN_FLUX = floatArrayOf(0.25f, 0.15f, 0.12f)
        private val REFRACTORY = doubleArrayOf(0.14, 0.12, 0.07)
        private val RELEASE = floatArrayOf(0.18f, 0.15f, 0.08f)
        private val WEIGHT = floatArrayOf(1f, 0.5f, 0.15f)
        private const val NEG = -1e9

        private fun hann(n: Int) = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    }
}
