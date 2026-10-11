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
import kotlin.math.min
import kotlin.math.sin

/**
 * Aurora: big soft clouds of the cover's colours folding through each other (domain-warped fbm
 * noise). Bass swells and brightens them, treble adds a shimmer, the music's level speeds the flow.
 * An AGSL shader on Android 13+, rendered at half resolution; older phones get blurred blobs.
 */
class AuroraRenderer : VisualiserRenderer() {
    override val renderScale: Float get() = if (quality >= 1f) 0.5f else 0.34f

    // A shader that fails to compile (or to take its uniforms) falls back to the blobs for good.
    private var agsl: AuroraShader? = if (Build.VERSION.SDK_INT >= 33) runCatching { AuroraShader() }.getOrNull() else null
    private val blobs = AuroraBlobs()
    private var flow = 0f

    override fun draw(scope: DrawScope, f: VisFrame) {
        val a = f.audio
        flow += f.dt * (0.6f + a.level * 1.4f + a.beat * 0.8f)
        val w = scope.size.width
        val h = scope.size.height
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val shader = agsl
            val drew = shader != null && Build.VERSION.SDK_INT >= 33 &&
                runCatching { shader.draw(nc, w, h, flow, f) }.onFailure { agsl = null }.isSuccess
            if (!drew) blobs.draw(nc, w, h, flow, f)
        }
    }
}

@RequiresApi(33)
private class AuroraShader {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint().apply { shader = this@AuroraShader.shader }

    fun draw(c: android.graphics.Canvas, w: Float, h: Float, flow: Float, f: VisFrame) {
        val a = f.audio
        shader.setFloatUniform("iResolution", w, h)
        shader.setFloatUniform("iTime", flow)
        shader.setFloatUniform("uBass", a.bass)
        shader.setFloatUniform("uMid", a.mid)
        shader.setFloatUniform("uTreble", a.treble)
        shader.setFloatUniform("uBeat", a.beat)
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
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * noise(p);
        p = p * 2.03 + float2(3.1, 1.7);
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.y;
    float aspect = iResolution.x / iResolution.y;
    float t = iTime * 0.07;
    float zoom = 1.5 - uBass * 0.3;
    float2 p = (uv - float2(0.5 * aspect, 0.5)) * zoom;
    float2 q = float2(fbm(p + float2(0.0, t)), fbm(p + float2(5.2, 1.3 - t)));
    float2 r = float2(fbm(p + 2.4 * q + float2(1.7, 9.2) + t * 1.3), fbm(p + 2.4 * q + float2(8.3, 2.8) - t * 0.9));
    float f = fbm(p + 2.2 * r + float2(uMid * 0.25, 0.0));

    float3 k0 = float3(c0.rgb);
    float3 k1 = float3(c1.rgb);
    float3 k2 = float3(c2.rgb);
    float3 k3 = float3(c3.rgb);
    float3 col = mix(k0, k1, smoothstep(0.25, 0.75, f));
    col = mix(col, k2, clamp(length(q) * 0.9 - 0.3, 0.0, 1.0));
    col = mix(col, k3, smoothstep(0.5, 0.9, r.y));

    // Clouds: glowing even in silence (bright cores, softly lit gaps); audio swells them.
    float cloud = smoothstep(0.18, 0.68, f + 0.35 * length(r) - 0.1);
    float shimmer = noise(p * 10.0 + float2(iTime * 1.9, -iTime * 1.4));
    float rest = mix(0.16, 0.95, cloud) + 0.25 * cloud * cloud;
    float swell = 1.0 + uBass * 0.55 + uBeat * 0.3 + uMid * 0.15;
    float3 lit = col * rest * swell + col * (uTreble * 0.35 * shimmer * cloud);
    // A soft tint of the first colour in the gaps, so it's never just black between clouds.
    lit += k0 * 0.08 * (1.0 - cloud);
    // Gentle tone curve: keeps colour in the highlights instead of clipping to white.
    lit = lit / (1.0 + lit * 0.35) * 1.2;

    float2 v = fragCoord / iResolution - 0.5;
    lit *= 1.0 - dot(v, v) * 0.6;
    return half4(half3(clamp(lit, 0.0, 1.0)), 1.0);
}
"""
    }
}

/** Fallback for Android 8–12: radial-gradient blobs drifting on Lissajous paths, added together. */
private class AuroraBlobs {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val shaders = arrayOfNulls<Shader>(4)
    private var version = -1

    fun draw(c: android.graphics.Canvas, w: Float, h: Float, flow: Float, f: VisFrame) {
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
        val a = f.audio
        c.drawColor(scaleArgb(pal.argb[0], 0.06f))
        val base = min(w, h) * (0.62f + a.bass * 0.25f + a.beat * 0.08f)
        val t = flow * 0.25f
        for (i in 0 until 6) {
            val x = w * (0.5f + 0.38f * sin(t * (0.7f + i * 0.13f) + i * 1.7f))
            val y = h * (0.5f + 0.38f * cos(t * (0.5f + i * 0.11f) + i * 2.3f))
            val r = base * (0.8f + 0.25f * sin(t * 1.3f + i)) * (1f + a.treble * 0.15f * sin(flow * 7f + i))
            paint.shader = shaders[i % 4]
            // Clearly visible at rest; louder music brightens them further.
            paint.alpha = (255 * (0.72f + 0.28f * a.level + 0.15f * a.beat)).toInt().coerceIn(0, 255)
            c.save()
            c.translate(x, y)
            c.scale(r, r)
            c.drawCircle(0f, 0f, 1f, paint)
            c.restore()
        }
    }
}
