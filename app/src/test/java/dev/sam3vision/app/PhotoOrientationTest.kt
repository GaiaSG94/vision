package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceReading
import org.junit.Assert.assertEquals
import org.junit.Test

/** Photo du double tap : alignement sur l'écran et pastilles qui tournent avec elle. */
class PhotoOrientationTest {

    @Test
    fun `une photo portrait dans un ecran portrait ne tourne pas`() {
        assertEquals(0, screenAlignDegrees(1080, 1920, screenPortrait = true))
    }

    @Test
    fun `une photo paysage dans un ecran portrait tourne d'un quart de tour`() {
        assertEquals(90, screenAlignDegrees(1920, 1080, screenPortrait = true))
    }

    @Test
    fun `une photo portrait dans un ecran paysage tourne aussi`() {
        assertEquals(90, screenAlignDegrees(1080, 1920, screenPortrait = false))
    }

    @Test
    fun `une taille inconnue ne tourne rien`() {
        assertEquals(0, screenAlignDegrees(0, 0, screenPortrait = true))
        assertEquals(0, screenAlignDegrees(0, 1920, screenPortrait = false))
    }

    @Test
    fun `une boite tourne avec l'image`() {
        // Image 200×100, boîte en haut à gauche : un quart de tour horaire la met en haut à droite.
        assertEquals(listOf(40f, 10f, 90f, 20f), rotateBoxClockwise(listOf(10f, 10f, 20f, 60f), 200, 100))
    }

    @Test
    fun `la couche tournee echange les dimensions et garde les lectures`() {
        val layer = DistanceLayer(
            width = 200,
            height = 100,
            readings = listOf(DistanceReading(1, "chaise", 2f, 2f, listOf(10f, 10f, 20f, 60f))),
        )
        val turned = layer.rotatedClockwise()
        assertEquals(100, turned.width)
        assertEquals(200, turned.height)
        assertEquals(listOf(40f, 10f, 90f, 20f), turned.readings.single().box)
        assertEquals("chaise", turned.readings.single().label)
    }

    @Test
    fun `une boite illisible est laissee telle quelle`() {
        assertEquals(listOf(1f, 2f), rotateBoxClockwise(listOf(1f, 2f), 200, 100))
    }
}
