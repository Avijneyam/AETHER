package app.aether.core.designsystem.art

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/*
 * Brushed gunmetal, full of glitter: a dark sheet with a fine brushed grain, broad bands of light
 * lying across it, and thousands of tiny flakes that each catch the light only at their own angle.
 * Moving [light] sweeps the bands and sets different flakes alight, which is what makes it read as metal.
 */
private const val GLITTER_METAL = """
    uniform float2 size;
    uniform float2 light;
    uniform float time;

    float hash(float2 p) {
        float3 q = fract(float3(p.xyx) * 0.1031);
        q += dot(q, q.yzx + 33.33);
        return fract((q.x + q.y) * q.z);
    }

    float noise(float2 p) {
        float2 i = floor(p);
        float2 f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
    }

    float flakes(float2 p, float cell, float seed, float tilt, float keep, float sharp) {
        float2 g = p / cell;
        float2 c = floor(g);
        float2 f = fract(g) - 0.5;
        float present = step(keep, hash(c + seed));
        float phase = hash(c + seed + 7.3) * 6.2832;
        float glint = pow(max(0.0, cos(tilt + phase)), sharp);
        float dot_ = smoothstep(0.55, 0.05, length(f));
        return present * glint * dot_;
    }

    half4 main(float2 p) {
        float2 uv = p / size;
        float a = 0.5;
        float2 r = float2(p.x * cos(a) + p.y * sin(a), -p.x * sin(a) + p.y * cos(a));
        float grain = noise(float2(r.x * 0.010, r.y * 1.3)) * 0.6 + noise(float2(r.x * 0.03, r.y * 2.9)) * 0.4;

        float d = dot(uv - light, float2(0.58, 0.81));
        float sheen = exp(-d * d * 8.0) * 0.8 + exp(-(d + 0.62) * (d + 0.62) * 16.0) * 0.4 + exp(-(d - 0.7) * (d - 0.7) * 20.0) * 0.28;
        float value = 0.075 + grain * 0.07 + sheen * 0.3;

        float tilt = (uv.x * 7.0 + uv.y * 5.0) + (light.x * 11.0 + light.y * 9.0) + time * 0.55;
        // Fine dust nearly everywhere, and only where the light falls does it catch: glitter is
        // uneven, and a few larger flakes flash. Even and bright, it read as snow over the words.
        float reach = 0.1 + sheen * 1.9;
        float dust = flakes(p, 1.7, 1.0, tilt, 0.45, 60.0) * 0.55 + flakes(p + 1.3, 3.1, 41.0, tilt * 1.3 + 2.0, 0.72, 90.0) * 0.85;
        float glitter = dust * reach + flakes(p + 3.7, 6.0, 83.0, tilt * 0.7 + 4.0, 0.93, 140.0) * (0.25 + sheen * 1.4);

        float v = value + glitter;
        // A breath of cold in the highlights, as polished steel has.
        return half4(half3(v * 0.975, v * 0.99, min(v * 1.04, 1.0)), 1.0);
    }
"""

/**
 * Fills this with glittering metal. [light] says where the light stands (0 to 1 across the surface) and
 * [time] gives the flakes their slow twinkle; both are read as it draws. Before Android 13, a brushed gradient.
 */
@Composable
fun Modifier.glitterMetal(light: () -> Pair<Float, Float>, time: () -> Float): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return drawBehind {
            drawRect(Brush.linearGradient(listOf(Color(0xFF2A2C30), Color(0xFF6B6F76), Color(0xFF17181B), Color(0xFF4A4D53))))
        }
    }
    val shader = remember { RuntimeShader(GLITTER_METAL) }
    val brush = remember { ShaderBrush(shader) }
    return drawBehind {
        val (x, y) = light()
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("light", x, y)
        shader.setFloatUniform("time", time())
        drawRect(brush)
    }
}
