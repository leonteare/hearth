package im.flume.hearth.ui.screens.visualiser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class RidgesTest {
    @Test
    fun envelopeIsFullInTheMiddleThirdAndFlatAtTheEnds() {
        assertEquals(1f, ridgeEnvelope(0f), 0f)
        assertEquals(1f, ridgeEnvelope(0.3f), 0f)
        assertEquals(1f, ridgeEnvelope(-1f / 3f), 0f)
        assertEquals(0f, ridgeEnvelope(0.8f), 0f)
        assertEquals(0f, ridgeEnvelope(-1f), 0f)
        assertEquals(0f, ridgeEnvelope(1f), 0f)
        // Symmetric, and never rising as you move outwards.
        var prev = 1f
        var x = 0f
        while (x <= 1f) {
            val e = ridgeEnvelope(x)
            assertEquals(e, ridgeEnvelope(-x), 1e-6f)
            assertTrue(e in 0f..1f)
            assertTrue("x=$x", e <= prev + 1e-6f)
            prev = e
            x += 0.01f
        }
    }

    @Test
    fun noiseIsBoundedAndContinuous() {
        var x = -20f
        var prev = valueNoise(x, 3)
        while (x < 20f) {
            x += 0.01f
            val v = valueNoise(x, 3)
            assertTrue(v in 0f..1f)
            assertTrue(abs(v - prev) < 0.05f)
            prev = v
        }
    }

    @Test
    fun intervalFollowsTrustedTempoInRange() {
        assertEquals(0.1f, ridgeInterval(0f, 1f), 0f)
        assertEquals(0.1f, ridgeInterval(120f, 0.2f), 0f)
        assertEquals(0.125f, ridgeInterval(120f, 0.9f), 1e-6f)
        for (bpm in 40..240) {
            val s = ridgeInterval(bpm.toFloat(), 1f)
            assertTrue("bpm $bpm → $s", s >= 1f / 14f - 1e-6f && s <= 1f / 6f + 1e-6f)
        }
    }

    @Test
    fun historyKeepsNewestFirstAndDropsTheOldest() {
        val h = RidgeHistory(lines = 3, points = 2)
        assertEquals(0f, h.get(0, 0), 0f)
        for (i in 1..5) h.push(floatArrayOf(i.toFloat(), i * 10f))
        assertEquals(3, h.size)
        assertEquals(5f, h.get(0, 0), 0f)
        assertEquals(50f, h.get(0, 1), 0f)
        assertEquals(4f, h.get(1, 0), 0f)
        assertEquals(3f, h.get(2, 0), 0f)
        assertEquals(0f, h.get(3, 0), 0f)
    }
}

class MystifyShapesTest {
    @Test
    fun cornersStayInsideTheBoxAndBounce() {
        val s = MystifyShapes(polygons = 3, vertices = 4, trail = 8, seed = 5)
        s.resize(320f, 600f)
        var flips = 0
        val lastVx = s.vx.copyOf()
        repeat(5000) {
            s.step(1f / 60f, speed = 0.4f + (it % 7) * 0.3f)
            for (i in s.x.indices) {
                assertTrue(s.x[i] in 0f..320f)
                assertTrue(s.y[i] in 0f..600f)
                if (s.vx[i] != lastVx[i]) { flips++; lastVx[i] = s.vx[i] }
            }
        }
        assertTrue(flips > 20)
    }

    @Test
    fun trailIsARingOfRecentOutlines() {
        val s = MystifyShapes(polygons = 2, vertices = 4, trail = 4, seed = 1)
        s.resize(100f, 100f)
        val recorded = ArrayList<Float>()
        for (k in 0 until 7) {
            s.step(0.05f, 1f)
            s.record()
            recorded += s.x[5]
        }
        assertEquals(4, s.stored)
        for (age in 0 until 4) assertEquals(recorded[recorded.size - 1 - age], s.trailX(age, 1, 1), 0f)
    }

    @Test
    fun resizeKeepsRelativePlace() {
        val s = MystifyShapes(seed = 2)
        s.resize(200f, 100f)
        val fx = s.x[0] / 200f
        s.resize(400f, 300f)
        assertEquals(fx, s.x[0] / 400f, 1e-5f)
    }
}

class MagnetFieldTest {
    @Test
    fun stepKeepsParticlesInBoundsAndSpeedsCapped() {
        val field = MagnetField(count = 800, magnets = 4, seed = 3)
        field.aspect = 0.5f
        val energy = FloatArray(4)
        for (t in 0 until 1200) {
            for (m in 0 until 4) energy[m] = if ((t / 30 + m) % 3 == 0) 1f else (t % 17) / 17f
            if (t % 25 == 0) field.kick(1.5f)
            field.step(if (t % 50 == 0) 0.5f else 1f / 60f, energy)
            for (m in 0 until 4) {
                assertTrue(abs(field.mx[m]) <= field.aspect)
                assertTrue(abs(field.my[m]) <= 1f)
            }
        }
        for (i in 0 until field.count) {
            assertTrue(field.x[i].isFinite() && field.y[i].isFinite())
            assertTrue(abs(field.x[i]) <= field.aspect + 0.08f)
            assertTrue(abs(field.y[i]) <= 1.08f)
            assertTrue(sqrt(field.vx[i] * field.vx[i] + field.vy[i] * field.vy[i]) <= MagnetField.MAX_SPEED + 1e-4f)
            assertTrue(field.owner[i] in 0 until 4)
        }
    }

    @Test
    fun loudMagnetGathersMoreParticles() {
        val field = MagnetField(count = 1500, magnets = 4, seed = 9)
        field.aspect = 0.55f
        val energy = floatArrayOf(1f, 0f, 0f, 0f)
        repeat(600) { field.step(1f / 60f, energy) }
        var near0 = 0
        var near1 = 0
        for (i in 0 until field.count) {
            val d0 = (field.x[i] - field.mx[0]).let { it * it } + (field.y[i] - field.my[0]).let { it * it }
            val d1 = (field.x[i] - field.mx[1]).let { it * it } + (field.y[i] - field.my[1]).let { it * it }
            if (d0 < 0.04f) near0++
            if (d1 < 0.04f) near1++
        }
        assertTrue("near loud $near0 vs quiet $near1", near0 > near1 * 2)
    }

    @Test
    fun onlyActiveParticlesMove() {
        val field = MagnetField(count = 100, seed = 4)
        val x99 = field.x[99]
        field.step(1f / 60f, FloatArray(4) { 0.5f }, active = 50)
        assertEquals(x99, field.x[99], 0f)
    }
}
