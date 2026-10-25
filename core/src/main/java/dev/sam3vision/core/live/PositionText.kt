package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import kotlin.math.roundToInt

/**
 * Un objet nommé à une position **normalisée** (0..1), avec sa distance estimée
 * quand elle est connue : ce que la voix met en mots.
 */
data class PlacedLabel(
    val label: String,
    val centerX: Float,
    val centerY: Float = 0.5f,
    val meters: Float? = null,
) {
    /** Repère lisible : « en haut à gauche », « au centre », « à droite ». */
    val position: String get() = PositionText.positionLabel(centerX, centerY)
}

/**
 * Mise en mots des positions détectées, pour l'annonce vocale ElevenLabs.
 *
 * Tout est **pur et testé en JVM** : les seuils de secteur, l'ordre de lecture
 * (gauche → droite), le dédoublonnage et la troncature à [MAX_OBJECTS] objets.
 *
 * Forme produite : `Personne à gauche, chien en bas à droite.` — sans article, donc
 * sans faute de genre sur des labels libres (« table », « livre », « personne »…).
 * Quand la distance est connue, elle s'ajoute à l'objet et les objets sont séparés
 * par des points-virgules : `Personne à gauche, à 1,4 mètre ; chien au centre, à 3 mètres.`
 */
object PositionText {

    /** Frontière de secteur : en deçà de 0,34 → gauche/haut, au-delà de 0,66 → droite/bas. */
    const val SIDE_EDGE = 0.34f

    /** Objets cités au plus dans une annonce (le reste est résumé par un compte). */
    const val MAX_OBJECTS = 3

    /** Repère horizontal : « à gauche », « au centre », « à droite ». */
    fun horizontal(centerX: Float): String = when {
        centerX < SIDE_EDGE -> "à gauche"
        centerX > 1f - SIDE_EDGE -> "à droite"
        else -> "au centre"
    }

    /** Repère vertical : « en haut », « en bas », ou `null` dans la bande centrale. */
    fun vertical(centerY: Float): String? = when {
        centerY < SIDE_EDGE -> "en haut"
        centerY > 1f - SIDE_EDGE -> "en bas"
        else -> null
    }

    /** Position complète : « en haut à gauche », « au centre », « en bas à droite ». */
    fun positionLabel(centerX: Float, centerY: Float): String =
        listOfNotNull(vertical(centerY), horizontal(centerX)).joinToString(" ")

    /** Détection (boîte en pixels) → label placé ; `null` si la géométrie est inutilisable. */
    fun fromDetection(detection: Detection): PlacedLabel? {
        val box = detection.normalizedBox() ?: return null
        if (detection.label.isBlank()) return null
        return PlacedLabel(detection.label, (box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f)
    }

    /** Objet suivi (boîte déjà normalisée) → label placé ; `null` si la boîte est illisible. */
    fun fromSeenObject(obj: SeenObject): PlacedLabel? {
        if (obj.label.isBlank() || obj.box.size < 4) return null
        return PlacedLabel(obj.label, (obj.box[0] + obj.box[2]) / 2f, (obj.box[1] + obj.box[3]) / 2f)
    }

    /** Les détections exploitables d'une frame, dans l'ordre d'arrivée. */
    fun placed(detections: List<Detection>): List<PlacedLabel> = detections.mapNotNull(::fromDetection)

    /**
     * Phrase annoncée, ou `null` s'il n'y a rien à dire. Un label répété à la même
     * position n'est cité qu'une fois ; au-delà de [maxObjects] objets cités, le reste
     * est résumé (« et 2 autres objets »).
     */
    fun phrase(labels: List<PlacedLabel>, maxObjects: Int = MAX_OBJECTS): String? {
        val cleaned = labels
            .filter { it.label.isNotBlank() }
            .map { it.copy(label = it.label.trim()) }
            .distinctBy { it.label.lowercase() to it.position }
            .sortedWith(compareBy({ it.centerX }, { it.label }))
        if (cleaned.isEmpty()) return null
        val shown = cleaned.take(maxObjects.coerceAtLeast(1))
        // Des distances dans la phrase imposent le point-virgule : la virgule sert
        // déjà à séparer la position de la distance (« à gauche, à 1,4 mètre »).
        val separator = if (shown.any { it.meters != null }) " ; " else ", "
        val body = shown.joinToString(separator, transform = ::item)
        val hidden = cleaned.size - shown.size
        val text = if (hidden > 0) "$body, ${others(hidden)}." else "$body."
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    /** Un objet en mots : « personne à gauche » puis, si on la connaît, « , à 1,4 mètre ». */
    fun item(label: PlacedLabel): String {
        val place = "${label.label} ${label.position}"
        val meters = label.meters ?: return place
        return "$place, à ${DistanceText.spoken(meters)}"
    }

    /**
     * Signature de scène (labels + secteurs + distance **arrondie au mètre**),
     * insensible à l'ordre : sert à ne pas répéter la même scène. Arrondir est ce
     * qui fait parler un objet qui s'approche (3 m → 2 m → 1 m) sans le faire
     * bavarder à chaque frame.
     */
    fun key(labels: List<PlacedLabel>): String = labels
        .filter { it.label.isNotBlank() }
        .map { "${it.label.trim().lowercase()}|${it.position}|${bucket(it.meters)}" }
        .distinct()
        .sorted()
        .joinToString(";")

    /** Distance au mètre près pour la signature ; « ? » quand elle est inconnue. */
    private fun bucket(meters: Float?): String =
        if (meters == null || !meters.isFinite()) "?" else meters.roundToInt().toString()

    /** « et 1 autre objet » / « et 3 autres objets ». */
    private fun others(count: Int): String =
        if (count == 1) "et 1 autre objet" else "et $count autres objets"
}