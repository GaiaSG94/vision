package dev.sam3vision.core.guidance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Une porte n'est pas un obstacle : elle est retirée de la scène de guidage. */
class OpeningConceptsTest {

    @Test
    fun `les portes et passages sont ouverts`() {
        assertTrue(OpeningConcepts.isOpening("door"))
        assertTrue(OpeningConcepts.isOpening("Porte"))
        assertTrue(OpeningConcepts.isOpening("portes"))
        assertTrue(OpeningConcepts.isOpening("doorway"))
        assertTrue(OpeningConcepts.isOpening("fenêtre"))
        assertTrue(OpeningConcepts.isOpening("Exit"))
    }

    @Test
    fun `le mobilier et les personnes restent des obstacles`() {
        assertFalse(OpeningConcepts.isOpening("chaise"))
        assertFalse(OpeningConcepts.isOpening("personne"))
        assertFalse(OpeningConcepts.isOpening("table"))
        assertFalse(OpeningConcepts.isOpening(""))
        // « porte-fenêtre » n'est pas un passage dégagé : la forme canonique ne le reconnaît pas
        assertFalse(OpeningConcepts.isOpening("porte-fenetre"))
    }
}
