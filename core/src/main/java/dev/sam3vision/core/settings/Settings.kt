package dev.sam3vision.core.settings

/**
 * Réglages de l'app. [samBaseUrl] vide = segmentation désactivée (la photo part
 * directement au LLM). Aucune valeur de clé n'est jamais écrite en dur.
 */
data class Settings(
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val samBaseUrl: String,
    val ttsApiKey: String = "",
    val ttsVoiceId: String = DEFAULT_TTS_VOICE_ID,
) {
    companion object {
        const val DEFAULT_LLM_BASE_URL = "https://api.inferhub.dev/v1"
        const val DEFAULT_LLM_MODEL = "ali/qwen3.8-omni-flash"

        /** Voix ElevenLabs de départ ; modifiable dans Réglages. */
        const val DEFAULT_TTS_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"

        val Default = Settings(
            llmBaseUrl = DEFAULT_LLM_BASE_URL,
            llmApiKey = "",
            llmModel = DEFAULT_LLM_MODEL,
            samBaseUrl = "",
            ttsApiKey = "",
            ttsVoiceId = DEFAULT_TTS_VOICE_ID,
        )
    }

    /** Vrai si un endpoint SAM est renseigné. */
    val segmentationEnabled: Boolean get() = samBaseUrl.isNotBlank()

    /** Vrai si l'app peut appeler le LLM. */
    val llmConfigured: Boolean get() = llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank()

    /** Vrai si l'app peut appeler ElevenLabs (clé + voix renseignées). */
    val ttsConfigured: Boolean get() = ttsApiKey.isNotBlank() && ttsVoiceId.isNotBlank()
}

interface SettingsStore {
    suspend fun load(): Settings
    suspend fun save(s: Settings)
}

/** Implémentation en mémoire (tests, previews Compose). */
class MemorySettingsStore(initial: Settings = Settings.Default) : SettingsStore {
    private var current: Settings = initial

    override suspend fun load(): Settings = current

    override suspend fun save(s: Settings) {
        current = s
    }
}
