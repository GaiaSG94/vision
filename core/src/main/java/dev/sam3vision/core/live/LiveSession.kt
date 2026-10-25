package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.net.LiveClient

/**
 * Orchestration du direct : frame → [FrameGate] → [LiveClient.live] → statistiques.
 * Une frame en vol maximum : si le créneau est pris, [runFrame] renvoie `null`
 * (frame jetée) — l'appelant caméra n'est jamais mis en attente ni bloqué.
 *
 * [stats] porte la latence bout-en-bout (celle qui cadence la capture),
 * [serverStats] la durée serveur `elapsed_ms` annoncée par `/live`.
 */
class LiveSession(
    private val client: LiveClient,
    private val gate: FrameGate = FrameGate(),
    val stats: LiveStats = LiveStats(),
    val serverStats: LiveStats = LiveStats(),
    val prompts: List<String> = LivePrompts.Default,
    private val workEdge: Int = DEFAULT_WORK_EDGE,
    private val maxObjects: Int = DEFAULT_MAX_OBJECTS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Une frame est-elle en train d'être traitée ? */
    val inFlight: Boolean get() = gate.inFlight

    /**
     * Traite une frame si le créneau est libre, sinon renvoie `null` immédiatement.
     * Consigne la latence bout-en-bout dans [stats] (échec compris : un backend
     * lent doit ralentir la capture) et `elapsed_ms` dans [serverStats].
     */
    suspend fun runFrame(jpegB64: String): Result<LiveFrame>? {
        if (!gate.tryStart()) return null
        val startedAt = clock()
        try {
            val result = client.live(jpegB64, LivePrompts.normalize(prompts), workEdge, maxObjects)
            result.getOrNull()?.let { frame -> serverStats.record(frame.elapsedMs) }
            stats.record(clock() - startedAt)
            return result
        } finally {
            gate.finish()
        }
    }

    /**
     * Intervalle de capture conseillé d'après la latence moyenne :
     * > 900 ms → 900 ms (on ralentit), < 350 ms → 350 ms (on accélère), sinon 500 ms.
     */
    fun intervalMsAdaptive(): Long = when {
        stats.averageMs > HIGH_MS -> HIGH_MS
        stats.averageMs < LOW_MS -> LOW_MS
        else -> TARGET_MS
    }

    /** Relâche le créneau (test / arrêt d'urgence) ; sans effet si rien en vol. */
    fun reset() {
        gate.finish()
        stats.reset()
        serverStats.reset()
    }

    companion object {
        const val DEFAULT_WORK_EDGE = 640
        /** Plafond d'objets affichés en direct (voir [ObjectLimits.MAX_ON_SCREEN]). */
        const val DEFAULT_MAX_OBJECTS = ObjectLimits.MAX_ON_SCREEN
        const val HIGH_MS = 900L
        const val LOW_MS = 350L
        const val TARGET_MS = 500L
    }
}
