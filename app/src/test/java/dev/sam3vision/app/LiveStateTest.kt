package dev.sam3vision.app

import dev.sam3vision.core.live.LiveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure du mode Direct : libellé d'état et cadence adaptative. */
class LiveStateTest {

    @Test
    fun `ligne d'etat montre cadence latence et gpu`() {
        val line = formatLiveStatus(true, 1.4f, 780, "A100-SXM4-40GB", 8)
        assertTrue(line.contains("1,4 ips"))
        assertTrue(line.contains("780 ms"))
        assertTrue(line.contains("8 objets"))
        assertTrue(line.contains("A100-SXM4-40GB"))
    }

    @Test
    fun `ligne d'etat degrade proprement sans mesure`() {
        val line = formatLiveStatus(true, 0f, 0, "", 0)
        assertTrue(line.contains("…"))
        assertTrue(line.contains("GPU ?"))
    }

    @Test
    fun `direct arrete est explicite`() {
        assertEquals("Direct arrêté", formatLiveStatus(false, 2f, 300, "A100", 3))
    }

    @Test
    fun `cadence par defaut sans session`() {
        assertEquals(LiveSession.TARGET_MS, nextIntervalMs(null))
        assertEquals(500L, LiveSession.TARGET_MS)
    }

    @Test
    fun `etat initial est arrete et vide`() {
        val s = LiveUiState()
        assertEquals(false, s.running)
        assertEquals(0, s.objectsCount)
        assertEquals("", s.lastJpegB64)
    }

    @Test
    fun `la fenetre demandee entoure l instant vise`() {
        val now = 100_000L
        val window = focusWindow(now, 5)

        assertEquals(now - 5_000L - FOCUS_HALF_SPAN_MS, window.first)
        assertEquals(now - 5_000L + FOCUS_HALF_SPAN_MS, window.last)
    }

    @Test
    fun `maintenant regarde les dernieres secondes`() {
        val now = 100_000L
        val window = focusWindow(now, 0)

        assertEquals(now - FOCUS_HALF_SPAN_MS, window.first)
        assertEquals(now + FOCUS_HALF_SPAN_MS, window.last)
    }

    @Test
    fun `le libelle du point focal annonce l instant`() {
        assertTrue(focusLabel(5).contains("Il y a 5 s"))
        assertTrue(focusLabel(0).contains("l'instant"))
    }

    @Test
    fun `la memoire expose les objets suivis`() {
        val state = LiveMemoryState()
        assertTrue(state.empty)
        assertEquals(0, state.focusSeconds)
    }

    @Test
    fun `le reveil du gpu se voit dans la ligne d'etat`() {
        val state = LiveUiState(running = true, starting = true)
        assertEquals(WAKING_LINE, state.statusLine)
        assertTrue(WAKING_LINE.contains("A100 40"))
    }

    @Test
    fun `nom court du gpu sans prefixe constructeur`() {
        assertEquals("A100-SXM4-40GB", shortGpu("NVIDIA A100-SXM4-40GB"))
        assertEquals("A100", shortGpu("A100"))
        assertEquals("", shortGpu(""))
    }
}
