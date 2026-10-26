package dev.sam3vision.core.guidance

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mise en mots : une seule action par phrase, deux langues, mêmes chiffres. */
class GuidanceTextTest {

    @Test
    fun `rien de mesure ne se dit pas`() {
        assertNull(GuidanceText.say(Guidance.Unknown))
        assertNull(GuidanceText.key(Guidance.Unknown))
    }

    @Test
    fun `voie libre en francais et en anglais`() {
        assertEquals("Tout droit, c'est libre.", GuidanceText.say(Guidance.Clear))
        assertEquals("Straight ahead is clear.", GuidanceText.say(Guidance.Clear, SpeechLanguage.En))
    }

    @Test
    fun `un pas chiffre a gauche`() {
        val guidance = Guidance.Pass("chaise", 2.1f, PassSide.Left, 0.5f)
        assertEquals(
            "Chaise devant, à 2,1 mètres. Décale à gauche de 50 centimètres.",
            GuidanceText.say(guidance),
        )
    }

    @Test
    fun `un cote deja libre se dit sans chiffre`() {
        val guidance = Guidance.Pass("personne", 1.4f, PassSide.Right, 0.1f)
        assertEquals("Personne devant, à 1,4 mètre. Passe à droite.", GuidanceText.say(guidance))
    }

    @Test
    fun `un pas chiffre en anglais`() {
        val guidance = Guidance.Pass("chair", 2f, PassSide.Right, 0.6f)
        assertEquals(
            "Chair ahead, 2 meters. Shift right by 60 centimeters.",
            GuidanceText.say(guidance, SpeechLanguage.En),
        )
    }

    @Test
    fun `l'arret nomme l'obstacle`() {
        assertEquals("Stop. Chaise à 1 mètre.", GuidanceText.say(Guidance.Halt("chaise", 1f)))
        assertEquals("Stop. Chair 1 meter ahead.", GuidanceText.say(Guidance.Halt("chair", 1f), SpeechLanguage.En))
    }

    @Test
    fun `la signature ignore les dixiemes et change de cote`() {
        val left = Guidance.Pass("chaise", 2.1f, PassSide.Left, 0.5f)
        val sameBucket = Guidance.Pass("chaise", 2.2f, PassSide.Left, 0.55f)
        val otherSide = Guidance.Pass("chaise", 2.1f, PassSide.Right, 0.5f)
        assertEquals(GuidanceText.key(left), GuidanceText.key(sameBucket))
        assertNotEquals(GuidanceText.key(left), GuidanceText.key(otherSide))
    }

    @Test
    fun `un objet qui s'approche change de signature`() {
        val near = Guidance.Pass("chaise", 1.8f, PassSide.Left, 0.5f)
        val far = Guidance.Pass("chaise", 2.6f, PassSide.Left, 0.5f)
        assertTrue(GuidanceText.key(near) != GuidanceText.key(far))
    }
}
