package dev.sam3vision.core.guidance

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Boîte SAM + distance → position latérale et largeur transverse, en mètres. */
class GuidanceGeometryTest {

    private val width = 320
    private val height = 240

    /** Focale de 240 px sur une image de 320 px : ±80 px = ±0,67 m à 2 m. */
    private val focal = 240f

    private fun detection(label: String, box: List<Float>) = Detection(label, 0.9f, box, width, height)

    @Test
    fun `centre de l'image veut dire axe optique`() {
        assertEquals(0f, GuidanceGeometry.lateralMeters(160f, width, focal, 2f)!!, 0.001f)
    }

    @Test
    fun `quatre-vingts pixels a droite valent 67 cm a 2 m`() {
        assertEquals(0.667f, GuidanceGeometry.lateralMeters(240f, width, focal, 2f)!!, 0.01f)
    }

    @Test
    fun `largeur transverse suit la distance`() {
        assertEquals(0.5f, GuidanceGeometry.widthMeters(60f, focal, 2f)!!, 0.01f)
        assertEquals(1f, GuidanceGeometry.widthMeters(60f, focal, 4f)!!, 0.01f)
    }

    @Test
    fun `geometrie inutilisable donne null`() {
        assertNull(GuidanceGeometry.lateralMeters(160f, 0, focal, 2f))
        assertNull(GuidanceGeometry.lateralMeters(160f, width, 0f, 2f))
        assertNull(GuidanceGeometry.lateralMeters(160f, width, focal, 0f))
        assertNull(GuidanceGeometry.widthMeters(0f, focal, 2f))
    }

    @Test
    fun `une detection devient un objet guide`() {
        val guided = GuidanceGeometry.of(detection("chaise", listOf(130f, 100f, 190f, 200f)), 2f, focal)!!
        assertEquals("chaise", guided.label)
        assertEquals(2f, guided.meters, 0.001f)
        assertEquals(0f, guided.lateralMeters, 0.001f)
        assertEquals(0.5f, guided.widthMeters, 0.01f)
        assertEquals(-0.25f, guided.leftMeters, 0.01f)
        assertEquals(0.25f, guided.rightMeters, 0.01f)
    }

    @Test
    fun `la scene ne garde que les objets dont la distance est connue`() {
        val known = detection("chaise", listOf(130f, 100f, 190f, 200f))
        val unknown = detection("plante", listOf(10f, 100f, 40f, 200f))
        val scene = GuidanceGeometry.scene(
            detections = listOf(known, unknown),
            metersByBox = mapOf(known.box to 2f),
            focalPx = focal,
        )
        assertEquals(1, scene.size)
        assertEquals("chaise", scene.first().label)
    }
}
