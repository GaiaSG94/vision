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
    /** Dictée Meta (muse-voice-transcribe-1.0) : endpoint et clé distincts du LLM. */
    val asrBaseUrl: String = DEFAULT_ASR_BASE_URL,
    val asrApiKey: String = "",
    val asrModel: String = DEFAULT_ASR_MODEL,
) {
    companion object {
        const val DEFAULT_LLM_BASE_URL = "https://api.inferhub.dev/v1"
        const val DEFAULT_LLM_MODEL = "ali/qwen3.8-omni-flash"

        /** API Meta (non compatible OpenAI) : la route est `{base}/asr/transcribe`. */
        const val DEFAULT_ASR_BASE_URL = "https://api.meta.ai/v1"
        const val DEFAULT_ASR_MODEL = "muse-voice-transcribe-1.0"

        /** Voix ElevenLabs de départ ; modifiable dans Réglages. */
        const val DEFAULT_TTS_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"

        val Default = Settings(
            llmBaseUrl = DEFAULT_LLM_BASE_URL,
            llmApiKey = "",
            llmModel = DEFAULT_LLM_MODEL,
            samBaseUrl = "",
            ttsApiKey = "",
            ttsVoiceId = DEFAULT_TTS_VOICE_ID,
            asrBaseUrl = DEFAULT_ASR_BASE_URL,
            asrApiKey = "",
            asrModel = DEFAULT_ASR_MODEL,
        )
    }

    /** Vrai si un endpoint SAM est renseigné. */
    val segmentationEnabled: Boolean get() = samBaseUrl.isNotBlank()

    /** Vrai si l'app peut appeler le LLM. */
    val llmConfigured: Boolean get() = llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank()

    /** Vrai si l'app peut appeler ElevenLabs (clé + voix renseignées). */
    val ttsConfigured: Boolean get() = ttsApiKey.isNotBlank() && ttsVoiceId.isNotBlank()

    /** Vrai si la dictée Meta est utilisable (endpoint + clé renseignés). */
    val asrConfigured: Boolean get() = asrBaseUrl.isNotBlank() && asrApiKey.isNotBlank()
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
