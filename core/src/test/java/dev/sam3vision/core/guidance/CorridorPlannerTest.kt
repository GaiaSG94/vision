package dev.sam3vision.core.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Corridor de marche : qui bloque, de quel côté, et quand s'arrêter. */
class CorridorPlannerTest {

    private fun obj(
        label: String = "chaise",
        meters: Float,
        lateral: Float = 0f,
        width: Float = 0.6f,
    ): GuidedObject = GuidedObject(label, meters, lateral, width)

    @Test
    fun `sans objet mesure, on se tait`() {
        assertEquals(Guidance.Unknown, CorridorPlanner().plan(emptyList()))
    }

    @Test
    fun `objet hors du couloir laisse la voie libre`() {
        assertEquals(Guidance.Clear, CorridorPlanner().plan(listOf(obj(meters = 2f, lateral = 2f))))
    }

    @Test
    fun `objet au-dela de la zone d'alerte ne bloque pas`() {
        assertEquals(Guidance.Clear, CorridorPlanner().plan(listOf(obj(meters = 3.4f))))
    }

    @Test
    fun `obstacle au centre, on passe du cote qui demande le moins de pas`() {
        val plan = CorridorPlanner().plan(listOf(obj(meters = 2.5f))) as Guidance.Pass
        assertEquals(PassSide.Left, plan.side)
        // 45 cm de demi-largeur + 20 cm de marge + 30 cm de demi-objet
        assertEquals(0.95f, plan.shiftMeters, 0.01f)
    }

    @Test
    fun `obstacle decale a gauche, on passe a droite`() {
        val plan = CorridorPlanner().plan(listOf(obj(meters = 2.5f, lateral = -0.3f, width = 0.8f))) as Guidance.Pass
        assertEquals(PassSide.Right, plan.side)
        assertEquals(0.75f, plan.shiftMeters, 0.01f)
    }

    @Test
    fun `obstacle trop pres demande l'arret`() {
        assertEquals(Guidance.Halt("chaise", 1.0f), CorridorPlanner().plan(listOf(obj(meters = 1.0f))))
    }

    @Test
    fun `le plus proche decide`() {
        // La porte est plus loin : c'est la chaise, à 1,1 m, qui commande l'arrêt.
        val plan = CorridorPlanner().plan(listOf(obj("porte", 2.8f), obj("chaise", 1.1f)))
        assertEquals(Guidance.Halt("chaise", 1.1f), plan)
    }

    @Test
    fun `mur large, le decalage est borne`() {
        val plan = CorridorPlanner().plan(listOf(obj(meters = 2.4f, width = 5f))) as Guidance.Pass
        assertEquals(Corridor.MAX_SHIFT_METERS, plan.shiftMeters, 0.001f)
        assertTrue(plan.side == PassSide.Left || plan.side == PassSide.Right)
    }
}
