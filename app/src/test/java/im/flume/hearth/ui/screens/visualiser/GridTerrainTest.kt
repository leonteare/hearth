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
    fun scannerSitsAQuarterToAThirdIntoTheLand() {
        val f = GridTerrain.SCAN_Z / GridTerrain.FAR_Z
        assertTrue("$f", f in 0.25f..0.35f)
        assertTrue(GridTerrain.ROW_MIN_Z < GridTerrain.SCAN_Z)
    }

    @Test
    fun cameraStaysLowWhateverTheMusic() {
        for (t in 0..400) for (p in listOf(0f, 0.5f, 1f, 3f)) for (b in listOf(0f, 1f, 2f)) {
            val y = GridTerrain.cameraHeight(t * 0.05f, p, b)
            assertTrue("$y", y in 0.95f..1.35f)
        }
    }

    @Test
    fun cappedHillsNeverReachTheNearPlane() {
        // Every camera Grid Runner sets up (bobbing, jolted, swaying), every point of every row it
        // projects (from ROW_MIN_Z out to the far edge, mountains included, capped) stays well in
        // front of the near plane and projects to a sane spot on screen.
        val out = FloatArray(3)
        for (camY in listOf(0.95f, 1.15f, 1.35f)) for (camX in listOf(-0.3f, 0f, 0.3f)) {
            val cam = RetroCamera().apply { x = camX; y = camY; pitch = -0.05f; focal = 180f; cx = 135f; cy = 240f; update() }
            var z = GridTerrain.ROW_MIN_Z
            while (z <= GridTerrain.FAR_Z + 1f) {
                for (x in listOf(-40f, -6f, -1f, 0f, 3f, 12f, 40f)) {
                    for (v in listOf(0f, 0.5f, 1f, 3f)) {
                        val h = minOf(GridTerrain.ridgeHeight(v, 1f, 12f) * 3f, GridTerrain.cap(z, camY))
                        assertTrue(cam.project(x, h, z, out, 0))
                        assertTrue("depth ${out[2]} at z=$z h=$h", out[2] > 0.4f && out[2] > cam.near * 3f)
                        assertTrue(kotlin.math.abs(out[0]) < 1e5f && kotlin.math.abs(out[1]) < 1e5f)
                    }
                }
                z += 0.25f
            }
        }
    }

    @Test
    fun linesRunningUnderTheCameraAreClipped() {
        val cam = RetroCamera().apply { y = 1.1f; pitch = -0.05f; focal = 180f; cx = 135f; cy = 240f; update() }
        val seg = FloatArray(4)
        // Rows slide from 1 ahead to just under the camera: their lines are cut at the near plane.
        for (x in listOf(-3f, 0f, 2f, 30f)) for (z0 in listOf(1f, 0.5f, 0.1f, 0f)) for (h in listOf(0f, 1.5f)) {
            if (cam.projectSegment(x, h, z0 + 1f, x, minOf(h, GridTerrain.cap(z0, 1.1f)), z0, seg)) {
                for (v in seg) assertTrue("$v", v.isFinite() && kotlin.math.abs(v) < 1e5f)
            }
            if (cam.projectSegment(-x, 0f, z0, x, h, z0, seg)) {
                for (v in seg) assertTrue("$v", v.isFinite() && kotlin.math.abs(v) < 1e5f)
            }
        }
        // Calm terrain and mountains stay in range.
        for (i in 0..200) {
            assertTrue(GridTerrain.calm(i * 0.37f, i % 29) in 0f..1f)
            for (j in 0 until 4) assertTrue(GridTerrain.mountain(i * 0.37f, j, 5.3f) in 0.2f..1f)
        }
    }
}
