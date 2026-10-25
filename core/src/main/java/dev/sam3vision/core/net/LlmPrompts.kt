package dev.sam3vision.core.net

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Consignes envoyées au LLM, **par langue de voix** ([SpeechLanguage]).
 *
 * La langue compte deux fois : le texte affiché et, surtout, ce que la personne nous
 * **dit** — la réponse est lue telle quelle par ElevenLabs. Un modèle qui répond en
 * français avec une voix anglaise, ou l'inverse, s'entend tout de suite.
 *
 * Les clés JSON de [describeSystem] ne changent jamais : `parseAnalysis` les lit.
 */
internal object LlmPrompts {

    /** Caractères de la description précédente repris dans la consigne du direct. */
    private const val MAX_PREVIOUS_CHARS = 300

    /** Règle de sortie de la description : même schéma dans les deux langues. */
    private const val JSON_RULE = "Reply STRICTLY in JSON, with no surrounding text: " +
        "{\"description\": \"...\", \"tags\": [\"...\"]}"

    /** Consigne système de la description de photo. */
    fun describeSystem(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr ->
            "Tu es un assistant vision. Tu décris une photo en français. Réponds STRICTEMENT " +
                "en JSON, sans texte autour : {\"description\": \"...\", \"tags\": [\"...\"]}"
        SpeechLanguage.En ->
            "You are a vision assistant and you describe a photo aloud for a blind user. " +
                "Describe it in English. $JSON_RULE"
    }

    /** Question jointe à l'image pour la description (avec ou sans objets déjà détectés). */
    fun describeUser(labels: List<String>, language: SpeechLanguage): String {
        val detected = labels.joinToString(", ")
        return when (language) {
            SpeechLanguage.Fr -> if (labels.isEmpty()) {
                "Décris cette image."
            } else {
                "Objets déjà détectés : $detected. Décris cette image."
            }
            SpeechLanguage.En -> if (labels.isEmpty()) {
                "Describe this image."
            } else {
                "Objects already detected: $detected. Describe this image."
            }
        }
    }

    /** Narration continue du direct : deux phrases, au présent. */
    fun liveSystem(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr ->
            "Tu observes un flux vidéo en direct et tu décris ce que tu vois, en français. " +
                "Réponds en deux phrases maximum, au présent, sans liste, sans markdown. " +
                "Si une description précédente est fournie, commence par ce qui a changé."
        SpeechLanguage.En ->
            "You watch a live video feed and you describe what you see in English. " +
                "Answer in two sentences at most, in the present tense, no lists, no markdown. " +
                "If a previous description is provided, start with what changed."
    }

    /** Consigne de la frame du direct : objets suivis, puis continuité avec la description précédente. */
    fun liveUser(labels: List<String>, previous: String, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> liveLine(
            "Décris cette frame du direct.",
            "Objets suivis",
            "Description précédente",
            labels,
            previous,
        )
        SpeechLanguage.En -> liveLine(
            "Describe this live frame.",
            "Tracked objects",
            "Previous description",
            labels,
            previous,
        )
    }

    /** Corps commun des deux langues : la phrase de base, puis les deux ajouts optionnels. */
    private fun liveLine(base: String, objects: String, previousLabel: String, labels: List<String>, previous: String): String =
        buildString {
            append(base)
            if (labels.isNotEmpty()) append(' ').append(objects).append(": ").append(labels.joinToString(", ")).append('.')
            if (previous.isNotBlank()) {
                append(' ').append(previousLabel).append(": \"").append(previous.take(MAX_PREVIOUS_CHARS)).append("\".")
            }
        }

    /** Question simple sur la photo courante. */
    fun askSystem(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Tu réponds en français, en quelques phrases, à partir de la photo fournie."
        SpeechLanguage.En -> "You answer in English, in a few sentences, based on the photo provided."
    }

    /** Question sur ce que la caméra a vu (journal joint en contexte). */
    fun askMemorySystem(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr ->
            "Tu es l'assistant mémoire d'une caméra. Tu réponds en français, en quelques phrases. " +
                "La photo montre l'instant présent ; le contexte joint liste ce que la caméra a vu " +
                "les secondes précédentes, avec la position et l'heure de chaque objet. " +
                "Pour une question sur le passé (« il y a 5 s »), réponds d'après ce contexte. " +
                "Si le contexte ne dit rien de ce qui est demandé, dis-le franchement."
        SpeechLanguage.En ->
            "You are a camera memory assistant. You answer in English, in a few sentences. " +
                "The photo shows the present moment; the attached context lists what the camera " +
                "saw in the previous seconds, with the position and time of each object. " +
                "For a question about the past (\"5 seconds ago\"), answer from that context. " +
                "If the context says nothing about what is asked, say so plainly."
    }

    /** En-tête du journal caméra joint à la question de mémoire. */
    fun contextHeader(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Contexte observé par la caméra :"
        SpeechLanguage.En -> "Context observed by the camera:"
    }
}