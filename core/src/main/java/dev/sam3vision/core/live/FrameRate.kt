package dev.sam3vision.core.live

/**
 * Cadence réelle, mesurée sur une **fenêtre glissante** d'horodatages de frames.
 *
 * La différence entre deux frames peut être absurdement courte (deux publications qui
 * se suivent de près après un repli de transport, une reprise après réveil du GPU) :
 * afficher « 52,6 ips » pour deux frames à 19 ms d'écart est un mensonge. En moyenner
 * huit donne une cadence lisible et bornée.
 *
 * Pur et sans horloge interne : les instants sont fournis par l'appelant.
 */
class FrameRate(private val windowSize: Int = DEFAULT_WINDOW) {

    private val stamps = ArrayDeque<Long>()

    /** Frame vue à [atMs] : renvoie la cadence sur la fenêtre (images/seconde). */
    fun record(atMs: Long): Float {
        if (stamps.size == windowSize) stamps.removeFirst()
        stamps.addLast(atMs)
        return rate
    }

    /** Cadence courante : `0` tant qu'il n'y a pas deux instants. */
    val rate: Float
        get() {
            if (stamps.size < 2) return 0f
            val span = stamps.last() - stamps.first()
            if (span <= 0L) return 0f
            return (stamps.size - 1) * 1000f / span
        }

    fun reset() = stamps.clear()

    companion object {
        const val DEFAULT_WINDOW = 8
    }
}