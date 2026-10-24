package dev.sam3vision.core.live

import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TrackClient

/**
 * Orchestration du tracker vidéo en 30 fps, calquée sur [LiveSession] : une frame
 * en vol au maximum ([FrameGate]), la nouvelle frame est **jetée** si le créneau
 * est pris — la caméra n'attend jamais le réseau.
 *
 * Cycle : [start] ouvre une session (`POST /session`) et mémorise sa taille,
 * [submit] envoie les frames (`POST /frame`) en conservant les ids stables,
 * [stop] ferme la session.
 *
 * Session perdue (404 : backend redémarré, session expirée) → [submit] recrée une
 * session avec la dernière frame reçue et renvoie les objets de cette réouverture.
 * **Une seule recréation par appel** : jamais de boucle, un 404 répété remonte l'erreur.
 */
class TrackRunner(
    private val client: TrackClient,
    private val gate: FrameGate = FrameGate(),
    val stats: LiveStats = LiveStats(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** État immuable d'une session : remplacé en bloc, jamais muté. */
    private data class Session(
        val id: String,
        val width: Int,
        val height: Int,
        val prompts: List<String>,
    )

    @Volatile
    private var current: Session? = null

    /** Identifiant de la session en cours, `null` si aucune. */
    val sessionId: String? get() = current?.id

    /** Largeur annoncée par la session (0 tant qu'aucune session). */
    val width: Int get() = current?.width ?: 0

    /** Hauteur annoncée par la session (0 tant qu'aucune session). */
    val height: Int get() = current?.height ?: 0

    /** Une frame est-elle en train d'être traitée ? */
    val inFlight: Boolean get() = gate.inFlight

    /**
     * Ouvre une session de suivi sur [firstFrameB64] avec [prompts] (normalisés par
     * [LivePrompts.normalize]) et retient la taille renvoyée par le backend.
     */
    suspend fun start(prompts: List<String>, firstFrameB64: String): Result<Unit> {
        val normalized = LivePrompts.normalize(prompts)
        return client.start(firstFrameB64, normalized).map { opened ->
            current = Session(opened.sessionId, opened.width, opened.height, normalized)
        }
    }

    /**
     * Envoie une frame au tracker ; `null` si une frame est déjà en vol (frame jetée).
     * La latence bout-en-bout est consignée dans [stats], échec compris.
     */
    suspend fun submit(jpegB64: String): Result<TrackFrame>? {
        if (!gate.tryStart()) return null
        val startedAt = clock()
        try {
            val session = current
                ?: return Result.failure(ApiError.BadResponse("tracker : session non démarrée"))
            val result = client.frame(session.id, jpegB64)
            val frame = if (sessionLost(result)) recover(jpegB64) else result
            stats.record(clock() - startedAt)
            return frame.map { it.withSessionSize() }
        } finally {
            gate.finish()
        }
    }

    /**
     * Intervalle de capture pour [targetFps], borné au contrat 30 fps :
     * jamais moins de [MIN_INTERVAL_MS] (33 ms), jamais plus de [MAX_INTERVAL_MS] (500 ms).
     */
    fun intervalMsFor(targetFps: Int): Long =
        (1000L / targetFps.coerceAtLeast(1)).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    /**
     * Ferme la session courante (`DELETE /session/{id}`). L'échec de fermeture n'est
     * pas bloquant : la session expirera côté serveur, l'UI n'a rien à en faire.
     */
    suspend fun stop() {
        val session = current ?: return
        current = null
        client.close(session.id)
        stats.reset()
    }

    private fun sessionLost(result: Result<TrackFrame>): Boolean =
        (result.exceptionOrNull() as? ApiError.Http)?.code == HTTP_NOT_FOUND

    /**
     * Recrée au plus une session sur [jpegB64] et renvoie les objets de la frame
     * d'ouverture (le backend vient d'analyser cette image : inutile de la renvoyer).
     */
    private suspend fun recover(jpegB64: String): Result<TrackFrame> {
        val prompts = current?.prompts ?: LivePrompts.Default
        val opened = client.start(jpegB64, prompts).getOrElse { error ->
            current = null
            return Result.failure(error)
        }
        current = Session(opened.sessionId, opened.width, opened.height, prompts)
        return Result.success(TrackFrame(opened.width, opened.height, opened.objects))
    }

    /** Une réponse `/frame` sans taille retombe sur celle de la session. */
    private fun TrackFrame.withSessionSize(): TrackFrame {
        if (width > 0 && height > 0) return this
        val session = current
        return TrackFrame(
            width = if (width > 0) width else session?.width ?: 0,
            height = if (height > 0) height else session?.height ?: 0,
            objects = objects,
        )
    }

    companion object {
        /** Cadence visée du direct. */
        const val TARGET_FPS = 30

        /** Plancher d'intervalle : 1000/30 ms. */
        const val MIN_INTERVAL_MS = 1000L / 30

        /** Plafond d'intervalle : 1000/2 ms (2 fps). */
        const val MAX_INTERVAL_MS = 1000L / 2

        private const val HTTP_NOT_FOUND = 404
    }
}