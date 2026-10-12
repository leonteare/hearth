package im.flume.hearth.feature.visualiser

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

    @Test
    fun segmentAheadMatchesPointProjection() {
        val cam = camera()
        val seg = FloatArray(4)
        val p = FloatArray(3)
        assertTrue(cam.projectSegment(-1f, 0.5f, 2f, 2f, -1f, 6f, seg, snap = false))
        cam.project(-1f, 0.5f, 2f, p, 0, snap = false)
        assertEquals(p[0], seg[0], 1e-4f); assertEquals(p[1], seg[1], 1e-4f)
        cam.project(2f, -1f, 6f, p, 0, snap = false)
        assertEquals(p[0], seg[2], 1e-4f); assertEquals(p[1], seg[3], 1e-4f)
    }

    @Test
    fun segmentCrossingTheNearPlaneIsCutWhereItCrosses() {
        val cam = camera().apply { near = 0.5f }
        val seg = FloatArray(4)
        // From 1 behind the camera to 3 ahead, rising from y = -1 to y = 1 (x fixed at 2).
        assertTrue(cam.projectSegment(2f, -1f, -1f, 2f, 1f, 3f, seg, snap = false))
        // At depth 0.5 the line is at y = -1 + 2 * (1.5 / 4) = -0.25: the clipped end is that point.
        assertEquals(160f + 2f / 0.5f * 100f, seg[0], 1e-3f)
        assertEquals(120f + 0.25f / 0.5f * 100f, seg[1], 1e-3f)
        // The far end is untouched.
        assertEquals(160f + 2f / 3f * 100f, seg[2], 1e-3f)
        assertEquals(120f - 1f / 3f * 100f, seg[3], 1e-3f)
        // Either way round.
        val back = FloatArray(4)
        assertTrue(cam.projectSegment(2f, 1f, 3f, 2f, -1f, -1f, back, snap = false))
        assertEquals(seg[0], back[2], 1e-3f); assertEquals(seg[1], back[3], 1e-3f)
        // Wholly behind: nothing.
        assertFalse(cam.projectSegment(0f, 0f, -2f, 1f, 1f, 0.2f, seg))
    }

    @Test
    fun clippedPointsStayFinite() {
        val cam = camera().apply { near = 0.1f }
        val seg = FloatArray(4)
        // Ends at or just behind the camera plane would project ~infinitely far away without clipping.
        for (z in listOf(-3f, -0.01f, 0f, 0.05f, 0.1f, 0.2f)) {
            if (cam.projectSegment(3f, 2f, z, -3f, 2f, 8f, seg)) {
                for (v in seg) assertTrue("z=$z → $v", v.isFinite() && kotlin.math.abs(v) < 1e5f)
            }
        }
        assertEquals(0f, smoothstep(1f, 2f, 0.5f))
        assertEquals(0.5f, smoothstep(1f, 2f, 1.5f), 1e-6f)
        assertEquals(1f, smoothstep(1f, 2f, 3f))
    }
}
