package app.aether.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqShape { Peak, LowShelf, HighShelf, LowPass, HighPass }

data class EqFilter(val shape: EqShape, val hz: Float, val gainDb: Float = 0f, val q: Float = 0.707f)

enum class EqKind { Tuning, Headphones, Imported }

/** A way of shaping the sound: [filters], in order. [group] is a headphone's maker. */
data class EqProfile(
    val id: String,
    val name: String,
    val kind: EqKind,
    val filters: List<EqFilter>,
    val group: String? = null,
    val detail: String? = null,
) {
    /** Whether it changes anything at all. */
    val shaping: Boolean
        get() = filters.any { it.shape == EqShape.LowPass || it.shape == EqShape.HighPass || abs(it.gainDb) >= 0.05f }
}

/** The filters' sums, the same ones for the sound and for the drawing of it. */
object EqMath {
    private const val DrawnAt = 48_000.0
    private const val Lowest = 20.0
    private const val Highest = 20_000.0

    /** RBJ cookbook biquads, normalised: b0, b1, b2, a1, a2. */
    fun coefficients(f: EqFilter, fs: Double): DoubleArray {
        val w = 2 * PI * f.hz / fs
        val c = cos(w)
        val alpha = sin(w) / (2 * f.q)
        val a = 10.0.pow(f.gainDb / 40.0)
        val shelf = 2 * sqrt(a) * alpha
        val raw = when (f.shape) {
            EqShape.Peak -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            EqShape.LowShelf -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * c + shelf),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - shelf),
                (a + 1) + (a - 1) * c + shelf,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - shelf,
            )
            EqShape.HighShelf -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * c + shelf),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - shelf),
                (a + 1) - (a - 1) * c + shelf,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - shelf,
            )
            EqShape.LowPass -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            EqShape.HighPass -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
        val a0 = raw[3]
        return doubleArrayOf(raw[0] / a0, raw[1] / a0, raw[2] / a0, raw[4] / a0, raw[5] / a0)
    }

    /** One biquad's gain at [hz], in dB. */
    fun responseDb(c: DoubleArray, hz: Double, fs: Double): Double {
        val w = 2 * PI * hz / fs
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2 * w)
        val s2w = sin(2 * w)
        val numRe = c[0] + c[1] * cw + c[2] * c2w
        val numIm = c[1] * sw + c[2] * s2w
        val denRe = 1 + c[3] * cw + c[4] * c2w
        val denIm = c[3] * sw + c[4] * s2w
        return 10 * log10(((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)).coerceAtLeast(1e-12))
    }

    private fun usable(filters: List<EqFilter>, fs: Double) = filters.filter { it.hz < fs * 0.45 }

    /** The cascade's highest gain, in dB, over what can be heard: what has to come off first, so a boost can't clip. */
    fun peakDb(filters: List<EqFilter>, fs: Double): Double {
        val here = usable(filters, fs)
        if (here.isEmpty()) return 0.0
        val c = here.map { coefficients(it, fs) }
        val top = minOf(Highest, fs * 0.49)
        val points = List(128) { Lowest * (top / Lowest).pow(it / 127.0) } + here.map { it.hz.toDouble() }
        return points.maxOf { f -> c.sumOf { responseDb(it, f, fs) } }
    }

    /** The curve in dB at [points] places, evenly along a logarithmic 20 Hz to 20 kHz. */
    fun curve(filters: List<EqFilter>, points: Int): FloatArray {
        val c = usable(filters, DrawnAt).map { coefficients(it, DrawnAt) }
        return FloatArray(points) { i ->
            val f = Lowest * (Highest / Lowest).pow(i / (points - 1.0))
            c.sumOf { responseDb(it, f, DrawnAt) }.toFloat()
        }
    }

    /** Where [hz] is along [curve]'s width, 0 to 1. */
    fun across(hz: Float): Float = (ln(hz / Lowest) / ln(Highest / Lowest)).toFloat()
}

/** The ones that come with the app. Plain curves, each one idea. */
object EqTunings {
    private fun low(hz: Float, db: Float) = EqFilter(EqShape.LowShelf, hz, db, 0.7f)
    private fun high(hz: Float, db: Float) = EqFilter(EqShape.HighShelf, hz, db, 0.7f)
    private fun peak(hz: Float, db: Float, q: Float) = EqFilter(EqShape.Peak, hz, db, q)
    private fun tuning(key: String, name: String, detail: String, vararg filters: EqFilter) =
        EqProfile("tuning:$key", name, EqKind.Tuning, filters.toList(), detail = detail)

    val Flat = tuning("flat", "Flat", "The music as it was mastered")
    val All = listOf(
        Flat,
        tuning("bass", "Bass", "Weight underneath, nothing else moved", low(120f, 6f)),
        tuning("warm", "Warm", "A fuller body and a softer top", low(180f, 2.5f), high(7_000f, -3f)),
        tuning("vocal", "Vocal", "Voices brought forward", low(90f, -2f), peak(350f, -1.5f, 1f), peak(2_800f, 3.5f, 0.9f), peak(8_000f, 1f, 1f)),
        tuning("bright", "Bright", "More air and detail", peak(250f, -1f, 1f), high(5_500f, 4.5f)),
        tuning("loud", "Quiet room", "Bass and treble lifted, for listening low", low(90f, 5f), peak(1_000f, -2f, 0.7f), high(9_000f, 4f)),
        tuning("soft", "Late night", "Easier on the ears", low(80f, -2.5f), peak(3_200f, -2.5f, 1f), high(9_000f, -2f)),
    )
}

/**
 * Equaliser profiles as other software writes them: parametric ("Filter 1: ON PK Fc 105 Hz Gain -3 dB
 * Q 0.7", from AutoEq, squig.link, Equalizer APO, Peace) and graphic ("GraphicEQ: 20 -1; 22 0.4", from
 * AutoEq and Wavelet). A file is whatever someone gave the listener, so nothing here throws, and every
 * number is kept within what a filter can sensibly do.
 */
object EqText {
    private const val MostFilters = 32
    private const val LongestText = 512 * 1024
    private const val MostPoints = 4_096

    private val Heading = Regex("""(?i)^filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)""")
    private val Centre = Regex("""(?i)\bfc\s+([0-9]+(?:[.,][0-9]+)?)\s*(k?)hz""")
    private val Gain = Regex("""(?i)\bgain\s+([-+]?[0-9]+(?:[.,][0-9]+)?)\s*db""")
    private val Width = Regex("""(?i)\bq\s+([0-9]+(?:[.,][0-9]+)?)""")

    /** The filters [text] describes, or null if it isn't a profile. */
    fun parse(text: String): List<EqFilter>? = runCatching { read(text) }.getOrNull()

    private fun read(text: String): List<EqFilter>? {
        if (text.length > LongestText) return null
        val filters = ArrayList<EqFilter>()
        var graphic: List<Pair<Double, Double>> = emptyList()
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("GraphicEQ", ignoreCase = true) -> graphic = points(line.substringAfter(':', ""))
                line.startsWith("Filter", ignoreCase = true) && filters.size < MostFilters -> filterOf(line)?.let { filters += it }
            }
        }
        return when {
            filters.isNotEmpty() -> filters
            graphic.size >= 2 -> fitted(graphic)
            else -> null
        }
    }

    private fun number(s: String) = s.replace(',', '.').toFloatOrNull()

    private fun filterOf(line: String): EqFilter? {
        val head = Heading.find(line) ?: return null
        if (head.groupValues[1].equals("off", ignoreCase = true)) return null
        val shape = when (head.groupValues[2].uppercase()) {
            "PK", "PEQ", "PEAK", "PEAKING", "MODAL" -> EqShape.Peak
            "LSC", "LS", "LSQ", "LOWSHELF" -> EqShape.LowShelf
            "HSC", "HS", "HSQ", "HIGHSHELF" -> EqShape.HighShelf
            "LP", "LPQ", "LOWPASS" -> EqShape.LowPass
            "HP", "HPQ", "HIGHPASS" -> EqShape.HighPass
            else -> return null
        }
        val fc = Centre.find(line) ?: return null
        val hz = (number(fc.groupValues[1]) ?: return null) * (if (fc.groupValues[2].isEmpty()) 1f else 1_000f)
        val gain = Gain.find(line)?.let { number(it.groupValues[1]) } ?: 0f
        val q = Width.find(line)?.let { number(it.groupValues[1]) } ?: if (shape == EqShape.Peak) 1.41f else 0.707f
        return EqFilter(shape, hz.coerceIn(10f, 24_000f), gain.coerceIn(-24f, 24f), q.coerceIn(0.05f, 30f))
    }

    private fun points(list: String): List<Pair<Double, Double>> = list.split(';').take(MostPoints).mapNotNull { pair ->
        val parts = pair.trim().split(Regex("""\s+"""))
        val hz = parts.getOrNull(0)?.let(::number)?.toDouble() ?: return@mapNotNull null
        val db = parts.getOrNull(1)?.let(::number)?.toDouble() ?: return@mapNotNull null
        if (hz > 0 && hz.isFinite() && db.isFinite()) hz to db.coerceIn(-40.0, 40.0) else null
    }.sortedBy { it.first }.distinctBy { it.first }

    /** A graphic curve as filters: twenty half-octave peaks, nudged until together they sit on it. Measured from 1 kHz. */
    private fun fitted(curve: List<Pair<Double, Double>>): List<EqFilter> {
        fun at(hz: Double): Double {
            val after = curve.indexOfFirst { it.first >= hz }
            if (after <= 0) return if (after == 0) curve.first().second else curve.last().second
            val (f0, g0) = curve[after - 1]
            val (f1, g1) = curve[after]
            return g0 + (g1 - g0) * ln(hz / f0) / ln(f1 / f0)
        }
        val level = at(1_000.0)
        val rate = 48_000.0
        val centres = DoubleArray(20) { 25.0 * (16_000.0 / 25.0).pow(it / 19.0) }
        val wanted = DoubleArray(20) { at(centres[it]) - level }
        val gains = wanted.copyOf()
        fun filters() = centres.indices.map { EqFilter(EqShape.Peak, centres[it].toFloat(), gains[it].toFloat(), 2f) }
        repeat(24) {
            val c = filters().map { EqMath.coefficients(it, rate) }
            for (i in centres.indices) {
                val now = c.sumOf { EqMath.responseDb(it, centres[i], rate) }
                gains[i] = (gains[i] + (wanted[i] - now) * 0.6).coerceIn(-24.0, 24.0)
            }
        }
        return filters()
    }
}
