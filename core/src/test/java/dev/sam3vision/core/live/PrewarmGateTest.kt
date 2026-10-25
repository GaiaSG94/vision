package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garde du réveil préventif : un seul réveil par fenêtre. */
class PrewarmGateTest {

    @Test
    fun `premier reveil toujours autorise`() {
        assertTrue(shouldPrewarm(now = 1_000_000L, lastAt = 0L, windowMs = 120_000L))
    }

    @Test
    fun `pas de second reveil dans la fenetre`() {
        assertFalse(shouldPrewarm(now = 1_100_000L, lastAt = 1_000_000L, windowMs = 120_000L))
    }

    @Test
    fun `reveil autorise une fois la fenetre ecoulee`() {
        assertTrue(shouldPrewarm(now = 1_120_000L, lastAt = 1_000_000L, windowMs = 120_000L))
    }

    @Test
    fun `horloge qui recule ne relance pas la carte`() {
        assertFalse(shouldPrewarm(now = 900_000L, lastAt = 1_000_000L, windowMs = 120_000L))
    }
}
