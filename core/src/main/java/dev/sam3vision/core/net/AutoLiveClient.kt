package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth

/**
 * Direct « WebSocket d'abord » : le socket `/live/ws` est ~31 ms plus rapide par frame
 * que `POST /live`. Au **premier échec structurel** du socket (connexion impossible,
 * délai dépassé, connexion fermée) le client bascule sur HTTP et **y reste** pour toute
 * la session : plus aucune tentative WebSocket, donc plus de frame perdue à chaque essai.
 *
 * Le repli est signalé par [usingHttp] / [fallbackReason] ; si le HTTP échoue lui aussi,
 * le message d'erreur nomme les deux transports. Le health n'est jamais concerné (il est
 * déjà en HTTP). Une erreur applicative du serveur (`400`, JSON illisible) n'est **pas**
 * structurelle : le socket fonctionne, on ne bascule pas.
 */
class AutoLiveClient(
    private val ws: LiveClient,
    private val http: LiveClient,
    forceHttp: Boolean = false,
    /** Appelé une fois, au moment du repli : l'UI peut l'afficher (« HTTP »). */
    private val onFallback: (String) -> Unit = {},
) : LiveClient {

    /** Vrai dès que le repli HTTP est décidé — mémorisé jusqu'à [close]. */
    var usingHttp: Boolean = forceHttp
        private set

    /** Raison du repli, `null` tant que le WebSocket tient. */
    var fallbackReason: String? = null
        private set

    override suspend fun live(
        jpegB64: String,
        prompts: List<String>?,
        workEdge: Int,
        maxObjects: Int,
    ): Result<LiveFrame> {
        if (usingHttp) return http.live(jpegB64, prompts, workEdge, maxObjects)
        val direct = ws.live(jpegB64, prompts, workEdge, maxObjects)
        val error = direct.exceptionOrNull()
        if (error == null || !isStructural(error)) return direct
        fallbackReason = error.message ?: error.toString()
        usingHttp = true
        onFallback(fallbackReason ?: "")
        return http.live(jpegB64, prompts, workEdge, maxObjects).fold(
            onSuccess = { frame -> Result.success(frame) },
            onFailure = { httpError ->
                Result.failure(ApiError.Network("WebSocket puis HTTP : $fallbackReason ; ${httpError.message}"))
            },
        )
    }

    /** Le WebSocket ne sert pas au health : un seul client HTTP, donc un seul `/health`. */
    override suspend fun health(): Result<LiveHealth> = http.health()

    override fun close() {
        ws.close()
        http.close()
    }

    /** Structurel = le transport ne tient pas (réseau, délai, socket fermé), pas le contrat. */
    private fun isStructural(error: Throwable): Boolean = when (error) {
        is ApiError.Timeout, is ApiError.Network -> true
        else -> false
    }
}
