package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Placement des étiquettes de distance : même cadrage que l'aperçu, testé sans écran. */
class DistanceLayoutTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(1, label, meters, meters, box)

    @Test
    fun `cadrage crop remplit l'ecran et deborde en largeur`() {
        val transform = cropTransform(imageWidth = 384, imageHeight = 512, boxWidth = 1080f, boxHeight = 2400f)
        assertEquals(4.6875f, transform.scale, 0.001f)
        assertEquals(-360f, transform.offsetX, 0.01f)
        assertEquals(0f, transform.offsetY, 0.01f)
        // coin haut-gauche de l'image, hors cadre à gauche après crop
        assertEquals(-360f, transform.screenX(0f), 0.01f)
        assertEquals(540f, transform.screenX(192f), 0.01f)
    }

    @Test
    fun `etiquette centree au-dessus de la boite`() {
        val layer = DistanceLayer(384, 512, listOf(reading("personne", 3.288f, listOf(100f, 100f, 160f, 300f))))
        val labels = screenLabels(layer, cropTransform(384, 512, 1080f, 2400f))
        assertEquals(1, labels.size)
        assertEquals("≈ 3,3 m", labels.first().text)
        // centre de la boîte (130 px) → 130 × 4,6875 − 360
        assertEquals(249.375f, labels.first().x, 0.5f)
        assertEquals(468.75f, labels.first().y, 0.5f)
        assertEquals(0, labels.first().colorIndex)
    }

    @Test
    fun `etiquette sur un bord reste dans le cadre`() {
        val layer = DistanceLayer(384, 512, listOf(reading("personne", 2f, listOf(0f, 100f, 0f, 300f))))
        assertEquals(0f, screenLabels(layer, cropTransform(384, 512, 1080f, 2400f)).first().x, 0.01f)
    }

    @Test
    fun `boite illisible ignoree et couche vide sans mesure`() {
        val broken = DistanceLayer(384, 512, listOf(reading("personne", 3f, listOf(1f, 2f))))
        assertTrue(screenLabels(broken, cropTransform(384, 512, 1080f, 2400f)).isEmpty())
        assertTrue(DistanceLayer(0, 0, listOf(reading("personne", 3f, listOf(0f, 0f, 1f, 1f)))).empty)
        assertTrue(DistanceLayer(384, 512).empty)
    }

    @Test
    fun `pastille centree reste entiere dans le cadre`() {
        // 200 px de large : au milieu, la pastille se centre ; aux bords, elle est ramenee
        assertEquals(-100f, pillTranslationX(540f, 200f, 1080f), 0.01f)
        assertEquals(0f, pillTranslationX(0f, 200f, 1080f), 0.01f)
        assertEquals(-200f, pillTranslationX(1080f, 200f, 1080f), 0.01f)
    }

    @Test
    fun `pastille ne se cache pas sous le bandeau du haut`() {
        assertEquals(550f, pillTranslationY(50f, 600f), 0.01f)
        assertEquals(0f, pillTranslationY(700f, 600f), 0.01f)
    }

    @Test
    fun `champ de vision saisi est tolere et borne`() {
        assertEquals(72.5f, parseFov("72,5"), 0.01f)
        assertEquals(0f, parseFov(""), 0f)
        assertEquals(0f, parseFov("0"), 0f)
        assertEquals(0f, parseFov("n'importe quoi"), 0f)
        assertEquals(15f, parseFov("2"), 0.01f)
        assertEquals(130f, parseFov("500"), 0.01f)
        assertEquals("67", fovText(67f))
        assertEquals("72,5", fovText(72.5f))
        assertEquals("", fovText(0f))
    }

    @Test
    fun `pastilles du bas portent la distance la plus courte`() {
        val measured = LiveUiState(
            distanceLayer = DistanceLayer(
                384,
                512,
                listOf(
                    reading("personne", 3.2f, listOf(0f, 0f, 10f, 20f)),
                    reading("chaise", 1.4f, listOf(0f, 0f, 10f, 20f)),
                    reading("personne", 2.1f, listOf(0f, 0f, 10f, 20f)),
                ),
            ),
        )
        assertEquals(listOf("personne 2,1 m", "chaise 1,4 m"), objectChips(measured))
    }

    @Test
    fun `sans distance, pastilles des concepts seuls`() {
        val frame = LiveFrame(
            width = 384,
            height = 512,
            elapsedMs = 40,
            objects = listOf(SegObject("personne", 0.9f, listOf(0f, 0f, 10f, 20f), ""), SegObject("chaise", 0.8f, listOf(0f, 0f, 10f, 20f), "")),
        )
        assertEquals(listOf("personne", "chaise"), objectChips(LiveUiState(frame = frame)))
        assertEquals(emptyList<String>(), objectChips(LiveUiState()))
    }

    @Test
    fun `ligne d'etat annonce l'objet le plus proche`() {
        val line = formatLiveStatus(true, 1.5f, 620, "A100", 3, nearestMeters = 1.44f)
        assertTrue(line.contains("1,4 m"))
        val without = formatLiveStatus(true, 1.5f, 620, "A100", 3)
        assertTrue(!without.contains("proche"))
    }

    @Test
    fun `deux pastilles au meme endroit sont decalees vers le bas`() {
        val labels = listOf(
            ScreenLabel("≈ 2,6 m", x = 300f, y = 900f, colorIndex = 0),
            ScreenLabel("≈ 2,5 m", x = 320f, y = 906f, colorIndex = 1),
        )
        val spread = spreadLabels(labels)
        assertEquals(900f, spread[0].y, 0.01f)
        assertEquals(906f + PILL_MIN_Y_GAP, spread[1].y, 0.01f)
    }

    @Test
    fun `des pastilles eloignees ne bougent pas`() {
        val labels = listOf(
            ScreenLabel("≈ 1,2 m", x = 200f, y = 400f, colorIndex = 0),
            ScreenLabel("≈ 3,0 m", x = 900f, y = 410f, colorIndex = 1),
        )
        assertEquals(labels, spreadLabels(labels))
    }

    @Test
    fun `pastille prefixee du concept pour les hotes sans masque`() {
        val lecture = reading("personne", 3.2f, listOf(100f, 100f, 160f, 300f))
        assertEquals("≈ 3,2 m", labelText(lecture, withLabel = false))
        assertEquals("personne ≈ 3,2 m", labelText(lecture, withLabel = true))
        assertEquals("objet ≈ 3,2 m", labelText(lecture.copy(label = "   "), withLabel = true))
    }
}
