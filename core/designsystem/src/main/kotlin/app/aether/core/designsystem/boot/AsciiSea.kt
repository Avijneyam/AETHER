package app.aether.core.designsystem.boot

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import app.aether.core.designsystem.art.GlyphAtlas
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink

/**
 * A sea at night, drawn in characters (after the volna reference): crests rolling toward the
 * viewer in perspective, foam where they break, a moonlit path down the middle, a dark sky above
 * the horizon. Every cell is a glyph chosen by its brightness. Still when motion is reduced.
 */
@Composable
fun AsciiSea(modifier: Modifier = Modifier, cellDp: Float = 6.5f, horizon: Float = 0.6f) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Canvas(modifier.fillMaxSize().background(Ink.Black)) {}
        return
    }
    SeaCanvas(modifier, cellDp, horizon)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun SeaCanvas(modifier: Modifier, cellDp: Float, horizon: Float) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val reduce = LocalReduceMotion.current
    // Symbols only: letters in a sea read as words, not water.
    val atlas = remember { GlyphAtlas.ramp(context, steps = 12, candidates = " .,:;-~=+*o%#@") }
    val shader = remember { RuntimeShader(SEA) }
    val glyphs = remember { BitmapShader(atlas.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    var time by remember { mutableFloatStateOf(12f) }
    if (!reduce) LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = 12f + (it - start) / 1e9f }
    }
    Canvas(modifier.fillMaxSize().background(Ink.Black)) {
        val cw = cellDp * density
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("cell", cw, cw * atlas.glyphHeight / atlas.glyphWidth)
        shader.setFloatUniform("glyphPx", atlas.glyphWidth.toFloat(), atlas.glyphHeight.toFloat())
        shader.setFloatUniform("glyphCount", atlas.count.toFloat())
        shader.setFloatUniform("horizon", horizon)
        shader.setInputShader("glyphs", glyphs)
        drawRect(ShaderBrush(shader))
    }
}

private const val SEA = """
uniform float2 res;
uniform float time;
uniform float2 cell;
uniform float2 glyphPx;
uniform float glyphCount;
uniform float horizon;
uniform shader glyphs;

float hash(float2 p) {
    float3 q = fract(float3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

// The sea's height at ground point (x, z): long swells, a cross chop, and fine noise.
float sea(float2 g) {
    float h = 0.55 * sin(g.y * 1.6 - time * 1.1 + sin(g.x * 0.35) * 0.8);
    h += 0.28 * sin(g.y * 3.1 + g.x * 0.9 - time * 1.7);
    h += 0.18 * sin(g.x * 2.3 - g.y * 2.0 + time * 0.8);
    h += 0.22 * (noise(g * float2(1.3, 2.2) + float2(0.0, -time * 0.6)) - 0.5);
    return h;
}

half4 main(float2 p) {
    float2 c = floor(p / cell);
    float2 centre = (c + 0.5) * cell;
    float2 uv = centre / res;
    float l = 0.0;
    if (uv.y > horizon) {
        float d = (uv.y - horizon) / (1.0 - horizon);
        // Perspective: distance shrinks toward the horizon, where the sea lies calm and flat.
        float z = 1.0 / max(d, 0.02);
        float x = (uv.x - 0.5) * z * 1.4;
        float calm = smoothstep(0.0, 0.4, d);
        float swell = sin(z * 2.6 - time * 1.4 + 0.6 * sin(x * 0.5 + time * 0.3));
        float chop = 0.35 * sin(z * 5.3 + x * 0.8 - time * 2.1);
        float n = noise(float2(x * 1.5, z * 3.0 - time * 0.8)) - 0.5;
        float crest = smoothstep(0.55, 1.05, (swell + chop * 0.5 + n * 0.3)) * calm;
        float path = exp(-pow((uv.x - 0.5) / (0.05 + 0.28 * d), 2.0));
        float body = (0.10 + 0.22 * (0.5 + 0.5 * swell) * calm) * (0.35 + 0.65 * d);
        l = body + crest * (0.30 + 0.45 * path) * (0.4 + 0.6 * d) + path * 0.10 * (1.0 - 0.5 * d);
        l *= smoothstep(0.0, 0.08, d) * 1.7;
        // the horizon itself: one thin line of light
        l += exp(-pow(d / 0.012, 2.0)) * 0.5 * exp(-pow((uv.x - 0.5) / 0.35, 2.0));
    } else {
        // The sky: a faint glow over the horizon, and a few still stars.
        float glow = exp(-pow((uv.y - horizon) / 0.05, 2.0)) * exp(-pow((uv.x - 0.5) / 0.22, 2.0));
        float star = step(0.997, hash(c * 1.7)) * 0.4 * (0.6 + 0.4 * sin(time * 1.3 + hash(c) * 6.28));
        l = glow * 0.35 + star;
    }
    l = clamp(l, 0.0, 1.0);
    float gi = floor(l * (glyphCount - 1.0) + 0.5);
    float2 f = clamp((p - c * cell) / cell, 0.02, 0.98);
    float ink = float(glyphs.eval(float2((gi + f.x) * glyphPx.x, f.y * glyphPx.y)).a);
    float v = ink * (0.35 + 0.65 * l);
    return half4(half3(v), 1.0);
}
"""
