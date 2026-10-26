package dev.sam3vision.core.guidance

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts qui **ouvrent** un passage au lieu de le fermer.
 *
 * Une porte occupe une bonne partie de l'image sans occuper le chemin : annoncer
 * « porte devant, décale à gauche de 90 centimètres » devant la porte qu'on veut passer
 * serait un contresens. Ces concepts sont donc retirés de la scène de guidage — la
 * détection reste affichée (masque, pastille), seule la décision les ignore.
 *
 * La liste est volontairement courte : mieux vaut guider sur un mur que se taire sur un
 * obstacle. Les libellés sont comparés sous leur forme canonique (casse, accents, pluriel).
 */
object OpeningConcepts {

    /** Formes canoniques des concepts ouverts (voir [KnownSizes.canonical]). */
    private val KEYS = setOf(
        "door", "doors", "doorway", "porte", "portes", "portail",
        "window", "windows", "fenetre", "fenetres",
        "exit", "sortie", "sorties", "passage", "ouvert",
    )

    /** Vrai si [label] désigne un passage plutôt qu'un obstacle. */
    fun isOpening(label: String): Boolean = KnownSizes.canonical(label) in KEYS
}
