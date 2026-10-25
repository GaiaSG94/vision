package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fenêtre en vol et cadence : la politique de pipeline du mode Direct. */
class PipelineGateTest {

    @Test
    fun `capacite 1 evince la plus ancienne`() {
        val queue = InFlightQueue(capacity = 1)
        assertFalse(queue.admit(1L))
        assertTrue(queue.admit(2L))
        assertEquals(1, queue.inFlight)
        assertEquals(1, queue.evicted)
    }

    @Test
    fun `une reponse evincee n'est jamais rendue`() {
        val queue = InFlightQueue(capacity = 1)
        queue.admit(1L)
        queue.admit(2L)
        assertFalse(queue.onResult(1L))
        assertTrue(queue.onResult(2L))
        assertEquals(0, queue.inFlight)
    }

    @Test
    fun `une reponse en retard ne reecrit pas un affichage plus recent`() {
        val queue = InFlightQueue(capacity = 3)
        listOf(1L, 2L, 3L).forEach { queue.admit(it) }
        assertTrue(queue.onResult(3L))
        assertFalse(queue.onResult(2L))
        assertFalse(queue.onResult(1L))
        assertEquals(0, queue.inFlight)
    }

    @Test
    fun `la capacite n'accepte jamais moins de 1`() {
        val queue = InFlightQueue(capacity = 0)
        assertFalse(queue.admit(1L))
        assertTrue(queue.admit(2L))
        assertEquals(1, queue.inFlight)
    }

    @Test
    fun `reset vide la fenetre`() {
        val queue = InFlightQueue(capacity = 2)
        queue.admit(1L)
        queue.reset()
        assertEquals(0, queue.inFlight)
        assertEquals(0, queue.evicted)
    }

    @Test
    fun `la cible de cadence est la latence divisee par la capacite`() {
        val cadence = CadenceController()
        assertEquals(201L, cadence.targetFor(600L, 3))
        assertEquals(500L, cadence.targetFor(600L, 1))
        assertEquals(CadenceController.MIN_MS, cadence.targetFor(0L, 1))
        assertEquals(CadenceController.MIN_MS, cadence.targetFor(20L, 4))
    }

    @Test
    fun `l'anti-rebond refuse deux changements rapproches`() {
        val cadence = CadenceController()
        assertEquals(201L, cadence.intervalFor(600L, 3, 0L))
        assertEquals(201L, cadence.intervalFor(2000L, 1, 100L))
        assertEquals(201L, cadence.intervalMs)
        assertEquals(500L, cadence.intervalFor(2000L, 1, 700L))
    }

    @Test
    fun `un petit ecart sous le bandeau mort ne change rien`() {
        val cadence = CadenceController()
        cadence.intervalFor(600L, 1, 0L)
        assertEquals(500L, cadence.intervalFor(520L, 1, 5_000L))
    }
}