package app.aether.core.designsystem.art

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Mesh
import android.graphics.MeshSpecification
import android.graphics.MeshSpecification.Attribute
import android.graphics.MeshSpecification.Varying
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * One halftone print turning into another, dot by dot. Every dot of the first is a particle with
 * somewhere to be in the second: it lifts, drifts along a curve and settles, shrinking in the air and
 * swelling to the size the new picture wants of it. It all happens in the vertex shader, from one
 * number, so that a frame costs a uniform and a draw call however many dots there are.
 */
private const val MeshVertex = """
    uniform float t;
    uniform float unit;

    Varyings main(const Attributes a) {
        Varyings v;
        float delay = float(a.extra.z);
        float seed = float(a.extra.w);
        float u = clamp((t / 0.94 - delay * 0.46) / 0.54, 0.0, 1.0);
        float e = u * u * (3.0 - 2.0 * u);
        float lift = sin(3.14159 * e);
        float2 way = a.goal - a.origin;
        float2 c = mix(a.origin, a.goal, e) + float2(-way.y, way.x) * lift * (seed - 0.5) * 0.4;
        c += float2(
            sin(c.y / (62.0 * unit) + seed * 6.283 + t * 5.0),
            cos(c.x / (54.0 * unit) + seed * 4.0 + t * 4.0)
        ) * lift * 11.0 * unit;
        float r = mix(a.radii.x, a.radii.y, e) * (1.0 - 0.38 * lift);
        float reach = r + 1.25;
        float2 corner = float2(a.extra.xy) * 2.0 - 1.0;
        v.position = c + corner * reach;
        v.local = corner * reach;
        v.radius = r;
        return v;
    }
"""

private const val MeshFragment = """
    float2 main(const Varyings v, out float4 color) {
        float ink = 1.0 - smoothstep(v.radius - 0.75, v.radius + 0.75, length(v.local));
        color = float4(ink);
        return v.position;
    }
"""

/** Brightness is matched in this many equal shares of the dots; within a share, nearby dots go to nearby places. */
private const val Shares = 24

/** Dots both ends leave smaller than this are never seen, and are left out. */
private const val SmallestPx = 0.35f

private const val VertexBytes = 28
private const val CurveSide = 1024

/** A dot morph made and ready to draw. Android 14 and later: it needs a programmable mesh. */
@RequiresApi(34)
class DotMorph private constructor(private val mesh: Mesh) {
    private val paint = Paint().apply { color = android.graphics.Color.WHITE }

    /** [t]: 0 is the first print, 0.94 and on the second. */
    fun draw(canvas: android.graphics.Canvas, t: Float) {
        mesh.setFloatUniform("t", t)
        canvas.drawMesh(mesh, BlendMode.MODULATE, paint)
    }

    /** The brightness a print's dot screen sees: the same crop and the same tone as [ArtTreatment.Halftone] draws. */
    private class Print(bitmap: Bitmap, width: Int, height: Int, private val contrast: Float) {
        private val bw = bitmap.width
        private val bh = bitmap.height
        private val pixels = IntArray(bw * bh).also { bitmap.getPixels(it, 0, bw, 0, 0, bw, bh) }
        private val scale = max(width.toFloat() / bw, height.toFloat() / bh)
        private val left = (width - bw * scale) / 2f
        private val top = (height - bh * scale) / 2f

        fun tone(x: Float, y: Float): Float {
            val c = pixels[((y - top) / scale).toInt().coerceIn(0, bh - 1) * bw + ((x - left) / scale).toInt().coerceIn(0, bw - 1)]
            val l = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
            return ((l - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
        }
    }

    companion object {
        private val Spec by lazy {
            MeshSpecification.make(
                arrayOf(
                    Attribute(MeshSpecification.TYPE_FLOAT2, 0, "origin"),
                    Attribute(MeshSpecification.TYPE_FLOAT2, 8, "goal"),
                    Attribute(MeshSpecification.TYPE_FLOAT2, 16, "radii"),
                    Attribute(MeshSpecification.TYPE_UBYTE4, 24, "extra"),
                ),
                VertexBytes,
                arrayOf(Varying(MeshSpecification.TYPE_FLOAT2, "local"), Varying(MeshSpecification.TYPE_FLOAT, "radius")),
                MeshVertex,
                MeshFragment,
            )
        }

        /** Position along a Hilbert curve: cells near on the curve are near on the page. */
        private fun along(x0: Int, y0: Int): Int {
            var x = x0
            var y = y0
            var d = 0
            var s = CurveSide / 2
            while (s > 0) {
                val rx = if (x and s != 0) 1 else 0
                val ry = if (y and s != 0) 1 else 0
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0) {
                    if (rx == 1) {
                        x = CurveSide - 1 - x
                        y = CurveSide - 1 - y
                    }
                    val t = x
                    x = y
                    y = t
                }
                s /= 2
            }
            return d
        }

        /** The cells from darkest to brightest, in equal shares, each share along the curve. */
        private fun ranked(tones: FloatArray, curve: IntArray): IntArray {
            val n = tones.size
            val byTone = LongArray(n) { ((tones[it] * 65535f).toLong() shl 32) or it.toLong() }
            byTone.sort()
            val out = IntArray(n)
            var start = 0
            for (share in 1..Shares) {
                val end = share * n / Shares
                val part = LongArray(end - start) { k ->
                    val cell = (byTone[start + k] and 0xFFFFFFFFL).toInt()
                    (curve[cell].toLong() shl 32) or cell.toLong()
                }
                part.sort()
                part.forEachIndexed { k, v -> out[start + k] = (v and 0xFFFFFFFFL).toInt() }
                start = end
            }
            return out
        }

        /**
         * Pairs the dots of [from] with the dots of [to], as they stand on a screen [width] by [height] at
         * [dotPx] apart and [angle] radians, and builds what draws them. Off the main thread.
         */
        fun build(from: Bitmap, to: Bitmap, width: Int, height: Int, dotPx: Float, angle: Float, contrast: Float, density: Float): DotMorph {
            val a = Print(from, width, height, contrast)
            val b = Print(to, width, height, contrast)
            val s = sin(angle)
            val k = cos(angle)
            // The lattice's reach, from the screen's corners in its own rotated axes.
            val xs = floatArrayOf(0f, width * k, -height * s, width * k - height * s)
            val ys = floatArrayOf(0f, width * s, height * k, width * s + height * k)
            val iLow = floor(xs.min() / dotPx).toInt()
            val iHigh = ceil(xs.max() / dotPx).toInt()
            val jLow = floor(ys.min() / dotPx).toInt()
            val jHigh = ceil(ys.max() / dotPx).toInt()
            val room = (iHigh - iLow + 1) * (jHigh - jLow + 1)
            val cx = FloatArray(room)
            val cy = FloatArray(room)
            val curve = IntArray(room)
            val seeds = IntArray(room)
            var n = 0
            for (i in iLow..iHigh) for (j in jLow..jHigh) {
                val rx = (i + 0.5f) * dotPx
                val ry = (j + 0.5f) * dotPx
                val x = k * rx + s * ry
                val y = -s * rx + k * ry
                if (x < -dotPx || x > width + dotPx || y < -dotPx || y > height + dotPx) continue
                cx[n] = x
                cy[n] = y
                curve[n] = along(min(((x + dotPx) / (width + dotPx * 2) * (CurveSide - 1)).toInt(), CurveSide - 1), min(((y + dotPx) / (height + dotPx * 2) * (CurveSide - 1)).toInt(), CurveSide - 1))
                val h = (i * 73856093) xor (j * 19349663)
                seeds[n] = (h xor (h ushr 13)) and 255
                n++
            }
            val tonesA = FloatArray(n) { a.tone(cx[it], cy[it]) }
            val tonesB = FloatArray(n) { b.tone(cx[it], cy[it]) }
            val orderA = ranked(tonesA, curve.copyOf(n))
            val orderB = ranked(tonesB, curve.copyOf(n))
            val reach = dotPx * 0.66f
            fun radius(tone: Float) = sqrt(tone) * reach

            val seen = (0 until n).count { max(radius(tonesA[orderA[it]]), radius(tonesB[orderB[it]])) >= SmallestPx }
            val buffer = ByteBuffer.allocateDirect(seen * 6 * VertexBytes).order(ByteOrder.nativeOrder())
            val cornersX = byteArrayOf(0, -1, 0, -1, -1, 0)
            val cornersY = byteArrayOf(0, 0, -1, 0, -1, -1)
            for (q in 0 until n) {
                val c = orderA[q]
                val g = orderB[q]
                val ra = radius(tonesA[c])
                val rb = radius(tonesB[g])
                if (max(ra, rb) < SmallestPx) continue
                // From the foot of the picture up, a little unevenly: the face comes last.
                val delay = ((0.72f * (1f - cy[c] / height) + 0.28f * seeds[c] / 255f).coerceIn(0f, 1f) * 255f).toInt().toByte()
                for (v in 0 until 6) {
                    buffer.putFloat(cx[c]).putFloat(cy[c])
                    buffer.putFloat(cx[g]).putFloat(cy[g])
                    buffer.putFloat(ra).putFloat(rb)
                    buffer.put(cornersX[v]).put(cornersY[v]).put(delay).put(seeds[c].toByte())
                }
            }
            buffer.rewind()
            val mesh = Mesh(Spec, Mesh.TRIANGLES, buffer, seen * 6, RectF(-width.toFloat(), -height.toFloat(), width * 2f, height * 2f))
            mesh.setFloatUniform("unit", density)
            return DotMorph(mesh)
        }
    }
}

/** Draws [morph] at the [progress] read each frame: only this layer redraws while it runs. */
@RequiresApi(34)
@Composable
fun DotMorphLayer(morph: DotMorph, progress: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) { morph.draw(drawContext.canvas.nativeCanvas, progress()) }
}
