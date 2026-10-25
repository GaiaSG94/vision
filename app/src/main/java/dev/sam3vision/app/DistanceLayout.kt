package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceText

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
 */
fun screenLabels(layer: DistanceLayer, transform: CropTransform): List<ScreenLabel> =
    layer.readings.mapIndexedNotNull { index, reading ->
        if (reading.box.size < 4) return@mapIndexedNotNull null
        val centerX = (reading.box[0] + reading.box[2]) / 2f
        ScreenLabel(
            text = DistanceText.approximate(reading.meters),
            x = transform.screenX(centerX).coerceIn(0f, transform.boxWidth),
            y = transform.screenY(reading.box[1]),
            colorIndex = index,
        )
    }