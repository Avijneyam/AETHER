package app.aether.core.designsystem.art

/*
 * AGSL (Android 13+). Every image treatment reads the artwork through `image`, already placed and
 * cropped by its local matrix, so `image.eval(p)` is the artwork's colour at drawing pixel p.
 */
internal object Shaders {

    private const val TONE = """
        uniform shader image;
        uniform float2 size;
        uniform float time;
        uniform float exposure;
        uniform float contrast;
        uniform float grain;
        uniform float develop;

        float luma(half3 c) { return dot(float3(c), float3(0.2126, 0.7152, 0.0722)); }

        float hash(float2 p) {
            float3 q = fract(float3(p.xyx) * 0.1031);
            q += dot(q, q.yzx + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        // Exposure, contrast around mid-grey, and the darkroom's arrival: at develop = 1 the print is
        // blown out to a high-key threshold, and settles into its tones as develop falls to 0.
        float tone(float l) {
            l = clamp(l * exposure * (1.0 + 1.6 * develop), 0.0, 1.0);
            l = clamp((l - 0.5) * contrast + 0.5, 0.0, 1.0);
            float key = smoothstep(0.28, 0.42, l);
            return mix(l, key, develop * 0.9);
        }

        float grainAt(float2 p) {
            return hash(floor(p) + floor(time * 24.0) * 37.0) - 0.5;
        }
    """

    /** The artwork in its own colours, through the same darkroom: used while a cover develops. */
    const val COLOR = TONE + """
        half4 main(float2 p) {
            half4 c = image.eval(p);
            float3 rgb = clamp(float3(c.rgb) * exposure * (1.0 + 1.6 * develop), 0.0, 1.0);
            float key = smoothstep(0.28, 0.42, luma(half3(rgb)));
            rgb = mix(rgb, float3(key), develop * 0.9);
            rgb = clamp(rgb + grainAt(p) * grain * 0.18, 0.0, 1.0);
            return half4(half3(rgb), 1.0) * c.a;
        }
    """

    /** Monochrome with film grain: the default face of artwork in AETHER. */
    const val MONOCHROME = TONE + """
        half4 main(float2 p) {
            half4 c = image.eval(p);
            float l = tone(luma(c.rgb));
            l = clamp(l + grainAt(p) * grain * 0.22, 0.0, 1.0);
            return half4(half3(l), 1.0) * c.a;
        }
    """

    /**
     * Characters as pixels: each cell takes the glyph whose ink matches the cell's brightness. The
     * glyphs sit in one row of [glyphs], darkest first, each `glyphPx` big.
     */
    const val ASCII = TONE + """
        uniform shader glyphs;
        uniform float2 cell;
        uniform float2 glyphPx;
        uniform float glyphCount;
        uniform float floorLevel;

        half4 main(float2 p) {
            float2 c = floor(p / cell);
            float2 centre = (c + 0.5) * cell;
            half4 src = image.eval(centre);
            float l = tone(luma(src.rgb));
            float gi = floor(l * (glyphCount - 1.0) + 0.5);
            float2 f = clamp((p - c * cell) / cell, 0.02, 0.98);
            float ink = float(glyphs.eval(float2((gi + f.x) * glyphPx.x, f.y * glyphPx.y)).a);
            // brighter cells draw brighter glyphs; the faintest still leave a trace on the black
            float v = ink * (0.45 + 0.55 * l) + floorLevel * l;
            v = clamp(v + grainAt(p) * grain * 0.08, 0.0, 1.0);
            return half4(half3(v), 1.0) * src.a;
        }
    """

    /** A dot screen at an angle, the dots' size the picture's brightness. */
    const val HALFTONE = TONE + """
        uniform float dotPx;
        uniform float angle;

        half4 main(float2 p) {
            float s = sin(angle);
            float k = cos(angle);
            float2 r = float2(k * p.x - s * p.y, s * p.x + k * p.y);
            float2 cr = (floor(r / dotPx) + 0.5) * dotPx;
            float2 centre = float2(k * cr.x + s * cr.y, -s * cr.x + k * cr.y);
            half4 src = image.eval(centre);
            float l = tone(luma(src.rgb));
            float radius = sqrt(l) * dotPx * 0.66;
            float d = length(r - cr);
            float ink = smoothstep(radius + 0.75, radius - 0.75, d);
            ink = clamp(ink + grainAt(p) * grain * 0.1, 0.0, 1.0);
            return half4(half3(ink), 1.0) * src.a;
        }
    """

    /** One bit per dot, ordered by an 8×8 Bayer matrix (built arithmetically: no arrays). */
    const val DITHER = TONE + """
        uniform float dotPx;

        float bayer2(float2 a) { a = floor(a); return fract(dot(a, float2(0.5, a.y * 0.75))); }
        float bayer4(float2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }
        float bayer8(float2 a) { return bayer4(0.5 * a) * 0.25 + bayer2(a); }

        half4 main(float2 p) {
            float2 q = floor(p / dotPx);
            half4 src = image.eval((q + 0.5) * dotPx);
            float l = tone(luma(src.rgb));
            float on = step(bayer8(q) + 0.001, l);
            return half4(half3(on), 1.0) * src.a;
        }
    """

    /**
     * Glass at the screen's edges (the carousel recording): what enters or leaves the side of a
     * horizontal list is seen through the curve of thick glass — pulled inward and magnified, made
     * taller, smeared, its colours split, and lit faintly where the glass turns away.
     */
    const val EDGE_REFRACTION = """
        uniform shader content;
        uniform float2 size;
        uniform float edge;
        uniform float pull;
        uniform float lift;
        uniform float chroma;
        uniform float smear;
        uniform float leftOn;
        uniform float rightOn;

        half4 main(float2 p) {
            float w = size.x;
            float tl = (1.0 - clamp(p.x / edge, 0.0, 1.0)) * leftOn;
            float tr = (1.0 - clamp((w - p.x) / edge, 0.0, 1.0)) * rightOn;
            float t = max(tl, tr);
            if (t <= 0.0) { return content.eval(p); }
            float dir = tl > tr ? 1.0 : -1.0;
            float k = t * t * (3.0 - 2.0 * t);
            float inner = dir > 0.0 ? edge : w - edge;
            float2 q = float2(mix(p.x, inner, k * pull), 0.0);
            float cy = size.y * 0.5;
            q.y = cy + (p.y - cy) / (1.0 + lift * k);
            float ca = chroma * k * dir;
            float bl = smear * k;
            half4 acc = half4(0.0);
            for (int i = -2; i <= 2; i++) {
                acc += content.eval(q + float2(float(i) * bl * 0.5, 0.0));
            }
            acc *= 0.2;
            // Only a breath of colour at the glass's edge: a fifth of what a true split would give.
            // In a black and white room a full one read as a glitch.
            acc.r = mix(acc.r, content.eval(q + float2(ca, 0.0)).r, 0.2);
            acc.b = mix(acc.b, content.eval(q - float2(ca, 0.0)).b, 0.2);
            acc.rgb += half3(0.05 * k * k) * acc.a;
            return acc;
        }
    """
}
