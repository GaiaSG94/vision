package dev.sam3vision.core.guidance

/**
 * Décide quoi faire d'une scène mesurée, en supposant que la personne veut **aller là
 * où elle filme** : tout droit s'il n'y a rien dans le couloir, contourner d'un côté si
 * quelque chose le ferme, s'arrêter si c'est déjà trop près.
 *
 * Le couloir est tenu par le corps ([Corridor.HALF_WIDTH_METERS]) plus une marge ; un
 * objet y entre dès que son intervalle latéral le recouvre. Le côté du contournement est
 * celui qui demande **le moins de pas** — c'est ce qui donne « décale à gauche de 40 cm »
 * plutôt qu'un « à gauche » arbitraire.
 *
 * Pure et testée en JVM : aucun Android, aucune horloge.
 */
class CorridorPlanner(
    private val halfWidthMeters: Float = Corridor.HALF_WIDTH_METERS,
    private val warnMeters: Float = Corridor.WARN_METERS,
    private val stopMeters: Float = Corridor.STOP_METERS,
    private val marginMeters: Float = Corridor.MARGIN_METERS,
    private val maxShiftMeters: Float = Corridor.MAX_SHIFT_METERS,
) {

    /** Décision pour une frame : [Guidance.Unknown] sans mesure, sinon utile ou libre. */
    fun plan(objects: List<GuidedObject>): Guidance {
        val usable = objects.filter { it.meters.isFinite() && it.meters > 0f }
        if (usable.isEmpty()) return Guidance.Unknown
        val nearest = usable
            .filter { it.meters <= warnMeters && overlapsCorridor(it) }
            .minByOrNull { it.meters }
            ?: return Guidance.Clear
        if (nearest.meters <= stopMeters) return Guidance.Halt(nearest.label, nearest.meters)
        return detour(nearest)
    }

    /** L'objet recouvre-t-il le couloir élargi de la marge ? */
    private fun overlapsCorridor(obj: GuidedObject): Boolean =
        obj.leftMeters <= halfWidthMeters + marginMeters &&
            obj.rightMeters >= -halfWidthMeters - marginMeters

    /** Contournement le moins coûteux, borné à [maxShiftMeters]. */
    private fun detour(obj: GuidedObject): Guidance {
        // Distance à parcourir pour que le corps entier passe à gauche, puis à droite.
        val left = (halfWidthMeters + marginMeters - obj.leftMeters).coerceAtLeast(0f)
        val right = (obj.rightMeters + marginMeters + halfWidthMeters).coerceAtLeast(0f)
        val side = if (left <= right) PassSide.Left else PassSide.Right
        val shift = minOf(left, right).coerceAtMost(maxShiftMeters)
        return Guidance.Pass(obj.label, obj.meters, side, shift)
    }
}
