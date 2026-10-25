package dev.sam3vision.core.live

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mise en mots des positions : secteurs, ordre de lecture, doublons, troncature. */
class PositionTextTest {

    private fun placed(label: String, x: Float, y: Float = 0.5f) = PlacedLabel(label, x, y)

    private fun detection(label: String, box: List<Float>, width: Int = 100, height: Int = 100) =
        Detection(label, 0.9f, box, width, height)

    @Test
    fun `secteurs horizontaux`() {
        assertEquals("à gauche", PositionText.horizontal(0.1f))
        assertEquals("au centre", PositionText.horizontal(0.5f))
        assertEquals("à droite", PositionText.horizontal(0.9f))
    }

    @Test
    fun `secteurs verticaux et position complete`() {
        assertEquals("en haut", PositionText.vertical(0.1f))
        assertNull(PositionText.vertical(0.5f))
        assertEquals("en bas", PositionText.vertical(0.9f))
        assertEquals("en haut à gauche", PositionText.positionLabel(0.1f, 0.1f))
        assertEquals("au centre", PositionText.positionLabel(0.5f, 0.5f))
        assertEquals("en bas à droite", PositionText.positionLabel(0.9f, 0.9f))
        assertEquals("à droite", PositionText.positionLabel(0.9f, 0.5f))
    }

    @Test
    fun `boite en pixels convertie en position normalisee`() {
        val obj = PositionText.fromDetection(detection("chaise", listOf(0f, 0f, 20f, 20f)))

        assertEquals(0.1f, obj!!.centerX, 0.001f)
        assertEquals(0.1f, obj.centerY, 0.001f)
        assertEquals("en haut à gauche", obj.position)
    }

    @Test
    fun `detection inutilisable ou label vide est ignoree`() {
        assertNull(PositionText.fromDetection(detection("chaise", listOf(0f, 0f, 20f, 20f), width = 0)))
        assertNull(PositionText.fromDetection(detection(" ", listOf(0f, 0f, 20f, 20f))))
        assertNull(PositionText.fromSeenObject(SeenObject(1, "chien", 0.5f, listOf(0f, 0f))))
        assertNull(PositionText.phrase(emptyList()))
    }

    @Test
    fun `phrase ordonnee de gauche a droite`() {
        val phrase = PositionText.phrase(
            listOf(placed("chien", 0.9f), placed("personne", 0.1f), placed("livre", 0.5f)),
        )

        assertEquals("Personne à gauche, livre au centre, chien à droite.", phrase)
    }

    @Test
    fun `meme label meme secteur cite une seule fois`() {
        val phrase = PositionText.phrase(listOf(placed("personne", 0.1f), placed("personne", 0.05f)))

        assertEquals("Personne à gauche.", phrase)
    }

    @Test
    fun `au dela du plafond le reste est compte`() {
        val labels = listOf(
            placed("personne", 0.05f),
            placed("chien", 0.25f),
            placed("livre", 0.5f),
            placed("table", 0.75f),
            placed("sac", 0.95f),
        )

        val phrase = PositionText.phrase(labels, maxObjects = 3)

        assertTrue(phrase!!.startsWith("Personne à gauche, chien à gauche, livre au centre"))
        assertTrue(phrase.endsWith(", et 2 autres objets."))
    }

    @Test
    fun `cle de scene insensible a l ordre et aux doublons`() {
        val a = PositionText.key(listOf(placed("chien", 0.9f), placed("personne", 0.1f)))
        val b = PositionText.key(listOf(placed("personne", 0.05f), placed("chien", 0.95f), placed("chien", 0.9f)))

        assertEquals(a, b)
        assertEquals("", PositionText.key(emptyList()))
    }
}