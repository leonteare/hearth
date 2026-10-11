package im.flume.hearth.ui.screens.visualiser

import android.graphics.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Tunnel '98: a low-poly tube of chequered quads rushing at the camera. It bends and twists with the
 * music, glides faster with the bass and surges gently forward (and a little brighter) on beats. Vertices snap to the low-res pixel grid (the
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
    private val wx = FloatArray((rings + 1) * segs)
    private val wy = FloatArray((rings + 1) * segs)
    private val ringBand = FloatArray(rings + 1)
    private var travel = 0f
    private var twist = 0f
    private var speed = 1.3f
    private var twistRate = 0.25f
    private var surge = 0f
    private var drive = 0f

    override fun render(c: Canvas, w: Int, h: Int, f: VisFrame) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt
        // Everything that moves the camera is eased, so it glides: speed and twist follow the music's
        // slow drive; a beat adds a forward surge that swells in (~60 ms) and ebbs away (~0.3 s).
        drive += (a.bassSlow * 0.7f + a.level * 0.3f - drive) * (1f - exp(-dt / 0.4f))
        surge += (a.beat - surge) * (1f - exp(-dt / (if (a.beat > surge) 0.06f else 0.3f)))
        speed += (1.3f + drive * 3.2f - speed) * (1f - exp(-dt / 0.35f))
        twistRate += (0.22f + drive * 0.6f + a.mid * 0.1f - twistRate) * (1f - exp(-dt / 0.6f))
        travel += dt * (speed + surge * 1.6f)
        twist += dt * twistRate

        // Only ever seen through a seam: the walls cover the whole screen.
        c.drawColor(scaleArgb(pal.argb[1], 0.12f))
        val cx = w / 2f
        val cy = h / 2f
        val focal = w * 0.75f
        val step = floor(travel / spacing)
        val offset = travel - step * spacing
        // Where the tunnel's path is at the camera, so the near end stays centred while the far end swings.
        val camBendX = bendX(travel, f.time)
        val camBendY = bendY(travel, f.time)
        val zFar = rings * spacing

        // Ring 0 can be at or behind the camera (it's about to pass); it's clipped to the near plane below.
        for (i in 0..rings) {
            val z = i * spacing - offset + 0.3f
            depth[i] = z
            val along = travel + z
            // The tube's centre may swing, but never more than about half its radius from the line of
            // sight: the camera always stays inside, and the walls always fill the screen.
            val ox = MAX_SWAY * tanh((bendX(along, f.time) - camBendX) / MAX_SWAY)
            val oy = MAX_SWAY * tanh((bendY(along, f.time) - camBendY) / MAX_SWAY)
            // The rings breathe with the spectrum, eased, and only a little: the walls never lurch.
            val band = a.bands[(i * 48 / rings).coerceAtMost(a.bands.size - 1)]
            ringBand[i] += (band - ringBand[i]) * (1f - exp(-dt / 0.12f))
            val r = radius * (1f + ringBand[i] * 0.12f + surge * 0.05f * (1f - i / rings.toFloat()))
            val rot = twist + z * (0.1f + drive * 0.08f)
            for (j in 0 until segs) {
                val ang = rot + j * (2f * PI.toFloat() / segs)
                wx[i * segs + j] = ox + cos(ang) * r
                wy[i * segs + j] = oy + sin(ang) * r
            }
        }
        // Near-plane clipping: a ring too close slides along the tube wall to exactly the near plane.
        for (i in rings - 1 downTo 0) {
            if (depth[i] >= NEAR) continue
            val t = (NEAR - depth[i]) / (depth[i + 1] - depth[i])
            for (j in 0 until segs) {
                val k = i * segs + j
                wx[k] += (wx[k + segs] - wx[k]) * t
                wy[k] += (wy[k + segs] - wy[k]) * t
            }
            depth[i] = NEAR
        }
        for (i in 0..rings) {
            val z = depth[i]
            for (j in 0 until segs) {
                val k = i * segs + j
                sx[k] = snapToGrid(cx + wx[k] / z * focal)
                sy[k] = snapToGrid(cy + wy[k] / z * focal)
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
                // Beats brighten the walls a touch (strongest close by), easing out: no white flash.
                col = scaleArgb(col, dim * (1f + surge * 0.3f * (1f - fog)))
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
        fill.color = withAlpha(pal.argb[3], 0.25f + 0.3f * drive + 0.15f * surge)
        c.drawCircle(ringCentre(sx, rings), ringCentre(sy, rings), w * 0.02f * (1f + drive * 0.8f), fill)
    }

    private fun ringCentre(coords: FloatArray, i: Int): Float {
        var s = 0f
        for (j in 0 until segs) s += coords[i * segs + j]
        return s / segs
    }

    private companion object {
        const val NEAR = 0.12f
        /** Furthest the tube's centre strays from the line of sight, as a fraction of its radius (1). */
        const val MAX_SWAY = 0.5f
    }

    private fun bendX(along: Float, t: Float) = sin(along * 0.21f + t * 0.3f) * 1.4f + sin(along * 0.07f) * 0.8f
    private fun bendY(along: Float, t: Float) = cos(along * 0.17f + t * 0.23f) * 1.1f
}
