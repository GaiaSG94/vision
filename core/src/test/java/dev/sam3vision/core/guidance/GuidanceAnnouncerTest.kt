package dev.sam3vision.core.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stabilité, nouveauté et délais : la voix ne bavarde pas et ne rate pas un arrêt. */
class GuidanceAnnouncerTest {

    private val pass = Guidance.Pass("chaise", 2.4f, PassSide.Left, 0.5f)
    private val nearPass = Guidance.Pass("chaise", 1.9f, PassSide.Left, 0.5f)
    private val halt = Guidance.Halt("chaise", 1.1f)

    @Test
    fun `une instruction doit etre vue deux fois`() {
        val voice = GuidanceAnnouncer()
        assertNull(voice.onFrame(0, pass))
        assertEquals("Chaise devant, à 2,4 mètres. Décale à gauche de 50 centimètres.", voice.onFrame(400, pass))
    }

    @Test
    fun `la meme instruction ne se repete pas`() {
        val voice = GuidanceAnnouncer()
        voice.onFrame(0, pass)
        assertEquals("Chaise devant, à 2,4 mètres. Décale à gauche de 50 centimètres.", voice.onFrame(400, pass))
        assertNull(voice.onFrame(900, pass))
        assertNull(voice.onFrame(4_000, pass))
    }

    @Test
    fun `un objet qui s'approche se dit apres le delai`() {
        val voice = GuidanceAnnouncer()
        voice.onFrame(0, pass)
        voice.onFrame(400, pass)
        assertNull(voice.onFrame(800, nearPass))
        assertNull(voice.onFrame(1_200, nearPass))
        assertEquals("Chaise devant, à 1,9 mètre. Décale à gauche de 50 centimètres.", voice.onFrame(2_000, nearPass))
    }

    @Test
    fun `l'arret part des la premiere frame`() {
        val voice = GuidanceAnnouncer()
        assertEquals("Stop. Chaise à 1,1 mètre.", voice.onFrame(0, halt))
    }

    @Test
    fun `l'arret passe devant le delai`() {
        val voice = GuidanceAnnouncer()
        voice.onFrame(0, pass)
        voice.onFrame(400, pass)
        assertEquals("Stop. Chaise à 1,1 mètre.", voice.onFrame(600, halt))
    }

    @Test
    fun `l'arret se repete, mais pas a chaque frame`() {
        val voice = GuidanceAnnouncer()
        assertEquals("Stop. Chaise à 1,1 mètre.", voice.onFrame(0, halt))
        assertNull(voice.onFrame(500, halt))
        assertEquals("Stop. Chaise à 1,1 mètre.", voice.onFrame(2_100, halt))
    }

    @Test
    fun `rien de mesure coupe la voix puis la rend`() {
        val voice = GuidanceAnnouncer()
        voice.onFrame(0, pass)
        voice.onFrame(400, pass)
        assertNull(voice.onFrame(800, Guidance.Unknown))
        assertNull(voice.onFrame(1_200, pass))
        assertEquals("Chaise devant, à 2,4 mètres. Décale à gauche de 50 centimètres.", voice.onFrame(2_100, pass))
    }

    @Test
    fun `reset oublie l'instruction dite`() {
        val voice = GuidanceAnnouncer()
        voice.onFrame(0, pass)
        assertEquals("Chaise devant, à 2,4 mètres. Décale à gauche de 50 centimètres.", voice.onFrame(400, pass))
        voice.reset()
        assertNull(voice.onFrame(500, pass))
        assertEquals("Chaise devant, à 2,4 mètres. Décale à gauche de 50 centimètres.", voice.onFrame(900, pass))
    }
}
