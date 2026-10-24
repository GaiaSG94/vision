package dev.sam3vision.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "sam3vision_secure_settings"

/**
 * Stockage chiffré (Jetpack security-crypto : AES256-SIV pour les clés,
 * AES256-GCM pour les valeurs, maître dans le Keystore matériel).
 */
class EncryptedSettingsStore(context: Context) : SettingsStore {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { createPrefs(appContext) }

    override suspend fun load(): Settings = withContext(Dispatchers.IO) {
        val default = Settings.Default
        Settings(
            llmBaseUrl = prefs.getString(KEY_LLM_BASE_URL, default.llmBaseUrl).orEmpty()
                .ifBlank { default.llmBaseUrl },
            llmApiKey = prefs.getString(KEY_LLM_API_KEY, default.llmApiKey).orEmpty(),
            llmModel = prefs.getString(KEY_LLM_MODEL, default.llmModel).orEmpty()
                .ifBlank { default.llmModel },
            samBaseUrl = prefs.getString(KEY_SAM_BASE_URL, default.samBaseUrl).orEmpty(),
            ttsApiKey = prefs.getString(KEY_TTS_API_KEY, default.ttsApiKey).orEmpty(),
            ttsVoiceId = prefs.getString(KEY_TTS_VOICE_ID, default.ttsVoiceId).orEmpty()
                .ifBlank { default.ttsVoiceId },
            ttsAutoplay = prefs.getBoolean(KEY_TTS_AUTOPLAY, default.ttsAutoplay),
            // `.ifBlank` : une valeur vide persistée par une version antérieure retombe sur
            // le défaut au lieu de laisser un endpoint invalide (origin/master, commit f77c61c).
            asrBaseUrl = prefs.getString(KEY_ASR_BASE_URL, default.asrBaseUrl).orEmpty()
                .ifBlank { default.asrBaseUrl },
            asrApiKey = prefs.getString(KEY_ASR_API_KEY, default.asrApiKey).orEmpty(),
            asrModel = prefs.getString(KEY_ASR_MODEL, default.asrModel).orEmpty()
                .ifBlank { default.asrModel },
        )
    }

    override suspend fun save(s: Settings) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_LLM_BASE_URL, s.llmBaseUrl)
            .putString(KEY_LLM_API_KEY, s.llmApiKey)
            .putString(KEY_LLM_MODEL, s.llmModel)
            .putString(KEY_SAM_BASE_URL, s.samBaseUrl)
            .putString(KEY_TTS_API_KEY, s.ttsApiKey)
            .putString(KEY_TTS_VOICE_ID, s.ttsVoiceId)
            .putBoolean(KEY_TTS_AUTOPLAY, s.ttsAutoplay)
            .putString(KEY_ASR_BASE_URL, s.asrBaseUrl)
            .putString(KEY_ASR_API_KEY, s.asrApiKey)
            .putString(KEY_ASR_MODEL, s.asrModel)
            .apply()
    }

    companion object {
        const val KEY_LLM_BASE_URL = "llm_base_url"
        const val KEY_LLM_API_KEY = "llm_api_key"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_SAM_BASE_URL = "sam_base_url"
        const val KEY_TTS_API_KEY = "tts_api_key"
        const val KEY_TTS_VOICE_ID = "tts_voice_id"
        const val KEY_TTS_AUTOPLAY = "tts_autoplay"
        const val KEY_ASR_BASE_URL = "asr_base_url"
        const val KEY_ASR_API_KEY = "asr_api_key"
        const val KEY_ASR_MODEL = "asr_model"

        /** Lève si le Keystore est indisponible : le factory retombe alors sur un fichier chiffré. */
        internal fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
