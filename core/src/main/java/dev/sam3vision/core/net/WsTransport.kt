package dev.sam3vision.core.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Transport texte minimal du direct : une abstraction, deux implémentations —
 * [OkHttpWsTransport] en production, un faux en test JVM **sans réseau**.
 * Aucun état métier ici : le contrat (`id`, `image_b64`, réponses) vit dans [WsLiveClient].
 */
interface WsTransport {
    /** Ouvre la connexion ; `false` = socket impossible à ouvrir (l'appelant bascule en HTTP). */
    fun open(url: String, onText: (String) -> Unit, onClosed: (Throwable?) -> Unit): Boolean

    /** Envoie un message texte ; `false` = socket fermé ou envoi refusé. */
    fun send(text: String): Boolean

    /** Ferme la connexion (idempotent). */
    fun close()
}

/** Chemin du WebSocket du direct, à côté de `POST /live`. */
internal const val LIVE_WS_PATH = "/live/ws"

/** `/live/ws` sur la même base que `/live` : `https` → `wss`, `http` → `ws`. */
internal fun webSocketUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val socket = when {
        base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
        base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
        else -> base
    }
    return socket + path
}

/**
 * Implémentation réelle (`okhttp3.WebSocket`), un seul socket à la fois.
 * Le `callTimeout` du client HTTP est neutralisé : il borne une requête, alors qu'un
 * WebSocket vit toute la session — c'est [WsLiveClient] qui borne chaque frame.
 */
class OkHttpWsTransport(client: OkHttpClient) : WsTransport {

    private val client = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null

    override fun open(url: String, onText: (String) -> Unit, onClosed: (Throwable?) -> Unit): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LIVE_USER_AGENT)
            .build()
        socket = try {
            client.newWebSocket(request, Listener(onText, onClosed))
        } catch (e: IllegalArgumentException) {
            return false
        }
        return true
    }

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun close() {
        socket?.close(NORMAL_CLOSE, "fin du direct")
        socket = null
    }

    /** Traduit les rappels OkHttp en `onText` / `onClosed(error?)`. */
    private class Listener(
        private val onText: (String) -> Unit,
        private val onClosed: (Throwable?) -> Unit,
    ) : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed(t)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(null)
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
    }
}
