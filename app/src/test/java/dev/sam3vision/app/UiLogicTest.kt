package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure de l'UI : palette des masques et échelle d'affichage. */
class UiLogicTest {

    @Test
    fun `la couleur des masques tourne sans trou`() {
        val first = maskColorAt(0)
        val second = maskColorAt(1)
        assertNotEquals(first, second)
        assertEquals(first, maskColorAt(MASK_COLORS.size))
        assertTrue(MASK_COLORS.size >= 6)
        assertTrue(MASK_COLORS.distinct().size == MASK_COLORS.size)
    }

    @Test
    fun `l'echelle d'overlay ne grandit jamais l'image`() {
        assertEquals(1f, overlayScaleFor(1200f, 800))
        assertEquals(0.5f, overlayScaleFor(500f, 1000))
        assertEquals(1f, overlayScaleFor(500f, 0))
    }

    @Test
    fun `la dictee s'ajoute au brouillon sans coller les mots`() {
        assertEquals("quelle heure", mergeDraft("", "  quelle heure "))
        assertEquals("decris la photo quelle heure", mergeDraft("decris la photo", "quelle heure"))
        assertEquals("deja la", mergeDraft("deja la", "   "))
        assertEquals("seule", mergeDraft("  ", "seule"))
    }

    @Test
    fun `la bascule d'intro dit l'etat qu'elle porte`() {
        assertEquals("Intro à chaque ouverture : on", introToggleLabel(true))
        assertEquals("Intro à chaque ouverture : off", introToggleLabel(false))
        assertNotEquals(introToggleLabel(true), introToggleLabel(false))
    }

    @Test
    fun `la bulle d'intro est posee a chaque ouverture tant que l'intro n'est pas coupee`() {
        assertTrue(introBubbleVisible(settingsReady = true, introEnabled = true))
        assertTrue(!introBubbleVisible(settingsReady = false, introEnabled = true))
        assertTrue(!introBubbleVisible(settingsReady = true, introEnabled = false))
    }

    @Test
    fun `la phrase Disable iSee intro coupe l'intro et rien d'autre`() {
        assertTrue(isIntroDisablePhrase("Please disable iSee intro"))
        assertTrue(isIntroDisablePhrase("  DISABLE ISEE INTRO. "))
        // La dictée entend « i see » en deux mots : la coupe doit quand même marcher.
        assertTrue(isIntroDisablePhrase("ok, disable I see intro please"))
        assertFalse(isIntroDisablePhrase("What does iSee intro say?"))
        assertFalse(isIntroDisablePhrase(""))
    }

    @Test
    fun `le texte de l'intro est celui que la voix dit`() {
        assertTrue(IHEAR_INTRO_TEXT.startsWith("Welcome to iSee."))
        assertTrue(IHEAR_INTRO_TEXT.contains("tap again to stop"))
        assertTrue(IHEAR_INTRO_TEXT.contains("Disable iSee intro"))
        // Le geste de balayage est annoncé avant la phrase de désactivation.
        val swipe = IHEAR_INTRO_TEXT.indexOf("swipe down")
        val disable = IHEAR_INTRO_TEXT.indexOf("Disable iSee intro")
        assertTrue(swipe > 0)
        assertTrue(swipe < disable)
    }

    @Test
    fun `les couleurs de theme sont distinctes`() {
        assertNotEquals(AppColors.primary, AppColors.danger)
        assertNotEquals(AppColors.background, AppColors.surface)
        assertTrue(MASK_ALPHA in 1..255)
    }
}
