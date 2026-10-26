package dev.sam3vision.core.guidance

/** Côté du contournement, du point de vue de la personne qui marche. */
enum class PassSide { Left, Right }

/**
 * Décision de guidage, **sans un mot** : [GuidanceText] la met en phrase,
 * [GuidanceAnnouncer] décide quand la dire.
 *
 * Volontairement pauvre — trois cas utiles et le silence. Le silence est un cas à part
 * entière : sans objet mesuré, on ne sait pas, et on ne dit pas « c'est libre ».
 */
sealed interface Guidance {
    /** Rien de mesuré : on ne sait pas, on se tait. */
    data object Unknown : Guidance

    /** Des objets mesurés, aucun ne ferme le couloir : on peut continuer tout droit. */
    data object Clear : Guidance

    /** Un obstacle ferme le couloir : contourner de [side], de [shiftMeters] mètres. */
    data class Pass(
        val label: String,
        val meters: Float,
        val side: PassSide,
        val shiftMeters: Float,
    ) : Guidance

    /** Trop près pour contourner : s'arrêter d'abord. */
    data class Halt(val label: String, val meters: Float) : Guidance
}

/**
 * Chiffres **figés** du couloir de marche (voir `docs/GUIDAGE.md`).
 *
 * Le couloir est celui d'une personne qui avance droit devant elle : demi-largeur
 * [HALF_WIDTH_METERS] (un buste plus une marge d'épaule), prévenue à [WARN_METERS],
 * arrêt demandé sous [STOP_METERS]. Ces valeurs viennent du budget de temps du direct :
 * à 1,2 m/s et ~0,5 s de latence, 3 m laissent deux pas pour agir ; sous 1,2 m, un pas
 * de plus ne suffit plus à contourner.
 *
 * Une conséquence assumée : au-delà de [WARN_METERS], on ne dit pas qu'il y a un objet
 * — la voix parle de la bande 1,2-3 m, celle où l'instruction est encore applicable.
 */
object Corridor {

    /** Demi-largeur du couloir tenu par le marcheur (m). */
    const val HALF_WIDTH_METERS = 0.45f

    /** Distance sous laquelle un objet dans le couloir est annoncé (m). */
    const val WARN_METERS = 3.0f

    /** Distance sous laquelle on demande l'arrêt au lieu d'un contournement (m). */
    const val STOP_METERS = 1.2f

    /** Marge de sécurité ajoutée de chaque côté du corps (m). */
    const val MARGIN_METERS = 0.20f

    /** Décalage au-delà duquel on annonce un contournement plutôt qu'un pas (m). */
    const val MAX_SHIFT_METERS = 1.20f
}
