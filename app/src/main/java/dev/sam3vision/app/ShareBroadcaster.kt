package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.ShareReporter
import dev.sam3vision.core.share.shareJson
import dev.sam3vision.core.share.sharedObjectsOf

/**
 * Relais vers le site parent, piloté par la route Direct (et non le ViewModel,
 * pour garder [LiveViewModel] sous la limite de taille) : position GPS + cap
 * ([SharePosition]) et objets en cap absolu, au plus toutes les 2 s.
 *
 * Silencieux sans position (permission refusée), relais injoignable ou partage
 * coupé : le site garde alors le dernier état et l'affiche périmé.
 */
class ShareBroadcaster(context: Context) {

    private val positions = SharePosition(context)
    private val reporter = ShareReporter()

    /** Démarre ou coupe l'écoute GPS selon la bascule des Réglages. */
    fun start(enabled: Boolean) {
        reporter.reset()
        if (enabled) positions.start() else positions.stop()
    }

    /** Coupe l'écoute et oublie la position (sortie du Direct). */
    fun stop() {
        positions.stop()
        reporter.reset()
    }

    /**
     * Envoie l'état courant si complet, sinon ne fait rien. Quand la frame ne
     * porte aucun objet mesuré (calibrage inconnu, SAM coupé, scène vide), la
     * position part quand même : sinon la carte restait OFFLINE alors que le
     * téléphone partageait — le cas le plus courant sur le terrain.
     */
    suspend fun publish(nowMs: Long, settings: Settings, layer: DistanceLayer) {
        val id = normalizeShareId(settings.shareId)
        if (id.isBlank()) return
        val fix = positions.lastFix ?: return
        val objects = if (layer.empty) {
            emptyList()
        } else {
            sharedObjectsOf(layer.readings, layer.width, layer.height, layer.fovDeg, fix.headingDeg)
        }
        val url = relayUrl(settings.parentSiteUrl)
        reporter.maybeSend(nowMs, url, shareJson(id, settings.shareWriteKey, fix, objects))
    }

    /** Position seule (couche vide ou Direct sans détecteur) : objets vides. */
    suspend fun publishFix(nowMs: Long, settings: Settings) {
        val id = normalizeShareId(settings.shareId)
        if (id.isBlank()) return
        val fix = positions.lastFix ?: return
        val url = relayUrl(settings.parentSiteUrl)
        reporter.maybeSend(nowMs, url, shareJson(id, settings.shareWriteKey, fix, emptyList()))
    }
}
