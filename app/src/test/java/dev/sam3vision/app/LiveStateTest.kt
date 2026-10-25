package dev.sam3vision.app

import dev.sam3vision.core.live.Cadence
import dev.sam3vision.core.live.LiveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(Cadence.Direct.targetMs, nextIntervalMs(null))
        assertEquals(500L, Cadence.Direct.targetMs)
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
    fun `premiere frame toujours gardee`() {
        assertTrue(shouldKeepFrame(nowMs = 12_345L, lastKeepAtMs = 0L))
    }

    @Test
    fun `frame trop rapprochee est ignoree`() {
        assertEquals(false, shouldKeepFrame(nowMs = 1_400L, lastKeepAtMs = 1_000L))
    }

    @Test
    fun `frame gardee une fois l intervalle ecoule`() {
        assertTrue(shouldKeepFrame(nowMs = 2_000L, lastKeepAtMs = 1_000L))
        assertTrue(shouldKeepFrame(nowMs = 2_500L, lastKeepAtMs = 1_000L, intervalMs = 1_000L))
    }

    @Test
    fun `la voix du direct est au repos par defaut`() {
        val state = LiveUiState()
        assertEquals(LiveVoicePhase.IDLE, state.voice.phase)
        assertEquals("", state.voice.text)
        assertEquals(null, state.voice.error)
    }

    @Test
    fun `nom court du gpu sans prefixe constructeur`() {
        assertEquals("A100-SXM4-40GB", shortGpu("NVIDIA A100-SXM4-40GB"))
        assertEquals("A100", shortGpu("A100"))
        assertEquals("", shortGpu(""))
    }

    @Test
    fun `vision seule affiche la ligne dediee sans GPU`() {
        val line = formatLiveStatus(true, 0f, 0, "", 0, null, samEnabled = false)
        assertEquals(VISION_ONLY_LINE, line)
        assertFalse(line.contains("GPU"))
    }

    @Test
    fun `statut du direct suit le mode sam`() {
        val vision = LiveUiState(running = true, samEnabled = false)
        assertEquals(VISION_ONLY_LINE, vision.statusLine)
        val sam = LiveUiState(running = true, samEnabled = true, gpu = "A100-SXM4-40GB")
        assertTrue(sam.statusLine.contains("A100-SXM4-40GB"))
    }
}
