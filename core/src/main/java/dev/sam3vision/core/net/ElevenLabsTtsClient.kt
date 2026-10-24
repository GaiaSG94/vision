package dev.sam3vision.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Format de sortie demandé à ElevenLabs : MP3 44,1 kHz / 128 kbit/s. */
private const val OUTPUT_FORMAT = "mp3_44100_128"

/** Taille maximale inspectée pour deviner si le corps est de l'audio ou du texte. */
private const val SNIFF_BYTES = 4_096

/**
 * Synthèse vocale ElevenLabs (`POST {baseUrl}/text-to-speech/{voiceId}`).
 * La clé vient des réglages, jamais du code. Le corps de la réponse est de l'audio brut
 * (MP3), d'où [OkHttpClient.executeBytes] plutôt que [OkHttpClient.execute].
 *
 * Erreurs typées : 401 → [ApiError.InvalidKey], 402 (compte sans crédits) →
 * [ApiError.PaymentRequired], 429 → [ApiError.RateLimited], le reste → [ApiError.Http].
 * Un texte vide ou une clé absente échoue sans aucun appel réseau.
 */
class ElevenLabsTtsClient(
    private val apiKey: String,
    private val voiceId: String,
    private val baseUrl: String = DEFAULT_TTS_BASE_URL,
    private val modelId: String = DEFAULT_TTS_MODEL,
    private val http: OkHttpClient = defaultHttpClient(),
) : TtsClient {

    override suspend fun synthesize(text: String): Result<ByteArray> {
        val refusal = localRefusal(text)?.let { Result.failure<ByteArray>(it) }
        if (refusal != null) return refusal
        return catchingResult { buildRequest(text) }
            .then { http.executeBytes(it) }
            .then(::ensureAudio)
    }

    /** Refus locaux, avant tout appel réseau : message déjà affichable dans l'UI. */
    private fun localRefusal(text: String): ApiError? = when {
        text.isBlank() -> ApiError.BadResponse("rien à lire : texte vide")
        apiKey.isBlank() -> ApiError.InvalidKey("clé ElevenLabs absente — renseigne-la dans Réglages")
        voiceId.isBlank() -> ApiError.BadResponse("voix ElevenLabs absente — renseigne-la dans Réglages")
        else -> null
    }

    /** Requête complète : URL encodée par OkHttp, clé ElevenLabs et corps JSON. */
    private fun buildRequest(text: String): Request = Request.Builder()
        .url(ttsUrl())
        .header("xi-api-key", apiKey)
        .header("User-Agent", USER_AGENT)
        .post(jsonBody(text).toRequestBody(JSON_MEDIA))
        .build()

    /**
     * `{baseUrl}/text-to-speech/{voiceId}?output_format=mp3_44100_128` : les segments
     * sont ajoutés par [okhttp3.HttpUrl.Builder], qui échappe proprement le voiceId.
     */
    private fun ttsUrl(): String = baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("text-to-speech")
        .addPathSegment(voiceId.trim())
        .addQueryParameter("output_format", OUTPUT_FORMAT)
        .build()
        .toString()

    /** Corps attendu par ElevenLabs, réglages de voix inclus. */
    private fun jsonBody(text: String): String {
        val voiceSettings = JSONObject()
            .put("stability", 0.5)
            .put("similarity_boost", 0.75)
            .put("style", 0.0)
            .put("use_speaker_boost", true)
        return JSONObject()
            .put("text", text)
            .put("model_id", modelId)
            .put("voice_settings", voiceSettings)
            .toString()
    }

    companion object {
        const val DEFAULT_TTS_BASE_URL = "https://api.elevenlabs.io/v1"

        /** Modèle multilingue rapide, accepté en compte free (vérifié le 24/10/2026). */
        const val DEFAULT_TTS_MODEL = "eleven_v4_turbo"
    }
}

/**
 * Un 2xx doit contenir de l'audio. Un corps vide, ou qui commence par `{`, `[` ou `<`
 * (JSON/XML d'erreur renvoyé avec un code 200), est traité comme un échec lisible.
 */
private fun ensureAudio(bytes: ByteArray): Result<ByteArray> =
    if (bytes.isNotEmpty() && !startsWithMarkup(bytes)) {
        Result.success(bytes)
    } else {
        Result.failure(ApiError.BadResponse("audio vide reçu d'ElevenLabs"))
    }

/** Vrai si les premiers octets non blancs annoncent du texte balisé plutôt que de l'audio. */
private fun startsWithMarkup(bytes: ByteArray): Boolean {
    val limit = minOf(bytes.size, SNIFF_BYTES)
    for (i in 0 until limit) {
        val c = bytes[i].toInt() and 0xFF
        if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) continue
        return c == '{'.code || c == '['.code || c == '<'.code
    }
    return true
}