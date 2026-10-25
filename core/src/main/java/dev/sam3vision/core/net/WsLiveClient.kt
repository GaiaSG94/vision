package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Direct par WebSocket : un message JSON par frame sur `/live/ws`, réponse portant le
 * **même `id`**. Mesuré côté Modal : 50,3 ms en WebSocket contre 81,4 ms en POST, soit
 * ~31 ms gagnées par frame.
 *
 * Corrélation : une **file par `id`** (et non une file FIFO) — le serveur peut répondre
 * dans le désordre sans ambiguïté, et un message dont l'`id` ne correspond à aucune
 * requête en vol est simplement ignoré. Le health n'a rien à voir avec le direct : il
 * est délégué au client HTTP [http], donc aucune duplication de l'appel `/health`.
 */
class WsLiveClient(
    private val baseUrl: String,
    private val transport: WsTransport = OkHttpWsTransport(defaultHttpClient(LIVE_TIMEOUT_SECONDS)),
    private val timeoutMs: Long = LIVE_TIMEOUT_SECONDS * 1000,
    private val http: LiveClient = HttpLiveClient(baseUrl),
) : LiveClient {

    private val lock = Any()

    /** Réponses attendues, indexées par `id` de requête. */
    private val pending = mutableMapOf<Int, CompletableDeferred<String>>()
    private var nextId = 0
    private var connected = false

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        val id = synchronized(lock) { ++nextId }
        val reply = CompletableDeferred<String>()
        val sent = synchronized(lock) {
            // L'attente est enregistrée **avant** l'ouverture : un échec de socket
            // (connexion refusée) peut survenir immédiatement, il doit réveiller la frame.
            pending[id] = reply
            if (!openLocked()) {
                pending.remove(id)
                return Result.failure(ApiError.Network(WS_UNREACHABLE))
            }
            transport.send(frameMessage(id, jpegB64, prompts, workEdge, maxObjects))
        }
        if (!sent) {
            synchronized(lock) { pending.remove(id) }
            return Result.failure(ApiError.Network("WebSocket /live/ws : envoi refusé"))
        }
        val raw = awaitReply(reply)
        synchronized(lock) { pending.remove(id) }
        return raw.then { text -> parseReply(text) }
    }

    /** Le WebSocket ne sert pas au health : un seul appel `/health`, celui du client HTTP. */
    override suspend fun health(): Result<LiveHealth> = http.health()

    override fun close() {
        transport.close()
        failPending(ApiError.Network(WS_CLOSED))
    }

    /** Ouvre la connexion si besoin (appel sous [lock]). */
    private fun openLocked(): Boolean {
        if (connected) return true
        connected = transport.open(
            webSocketUrl(baseUrl, LIVE_WS_PATH),
            { text -> onText(text) },
            { error -> onClosed(error) },
        )
        return connected
    }

    /** Message de requête : même corps que `POST /live`, plus l'`id` de corrélation. */
    private fun frameMessage(
        id: Int,
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): String = JSONObject()
        .put("id", id)
        .put("image_b64", jpegB64)
        .put("prompts", if (prompts == null) JSONObject.NULL else jsonArrayOf(prompts))
        .put("max_objects", maxObjects)
        .put("work_edge", workEdge)
        .toString()

    /** Réponse du serveur : même forme que `POST /live`, ou `error` (connexion maintenue). */
    private fun parseReply(raw: String): Result<LiveFrame> {
        val json = parse(raw) ?: return Result.failure(ApiError.BadResponse("WebSocket : message illisible"))
        json.optJSONObject("error")?.let { error -> return Result.failure(error.toApiError()) }
        return catchingResult { parseLiveFrame(raw) }
    }

    /** Message entrant : seule la requête portant cet `id` est réveillée. */
    private fun onText(text: String) {
        val id = parse(text)?.optInt("id", -1) ?: -1
        synchronized(lock) { pending[id] }?.complete(text)
    }

    /** Socket fermé ou en panne : les frames en vol échouent tout de suite. */
    private fun onClosed(error: Throwable?) {
        synchronized(lock) { connected = false }
        failPending(error?.toApiError() ?: ApiError.Network(WS_CLOSED))
    }

    private fun failPending(failure: Throwable) {
        val waiting = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { reply -> reply.completeExceptionally(failure) }
    }

    private suspend fun awaitReply(reply: CompletableDeferred<String>): Result<String> = try {
        val raw = withTimeoutOrNull(timeoutMs) { reply.await() }
        if (raw == null) Result.failure(ApiError.Timeout()) else Result.success(raw)
    } catch (e: ApiError) {
        Result.failure(e)
    }

    private companion object {
        const val WS_UNREACHABLE = "WebSocket /live/ws injoignable"
        const val WS_CLOSED = "WebSocket /live/ws fermé"
    }
}

/** `{"id":n,"error":{"status":400,"detail":"…"}}` → [ApiError.BadResponse] avec le détail. */
private fun JSONObject.toApiError(): ApiError {
    val status = optInt("status", 0)
    val detail = optString("detail").ifBlank { "erreur serveur" }
    return ApiError.BadResponse(if (status > 0) "$status : $detail" else detail)
}
