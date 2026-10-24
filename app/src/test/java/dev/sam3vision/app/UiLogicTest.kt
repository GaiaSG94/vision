package dev.sam3vision.app

import org.junit.Assert.assertEquals
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
    fun `les couleurs de theme sont distinctes`() {
        assertNotEquals(AppColors.primary, AppColors.danger)
        assertNotEquals(AppColors.background, AppColors.surface)
        assertTrue(MASK_ALPHA in 1..255)
    }
}
