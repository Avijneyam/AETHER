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
 * The metal of a charge card, anodised black or brushed silver. A fine grain along its length; arcs
 * turned into it from a point beyond its edge, as fine as a record's grooves and too faint to read
 * until the light crosses them; and one long soft band of light. Nothing sparkles: what makes it
 * expensive is how little it does.
 */
private const val CARD_METAL = """
    uniform float2 size;
    uniform float2 light;
    uniform float silver;

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

    half4 main(float2 p) {
        float2 uv = p / size;
        float grain = noise(float2(p.x * 0.006, p.y * 1.4)) * 0.6 + noise(float2(p.x * 0.02, p.y * 3.1)) * 0.4;

        float pitch = size.x / 170.0;
        float turned = 0.5 + 0.5 * sin(length(p - float2(-0.35 * size.x, 0.18 * size.y)) / pitch * 6.28318);

        float d = dot(uv - light, float2(0.42, 0.91));
        float sheen = exp(-d * d * 7.0) + exp(-(d - 0.72) * (d - 0.72) * 14.0) * 0.35;

        float blackTone = 0.046 + grain * 0.016 + turned * (0.006 + sheen * 0.036) + sheen * 0.075;
        float silverTone = 0.60 + grain * 0.07 + turned * 0.024 + sheen * 0.27;
        float v = mix(blackTone, silverTone, silver);
        return half4(half3(v * 0.985, v * 0.992, min(v * 1.012, 1.0)), 1.0);
    }
"""

/**
 * Fills this with card metal, [silver] or black. [light] says where the light stands (0 to 1 across
 * the surface), read as it draws. Before Android 13, a plain gradient of the same tones.
 */
@Composable
fun Modifier.cardMetal(silver: Boolean, light: () -> Pair<Float, Float>): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return drawBehind {
            val tones = if (silver) listOf(Color(0xFFD9DBDF), Color(0xFF9EA1A7), Color(0xFFC9CCD1)) else listOf(Color(0xFF17181A), Color(0xFF0A0A0B), Color(0xFF131416))
            drawRect(Brush.linearGradient(tones))
        }
    }
    val shader = remember { RuntimeShader(CARD_METAL) }
    val brush = remember { ShaderBrush(shader) }
    return drawBehind {
        val (x, y) = light()
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("light", x, y)
        shader.setFloatUniform("silver", if (silver) 1f else 0f)
        drawRect(brush)
    }
}
