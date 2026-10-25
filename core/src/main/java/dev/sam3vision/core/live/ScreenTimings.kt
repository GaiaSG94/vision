package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.net.WireMarks

/**
 * Chronologie d'un écran (Direct ou iHear) : les marques de la frame en cours, la garde du
 * premier pixel et l'instantané lu par l'UI. Isolée ici pour que les deux écrans partagent
 * exactement la même logique — et pour garder les ViewModel sous la limite de lignes.
 */
class ScreenTimings(private val clock: () -> Long = System::nanoTime) : WireMarks {

    private val marks = LiveTimings(clock)

    /** Masques de la frame courante pr\u00eats \u00e0 dessiner (garde de [noteDrawn]). */
    @Volatile
    private var overlaysReady = false

    /** Nouvelle frame : les postes de tête sont posés d'un coup ([encodedAtNs] = 0 = maintenant). */
    fun beginFrame(captureAtNs: Long = 0L, encodedAtNs: Long = 0L) {
        marks.reset()
        overlaysReady = false
        if (captureAtNs > 0L) marks.mark(LiveStage.CAPTURE, captureAtNs)
        marks.mark(LiveStage.ENCODED, if (encodedAtNs > 0L) encodedAtNs else clock())
        marks.mark(LiveStage.SENT)
    }

    /** Réponse complète (corps lu et JSON tourné). */
    fun markReceived() = marks.mark(LiveStage.RECEIVED)

    /**
     * En-têtes de la réponse reçus : c'est ce qui sépare l'attente (`envoi → en-têtes`) de
     * la descente du corps. Appelé par le client réseau ([WireMarks.onHeaders]).
     */
    fun markHeaders() = marks.mark(LiveStage.HEADERS)

    /** Corps complet reçu, **avant** le parsing JSON ([WireMarks.onBody]). */
    fun markBody() = marks.mark(LiveStage.BODY)

    /** Le réseau nous prévient : même chemin que [markHeaders], interface du `core`. */
    override fun onHeaders() = markHeaders()

    /** Idem pour le corps : [onBody] ne fait que nommer [markBody] côté client réseau. */
    override fun onBody() = markBody()

    /** Masques décods et coloriés : prêts à dessiner. */
    fun markOverlays() {
        marks.mark(LiveStage.OVERLAYS)
        overlaysReady = true
    }

    /** Sortie d'écran, nouvelle session, début d'enregistrement : plus aucune marque. */
    fun reset() {
        marks.reset()
        overlaysReady = false
    }

    /** Vrai si le poste « pixel » vient d'être posé — l'UI doit publier l'instantané. */
    fun noteDrawn(): Boolean {
        if (!overlaysReady) return false
        marks.mark(LiveStage.PIXEL)
        return true
    }

    /** Instantané immuable : l'UI ne lit jamais les marques, seulement ceci. */
    fun snapshot(frame: LiveFrame? = null): FrameTimings =
        marks.snapshot(frame?.elapsedMs ?: -1L, frame?.timingsMs.orEmpty())

    /** Idem après coup (poste « pixel ») : les chiffres serveur viennent du poste d'avant. */
    fun snapshot(previous: FrameTimings): FrameTimings = marks.snapshot(previous.serverTotalMs, previous.server)
}
