package dev.sam3vision.core.live

/**
 * Fenêtre de frames en vol, **bornée**, avec éviction de la plus ancienne.
 *
 * Remplaçante de [FrameGate] quand on veut plus d'une frame en vol. La règle qui
 * compte n'est pas « combien on envoie » mais « laquelle on affiche » :
 *
 * - [admit] enregistre un numéro de séquence ; au-delà de [capacity], la plus
 *   ancienne est évincée (elle sera envoyée quand même, mais sa réponse sera jetée) ;
 * - [onResult] n'accepte que la séquence d'une frame encore admise **et** plus récente
 *   que tout ce qui a déjà été affiché : une réponse en retard ne réécrit jamais un
 *   affichage postérieur (c'est l'anti-rebond côté rendu).
 *
 * Pourquoi borner : à service serveur constant `S`, une frame envoyée avec `k` autres
 * devant attend `k × S` avant d'être traitée. Mesuré sur A100 eu-west : `k = 1` →
 * 600-715 ms, `k = 6` → 1220 ms, soit exactement `(6-1) × ~200 ms` de file GPU
 * sérialisée. Le débit d'affichage, lui, ne bouge pas : `1/S` dans les deux cas.
 */
class InFlightQueue(var capacity: Int = 1) {

    private val admitted = ArrayDeque<Long>()
    private var highestRendered = -1L
    private var evictions = 0

    /** Frames admises et pas encore répondue (≤ [capacity]). */
    val inFlight: Int get() = synchronized(this) { admitted.size }

    /** Nombre total de frames évincées (indicateur de saturation du lien montant). */
    val evicted: Int get() = synchronized(this) { evictions }

    /**
     * Admet [seq] ; évince la plus ancienne si la fenêtre est pleine.
     * Renvoie `true` si une éviction a eu lieu (donc une réponse à jeter).
     */
    fun admit(seq: Long): Boolean = synchronized(this) {
        var evictedNow = false
        while (admitted.size >= capacity.coerceAtLeast(1)) {
            admitted.removeFirst()
            evictions += 1
            evictedNow = true
        }
        admitted.addLast(seq)
        evictedNow
    }

    /**
     * Réponse arrivée : `true` si elle doit être rendue (admise et plus récente que le
     * dernier rendu). Les séquences plus anciennes encore en vol sont retirées : leur
     * réponse, quand elle arrivera, sera jetée.
     */
    fun onResult(seq: Long): Boolean = synchronized(this) {
        val live = admitted.contains(seq)
        if (live) {
            while (admitted.isNotEmpty() && admitted.first() <= seq) admitted.removeFirst()
        }
        if (!live || seq <= highestRendered) return false
        highestRendered = seq
        true
    }

    /** Remet la fenêtre à zéro (arrêt du direct, changement de concepts). */
    fun reset() = synchronized(this) {
        admitted.clear()
        highestRendered = -1L
        evictions = 0
    }
}

/**
 * Anti-rebond de cadence : la période d'envoi ne change que si la cible s'éloigne de
 * plus de [deadbandMs] **et** si le dernier changement date de plus de [dwellMs].
 * Sans ça, la latence moyenne oscille autour d'un seuil et la cadence bat la mesure
 * (observé : `500 → 900 → 500 ms` en boucle sur le direct).
 *
 * Formule de cible : `latence moyenne / capacité`, bornée à `[minMs, maxMs]`. C'est le
 * débit maximal que la fenêtre peut absorber sans fabriquer de file : envoyer plus
 * vite que `S / k` ne produit que de la latence, pas des images.
 */
class CadenceController(
    private val minMs: Long = MIN_MS,
    private val maxMs: Long = MAX_MS,
    private val deadbandMs: Long = DEADBAND_MS,
    private val dwellMs: Long = DWELL_MS,
) {

    private var current = minMs
    private var lastChangeMs = Long.MIN_VALUE

    /** Période actuellement appliquée. */
    val intervalMs: Long get() = synchronized(this) { current }

    /** Cadence théorique pour [averageLatencyMs] et une fenêtre de [capacity] frames. */
    fun targetFor(averageLatencyMs: Long, capacity: Int): Long {
        val k = capacity.coerceAtLeast(1)
        if (averageLatencyMs <= 0L) return minMs
        return (averageLatencyMs / k + 1L).coerceIn(minMs, maxMs)
    }

    /**
     * Période à appliquer à [nowMs] : la cible si elle s'écarte assez et que le temps
     * de stabilisation est passé, sinon la valeur courante (aucun à-coup).
     */
    fun intervalFor(averageLatencyMs: Long, capacity: Int, nowMs: Long): Long = synchronized(this) {
        val target = targetFor(averageLatencyMs, capacity)
        val settled = lastChangeMs == Long.MIN_VALUE || nowMs - lastChangeMs >= dwellMs
        val moved = kotlin.math.abs(target - current) >= deadbandMs
        if (!settled || !moved) return current
        current = target
        lastChangeMs = nowMs
        current
    }

    fun reset() = synchronized(this) {
        current = minMs
        lastChangeMs = Long.MIN_VALUE
    }

    companion object {
        /** 30 fps : on n'envoie jamais plus vite que le capteur. */
        const val MIN_MS = 1000L / 30

        /** 2 fps : plancher d'utilité du direct. */
        const val MAX_MS = 500L

        /** Écart minimal avant de changer de cadence. */
        const val DEADBAND_MS = 40L

        /** Durée minimale entre deux changements. */
        const val DWELL_MS = 600L
    }
}