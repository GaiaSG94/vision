package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Déclencheur : stabilité, non-répétition, délai minimal, retour des objets. */
class PositionAnnouncerTest {

    private val gauche = PlacedLabel("personne", 0.1f, 0.5f)
    private val droite = PlacedLabel("chien", 0.9f, 0.5f)

    @Test
    fun `une frame isolee ne declenche rien`() {
        val announcer = PositionAnnouncer()

        assertNull(announcer.onFrame(0L, listOf(gauche)))
    }

    @Test
    fun `scene stable annoncee une fois puis silence`() {
        val announcer = PositionAnnouncer()

        assertNull(announcer.onFrame(0L, listOf(gauche)))
        assertEquals("Personne à gauche.", announcer.onFrame(500L, listOf(gauche)))
        assertNull(announcer.onFrame(10_000L, listOf(gauche)))
    }

    @Test
    fun `nouvel objet annonce apres le delai`() {
        val announcer = PositionAnnouncer(cooldownMs = 4_000L)

        announcer.onFrame(0L, listOf(gauche))
        assertEquals("Personne à gauche.", announcer.onFrame(500L, listOf(gauche)))
        // nouvel objet : la scène change, mais le délai n'est pas écoulé
        assertNull(announcer.onFrame(1_000L, listOf(gauche, droite)))
        assertNull(announcer.onFrame(4_000L, listOf(gauche, droite)))
        assertEquals(
            "Personne à gauche, chien à droite.",
            announcer.onFrame(4_600L, listOf(gauche, droite)),
        )
    }

    @Test
    fun `objet qui change de cote est reannonce`() {
        val announcer = PositionAnnouncer(cooldownMs = 1_000L)

        announcer.onFrame(0L, listOf(gauche))
        announcer.onFrame(500L, listOf(gauche))
        announcer.onFrame(1_500L, listOf(PlacedLabel("personne", 0.9f)))
        val phrase = announcer.onFrame(2_000L, listOf(PlacedLabel("personne", 0.9f)))

        assertEquals("Personne à droite.", phrase)
    }

    @Test
    fun `scene vide oublie la memoire de scene`() {
        val announcer = PositionAnnouncer()

        announcer.onFrame(0L, listOf(gauche))
        assertEquals("Personne à gauche.", announcer.onFrame(500L, listOf(gauche)))
        assertNull(announcer.onFrame(9_000L, emptyList()))
        announcer.onFrame(9_500L, listOf(gauche))

        assertEquals("Personne à gauche.", announcer.onFrame(10_000L, listOf(gauche)))
    }

    @Test
    fun `reset autorise une nouvelle annonce immediate`() {
        val announcer = PositionAnnouncer()

        announcer.onFrame(0L, listOf(gauche))
        announcer.onFrame(500L, listOf(gauche))
        announcer.reset()
        assertNull(announcer.onFrame(600L, listOf(gauche)))

        assertEquals("Personne à gauche.", announcer.onFrame(700L, listOf(gauche)))
    }

    @Test
    fun `phrase retenue pour l affichage`() {
        val announcer = PositionAnnouncer()

        announcer.onFrame(0L, listOf(gauche))
        announcer.onFrame(500L, listOf(gauche))

        assertEquals("Personne à gauche.", announcer.lastPhrase)
    }
}