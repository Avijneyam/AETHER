package app.aether.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqualiserTest {
    @Test
    fun readsAnAutoEqFile() {
        val text = """
            Preamp: -4.7 dB
            Filter 1: ON LSC Fc 105 Hz Gain -3.0 dB Q 0.70
            Filter 2: ON PK Fc 7273 Hz Gain 3.6 dB Q 2.41
            Filter 3: ON HSC Fc 10000 Hz Gain -5.5 dB Q 0.70
        """.trimIndent()
        val filters = assertNotNull(EqText.parse(text))
        assertEquals(listOf(EqShape.LowShelf, EqShape.Peak, EqShape.HighShelf), filters.map { it.shape })
        assertEquals(EqFilter(EqShape.Peak, 7273f, 3.6f, 2.41f), filters[1])
    }

    @Test
    fun leavesOutFiltersThatAreOff() {
        val filters = assertNotNull(EqText.parse("Filter 1: OFF PK Fc 100 Hz Gain 3 dB Q 1\nFilter 2: ON PK Fc 200 Hz Gain 2 dB Q 1"))
        assertEquals(listOf(200f), filters.map { it.hz })
    }

    @Test
    fun takesCommasAndKilohertz() {
        val filters = assertNotNull(EqText.parse("Filter: ON PK Fc 1,5 kHz Gain -2,5 dB Q 1,41"))
        assertEquals(EqFilter(EqShape.Peak, 1500f, -2.5f, 1.41f), filters.single())
    }

    @Test
    fun aFileThatIsNotAProfileIsNotOne() {
        assertNull(EqText.parse("hello\nthis is a shopping list"))
        assertNull(EqText.parse("Filter 1: ON XX Fc 100 Hz Gain 3 dB Q 1"))
        assertNull(EqText.parse("GraphicEQ: nonsense"))
        assertNull(EqText.parse(""))
    }

    @Test
    fun keepsAbsurdNumbersWithinReason() {
        val f = assertNotNull(EqText.parse("Filter 1: ON PK Fc 999999 Hz Gain 500 dB Q 9000")).single()
        assertEquals(24_000f, f.hz)
        assertEquals(24f, f.gainDb)
        assertEquals(30f, f.q)
    }

    @Test
    fun aPeakReachesItsGainAtItsCentre() {
        val c = EqMath.coefficients(EqFilter(EqShape.Peak, 1_000f, 6f, 1.41f), 48_000.0)
        assertEquals(6.0, EqMath.responseDb(c, 1_000.0, 48_000.0), 0.01)
        assertEquals(0.0, EqMath.responseDb(c, 20.0, 48_000.0), 0.05)
    }

    @Test
    fun shelvesReachTheirGainAtTheirEnd() {
        val low = EqMath.coefficients(EqFilter(EqShape.LowShelf, 1_000f, 6f, 0.707f), 48_000.0)
        assertEquals(6.0, EqMath.responseDb(low, 20.0, 48_000.0), 0.05)
        assertEquals(0.0, EqMath.responseDb(low, 18_000.0, 48_000.0), 0.1)
        val high = EqMath.coefficients(EqFilter(EqShape.HighShelf, 1_000f, -6f, 0.707f), 48_000.0)
        assertEquals(-6.0, EqMath.responseDb(high, 18_000.0, 48_000.0), 0.1)
        assertEquals(0.0, EqMath.responseDb(high, 20.0, 48_000.0), 0.05)
    }

    @Test
    fun aGraphicCurveIsFollowed() {
        val filters = assertNotNull(EqText.parse("GraphicEQ: 20 6; 200 6; 2000 0; 20000 -4"))
        val c = filters.map { EqMath.coefficients(it, 48_000.0) }
        fun db(hz: Double) = c.sumOf { EqMath.responseDb(it, hz, 48_000.0) }
        // Measured from 1 kHz, where the curve stands at 1.8 dB.
        assertEquals(4.2, db(100.0), 1.0)
        assertEquals(0.0, db(1_000.0), 1.0)
        assertEquals(-4.6, db(10_000.0), 1.0)
    }

    @Test
    fun flatChangesNothing() {
        assertTrue(!EqTunings.Flat.shaping)
        assertTrue(EqTunings.All.drop(1).all { it.shaping })
        assertEquals(0.0, EqMath.peakDb(emptyList(), 48_000.0), 0.0)
    }
}
