package dev.sam3vision.core.net

import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.model.TrackHealth
import dev.sam3vision.core.model.TrackSessionStart
import dev.sam3vision.core.model.TrackedObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Une frame du tracker doit répondre vite : garde-fou à 15 s (le direct est en 30 fps). */
const val TRACK_TIMEOUT_SECONDS: Long = 15L

/** User-Agent propre au tracker, identifiable côté backend. */
internal const val TRACK_USER_AGENT = "$USER_AGENT track"

/**
 * Client du tracker vidéo `sam3-track` : une session garde les identités des objets,
 * chaque frame renvoie les mêmes `id` tant que l'objet est suivi.
 *
 * Un 404 sur `/frame` signifie « session inconnue » (backend redémarré, session
 * expirée) : c'est à l'appelant de recréer une session — voir `TrackRunner`.
 */
interface TrackClient {
    suspend fun start(imageB64: String, prompts: List<String>): Result<TrackSessionStart>

    suspend fun frame(sessionId: String, imageB64: String): Result<TrackFrame>

    suspend fun close(sessionId: String): Result<Unit>

    suspend fun health(): Result<TrackHealth>
}

/**
 * Implémentation HTTP réelle, contrat `docs/LIVE-SPEC.md` du backend `sam3-track` :
 * `POST /session`, `POST /frame`, `DELETE /session/{id}`, `GET /health`.
 * Réutilise le client HTTP unique de `:core` (délai 15 s), `Json.kt` et `Errors.kt`.
 */
class HttpTrackClient(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(TRACK_TIMEOUT_SECONDS),
) : TrackClient {

    override suspend fun start(imageB64: String, prompts: List<String>): Result<TrackSessionStart> {
        val body = JSONObject()
            .put("image_b64", imageB64)
            .put("prompts", jsonArrayOf(prompts))
            .toString()
        return post("/session", body).then { raw -> catchingResult { parseSessionStart(raw) } }
    }

    override suspend fun frame(sessionId: String, imageB64: String): Result<TrackFrame> {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("image_b64", imageB64)
            .toString()
        return post("/frame", body).then { raw -> catchingResult { parseFrame(raw) } }
    }

    override suspend fun close(sessionId: String): Result<Unit> {
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "/session/" + sessionId.trim()))
            .header("User-Agent", TRACK_USER_AGENT)
            .delete()
            .build()
        return client.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .map { Unit }
    }

    override suspend fun health(): Result<TrackHealth> {
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "/health"))
            .header("User-Agent", TRACK_USER_AGENT)
            .get()
            .build()
        return client.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { parseTrackHealth(raw) } }
    }

    private suspend fun post(path: String, body: String): Result<String> {
        val request = Request.Builder()
            .url(joinUrl(baseUrl, path))
            .header("User-Agent", TRACK_USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return client.execute(request).then { outcome -> outcome.requireSuccess() }
    }
}

internal fun parseSessionStart(raw: String): TrackSessionStart {
    val json = parse(raw) ?: throw ApiError.BadResponse("/session : JSON illisible")
    val start = TrackSessionStart(
        sessionId = json.str("session_id"),
        width = json.intOr("width"),
        height = json.intOr("height"),
        objects = parseTrackedObjects(json),
    )
    if (start.sessionId.isBlank()) throw ApiError.BadResponse("/session : session_id manquant")
    return start
}

internal fun parseFrame(raw: String): TrackFrame {
    val json = parse(raw) ?: throw ApiError.BadResponse("/frame : JSON illisible")
    return TrackFrame(
        width = json.intOr("width"),
        height = json.intOr("height"),
        objects = parseTrackedObjects(json),
    )
}

internal fun parseTrackHealth(raw: String): TrackHealth {
    val json = parse(raw) ?: throw ApiError.BadResponse("/health : JSON illisible")
    return TrackHealth(
        ok = json.optBoolean("ok", false),
        model = json.str("model"),
        gpu = json.str("gpu"),
        maxObjects = json.intOr("max_objects"),
    )
}

/** `objects` → objets suivis ; l'`id` vient du backend, les autres champs de `Json.kt`. */
private fun parseTrackedObjects(json: JSONObject): List<TrackedObject> =
    json.objects("objects").map { obj ->
        val fields = obj.maskedFields()
        TrackedObject(
            id = obj.intOr("id", NO_TRACK_ID),
            label = fields.label,
            score = fields.score,
            box = fields.box,
            maskPngB64 = fields.maskB64,
        )
    }

/**
 * Valeur d'`id` quand le backend l'omet : objet détecté mais pas encore identifié
 * dans le temps. À traiter comme « nouveau », jamais comme un suivi.
 */
const val NO_TRACK_ID: Int = -1