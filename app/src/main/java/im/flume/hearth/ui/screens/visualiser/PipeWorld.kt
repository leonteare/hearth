package im.flume.hearth.ui.screens.visualiser

import kotlin.random.Random

/**
 * The growth rules behind Pipes, free of Android types for unit tests: up to [maxPipes] pipes grow
 * one cell at a time through an [n]³ grid of cells, never entering a cell that's already taken.
 * A pipe turns 90° when asked to (on kicks) or when blocked; with nowhere left to go it ends and a
 * new pipe, in the next colour, starts at a free cell.
 *
 * Segments (one per cell step) live in a ring of [maxSegments]; when it's full the oldest is
 * recycled and its cell freed. Cells are numbered x + n * (y + n * z).
 */
internal class PipeWorld(
    val n: Int = 10,
    val maxPipes: Int = 6,
    val maxSegments: Int = 640,
    seed: Int = 7,
    /** Chance per step that a pipe turns anyway, for variety. */
    private val wanderChance: Float = 0.06f,
) {
    val cells = n * n * n
    private val occupied = BooleanArray(cells)
    private val rnd = Random(seed)

    /** Cells taken, by pipes and their segments. */
    var filled = 0
        private set
    val fillFraction: Float get() = filled.toFloat() / cells

    // Pipes.
    val alive = BooleanArray(maxPipes)
    val head = IntArray(maxPipes)
    val heading = IntArray(maxPipes)
    val colour = IntArray(maxPipes)
    /** Ring slot of each pipe's newest segment, or -1 before its first step. */
    val newest = IntArray(maxPipes) { -1 }
    var activeCount = 0
        private set
    private var colourSerial = 0

    // Segments, by ring slot: from cell A to neighbouring cell B.
    val segA = IntArray(maxSegments)
    val segB = IntArray(maxSegments)
    val segColour = IntArray(maxSegments)
    /** A ball at A: the pipe turned here, or started here. */
    val segJointA = BooleanArray(maxSegments)
    /** A cap at B: the pipe's current head, or where it ended. */
    val segCapB = BooleanArray(maxSegments)
    /** When the segment appeared (caller's clock), for the grow-in animation. */
    val segBirth = FloatArray(maxSegments)
    /** Oldest segment's slot and how many there are; the i-th oldest is at (segStart + i) % maxSegments. */
    var segStart = 0
        private set
    var segCount = 0
        private set

    /** Pipes that ended (stuck) since the last [reset]. */
    var ended = 0
        private set

    fun slot(i: Int): Int = (segStart + i) % maxSegments

    fun isOccupied(cell: Int): Boolean = occupied[cell]

    fun reset() {
        occupied.fill(false)
        alive.fill(false)
        newest.fill(-1)
        filled = 0
        activeCount = 0
        segStart = 0
        segCount = 0
        ended = 0
    }

    /** The cell next to [cell] in direction [dir] (0 +x, 1 -x, 2 +y, 3 -y, 4 +z, 5 -z), or -1 off the grid. */
    fun neighbour(cell: Int, dir: Int): Int {
        val x = cell % n
        val y = (cell / n) % n
        val z = cell / (n * n)
        return when (dir) {
            0 -> if (x + 1 < n) cell + 1 else -1
            1 -> if (x > 0) cell - 1 else -1
            2 -> if (y + 1 < n) cell + n else -1
            3 -> if (y > 0) cell - n else -1
            4 -> if (z + 1 < n) cell + n * n else -1
            else -> if (z > 0) cell - n * n else -1
        }
    }

    private fun free(cell: Int, dir: Int): Boolean {
        val next = neighbour(cell, dir)
        return next >= 0 && !occupied[next]
    }

    private fun freeNeighbours(cell: Int): Int {
        var k = 0
        for (d in 0 until 6) if (free(cell, d)) k++
        return k
    }

    /** A random free direction from [cell], skipping [dir]'s axis when [turn]; -1 if there's none. */
    private fun pickDirection(cell: Int, dir: Int, turn: Boolean): Int {
        val start = rnd.nextInt(6)
        for (i in 0 until 6) {
            val d = (start + i) % 6
            if (turn && d / 2 == dir / 2) continue
            if (free(cell, d)) return d
        }
        return -1
    }

    /**
     * Starts a pipe at a random free cell with room to grow. False when every pipe is already
     * running or no such cell is left.
     */
    fun spawn(): Boolean {
        val p = alive.indexOfFirst { !it }
        if (p < 0) return false
        var cell = -1
        for (i in 0 until 40) {
            val c = rnd.nextInt(cells)
            if (!occupied[c] && freeNeighbours(c) > 0) { cell = c; break }
        }
        if (cell < 0) {
            val start = rnd.nextInt(cells)
            for (i in 0 until cells) {
                val c = (start + i) % cells
                if (!occupied[c] && freeNeighbours(c) > 0) { cell = c; break }
            }
        }
        if (cell < 0) return false
        occupied[cell] = true
        filled++
        alive[p] = true
        head[p] = cell
        heading[p] = pickDirection(cell, 0, false)
        colour[p] = colourSerial++
        newest[p] = -1
        activeCount++
        return true
    }

    /**
     * Grows every live pipe by one cell. [turn] asks each to turn 90° (it goes straight if it can't).
     * Stuck pipes end, and a new pipe is started for each. [now] stamps the new segments.
     */
    fun step(turn: Boolean, now: Float) {
        for (p in 0 until maxPipes) {
            if (!alive[p]) continue
            if (!advance(p, turn || rnd.nextFloat() < wanderChance, now)) {
                end(p)
                spawn()
            }
        }
    }

    private fun advance(p: Int, wantTurn: Boolean, now: Float): Boolean {
        val cell = head[p]
        val dir = heading[p]
        var next = dir
        if (wantTurn || !free(cell, dir)) {
            val turned = pickDirection(cell, dir, true)
            if (turned >= 0) next = turned else if (!free(cell, dir)) return false
        }
        val to = neighbour(cell, next)
        if (segCount == maxSegments) recycleOldest()
        val s = (segStart + segCount) % maxSegments
        segCount++
        segA[s] = cell
        segB[s] = to
        segColour[s] = colour[p]
        segJointA[s] = newest[p] < 0 || next != dir
        segCapB[s] = true
        segBirth[s] = now
        if (newest[p] >= 0) segCapB[newest[p]] = false
        newest[p] = s
        occupied[to] = true
        filled++
        head[p] = to
        heading[p] = next
        return true
    }

    private fun end(p: Int) {
        alive[p] = false
        activeCount--
        ended++
    }

    /** Drops the oldest segment and frees its far cell (unless a pipe's head is there). */
    private fun recycleOldest() {
        val s = segStart
        val cell = segB[s]
        var isHead = false
        for (p in 0 until maxPipes) {
            if (newest[p] == s) newest[p] = -1
            if (alive[p] && head[p] == cell) isHead = true
        }
        if (!isHead && occupied[cell]) {
            occupied[cell] = false
            filled--
        }
        // Whatever continued from that cell now starts there: give it a cap.
        for (i in 1 until segCount) {
            val o = slot(i)
            if (segA[o] == cell) { segJointA[o] = true; break }
        }
        segStart = (segStart + 1) % maxSegments
        segCount--
    }
}
