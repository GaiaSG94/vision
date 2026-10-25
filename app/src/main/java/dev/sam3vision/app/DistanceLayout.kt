package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceText

/**
 * Cadrage `ContentScale.Crop` : une seule échelle, puis un décalage de centrage.
 * Reproduire ce calcul côté Compose garantit que les étiquettes de distance
 * tombent sur les mêmes pixels que les masques, quel que soit l'écran.
 */
data class CropTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {

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
    )
}

/** Étiquette de distance prête à poser à l'écran (pixels), ancrée en haut à gauche de la boîte. */
data class ScreenLabel(val text: String, val x: Float, val y: Float, val colorIndex: Int)

/**
 * Étiquettes des objets mesurés de [layer], dans l'ordre des objets de la frame
 * (la couleur suit donc le masque). Une boîte illisible est ignorée.
 */
fun screenLabels(layer: DistanceLayer, transform: CropTransform): List<ScreenLabel> =
    layer.readings.mapIndexedNotNull { index, reading ->
        if (reading.box.size < 4) return@mapIndexedNotNull null
        ScreenLabel(
            text = DistanceText.approximate(reading.meters),
            x = transform.screenX(reading.box[0]),
            y = transform.screenY(reading.box[1]),
            colorIndex = index,
        )
    }