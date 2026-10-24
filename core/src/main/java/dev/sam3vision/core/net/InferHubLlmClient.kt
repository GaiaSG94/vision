package dev.sam3vision.core.net

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_TOKENS = 800
private const val TEMPERATURE = 0.2

private val DESCRIBE_SYSTEM =
    "Tu es un assistant vision. Tu décris une photo en français. " +
        "Réponds STRICTEMENT en JSON, sans texte autour : " +
        "{\"description\": \"...\", \"tags\": [\"...\"]}"

private val ASK_SYSTEM =
    "Tu réponds en français, en quelques phrases, à partir de la photo fournie."

/**
 * Implémentation OkHttp d'un endpoint compatible OpenAI (`POST {baseUrl}/chat/completions`).
 * Aucune clé en dur : [apiKey] vient des réglages. Erreurs typées : 401 / 429 / timeout / réseau.
 */
class InferHubLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = defaultHttpClient(),
    private val maxHistoryTurns: Int = 20,
) : LlmClient {

    override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> {
        val prompt = if (labels.isEmpty()) {
            "Décris cette image."
        } else {
            "Objets déjà détectés : ${labels.joinToString(", ")}. Décris cette image."
        }
        val messages = JSONArray().put(system(DESCRIBE_SYSTEM)).put(userWithImage(prompt, jpegB64))
        return postChat(messages).mapCatching { content -> parseAnalysis(content, labels) }
    }

    override suspend fun ask(
        jpegB64: String,
        question: String,
        history: List<ChatTurn>,
    ): Result<String> {
        val messages = JSONArray().put(system(ASK_SYSTEM))
        history.takeLast(maxHistoryTurns).forEach { messages.put(role(it.role, it.content)) }
        messages.put(userWithImage(question, jpegB64))
        return postChat(messages).map { it.trim() }
    }

    /** Un seul point de sortie HTTP pour les deux méthodes : corps, en-têtes, extraction du texte. */
    private suspend fun postChat(messages: JSONArray): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("clé API absente — renseigne-la dans Réglages"))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", MAX_TOKENS)
            .toString()
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return client.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { extractContent(raw) } }
    }

    private fun extractContent(raw: String): String {
        val json = parse(raw) ?: throw ApiError.BadResponse("corps de réponse illisible")
        val choices = json.optJSONArray("choices") ?: throw ApiError.BadResponse("pas de choices")
        val first = choices.optJSONObject(0) ?: throw ApiError.BadResponse("choices vide")
        val content = first.optJSONObject("message").str("content")
        if (content.isBlank()) throw ApiError.BadResponse("contenu vide")
        return content
    }
}

private fun system(text: String): JSONObject =
    JSONObject().put("role", "system").put("content", text)

private fun role(role: Role, text: String): JSONObject =
    JSONObject().put("role", if (role == Role.USER) "user" else "assistant").put("content", text)

private fun userWithImage(text: String, jpegB64: String): JSONObject = JSONObject()
    .put("role", "user")
    .put(
        "content",
        JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")),
            ),
    )

internal fun parse(raw: String): JSONObject? = try {
    JSONObject(raw)
} catch (e: Exception) {
    null
}

/**
 * Tolérant : JSON attendu `{"description","tags"}` ; si le modèle renvoie du texte
 * libre, on garde ce texte en description et les labels segmentés en tags.
 */
internal fun parseAnalysis(content: String, labels: List<String>): Analysis {
    val json = extractFirstJsonObject(content)?.let { parse(it) }
        ?: return Analysis(content.trim(), labels, emptyList())
    val description = json.str("description").ifBlank { content.trim() }
    val tags = json.strings("tags").ifEmpty { labels }
    return Analysis(description, tags, emptyList())
}
