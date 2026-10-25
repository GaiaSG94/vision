package dev.sam3vision.app.ihear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La machine pure du geste vertical : un geste = une marche de bulle. */
class SwipeStepTest {

    @Test
    fun `le premier geste vers le bas relit la derniere bulle`() {
        val step = swipeStep(entriesSize = 4, selected = 3, firstSwipeDownDone = false, downward = true)
        assertTrue(step is SwipeStep.Replay)
        assertEquals(true, (step as SwipeStep.Replay).firstSwipeDownDone)
    }

    @Test
    fun `le geste suivant vers le bas remonte d'une bulle`() {
        assertEquals(SwipeStep.Move(2), swipeStep(4, 3, true, true))
    }

    @Test
    fun `un premier geste vers le bas avant la fin remonte aussi`() {
        assertEquals(SwipeStep.Move(1), swipeStep(4, 2, false, true))
    }

    @Test
    fun `vers le bas en haut de l'historique ne fait rien`() {
        assertEquals(SwipeStep.NoMove, swipeStep(4, 0, true, true))
    }

    @Test
    fun `vers le haut descend vers une bulle plus recente`() {
        assertEquals(SwipeStep.Move(2), swipeStep(4, 1, false, false))
    }

    @Test
    fun `vers le haut a la derniere bulle ne fait rien`() {
        assertEquals(SwipeStep.NoMove, swipeStep(4, 3, false, false))
    }

    @Test
    fun `sans selection, vers le haut pose la premiere bulle`() {
        assertEquals(SwipeStep.Move(0), swipeStep(3, -1, false, false))
    }
}
