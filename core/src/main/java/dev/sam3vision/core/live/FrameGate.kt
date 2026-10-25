package dev.sam3vision.core.live

import java.util.concurrent.atomic.AtomicInteger

/**
 * Régulateur « goutte-à-goutte » sans file : au plus [capacity] frames en vol.
 * Si tous les créneaux sont pris, la nouvelle frame est jetée (jamais mise en
 * attente). Sûre entre threads (callback caméra sur un thread de fond).
 *
 * Un créneau par défaut (comportement historique du Direct). La page caméra d'iHear
 * en autorise deux, mais [LiveSession] redescend à un seul dès que la latence dépasse
 * son budget : deux frames en vol ne valent le coup que si le backend les traite en
 * parallèle, sinon la seconde attend la première et la latence double pour rien.
 */
class FrameGate(slots: Int = 1) {

    /**
     * Créneaux autorisés **maintenant** : le régulateur de [LiveSession] le baisse à 1
     * quand la latence dépasse le budget, et le remonte quand le backend respire.
     */
    @Volatile
    var capacity: Int = slots.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private val inUse = AtomicInteger(0)

    /** Vrai si l'appelant obtient un créneau ; faux = frame à jeter. */
    fun tryStart(): Boolean {
        while (true) {
            val current = inUse.get()
            if (current >= capacity) return false
            if (inUse.compareAndSet(current, current + 1)) return true
        }
    }

    /** Libère un créneau, quel que soit le sort de la frame en vol. */
    fun finish() {
        while (true) {
            val current = inUse.get()
            if (current == 0) return
            if (inUse.compareAndSet(current, current - 1)) return
        }
    }

    /** Au moins une frame est-elle en vol ? */
    val inFlight: Boolean get() = inUse.get() > 0

    /** Créneaux encore libres : 0 = la frame suivante serait jetée. */
    val freeSlots: Int get() = (capacity - inUse.get()).coerceAtLeast(0)
}