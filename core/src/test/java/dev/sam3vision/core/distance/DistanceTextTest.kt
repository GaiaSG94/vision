package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mise en forme des distances : lisible, honnête sur la précision, en français. */
class DistanceTextTest {

    private fun reading(label: String, meters: Float) =
        DistanceReading(1, label, meters, meters, listOf(0f, 0f, 10f, 10f))

    @Test
    fun `metres avec une decimale jusqu'a dix metres`() {
        assertEquals("3,3 m", DistanceText.format(3.288f))
        assertEquals("≈ 3,3 m", DistanceText.approximate(3.288f))
        assertEquals("1,0 m", DistanceText.format(1f))
    }

    @Test
    fun `centimetres en deca d'un metre`() {
        assertEquals("85 cm", DistanceText.format(0.85f))
        assertEquals("30 cm", DistanceText.format(0.3f))
    }

    @Test
    fun `metres entiers au-dela de dix metres`() {
        assertEquals("12 m", DistanceText.format(12.4f))
        assertEquals("23 m", DistanceText.format(23.4f))
    }

    @Test
    fun `valeur absurde devient un point d'interrogation`() {
        assertEquals("?", DistanceText.format(0f))
        assertEquals("?", DistanceText.format(-2f))
        assertEquals("?", DistanceText.format(Float.NaN))
    }

    @Test
    fun `objet le plus proche et ligne de barre d'etat`() {
        val readings = listOf(reading("personne", 3.2f), reading("chaise", 1.4f), reading("table", 2.0f))
        assertEquals("chaise", DistanceText.nearest(readings)!!.label)
        assertEquals("plus proche 1,4 m", DistanceText.nearestLabel(readings))
        assertNull(DistanceText.nearest(emptyList()))
        assertEquals("", DistanceText.nearestLabel(emptyList()))
    }

    @Test
    fun `une distance par etiquette, la plus courte`() {
        val readings = listOf(reading("personne", 3.2f), reading("chaise", 1.4f), reading("personne", 2.1f))
        val perLabel = DistanceText.perLabel(readings)
        assertEquals(listOf("personne" to 2.1f, "chaise" to 1.4f), perLabel)
    }
}