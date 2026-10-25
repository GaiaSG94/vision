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
        assertEquals("Intro à la 1re ouverture : on", introToggleLabel(true))
        assertEquals("Intro à la 1re ouverture : off", introToggleLabel(false))
        assertNotEquals(introToggleLabel(true), introToggleLabel(false))
    }

    @Test
    fun `la bulle d'intro reste posee seulement avant la premiere ecoute`() {
        assertTrue(introBubbleVisible(settingsReady = true, introEnabled = true, introPlayed = false))
        assertTrue(!introBubbleVisible(settingsReady = false, introEnabled = true, introPlayed = false))
        assertTrue(!introBubbleVisible(settingsReady = true, introEnabled = true, introPlayed = true))
        assertTrue(!introBubbleVisible(settingsReady = true, introEnabled = false, introPlayed = false))
    }

    @Test
    fun `la phrase Disable iHear intro coupe l'intro et rien d'autre`() {
        assertTrue(isIntroDisablePhrase("Please disable iHear intro"))
        assertTrue(isIntroDisablePhrase("  DISABLE IHEAR INTRO. "))
        assertFalse(isIntroDisablePhrase("What does iHear intro say?"))
        assertFalse(isIntroDisablePhrase(""))
    }

    @Test
    fun `le texte de l'intro est celui que la voix dit`() {
        assertTrue(IHEAR_INTRO_TEXT.startsWith("Welcome to iHear."))
        assertTrue(IHEAR_INTRO_TEXT.contains("tap again to stop"))
        assertTrue(IHEAR_INTRO_TEXT.contains("Disable iHear intro"))
    }

    @Test
    fun `les couleurs de theme sont distinctes`() {
        assertNotEquals(AppColors.primary, AppColors.danger)
        assertNotEquals(AppColors.background, AppColors.surface)
        assertTrue(MASK_ALPHA in 1..255)
    }
}
