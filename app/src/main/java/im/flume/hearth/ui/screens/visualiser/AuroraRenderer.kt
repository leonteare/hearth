package im.flume.hearth.ui.screens.visualiser

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Aurora: big soft clouds of the cover's colours drifting through a faint haze (five gaussian glows
 * on slow paths, lightly warped by two octaves of noise: cheap enough for mid-range phones, and with
 * no folds or creases). The slow bass swells and brightens them, beats add a soft bloom, treble a shimmer, and the
 * music's level speeds the flow. An AGSL shader on Android 13+ (rotated octaves and quintic easing so
 * no lattice shows, dithered so dark gradients don't band), at 3/4 resolution scaled up smoothly;
 * older phones get dithered gradient blobs.
 */
class AuroraRenderer : VisualiserRenderer() {
    override val renderScale: Float get() = if (quality >= 1f) 0.5f else 0.4f // soft by nature, so a smaller layer scaled up looks the same

    // A shader that fails to compile (or to take its uniforms) falls back to the blobs for good.
    private var agsl: AuroraShader? = if (Build.VERSION.SDK_INT >= 33) runCatching { AuroraShader() }.getOrNull() else null
    private val blobs = AuroraBlobs()
    private val drive = AuroraDrive()

    override fun draw(scope: DrawScope, f: VisFrame) {
        drive.step(f)
        val w = scope.size.width
        val h = scope.size.height
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val shader = agsl
            val drew = shader != null && Build.VERSION.SDK_INT >= 33 &&
                runCatching { shader.draw(nc, w, h, drive, f) }.onFailure { agsl = null }.isSuccess
            if (!drew) blobs.draw(nc, w, h, drive, f)
        }
    }
}

/** The audio, eased for big, slow shapes: nothing in Aurora moves faster than these. */
private class AuroraDrive {
    var flow = 0f
    var swell = 0f
    var bloom = 0f
    var mid = 0f
    var treble = 0f

    fun step(f: VisFrame) {
        val a = f.audio
        val dt = f.dt
        swell = ease(swell, a.bassSlow, dt, 0.12f, 0.5f)
        bloom = ease(bloom, a.beat, dt, 0.05f, 0.35f)
        mid = ease(mid, a.mid, dt, 0.2f, 0.6f)
        treble = ease(treble, a.treble, dt, 0.1f, 0.4f)
        flow += dt * (0.6f + a.level * 1.1f + bloom * 0.5f)
    }

    private fun ease(cur: Float, target: Float, dt: Float, up: Float, down: Float) =
        cur + (target - cur) * (1f - exp(-dt / (if (target > cur) up else down)))
}

@RequiresApi(33)
private class AuroraShader {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint().apply {
        shader = this@AuroraShader.shader
        isDither = true
    }

    fun draw(c: android.graphics.Canvas, w: Float, h: Float, d: AuroraDrive, f: VisFrame) {
        shader.setFloatUniform("iResolution", w, h)
        shader.setFloatUniform("iTime", d.flow)
        shader.setFloatUniform("uBass", d.swell)
        shader.setFloatUniform("uMid", d.mid)
        shader.setFloatUniform("uTreble", d.treble)
        shader.setFloatUniform("uBeat", d.bloom)
        val p = f.palette.argb
        shader.setColorUniform("c0", p[0])
        shader.setColorUniform("c1", p[1])
        shader.setColorUniform("c2", p[2])
        shader.setColorUniform("c3", p[3])
        c.drawRect(0f, 0f, w, h, paint)
    }

    private companion object {
        const val SOURCE = """
uniform float2 iResolution;
uniform float iTime;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform float uBeat;
layout(color) uniform half4 c0;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    // Quintic easing: the slope is continuous across cells, so no creases along the lattice.
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm2(float2 p) {
    // Two gentle octaves only: a faint veil of texture, not structure.
    float v = 0.6 * noise(p);
    p = float2(0.8 * p.x - 0.6 * p.y, 0.6 * p.x + 0.8 * p.y) * 2.03 + float2(3.1, 1.7);
    return v + 0.4 * noise(p);
}

// One soft cloud: a wide gaussian glow that drifts on a slow Lissajous path.
float cloud(float2 p, float t, float i, float size) {
    float2 c = float2(sin(t * (0.31 + i * 0.07) + i * 1.7), cos(t * (0.23 + i * 0.05) + i * 2.3)) * float2(0.55, 0.75);
    float2 d = p - c;
    return exp(-dot(d, d) / (size * size));
}

half4 main(float2 fragCoord) {
    float aspect = iResolution.x / iResolution.y;
    float2 p = (fragCoord / iResolution - 0.5) * float2(aspect, 1.0) * 2.0;
    float t = iTime * 0.35;
    float size = 0.4 + uBass * 0.2 + uBeat * 0.07;
    // A slow, soft warp so the clouds feel like light through haze rather than perfect circles.
    float2 w = p + 0.25 * (float2(fbm2(p * 0.9 + t * 0.2), fbm2(p * 0.9 + float2(4.1, 2.7) - t * 0.17)) - 0.5);

    float3 k0 = float3(c0.rgb);
    float3 k1 = float3(c1.rgb);
    float3 k2 = float3(c2.rgb);
    float3 k3 = float3(c3.rgb);
    float a0 = cloud(w, t, 0.0, size);
    float a1 = cloud(w, t, 1.0, size * 0.9);
    float a2 = cloud(w, t, 2.0, size * 1.1);
    float a3 = cloud(w, t, 3.0, size * 0.8);
    float a4 = cloud(w, t, 4.0, size);
    float3 lit = k0 * (a0 + a4 * 0.6) + k1 * a1 + k2 * a2 + k3 * (a3 + a4 * 0.4);
    float glow = a0 + a1 + a2 + a3 + a4;

    // Rest brightness with the music swelling it; treble adds a faint shimmer in the veil.
    float swell = 0.75 + uBass * 0.45 + uBeat * 0.25 + uMid * 0.1;
    lit *= swell;
    lit += k0 * 0.04;
    lit += (k1 + k2) * 0.06 * uTreble * (fbm2(p * 3.0 + float2(iTime * 0.5, 0.0)) - 0.3) * min(glow, 1.0);
    // Gentle tone curve: colour stays in the highlights instead of clipping to white.
    lit = lit / (1.0 + lit * 0.45) * 1.25;

    float2 v = fragCoord / iResolution - 0.5;
    lit *= 1.0 - dot(v, v) * 0.6;
    // Triangular dither of about 1.5 levels: breaks up 8-bit banding in the soft gradients.
    float dz = hash(fragCoord) + hash(fragCoord + float2(17.3, 41.9)) - 1.0;
    lit += dz * (1.5 / 255.0);
    return half4(half3(clamp(lit, 0.0, 1.0)), 1.0);
}
"""
    }
}

/** Fallback for Android 8–12: dithered radial-gradient blobs drifting on Lissajous paths, added together. */
private class AuroraBlobs {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val shaders = arrayOfNulls<Shader>(4)
    private var version = -1

    fun draw(c: android.graphics.Canvas, w: Float, h: Float, d: AuroraDrive, f: VisFrame) {
        val pal = f.palette
        if (version != pal.version) {
            version = pal.version
            for (i in 0 until 4) {
                val col = pal.argb[i]
                shaders[i] = RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(withAlpha(col, 0.62f), withAlpha(col, 0.4f), withAlpha(col, 0.12f), withAlpha(col, 0f)),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP,
                )
            }
        }
        c.drawColor(scaleArgb(pal.argb[0], 0.06f))
        val flow = d.flow
        val base = min(w, h) * (0.62f + d.swell * 0.25f + d.bloom * 0.08f)
        val t = flow * 0.25f
        for (i in 0 until 6) {
            val x = w * (0.5f + 0.38f * sin(t * (0.7f + i * 0.13f) + i * 1.7f))
            val y = h * (0.5f + 0.38f * cos(t * (0.5f + i * 0.11f) + i * 2.3f))
            val r = base * (0.8f + 0.25f * sin(t * 1.3f + i)) * (1f + d.treble * 0.1f * sin(flow * 3f + i))
            paint.shader = shaders[i % 4]
            // Clearly visible at rest; louder music brightens them further.
            paint.alpha = (255 * (0.72f + 0.28f * d.swell + 0.15f * d.bloom)).toInt().coerceIn(0, 255)
            c.save()
            c.translate(x, y)
            c.scale(r, r)
            c.drawCircle(0f, 0f, 1f, paint)
            c.restore()
        }
    }
}
