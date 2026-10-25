package dev.sam3vision.core.model

/**
 * Bornes partagées par les deux modes d'affichage (photo et direct).
 *
 * Une seule source de vérité : le plafond est appliqué côté client (photo, après
 * `/segment`) et transmis au serveur (direct, champ `max_objects` de `/live`),
 * qui élague en plus les scores les plus faibles.
 */
object ObjectLimits {
    /** Nombre maximum d'objets segmentés gardés à l'écran. */
    const val MAX_ON_SCREEN = 4
}
