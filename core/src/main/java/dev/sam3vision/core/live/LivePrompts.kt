package dev.sam3vision.core.live

/**
 * Prompts du direct : concepts suivis par SAM 3.1, liste courte modifiable depuis l'UI.
 * [normalize] assainit avant l'envoi au backend (`/live` borne de toute façon à 12 objets).
 */
object LivePrompts {

    /**
     * Concepts par défaut : **huit**, les plus utiles au guidage à l'intérieur,
     * modifiables à chaud dans le direct (champ « Suivre »).
     *
     * Une version antérieure les avait réduits à deux (« person », « door ») parce que
     * chaque concept semblait coûter ~4 ms de serveur. Ce n'est plus vrai : l'app
     * déployée batche les prompts (`/health` → `levers.batch_prompts = true`) et
     * **mesure du 2026-10-25 sur `/live` (5 tirs par point, même image, 4 objets max) :
     * 2 concepts = 118 ms médian, 8 concepts = 118 ms médian**. Huit concepts sont
     * gratuits : autant tout suivre.
     */
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
