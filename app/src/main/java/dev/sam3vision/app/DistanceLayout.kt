package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText
import kotlin.math.abs

/**
 * Cadrage `ContentScale.Crop` : une seule échelle, puis un décalage de centrage.
 * Reproduire ce calcul côté Compose garantit que les étiquettes de distance
 * tombent sur les mêmes pixels que les masques, quel que soit l'écran.
 */
data class CropTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val boxWidth: Float = 0f,
    val boxHeight: Float = 0f,
) {

    /** Abscisse écran d'un point de l'image analysée. */
    fun screenX(frameX: Float): Float = frameX * scale + offsetX

    /** Ordonnée écran d'un point de l'image analysée. */
    fun screenY(frameY: Float): Float = frameY * scale + offsetY
}

/**
 * Transformation d'une image `[imageWidth]`×`[imageHeight]` dans un cadre
 * `[boxWidth]`×`[boxHeight]` en `Crop` : l'image remplit le cadre en gardant son
 * rapport et déborde, centrée. Cadre ou image dégénéré : transformation neutre.
 */
fun cropTransform(imageWidth: Int, imageHeight: Int, boxWidth: Float, boxHeight: Float): CropTransform {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
        return CropTransform(1f, 0f, 0f)
    }
    val scale = maxOf(boxWidth / imageWidth, boxHeight / imageHeight)
    return CropTransform(
        scale = scale,
        offsetX = (boxWidth - imageWidth * scale) / 2f,
        offsetY = (boxHeight - imageHeight * scale) / 2f,
        boxWidth = boxWidth,
        boxHeight = boxHeight,
    )
}

/**
 * Étiquette de distance prête à poser à l'écran (pixels) : ancrée au **milieu
 * horizontal** de la boîte et juste au-dessus d'elle. Centrer plutôt qu'aligner à
 * gauche évite l'empilement de plusieurs pastilles sur le bord de l'image (constaté
 * le 25/10 quand plusieurs objets touchent le bord gauche).
 */
data class ScreenLabel(val text: String, val x: Float, val y: Float, val colorIndex: Int)

/**
 * Décalage horizontal à appliquer à une pastille de [pillWidth] px, centrée sur
 * [anchorX], pour qu'elle reste **entièrement** dans un cadre de [boxWidth] : une
 * pastille posée sur un objet collé au bord sortait à moitié de l'écran (constaté
 * le 25/10 sur le bord droit).
 */
fun pillTranslationX(anchorX: Float, pillWidth: Float, boxWidth: Float): Float {
    val centered = -pillWidth / 2f
    val left = anchorX + centered
    return when {
        pillWidth >= boxWidth -> (boxWidth - pillWidth) / 2f - anchorX
        left < 0f -> centered - left
        left + pillWidth > boxWidth -> centered - (left + pillWidth - boxWidth)
        else -> centered
    }
}

/**
 * Décalage vertical pour qu'une pastille ancrée en [anchorY] ne passe pas sous le
 * bandeau du haut ([topInset] px) : le texte restait caché derrière les boutons
 * « Arrêter / Réglages » pour un objet dans le haut de l'image.
 */
fun pillTranslationY(anchorY: Float, topInset: Float): Float =
    (topInset - anchorY).coerceAtLeast(0f)

/**
 * Étiquettes des objets mesurés de [layer], dans l'ordre des objets de la frame
 * (la couleur suit donc le masque). Une boîte illisible est ignorée.
 *
 * [withLabel] préfixe la pastille du concept (« personne ≈ 3,9 m ») : les hôtes
 * sans masques — la fenêtre iSee — n'ont que ce texte pour savoir de quel objet
 * elle parle.
 */
fun screenLabels(
    layer: DistanceLayer,
    transform: CropTransform,
    withLabel: Boolean = false,
): List<ScreenLabel> = spreadLabels(
    layer.readings.mapIndexedNotNull { index, reading ->
        if (reading.box.size < 4) return@mapIndexedNotNull null
        val centerX = (reading.box[0] + reading.box[2]) / 2f
        ScreenLabel(
            text = labelText(reading, withLabel),
            x = transform.screenX(centerX).coerceIn(0f, transform.boxWidth),
            y = transform.screenY(reading.box[1]),
            colorIndex = index,
        )
    },
)

/** Écart horizontal (px écran) en deçà duquel deux pastilles se chevauchent. */
const val PILL_MIN_X_GAP = 150f

/** Hauteur de décalage (px écran) quand une pastille en cache une autre. */
const val PILL_MIN_Y_GAP = 76f

/**
 * Deux objets voisins donnaient deux pastilles posées au même endroit : la seconde
 * était illisible. On décale vers le bas celles qui tombent dans la zone d'une
 * pastille déjà posée — l'ordre des masques est conservé, la couleur suit l'objet.
 * Fonction pure, testée en JVM.
 */
fun spreadLabels(labels: List<ScreenLabel>): List<ScreenLabel> {
    val placed = ArrayList<ScreenLabel>(labels.size)
    for (label in labels) {
        var y = label.y
        var guard = 0
        while (guard++ < labels.size && placed.any { collides(it, label.x, y) }) y += PILL_MIN_Y_GAP
        placed += label.copy(y = y)
    }
    return placed
}

/** Vrai si une pastille de [otherX]/[y] tombe dans la zone déjà occupée par [placed]. */
private fun collides(placed: ScreenLabel, otherX: Float, y: Float): Boolean =
    abs(placed.x - otherX) < PILL_MIN_X_GAP && abs(placed.y - y) < PILL_MIN_Y_GAP


/** Texte d'une pastille : la distance seule, ou le concept borné puis la distance. */
fun labelText(reading: DistanceReading, withLabel: Boolean): String {
    val distance = DistanceText.approximate(reading.meters)
    if (!withLabel) return distance
    val concept = reading.label.trim().take(MAX_CONCEPT_CHARS).ifEmpty { "objet" }
    return "$concept $distance"
}

/** Longueur maximale du concept dans une pastille : au-delà, la pastille déborde. */
private const val MAX_CONCEPT_CHARS = 14
