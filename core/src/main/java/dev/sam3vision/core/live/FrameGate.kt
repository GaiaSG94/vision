package dev.sam3vision.core.live

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Régulateur « goutte-à-goutte » sans file : une seule frame peut être en vol.
 * Si le créneau est pris, la nouvelle frame est jetée (jamais mise en attente).
 * Sûre entre threads (callback caméra sur un thread de fond).
 */
class FrameGate {

    private val busy = AtomicBoolean(false)

    /** Vrai si l'appelant obtient le créneau ; faux = frame à jeter. */
    fun tryStart(): Boolean = busy.compareAndSet(false, true)

    /** Libère le créneau, quel que soit le sort de la frame en vol. */
    fun finish() {
        busy.set(false)
    }

    val inFlight: Boolean get() = busy.get()
}
