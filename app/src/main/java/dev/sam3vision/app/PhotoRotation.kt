package dev.sam3vision.app

/**
 * Boîte `x1,y1,x2,y2` d'une image `[width]`×`[height]` après un quart de tour horaire.
 * Pure : même rotation que `uprightScaled(…, 90)`, appliquée aux coordonnées.
 */
fun rotateBoxClockwise(box: List<Float>, width: Int, height: Int): List<Float> {
    if (box.size < 4 || width <= 0 || height <= 0) return box
    return listOf(height - box[3], box[0], height - box[1], box[2])
}

/**
 * La même image après un quart de tour horaire : largeur et hauteur échangées, boîtes
 * tournées.
 *
 * Sert à la photo du double tap : quand elle est alignée sur l'orientation de l'écran
 * (`decodeForScreen`), les pastilles de distance doivent tourner avec elle, sinon leur
 * fil ne désigne plus le bon objet.
 */
fun DistanceLayer.rotatedClockwise(): DistanceLayer = copy(
    width = height,
    height = width,
    readings = readings.map { it.copy(box = rotateBoxClockwise(it.box, width, height)) },
)
