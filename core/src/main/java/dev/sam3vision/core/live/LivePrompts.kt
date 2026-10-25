package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`/live` borne de toute façon à 12 objets).
 */
object LivePrompts {

    /**
     * Concepts par défaut : **deux seulement**, les plus utiles au guidage (une personne,
     * une issue). Mesuré sur `/live` (A100 eu-west, frame 512 px, 4 objets max) :
     * 2 concepts = 138-140 ms serveur et 6,3 Ko de réponse, 8 concepts = 161-163 ms et
     * 8,3 Ko. Le DETR est batché, donc chaque concept en plus coûte ~4 ms et un masque
     * de plus à télécharger. Modifiable à chaud dans le direct (champ « Suivre »).
     */
    val Default: List<String> = listOf("person", "door")

    /** Nombre maximal de prompts envoyés par frame. */
    const val MAX = 8

    /** Longueur maximale d'un prompt : au-delà, ce n'est plus un concept. */
    const val MAX_LENGTH = 32

    /**
     * Nettoie une liste de prompts : espaces retirés, vides et doublons éliminés,
     * prompts trop longs rejetés, liste bornée à [MAX]. Une liste qui devient vide
     * retombe sur [Default].
     */
    fun normalize(prompts: List<String>): List<String> {
        val cleaned = prompts.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= MAX_LENGTH }
            .distinct()
            .take(MAX)
            .toList()
        return cleaned.ifEmpty { Default }
    }
}
