package im.flume.hearth.ui.screens.visualiser

import android.graphics.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Tunnel '98: a low-poly tube of chequered quads rushing at the camera. It bends and twists with the
 * music, speeds up on the bass and flashes on beats. Vertices snap to the low-res pixel grid (the
 * wobble) and each quad's two triangles are shaded apart, like the seams of affine texturing.
 */
class TunnelRenderer : RetroRenderer() {
    override val bloom: Float get() = 0.35f
    override val ditherLevels: Int get() = 18

    private val rings = 26
    private val segs = 14
    private val spacing = 0.85f
    private val radius = 1f
    private val sx = FloatArray((rings + 1) * segs)
    private val sy = FloatArray((rings + 1) * segs)
    private val depth = FloatArray(rings + 1)
    private var travel = 0f
    private var twist = 0f
    private var flash = 0f
    private var lastBeat = -1

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt
        travel += dt * (1.3f + a.bass * 4.5f + a.beat * 4f)
        twist += dt * (0.25f + a.bass * 1.2f + a.mid * 0.4f)
        if (a.beatCount != lastBeat) { if (lastBeat >= 0) flash = 1f; lastBeat = a.beatCount }
        flash *= kotlin.math.exp(-dt * 6f)

        c.drawColor(0xFF000000.toInt())
        val cx = w / 2f
        val cy = h / 2f
        val focal = w * 0.75f
        val step = floor(travel / spacing)
        val offset = travel - step * spacing
        // Where the tunnel's path is at the camera, so the near end stays centred while the far end swings.
        val camBendX = bendX(travel, f.time)
        val camBendY = bendY(travel, f.time)
        val zFar = rings * spacing

        for (i in 0..rings) {
            val z = i * spacing - offset + 0.3f
            depth[i] = z
            val along = travel + z
            val ox = bendX(along, f.time) - camBendX
            val oy = bendY(along, f.time) - camBendY
            val band = a.bands[(i * 48 / rings).coerceAtMost(a.bands.size - 1)]
            val r = radius * (1f + band * 0.22f + (if (i < 4) a.beat * 0.12f else 0f))
            val rot = twist + z * (0.1f + a.bass * 0.15f)
            for (j in 0 until segs) {
                val ang = rot + j * (2f * PI.toFloat() / segs)
                val x = ox + cos(ang) * r
                val y = oy + sin(ang) * r
                sx[i * segs + j] = snapToGrid(cx + x / z * focal)
                sy[i * segs + j] = snapToGrid(cy + y / z * focal)
            }
        }

        // Far to near: painter's algorithm, each ring's quads between it and the next one out.
        for (i in rings - 1 downTo 0) {
            val zMid = (depth[i] + depth[i + 1]) / 2f
            val fog = fogAmount(zMid, 1.5f, zFar * 0.95f)
            val dim = (1f - fog) * (1f - fog)
            val ringIndex = (step + i).toInt()
            val stripe = ringIndex % 6 == 0
            for (j in 0 until segs) {
                val chequer = (ringIndex + j) and 1 == 0
                var col = when {
                    stripe -> pal.argb[2]
                    chequer -> pal.argb[0]
                    else -> scaleArgb(pal.argb[1], 0.55f)
                }
                if (flash > 0.01f) col = lerpArgb(col, 0xFFFFFFFF.toInt(), flash * 0.5f * (1f - fog))
                col = scaleArgb(col, dim)
                val j2 = (j + 1) % segs
                val a0 = i * segs + j
                val a1 = i * segs + j2
                val b0 = (i + 1) * segs + j
                val b1 = (i + 1) * segs + j2
                fillTri(c, sx[a0], sy[a0], sx[a1], sy[a1], sx[b1], sy[b1], col)
                fillTri(c, sx[a0], sy[a0], sx[b1], sy[b1], sx[b0], sy[b0], scaleArgb(col, 0.82f))
            }
        }
        // The light at the end of the tunnel.
        fill.color = withAlpha(pal.argb[3], 0.25f + 0.3f * a.level)
        c.drawCircle(ringCentre(sx, rings), ringCentre(sy, rings), w * 0.02f * (1f + a.bass), fill)
    }

    private fun ringCentre(coords: FloatArray, i: Int): Float {
        var s = 0f
        for (j in 0 until segs) s += coords[i * segs + j]
        return s / segs
    }

    private fun bendX(along: Float, t: Float) = sin(along * 0.21f + t * 0.3f) * 1.4f + sin(along * 0.07f) * 0.8f
    private fun bendY(along: Float, t: Float) = cos(along * 0.17f + t * 0.23f) * 1.1f
}
