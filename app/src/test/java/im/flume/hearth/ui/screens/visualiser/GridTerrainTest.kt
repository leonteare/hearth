package im.flume.hearth.ui.screens.visualiser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridTerrainTest {

    @Test
    fun bassBesideTheRoadRisingToTrebleAtTheEdges() {
        val side = 13
        val bands = 64
        var lastLo = -1
        for (e in 0 until side) {
            val lo = GridTerrain.bandLo(e, side, bands)
            val hi = GridTerrain.bandHi(e, side, bands)
            assertTrue("column $e starts above the one inside it", lo > lastLo)
            assertTrue("column $e shows at least one band", hi > lo)
            assertTrue(hi <= bands)
            lastLo = lo
        }
        assertEquals(1, GridTerrain.bandLo(0, side, bands))
        // Neighbouring columns don't overlap or leave gaps.
        for (e in 0 until side - 1) assertEquals(GridTerrain.bandHi(e, side, bands), GridTerrain.bandLo(e + 1, side, bands))
        // Works with fewer bands than columns too.
        for (e in 0 until side) assertTrue(GridTerrain.bandHi(e, side, 8) <= 8)
    }

    @Test
    fun louderIsTallerAndQuietIsAValley() {
        val unit = 2.5f
        assertTrue(GridTerrain.ridgeHeight(1f, 0f, unit) > GridTerrain.ridgeHeight(0.5f, 0f, unit))
        assertTrue(GridTerrain.ridgeHeight(0f, 1f, unit) > GridTerrain.ridgeHeight(0f, 0f, unit))
        assertTrue(GridTerrain.ridgeHeight(0f, 0f, unit) < 0.2f)
        assertTrue(GridTerrain.ridgeHeight(5f, 1f, unit).isFinite())
    }

    @Test
    fun cappedHillsNeverReachTheNearPlane() {
        // Every camera Grid Runner can set up (height 2.2..6 looking down 0.12 rad), every hill
        // (capped) at or beyond the live row (2.5+ ahead) stays far in front of the near plane, and
        // projects to a sane spot on screen.
        val out = FloatArray(3)
        for (camY in listOf(2.2f, 3f, 4.5f, 6f)) {
            val cam = RetroCamera().apply { y = camY; pitch = -0.12f; focal = 240f; cx = 135f; cy = 200f; update() }
            for (z in listOf(2.5f, 3f, 6f, 20f, 40f)) {
                for (x in listOf(-6f, -1f, 0f, 3f, 6f)) {
                    for (v in listOf(0f, 0.5f, 1f, 3f)) {
                        val h = minOf(GridTerrain.ridgeHeight(v, 1f, 6f), GridTerrain.cap(camY))
                        assertTrue(cam.project(x, h, z, out, 0))
                        assertTrue("depth ${out[2]} at z=$z h=$h", out[2] > 2f)
                        assertTrue(kotlin.math.abs(out[0]) < 1e4f && kotlin.math.abs(out[1]) < 1e4f)
                    }
                }
            }
        }
    }

    @Test
    fun groundLinesUnderTheCameraAreClipped() {
        val cam = RetroCamera().apply { y = 4f; pitch = -0.12f; focal = 240f; cx = 135f; cy = 200f; update() }
        val seg = FloatArray(4)
        // The flat grid runs from the live row to just in front of the camera: never past the near plane.
        for (x in listOf(-3f, 0f, 2f)) {
            if (cam.projectSegment(x, 0f, 4f, x, 0f, 0.2f, seg)) {
                for (v in seg) assertTrue("$v", v.isFinite() && kotlin.math.abs(v) < 1e5f)
            }
        }
    }
}
