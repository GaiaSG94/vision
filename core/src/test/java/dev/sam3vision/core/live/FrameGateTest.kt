package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    @Test
    fun `une frame en vol accepte la premiere puis rejette la seconde`() {
        val gate = FrameGate()
        assertFalse(gate.inFlight)
        assertTrue(gate.tryStart())
        assertTrue(gate.inFlight)
        assertFalse("2e frame doit etre jetee", gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun `finish libere le creneau`() {
        val gate = FrameGate()
        assertTrue(gate.tryStart())
        gate.finish()
        assertFalse(gate.inFlight)
        assertTrue(gate.tryStart())
        gate.finish()
    }

    @Test
    fun `finish sans frame en vol est sans effet`() {
        val gate = FrameGate()
        gate.finish()
        assertFalse(gate.inFlight)
        assertTrue(gate.tryStart())
    }
}
