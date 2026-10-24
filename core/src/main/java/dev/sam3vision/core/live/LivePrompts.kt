package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`/live` borne de toute façon à 12 objets).
 */
object LivePrompts {

    /** Concepts par défaut, ceux que le téléphone cherche en continu. */
    val Default: List<String> = listOf("person", "chair", "table", "laptop", "bottle", "cup", "bag", "door")

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
