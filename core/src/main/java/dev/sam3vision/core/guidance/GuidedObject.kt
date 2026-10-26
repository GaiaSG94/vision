package dev.sam3vision.core.guidance

import dev.sam3vision.core.model.Detection

/**
 * Un objet mesuré, ramené dans le repère de la marche : **l'axe optique** est la
 * direction voulue (la personne filme là où elle veut aller).
 *
 * [lateralMeters] est signé, positif à droite de l'axe. [widthMeters] est la largeur
 * transverse occupée par l'objet à sa distance : c'est elle qui dit si l'objet ferme
 * le passage ou si on peut le frôler.
 */
data class GuidedObject(
    val label: String,
    val meters: Float,
    val lateralMeters: Float,
    val widthMeters: Float,
) {
    /** Bord gauche de l'objet, en mètres (négatif = à gauche de l'axe). */
    val leftMeters: Float get() = lateralMeters - widthMeters / 2f

    /** Bord droit de l'objet, en mètres. */
    val rightMeters: Float get() = lateralMeters + widthMeters / 2f
}

/**
 * Géométrie de la marche, à partir de ce que SAM renvoie **déjà** : la boîte en pixels
 * de l'image analysée et la distance sténopé mesurée sur le téléphone.
 *
 * Rien de nouveau n'est demandé au serveur. Le sténopé est linéaire en angle, donc :
 * - latéral : `x = d · (cx − W/2) / f`
 * - largeur : `L = d · largeur_px / f`
 *
 * où `f` est la focale en pixels de l'image analysée ([dev.sam3vision.core.distance.LensSpec]).
 * Fonctions pures, testées en JVM.
 */
object GuidanceGeometry {

    /** Largeur transverse au-delà de laquelle la boîte déborde (une baie, un mur). */
    const val MAX_WIDTH_METERS = 8f

    /** Largeur plancher : une boîte de quelques pixels ne mesure pas un centimètre. */
    const val MIN_WIDTH_METERS = 0.05f

    /** Relevé latéral en mètres : positif à droite de l'axe optique. */
    fun lateralMeters(centerXPx: Float, imageWidth: Int, focalPx: Float, meters: Float): Float? {
        if (imageWidth <= 0 || focalPx <= 0f) return null
        if (!meters.isFinite() || meters <= 0f) return null
        return meters * (centerXPx - imageWidth / 2f) / focalPx
    }

    /** Largeur transverse en mètres d'une boîte de [widthPx] pixels à [meters]. */
    fun widthMeters(widthPx: Float, focalPx: Float, meters: Float): Float? {
        if (focalPx <= 0f || widthPx <= 0f || !meters.isFinite() || meters <= 0f) return null
        return (meters * widthPx / focalPx).coerceIn(MIN_WIDTH_METERS, MAX_WIDTH_METERS)
    }

    /** Un objet mesuré, prêt pour le corridor ; `null` si la boîte est illisible. */
    fun of(detection: Detection, meters: Float, focalPx: Float): GuidedObject? {
        val box = detection.box
        if (box.size < 4 || detection.label.isBlank()) return null
        val widthPx = box[2] - box[0]
        if (widthPx <= 0f) return null
        val centerX = (box[0] + box[2]) / 2f
        val lateral = lateralMeters(centerX, detection.width, focalPx, meters) ?: return null
        val width = widthMeters(widthPx, focalPx, meters) ?: return null
        return GuidedObject(detection.label.trim(), meters, lateral, width)
    }

    /**
     * Toute la scène : les détections dont la distance est connue. [metersByBox] vient
     * des lectures de [dev.sam3vision.core.distance.DistanceMeter] — la boîte identifie
     * l'objet, comme pour les pastilles.
     */
    fun scene(
        detections: List<Detection>,
        metersByBox: Map<List<Float>, Float>,
        focalPx: Float,
    ): List<GuidedObject> = detections.mapNotNull { detection ->
        metersByBox[detection.box]?.let { meters -> of(detection, meters, focalPx) }
    }
}
