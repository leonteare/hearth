package im.flume.hearth.ui.screens.visualiser

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pipes: shiny tubes grow through a 3D lattice, seen by a slowly orbiting camera, in the spirit of
 * the classic 3D pipes screensaver. Each pipe grows one cell per eighth note (or on hats and beats
 * when the tempo isn't known), turns on kicks or when blocked, and when stuck it ends and another
 * starts elsewhere in a new colour; snares and every eighth beat bring in extra pipes. When the
 * lattice is well filled everything fades out and it starts again.
 */
class PipesRenderer : VisualiserRenderer() {
    private val world = PipeWorld(n = N, maxPipes = 6, maxSegments = 640, seed = 11)
    private val cells = world.cells

    // Cell centres in the world, and their projections this frame.
    private val wx = FloatArray(cells)
    private val wy = FloatArray(cells)
    private val wz = FloatArray(cells)
    private val sx = FloatArray(cells)
    private val sy = FloatArray(cells)
    private val sd = FloatArray(cells)

    // Painter's order: segments and corner balls bucketed by depth, far first. Items below
    // maxSegments are segments; maxSegments + s is the corner ball at the start of segment s.
    private val bucketStart = IntArray(BUCKETS + 1)
    private val bucketOf = IntArray(world.maxSegments * 2)
    private val order = IntArray(world.maxSegments * 2)
    private var itemCount = 0

    private val colours = IntArray(8)
    private var coloursFor = -1

    private var yaw = 0.6f
    private var lastSub = Int.MIN_VALUE
    private var lastBeat = Int.MIN_VALUE
    private var lastKick = 0
    private var lastSnare = 0
    private var prevHat = 0f
    private var sinceStep = 0f
    private var sinceSpawn = 0f
    private var stepInterval = 0.25f
    private var beatsThisRun = 0
    private var runTime = 0f
    /** 0 while growing; counts up through the fade-out, then the lattice restarts. */
    private var fadeOut = -1f
    private var fadeIn = 0f
    private var glow = 0f

    private var background: Brush? = null
    private var backgroundKey = 0L
    private var scanlines: ShaderBrush? = null

    init {
        val c = (N - 1) / 2f
        for (i in 0 until cells) {
            wx[i] = i % N - c
            wy[i] = (i / N) % N - c
            wz[i] = i / (N * N) - c
        }
        restart()
    }

    private fun restart() {
        world.reset()
        world.spawn()
        world.spawn()
        beatsThisRun = 0
        runTime = 0f
        fadeOut = -1f
        fadeIn = 0f
    }

    override fun draw(scope: DrawScope, f: VisFrame) = with(scope) {
        val a = f.audio
        val pal = f.palette
        val dt = f.dt
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@with

        grow(f)

        // Camera: a slow orbit, a little faster with the music, gently rising and dipping.
        yaw += dt * (0.09f + 0.12f * a.level)
        val tilt = 0.38f + 0.12f * sin(f.time * 0.13f)
        val camDist = N * 2.3f
        val focal = min(w, h) * 0.5f * camDist / (N * 0.62f)
        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosT = cos(tilt); val sinT = sin(tilt)
        val cx = w / 2f
        val cy = h / 2f
        for (i in 0 until cells) {
            val x1 = wx[i] * cosY - wz[i] * sinY
            val z1 = wx[i] * sinY + wz[i] * cosY
            val y2 = wy[i] * cosT - z1 * sinT
            val z2 = wy[i] * sinT + z1 * cosT
            val depth = camDist + z2
            val s = focal / depth
            sx[i] = cx + x1 * s
            sy[i] = cy - y2 * s
            sd[i] = depth
        }

        // Background, cached per palette and size.
        val key = (pal.version.toLong() shl 32) xor (w.toLong() * 31 + h.toLong())
        if (backgroundKey != key || background == null) {
            backgroundKey = key
            background = Brush.radialGradient(
                listOf(pal.color(3).copy(alpha = 0.16f), pal.color(2).copy(alpha = 0.05f), Color.Black),
                center = Offset(cx, cy), radius = max(w, h) * 0.75f,
            )
        }
        drawRect(Color.Black)
        drawRect(background!!)

        if (coloursFor != pal.version) {
            coloursFor = pal.version
            for (i in 0 until 8) colours[i] = lerpArgb(pal.argbAt(i * 0.375f + 0.02f), 0xFFFFFFFF.toInt(), 0.08f)
        }

        val fade = if (fadeOut >= 0f) (1f - fadeOut / FADE_OUT).coerceIn(0f, 1f) else smoothstep(0f, FADE_IN, fadeIn)
        glow += (a.kick - glow) * (1f - kotlin.math.exp(-dt / if (a.kick > glow) 0.03f else 0.25f))
        val pulse = 0.82f + 0.13f * a.level + 0.15f * glow
        val near = camDist - N * 0.87f
        val far = camDist + N * 0.87f
        val radius = TUBE * focal
        val lowQuality = quality < 0.8f

        sortSegments(near, far)
        for (o in 0 until itemCount) {
            val item = order[o]
            if (item >= world.maxSegments) {
                // A corner ball, sorted on its own just in front of the pieces it joins, so it never
                // flickers behind one of them as the camera turns.
                val s = item - world.maxSegments
                val ca = world.segA[s]
                val fog = (sd[ca] - near) / (far - near)
                val shade = (1f - 0.55f * fog.coerceIn(0f, 1f)) * pulse
                ball(sx[ca], sy[ca], radius / sd[ca], scaleArgb(colours[world.segColour[s] and 7], shade), fade, lowQuality)
                continue
            }
            val s = item
            val ca = world.segA[s]
            val cb = world.segB[s]
            val grow = ((f.time - world.segBirth[s]) / GROW_TIME).coerceIn(0f, 1f)
            val ax = sx[ca]; val ay = sy[ca]
            val bx = ax + (sx[cb] - ax) * grow
            val by = ay + (sy[cb] - ay) * grow
            val depthA = sd[ca]
            val depthB = depthA + (sd[cb] - depthA) * grow
            val fog = ((depthA + depthB) * 0.5f - near) / (far - near)
            val shade = (1f - 0.55f * fog.coerceIn(0f, 1f)) * pulse
            val base = scaleArgb(colours[world.segColour[s] and 7], shade)
            val r = radius / ((depthA + depthB) * 0.5f)
            tube(ax, ay, bx, by, r, base, fade, lowQuality)
            if (world.segCapB[s] && grow >= 1f) ball(bx, by, radius / depthB, base, fade, lowQuality)
            else if (world.segCapB[s]) ball(bx, by, radius / depthB * 0.9f, base, fade, lowQuality)
        }

        // The newest segment of each growing pipe glows, more on kicks.
        if (!lowQuality) {
            for (p in 0 until world.maxPipes) {
                val s = world.newest[p]
                if (!world.alive[p] || s < 0) continue
                val ca = world.segA[s]
                val cb = world.segB[s]
                val grow = ((f.time - world.segBirth[s]) / GROW_TIME).coerceIn(0f, 1f)
                val bx = sx[ca] + (sx[cb] - sx[ca]) * grow
                val by = sy[ca] + (sy[cb] - sy[ca]) * grow
                val r = radius / sd[cb]
                val c = Color(colours[world.segColour[s] and 7])
                drawLine(
                    c, Offset(sx[ca], sy[ca]), Offset(bx, by), strokeWidth = r * 4.5f, cap = StrokeCap.Round,
                    alpha = (0.12f + 0.12f * a.level + 0.18f * glow) * fade, blendMode = BlendMode.Plus,
                )
                drawCircle(c, radius = r * 2.2f, center = Offset(bx, by), alpha = (0.1f + 0.2f * glow) * fade, blendMode = BlendMode.Plus)
            }
        }

        drawRect(scanlineBrush(), alpha = 0.12f)
        Unit
    }

    /** A lit tube: dark edge, body colour, then a thin highlight shifted towards the light. */
    private fun DrawScope.tube(ax: Float, ay: Float, bx: Float, by: Float, r: Float, base: Int, alpha: Float, low: Boolean) {
        val a = Offset(ax, ay)
        val b = Offset(bx, by)
        drawLine(Color(scaleArgb(base, 0.32f)), a, b, strokeWidth = r * 2f, cap = StrokeCap.Round, alpha = alpha)
        drawLine(Color(base), a, b, strokeWidth = r * 1.55f, cap = StrokeCap.Round, alpha = alpha)
        if (low) return
        // Normal to the segment on screen, on the light's side (upper left).
        var nx = -(by - ay)
        var ny = bx - ax
        val len = sqrt(nx * nx + ny * ny)
        if (len > 1e-3f) { nx /= len; ny /= len } else { nx = LIGHT_X; ny = LIGHT_Y }
        if (nx * LIGHT_X + ny * LIGHT_Y < 0f) { nx = -nx; ny = -ny }
        val o = r * 0.4f
        drawLine(
            Color(lerpArgb(base, 0xFFFFFFFF.toInt(), 0.55f)), Offset(ax + nx * o, ay + ny * o), Offset(bx + nx * o, by + ny * o),
            strokeWidth = r * 0.42f, cap = StrokeCap.Round, alpha = alpha * 0.85f,
        )
    }

    /** A lit ball: dark rim, body, and a highlight up towards the light. */
    private fun DrawScope.ball(x: Float, y: Float, r0: Float, base: Int, alpha: Float, low: Boolean) {
        val r = r0 * 1.3f
        drawCircle(Color(scaleArgb(base, 0.32f)), radius = r, center = Offset(x, y), alpha = alpha)
        drawCircle(Color(base), radius = r * 0.84f, center = Offset(x + LIGHT_X * r * 0.08f, y + LIGHT_Y * r * 0.08f), alpha = alpha)
        if (low) return
        drawCircle(
            Color(lerpArgb(base, 0xFFFFFFFF.toInt(), 0.45f)), radius = r * 0.45f,
            center = Offset(x + LIGHT_X * r * 0.3f, y + LIGHT_Y * r * 0.3f), alpha = alpha * 0.8f,
        )
        drawCircle(Color.White, radius = r * 0.16f, center = Offset(x + LIGHT_X * r * 0.42f, y + LIGHT_Y * r * 0.42f), alpha = alpha * 0.7f)
    }

    /**
     * Counting sort of the segments and corner balls into depth buckets, far first: cheap and
     * allocation-free. A ball is placed a little nearer than its corner so it lands over both pieces
     * that meet there.
     */
    private fun sortSegments(near: Float, far: Float) {
        val count = world.segCount
        val joint = world.maxSegments
        bucketStart.fill(0)
        fun bucket(d: Float) = (((far - d) / (far - near)) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
        for (i in 0 until count) {
            val s = world.slot(i)
            val b = bucket((sd[world.segA[s]] + sd[world.segB[s]]) * 0.5f)
            bucketOf[s] = b
            bucketStart[b + 1]++
            if (world.segJointA[s]) {
                val bj = (bucket(sd[world.segA[s]] - JOINT_BIAS) + 1).coerceAtMost(BUCKETS - 1)
                bucketOf[joint + s] = bj
                bucketStart[bj + 1]++
            }
        }
        for (b in 0 until BUCKETS) bucketStart[b + 1] += bucketStart[b]
        itemCount = bucketStart[BUCKETS]
        // Within a bucket, oldest first, so a pipe's newer segment lands over its older one; a ball
        // comes after the segments in its bucket.
        for (i in 0 until count) {
            val s = world.slot(i)
            order[bucketStart[bucketOf[s]]++] = s
        }
        for (i in 0 until count) {
            val s = world.slot(i)
            if (world.segJointA[s]) order[bucketStart[bucketOf[joint + s]]++] = joint + s
        }
    }

    /** Steps the pipes in time with the music, spawns new ones, and handles the fade and restart. */
    private fun grow(f: VisFrame) {
        val a = f.audio
        val dt = f.dt
        sinceStep += dt
        sinceSpawn += dt
        runTime += dt
        fadeIn += dt

        if (fadeOut >= 0f) {
            fadeOut += dt
            if (fadeOut >= FADE_OUT) restart()
            prevHat = a.hat
            lastSub = Int.MIN_VALUE
            return
        }

        val confident = a.bpm > 0f && a.tempoConfidence > 0.4f
        var step = false
        if (confident) {
            // Eighth notes, from the beat clock; quarters if that'd be frantic.
            val eighth = 30f / a.bpm
            val split = if (eighth < 0.14f) 1 else 2
            val sub = a.beatCount * split + (a.beatPhase * split).toInt().coerceIn(0, split - 1)
            stepInterval = eighth * (3 - split)
            if (sub != lastSub) {
                lastSub = sub
                if (sinceStep > stepInterval * 0.5f) step = true
            }
            if (sinceStep > stepInterval * 1.8f) step = true
        } else {
            lastSub = Int.MIN_VALUE
            stepInterval = 0.25f
            val hatOnset = a.hat > 0.6f && prevHat <= 0.6f
            if ((hatOnset || a.beatCount != lastBeat) && sinceStep > 0.11f) step = true
            // Keep moving through quiet passages (slowly when nothing's playing).
            if (sinceStep > (if (f.isPlaying) 0.4f else 0.6f)) step = true
        }
        prevHat = a.hat

        if (a.beatCount != lastBeat) {
            if (lastBeat != Int.MIN_VALUE) {
                beatsThisRun++
                if (a.beatCount % 8 == 0 && sinceSpawn > 1f) { if (world.spawn()) sinceSpawn = 0f }
            }
            lastBeat = a.beatCount
        }
        if (a.snareCount != lastSnare) {
            if (a.level > 0.3f && sinceSpawn > 2f && world.spawn()) sinceSpawn = 0f
            lastSnare = a.snareCount
        }

        if (step) {
            val kicked = a.kickCount != lastKick
            lastKick = a.kickCount
            sinceStep = 0f
            world.step(kicked, f.time)
            if (world.activeCount == 0) world.spawn()
        }

        val tooLong = if (confident) beatsThisRun >= MAX_BARS * 4 else runTime > MAX_RUN_SEC
        if (world.fillFraction >= FULL || tooLong || (world.activeCount == 0 && !world.spawn())) fadeOut = 0f
    }

    private fun DrawScope.scanlineBrush(): ShaderBrush {
        scanlines?.let { return it }
        val period = max(3, (density * 2.5f).toInt())
        val tile = Bitmap.createBitmap(1, period, Bitmap.Config.ARGB_8888)
        tile.setPixel(0, period - 1, 0xFF000000.toInt())
        val brush = ShaderBrush(ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        scanlines = brush
        return brush
    }

    override fun release() {
        scanlines = null
        background = null
    }

    private companion object {
        /** How much nearer than its corner a ball sorts, in world units (about half a tube). */
        const val JOINT_BIAS = 0.05f
        const val N = 10
        /** Tube radius, in cells. */
        const val TUBE = 0.2f
        const val GROW_TIME = 0.1f
        const val BUCKETS = 96
        /** Restart once this much of the lattice is filled, or after so long. */
        const val FULL = 0.6f
        const val MAX_BARS = 48
        const val MAX_RUN_SEC = 100f
        const val FADE_OUT = 1.5f
        const val FADE_IN = 0.6f
        const val LIGHT_X = -0.45f
        const val LIGHT_Y = -0.89f
    }
}
