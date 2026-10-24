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
        )
    }

    override suspend fun save(s: Settings) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_LLM_BASE_URL, s.llmBaseUrl)
            .putString(KEY_LLM_API_KEY, s.llmApiKey)
            .putString(KEY_LLM_MODEL, s.llmModel)
            .putString(KEY_SAM_BASE_URL, s.samBaseUrl)
            .apply()
    }

    companion object {
        const val KEY_LLM_BASE_URL = "llm_base_url"
        const val KEY_LLM_API_KEY = "llm_api_key"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_SAM_BASE_URL = "sam_base_url"

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
