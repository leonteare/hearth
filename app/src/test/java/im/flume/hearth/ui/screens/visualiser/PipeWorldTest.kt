package im.flume.hearth.ui.screens.visualiser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeWorldTest {

    private fun PipeWorld.coords(cell: Int) = Triple(cell % n, (cell / n) % n, cell / (n * n))

    @Test
    fun segmentsStepOneCellInsideTheGridAndNeverReenterACell() {
        for (seed in 1..20) {
            val world = PipeWorld(n = 6, maxPipes = 6, maxSegments = 10_000, seed = seed)
            world.spawn(); world.spawn(); world.spawn()
            val entered = HashSet<Int>()
            for (i in 0 until 400) {
                world.step(turn = i % 3 == 0, now = i.toFloat())
                if (world.fillFraction >= 0.95f) break
            }
            assertTrue(world.segCount > 50)
            for (k in 0 until world.segCount) {
                val s = world.slot(k)
                val a = world.segA[s]
                val b = world.segB[s]
                assertTrue(a in 0 until world.cells && b in 0 until world.cells)
                val (ax, ay, az) = world.coords(a)
                val (bx, by, bz) = world.coords(b)
                assertEquals("seed $seed seg $k: one step", 1, Math.abs(ax - bx) + Math.abs(ay - by) + Math.abs(az - bz))
                assertTrue("seed $seed: cell $b entered twice", entered.add(b))
                assertTrue(world.isOccupied(a) && world.isOccupied(b))
            }
        }
    }

    @Test
    fun turnsAreRightAngles() {
        val world = PipeWorld(n = 8, maxPipes = 1, maxSegments = 10_000, seed = 3, wanderChance = 0f)
        world.spawn()
        for (i in 0 until 60) world.step(turn = true, now = 0f)
        // Consecutive segments of the pipe never double straight back on themselves.
        var prev = -1
        for (k in 0 until world.segCount) {
            val s = world.slot(k)
            val d = world.segB[s] - world.segA[s]
            if (prev != -1 && world.segA[s] == world.segB[world.slot(k - 1)]) {
                assertTrue("reversal at $k", d != -prev)
                if (!world.segJointA[s]) assertEquals(prev, d)
            }
            prev = d
        }
    }

    @Test
    fun stuckPipeEndsAndANewOneStartsElsewhere() {
        // A 3×3×3 grid: a pipe soon boxes itself in.
        val world = PipeWorld(n = 3, maxPipes = 1, maxSegments = 1000, seed = 5)
        assertTrue(world.spawn())
        var guard = 0
        var lastHead = -1
        while (world.ended == 0 && guard++ < 100) {
            lastHead = world.head[0]
            world.step(turn = false, now = 0f)
        }
        assertEquals(1, world.ended)
        assertEquals("a new pipe took over", 1, world.activeCount)
        assertTrue(world.head[0] != lastHead)
        assertTrue(world.colour[0] == 1)
        // Keep going until nothing can start any more: every pipe ends, none overlap.
        repeat(200) { world.step(turn = it % 2 == 0, now = 0f) }
        assertEquals(0, world.activeCount)
        assertFalse(world.spawn())
        assertTrue(world.filled <= world.cells)
    }

    @Test
    fun newPipesGetNewColours() {
        val world = PipeWorld(n = 6, maxPipes = 6, seed = 2)
        world.spawn(); world.spawn(); world.spawn()
        val colours = (0 until 3).map { world.colour[it] }.toSet()
        assertEquals(3, colours.size)
        // Never more than maxPipes at once.
        repeat(10) { world.spawn() }
        assertEquals(6, world.activeCount)
    }

    @Test
    fun fillsUpThenResetsEmpty() {
        val world = PipeWorld(n = 5, maxPipes = 6, maxSegments = 1000, seed = 8)
        world.spawn(); world.spawn()
        var steps = 0
        while (world.fillFraction < 0.6f && steps < 1000) {
            world.step(turn = steps % 4 == 0, now = 0f)
            if (world.activeCount == 0) world.spawn()
            steps++
        }
        assertTrue("reaches 60%: ${world.fillFraction}", world.fillFraction >= 0.6f)
        world.reset()
        assertEquals(0, world.filled)
        assertEquals(0, world.segCount)
        assertEquals(0, world.activeCount)
        for (c in 0 until world.cells) assertFalse(world.isOccupied(c))
        assertTrue(world.spawn())
    }

    @Test
    fun segmentRingRecyclesTheOldestAndFreesItsCell() {
        val world = PipeWorld(n = 10, maxPipes = 2, maxSegments = 20, seed = 4)
        world.spawn(); world.spawn()
        repeat(60) { world.step(turn = it % 5 == 0, now = it.toFloat()) }
        assertTrue(world.segCount <= 20)
        // Only the live segments' cells (plus spawn cells and heads) stay taken.
        assertTrue("filled ${world.filled}", world.filled <= 20 + world.ended + 2 + 2)
        // The oldest kept segment is recent.
        assertTrue(world.segBirth[world.slot(0)] >= 60 - 21)
    }
}
