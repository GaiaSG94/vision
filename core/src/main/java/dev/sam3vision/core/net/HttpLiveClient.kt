package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.SegObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** /live est plus court que /segment (60 s) : délai dédié de 10 s. */
const val LIVE_TIMEOUT_SECONDS: Long = 10L

/**
 * `/health` sert aussi de bouton « démarre la GPU » : la requête qui réveille un
 * conteneur Modal froid attend le démarrage (image + poids ≈ 30-50 s mesurés, jusqu'à
 * ~150 s si l'image doit être reconstruite). Avec le délai de 10 s du direct, chaque
 * réveil échouait avant la fin du démarrage : délai patient dédié.
 */
const val WAKE_TIMEOUT_SECONDS: Long = 180L

/** User-Agent propre au flux live, identifiable côté backend. */
internal const val LIVE_USER_AGENT = "$USER_AGENT live"

/**
 * Client de l'endpoint `/live` du direct (backend Modal `sam3-live`, contrat docs/LIVE-SPEC.md).
 * Réutilise la fabrique unique de clients HTTP de `:core` avec un délai de 10 s,
 * le parsing de `Json.kt` et les erreurs typées de `Errors.kt`.
 */
class HttpLiveClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(LIVE_TIMEOUT_SECONDS),
    private val wakeClient: OkHttpClient = defaultHttpClient(WAKE_TIMEOUT_SECONDS),
) : LiveClient {

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        val body = JSONObject()
            .put("image_b64", jpegB64)
            .put("prompts", if (prompts == null) JSONObject.NULL else jsonArrayOf(prompts))
            .put("work_edge", workEdge)
            .put("max_objects", maxObjects)
            .toString()
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "/live"))
            .header("User-Agent", LIVE_USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return client.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseLive(raw) } }
    }

    override suspend fun health(): Result<LiveHealth> {
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "/health"))
            .header("User-Agent", LIVE_USER_AGENT)
            .get()
            .build()
        return wakeClient.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseHealth(raw) } }
    }
}

private fun parseLive(raw: String): LiveFrame {
    val json = parse(raw) ?: throw ApiError.BadResponse("/live : JSON illisible")
    val objects = json.objects("objects").map { obj ->
        val fields = obj.maskedFields()
        SegObject(
            label = fields.label,
            score = fields.score,
            box = fields.box,
            maskPngB64 = fields.maskB64,
        )
    }
    return LiveFrame(
        width = json.intOr("width"),
        height = json.intOr("height"),
        elapsedMs = json.intOr("elapsed_ms").toLong(),
        objects = objects,
    )
}

private fun parseHealth(raw: String): LiveHealth {
    val json = parse(raw) ?: throw ApiError.BadResponse("/health : JSON illisible")
    return LiveHealth(
        ok = json.optBoolean("ok", false),
        model = json.str("model"),
        device = json.str("device"),
        gpu = json.str("gpu"),
    )
}
