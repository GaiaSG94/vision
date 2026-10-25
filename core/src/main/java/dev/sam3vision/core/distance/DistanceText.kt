package dev.sam3vision.core.distance

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Mise en forme des distances pour l'écran et pour le LLM.
 *
 * Précision volontairement dégressive : le centimètre en deçà d'un mètre, le
 * décimètre jusqu'à dix mètres, le mètre au-delà — afficher « 23,4 m » laisserait
 * croire à une précision que la mesure monocular n'a pas.
 */
object DistanceText {

    /** Étiquette française : « 85 cm », « 3,2 m », « 12 m ». */
    fun format(meters: Float): String {
        if (!meters.isFinite() || meters <= 0f) return "?"
        if (meters < 1f) return "${(meters * 100f).roundToInt()} cm"
        if (meters < 10f) return "${decimal(meters, 1)} m"
        return "${decimal(meters, 0)} m"
    }

    /** Étiquette avec l'approximation assumée : « ≈ 3,2 m ». */
    fun approximate(meters: Float): String = "≈ ${format(meters)}"

    /**
     * Distance **prononcée** par la voix : « 85 centimètres », « 1,4 mètre »,
     * « 12 mètres ». Le singulier en français va jusqu'à 2 (« 1,5 mètre »), d'où la
     * règle sur la valeur arrondie, pas sur la valeur brute.
     */
    fun spoken(meters: Float): String {
        if (!meters.isFinite() || meters <= 0f) return "distance inconnue"
        if (meters < 1f) {
            val centimeters = (meters * 100f).roundToInt()
            return "$centimeters ${if (centimeters < 2) "centimètre" else "centimètres"}"
        }
        val digits = if (meters < 10f) 1 else 0
        val text = decimal(meters, digits).removeSuffix(",0")
        val rounded = text.replace(',', '.').toFloatOrNull() ?: meters
        return "$text ${if (rounded < 2f) "mètre" else "mètres"}"
    }

    /** L'objet le plus proche de la frame, `null` si rien n'est mesuré. */
    fun nearest(readings: List<DistanceReading>): DistanceReading? =
        readings.minByOrNull { it.meters }

    /**
     * Distance la plus courte par étiquette, dans l'ordre d'apparition des objets :
     * c'est la pastille du bas d'écran (« personne 2,1 m »).
     */
    fun perLabel(readings: List<DistanceReading>): List<Pair<String, Float>> {
        val best = LinkedHashMap<String, Float>()
        readings.forEach { reading ->
            val previous = best[reading.label]
            best[reading.label] = if (previous == null) reading.meters else minOf(previous, reading.meters)
        }
        return best.map { (label, meters) -> label to meters }
    }

    /** Ligne courte pour la barre d'état : « plus proche 1,8 m », vide si rien. */
    fun nearestLabel(readings: List<DistanceReading>): String =
        nearest(readings)?.let { "plus proche ${format(it.meters)}" }.orEmpty()

    private fun decimal(value: Float, digits: Int): String =
        String.format(Locale.FRANCE, "%.${digits}f", value)
}