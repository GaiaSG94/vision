package dev.sam3vision.core.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plan B si security-crypto est indisponible : fichier privé (`filesDir`) chiffré
 * maison en AES-256/GCM, clé générée dans l'Android Keystore (non exportable).
 * Format : [IV 12 octets][ciphertext].
 */
class PrivateFileSettingsStore(private val file: File) : SettingsStore {

    override suspend fun load(): Settings = withContext(Dispatchers.IO) { read() }

    override suspend fun save(s: Settings) = withContext(Dispatchers.IO) {
        write(s)
    }

    private fun read(): Settings {
        if (!file.exists()) return Settings.Default
        val plain = KeystoreCrypto.decrypt(file.readBytes()) ?: return Settings.Default
        return SettingsCodec.decode(plain.toString(Charsets.UTF_8))
    }

    private fun write(s: Settings) {
        file.parentFile?.mkdirs()
        val bytes = KeystoreCrypto.encrypt(SettingsCodec.encode(s).toByteArray(Charsets.UTF_8))
        file.writeBytes(bytes)
    }
}

/** Sérialisation `clé=valeur`, une par ligne (pas de dépendance, un seul format). */
internal object SettingsCodec {
    private const val K_LLM_BASE_URL = "llmBaseUrl"
    private const val K_LLM_API_KEY = "llmApiKey"
    private const val K_LLM_MODEL = "llmModel"
    private const val K_SAM_BASE_URL = "samBaseUrl"
    private const val K_ASR_BASE_URL = "asrBaseUrl"
    private const val K_ASR_API_KEY = "asrApiKey"
    private const val K_ASR_MODEL = "asrModel"

    fun encode(s: Settings): String = buildString {
        appendLine("$K_LLM_BASE_URL=${s.llmBaseUrl}")
        appendLine("$K_LLM_API_KEY=${s.llmApiKey}")
        appendLine("$K_LLM_MODEL=${s.llmModel}")
        appendLine("$K_SAM_BASE_URL=${s.samBaseUrl}")
        appendLine("$K_ASR_BASE_URL=${s.asrBaseUrl}")
        appendLine("$K_ASR_API_KEY=${s.asrApiKey}")
        appendLine("$K_ASR_MODEL=${s.asrModel}")
    }

    fun decode(text: String): Settings {
        val values = text.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=') }
        val default = Settings.Default
        return Settings(
            llmBaseUrl = values[K_LLM_BASE_URL].orEmpty().ifBlank { default.llmBaseUrl },
            llmApiKey = values[K_LLM_API_KEY].orEmpty(),
            llmModel = values[K_LLM_MODEL].orEmpty().ifBlank { default.llmModel },
            samBaseUrl = values[K_SAM_BASE_URL].orEmpty(),
            asrBaseUrl = values[K_ASR_BASE_URL].orEmpty().ifBlank { default.asrBaseUrl },
            asrApiKey = values[K_ASR_API_KEY].orEmpty(),
            asrModel = values[K_ASR_MODEL].orEmpty().ifBlank { default.asrModel },
        )
    }
}

/** AES/GCM adossé à l'Android Keystore (clé non exportable, alias dédié). */
internal object KeystoreCrypto {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "sam3vision_settings_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray? = try {
        if (data.size <= IV_SIZE) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE),
            )
            cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
        }
    } catch (e: Exception) {
        null
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
