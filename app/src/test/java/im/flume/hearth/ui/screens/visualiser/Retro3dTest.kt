package im.flume.hearth.ui.screens.visualiser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class Retro3dTest {

    private fun camera() = RetroCamera().apply { focal = 100f; cx = 160f; cy = 120f; update() }

    @Test
    fun pointAheadProjectsToCentre() {
        val cam = camera()
        val out = FloatArray(3)
        assertTrue(cam.project(0f, 0f, 5f, out, 0))
        assertEquals(160f, out[0])
        assertEquals(120f, out[1])
        assertEquals(5f, out[2], 1e-5f)
    }

    @Test
    fun perspectiveDividesByDepthAndYIsUp() {
        val cam = camera()
        val out = FloatArray(3)
        cam.project(1f, 1f, 2f, out, 0, snap = false)
        assertEquals(160f + 50f, out[0], 1e-4f)
        assertEquals(120f - 50f, out[1], 1e-4f)
        cam.project(1f, 1f, 4f, out, 0, snap = false)
        assertEquals(160f + 25f, out[0], 1e-4f)
    }

    @Test
    fun behindTheCameraIsRejected() {
        val cam = camera()
        val out = FloatArray(3) { -1f }
        assertFalse(cam.project(0f, 0f, -3f, out, 0))
        assertFalse(cam.project(0f, 0f, 0.05f, out, 0))
        assertEquals(-1f, out[0])
    }

    @Test
    fun lookAtPutsTheTargetInTheMiddle() {
        val cam = camera().apply { x = 10f; y = 6f; z = -4f }
        cam.lookAt(1f, 2f, 3f)
        val out = FloatArray(3)
        assertTrue(cam.project(1f, 2f, 3f, out, 0, snap = false))
        assertEquals(160f, out[0], 1e-3f)
        assertEquals(120f, out[1], 1e-3f)
    }

    @Test
    fun snappedVerticesLandOnWholePixels() {
        val cam = camera()
        val out = FloatArray(6)
        cam.project(0.123f, -0.456f, 3.3f, out, 3)
        assertEquals(floor(out[3]), out[3])
        assertEquals(floor(out[4]), out[4])
        assertEquals(3f, snapToGrid(3.4f))
        assertEquals(4f, snapToGrid(3.5f))
        assertEquals(-2f, snapToGrid(-2.4f))
        assertEquals(8f, snapToGrid(9.9f, grid = 4f))
    }

    @Test
    fun fogRampsBetweenStartAndEnd() {
        assertEquals(0f, fogAmount(1f, 2f, 10f))
        assertEquals(0.5f, fogAmount(6f, 2f, 10f))
        assertEquals(1f, fogAmount(50f, 2f, 10f))
    }

    @Test
    fun colourHelpers() {
        assertEquals(0xFF808080.toInt(), lerpArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFFFF0000.toInt(), lerpArgb(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0f))
        assertEquals(0xFF0000FF.toInt(), lerpArgb(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 1f))
        assertEquals(0xFF402010.toInt(), scaleArgb(0xFF804020.toInt(), 0.5f))
        assertEquals(0x80123456.toInt(), withAlpha(0xFF123456.toInt(), 128 / 255f))
    }

    @Test
    fun ditherKeepsExtremesAndAveragesOut() {
        val levels = 5
        for (cell in 0 until 16) {
            assertEquals(0, Bayer.quantize(0, cell, levels))
            assertEquals(255, Bayer.quantize(255, cell, levels))
        }
        // Over the 4x4 matrix, a mid-level value dithers to about the same average brightness.
        for (v in listOf(40, 100, 170, 220)) {
            val avg = (0 until 16).sumOf { Bayer.quantize(v, it, levels) } / 16.0
            assertTrue("$v -> $avg", kotlin.math.abs(avg - v) < 255.0 / (levels - 1) / 2)
        }
        val table = Bayer.table(levels)
        assertEquals(Bayer.quantize(123, 7, levels), table[7 * 256 + 123])
    }
}
