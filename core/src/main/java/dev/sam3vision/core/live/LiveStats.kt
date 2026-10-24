package dev.sam3vision.core.live

/**
 * Statistiques glissantes du direct : moyenne et p95 de latence sur les
 * [maxSamples] dernières mesures (20 par défaut). Les anciens échantillons
 * sortent de la fenêtre, [samples] ne dépasse jamais [maxSamples].
 * Sûre entre threads (enregistrement depuis le thread IO, lecture depuis l'UI).
 */
class LiveStats(private val maxSamples: Int = 20) {

    private val window = ArrayDeque<Long>()
    private var sum = 0L

    /** Ajoute une mesure de latence (en ms), bornée à [maxSamples] chiffres significatifs. */
    @Synchronized
    fun record(latencyMs: Long) {
        val value = latencyMs.coerceAtLeast(0L)
        if (window.size == maxSamples) sum -= window.removeFirst()
        window.addLast(value)
        sum += value
    }

    val samples: Int get() = synchronized(this) { window.size }

    /** Moyenne entière de la fenêtre, 0 si aucune mesure. */
    val averageMs: Long get() = synchronized(this) {
        if (window.isEmpty()) 0L else sum / window.size
    }

    /**
     * p95 par rang le plus proche : sur la fenêtre triée, élément d'indice
     * `ceil(0.95 × n) - 1` (19e des 20 derniers échantillons). 0 si vide.
     */
    val p95Ms: Long get() = synchronized(this) {
        if (window.isEmpty()) return 0L
        val sorted = window.sorted()
        val rank = Math.ceil(P95 * sorted.size / 100.0).toInt().coerceIn(1, sorted.size)
        sorted[rank - 1]
    }

    @Synchronized
    fun reset() {
        window.clear()
        sum = 0L
    }

    companion object {
        private const val P95 = 95.0
    }
}
