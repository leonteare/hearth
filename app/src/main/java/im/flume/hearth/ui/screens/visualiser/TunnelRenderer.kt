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
 *
 * Kept cheap enough for 60 fps on mid-range phones: the whole tube goes to the canvas as one
 * flat-shaded triangle list (a single drawVertices call, far to near), every buffer is a reused
 * field, and each ring needs one sin/cos pair (its segments are a fixed unit circle, rotated).
 */
class TunnelRenderer : RetroRenderer() {
    override val bloom: Float get() = 0.35f
    override val ditherLevels: Int get() = 18

    private val rings = 24
    private val segs = 14
    private val spacing = 0.85f
    private val radius = 1f
    private val sx = FloatArray((rings + 1) * segs)
    private val sy = FloatArray((rings + 1) * segs)
    private val depth = FloatArray(rings + 1)
    private val wx = FloatArray((rings + 1) * segs)
    private val wy = FloatArray((rings + 1) * segs)
    private val ringBand = FloatArray(rings + 1)
    private val unitCos = FloatArray(segs) { cos(it * 2.0 * PI / segs).toFloat() }
    private val unitSin = FloatArray(segs) { sin(it * 2.0 * PI / segs).toFloat() }
    /** The frame's triangles: x, y per vertex, and one colour per vertex (all three the same). */
    private val verts = FloatArray(rings * segs * 2 * 3 * 2)
    private val colors = IntArray(rings * segs * 2 * 3)
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
        val bandEase = 1f - exp(-dt / 0.12f)
        val lastBand = a.bands.size - 1

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
            val band = a.bands[(i * 48 / rings).coerceAtMost(lastBand)]
            ringBand[i] += (band - ringBand[i]) * bandEase
            val r = radius * (1f + ringBand[i] * 0.12f + surge * 0.05f * (1f - i / rings.toFloat()))
            val rot = twist + z * (0.1f + drive * 0.08f)
            // One rotation per ring: each segment is the unit circle's point turned by rot.
            val rc = cos(rot) * r
            val rs = sin(rot) * r
            val base = i * segs
            for (j in 0 until segs) {
                wx[base + j] = ox + unitCos[j] * rc - unitSin[j] * rs
                wy[base + j] = oy + unitSin[j] * rc + unitCos[j] * rs
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
            val k0 = i * segs
            val s = focal / depth[i]
            for (j in 0 until segs) {
                val k = k0 + j
                sx[k] = snapToGrid(cx + wx[k] * s)
                sy[k] = snapToGrid(cy + wy[k] * s)
            }
        }

        // Far to near: painter's algorithm, each ring's quads between it and the next one out, all
        // in one triangle list drawn in that order.
        val chequerOn = pal.argb[0]
        val chequerOff = scaleArgb(pal.argb[1], 0.55f)
        val stripeColor = pal.argb[2]
        var n = 0
        for (i in rings - 1 downTo 0) {
            val zMid = (depth[i] + depth[i + 1]) / 2f
            val fog = fogAmount(zMid, 1.5f, zFar * 0.95f)
            val dim = (1f - fog) * (1f - fog)
            val ringIndex = (step + i).toInt()
            val stripe = ringIndex % 6 == 0
            // Beats brighten the walls a touch (strongest close by), easing out: no white flash.
            val bright = dim * (1f + surge * 0.3f * (1f - fog))
            // Three shades per ring, computed once rather than per quad.
            val lit0 = scaleArgb(if (stripe) stripeColor else chequerOn, bright)
            val lit1 = if (stripe) lit0 else scaleArgb(chequerOff, bright)
            val seam0 = scaleArgb(lit0, 0.82f)
            val seam1 = scaleArgb(lit1, 0.82f)
            for (j in 0 until segs) {
                val chequer = (ringIndex + j) and 1 == 0
                val j2 = if (j + 1 == segs) 0 else j + 1
                val a0 = i * segs + j
                val a1 = i * segs + j2
                val b0 = a0 + segs
                val b1 = a1 + segs
                n = tri(n, a0, a1, b1, if (chequer) lit0 else lit1)
                n = tri(n, a0, b1, b0, if (chequer) seam0 else seam1)
            }
        }
        if (n > 0) {
            fill.color = 0xFFFFFFFF.toInt()
            c.drawVertices(Canvas.VertexMode.TRIANGLES, n * 2, verts, 0, null, 0, colors, 0, null, 0, 0, fill)
        }
        // The light at the end of the tunnel.
        fill.color = withAlpha(pal.argb[3], 0.25f + 0.3f * drive + 0.15f * surge)
        c.drawCircle(ringCentre(sx, rings), ringCentre(sy, rings), w * 0.02f * (1f + drive * 0.8f), fill)
    }

    /** Appends one flat triangle (vertex indices into [sx]/[sy]) at vertex [n]; returns the next free one. */
    private fun tri(n: Int, p: Int, q: Int, r: Int, color: Int): Int {
        var v = n * 2
        verts[v++] = sx[p]; verts[v++] = sy[p]
        verts[v++] = sx[q]; verts[v++] = sy[q]
        verts[v++] = sx[r]; verts[v] = sy[r]
        colors[n] = color; colors[n + 1] = color; colors[n + 2] = color
        return n + 3
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
