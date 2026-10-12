package im.flume.hearth.playback

/**
 * Mono samples in a ring, indexed by the media time they play at, so the visualiser can analyse
 * exactly the audio the player says is coming out of the speaker now.
 *
 * One writer thread (the audio sink) and one reader thread (the UI). The writer marks where each
 * buffer starts with [beginChunk] (its presentation time and sample rate), [put]s its samples and
 * then [commit]s them. [discontinuity] (a flush: seek, new stream) makes everything written before it
 * unreadable, so a window never mixes audio from both sides of a jump. Allocation-free.
 */
class TimedRing(val capacity: Int = 1 shl 18, private val anchorCapacity: Int = 1024) {
    init {
        require(capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    private val mask = (capacity - 1).toLong()
    private val data = FloatArray(capacity)
    private val anchorFrame = LongArray(anchorCapacity)
    private val anchorUs = LongArray(anchorCapacity)
    private val anchorRate = IntArray(anchorCapacity)

    /** Samples committed so far (readable up to here). */
    @Volatile var written = 0L
        private set
    @Volatile private var anchors = 0L
    @Volatile private var validFrom = 0L
    private var cursor = 0L

    /** Sample rate of the audio found by the last successful [read]. */
    var lastRate = 44_100
        private set

    // ------------------------------------------------------------------ writer

    /** The next sample [put] plays at [timeUs] (media time); samples follow at [sampleRate]. */
    fun beginChunk(timeUs: Long, sampleRate: Int) {
        val n = anchors
        val i = (n % anchorCapacity).toInt()
        anchorFrame[i] = cursor
        anchorUs[i] = timeUs
        anchorRate[i] = sampleRate
        anchors = n + 1
    }

    fun put(v: Float) {
        data[(cursor and mask).toInt()] = v
        cursor++
    }

    fun commit() {
        written = cursor
    }

    /** Nothing written so far may be read again (seek, flush, or the reader starting afresh). */
    fun discontinuity() {
        validFrom = written
    }

    // ------------------------------------------------------------------ reader

    /**
     * Copies the [n] samples that end at media time [timeUs] into [out] (oldest first). Samples from
     * before the last [discontinuity] read as silence. False when that moment isn't in the ring (not
     * written yet, overwritten, or in a gap), and [out] is then untouched.
     */
    fun read(timeUs: Long, out: FloatArray, n: Int = out.size): Boolean {
        val w = written
        val count = anchors
        val valid = validFrom
        val oldest = maxOf(0L, count - anchorCapacity + 8)
        var segEnd = w
        var k = count - 1
        while (k >= oldest) {
            val i = (k % anchorCapacity).toInt()
            val start = anchorFrame[i]
            if (start >= w) { k--; continue } // begun but not committed yet
            if (segEnd <= valid) return false // this chunk, and all older ones, are from before the jump
            val at = anchorUs[i]
            if (at <= timeUs) {
                val rate = anchorRate[i]
                val end = start + (timeUs - at) * rate / 1_000_000L
                if (end > segEnd || end < valid) return false
                val from = end - n
                if (from < w - capacity + 1024) return false
                var p = from
                for (j in 0 until n) {
                    out[j] = if (p < valid) 0f else data[(p and mask).toInt()]
                    p++
                }
                lastRate = rate
                return true
            }
            segEnd = start
            k--
        }
        return false
    }
}
