package dev.sam3vision.app

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.settings.Settings

/**
 * Mémoire du direct : les objets suivis (avec leur dernière vue), l'instant
 * demandé et la question en cours. C'est ce qui permet de demander « qu'y
 * avait-il à gauche il y a 5 s ? » sans quitter le flux.
 */
data class LiveMemoryState(
    val tracks: List<SeenTrack> = emptyList(),
    val focusSeconds: Int = 0,
    val asking: Boolean = false,
    val question: String = "",
    val answer: String? = null,
    val error: String? = null,
) {
    val empty: Boolean get() = tracks.isEmpty()
}

/** État du mode Direct, observé par [LiveScreen]. */
data class LiveUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val frame: dev.sam3vision.core.model.LiveFrame? = null,
    val overlays: List<android.graphics.Bitmap> = emptyList(),
    val gpu: String = "",
    val fps: Float = 0f,
    val latencyMs: Long = 0,
    val objectsCount: Int = 0,
    val error: String? = null,
    val lastJpegB64: String = "",
    /** Détecteur SAM actif : `false` = vision LLM seule (l'image part directement au LLM). */
    val samEnabled: Boolean = false,
    val prompts: List<String> = emptyList(),
    val memory: LiveMemoryState = LiveMemoryState(),
    val distanceLayer: DistanceLayer? = null,
    /** Dernière phrase dite par la voix (positions) : affichée comme sous-titre. */
    val spoken: String? = null,
    /** Description approfondie du LLM sur la frame courante ("" tant qu'aucune). */
    val narration: String = "",
    val narrationBusy: Boolean = false,
    val narrationError: String? = null,
    /** Voix du direct : on parle pour poser sa question sur le flux en cours. */
    val voice: LiveVoiceState = LiveVoiceState(),
) {
    val statusLine: String
        get() = if (running && starting) {
            WAKING_LINE
        } else {
            formatLiveStatus(running, fps, latencyMs, gpu, objectsCount, nearestMeters)
        }

    /** Distance de l'objet le plus proche de la frame, `null` si rien n'est mesuré. */
    val nearestMeters: Float?
        get() = distanceLayer?.readings?.let { DistanceText.nearest(it)?.meters }
}

/**
 * Distances de la frame courante : taille de l'image analysée (le repère des
 * boîtes, en pixels) et une lecture par objet mesuré. Vide = rien à afficher.
 */
data class DistanceLayer(
    val width: Int = 0,
    val height: Int = 0,
    val readings: List<DistanceReading> = emptyList(),
) {
    /** Vrai si l'overlay n'a rien à poser (pas d'objet calibré, taille inconnue). */
    val empty: Boolean get() = width <= 0 || height <= 0 || readings.isEmpty()
}

/** Ligne affichée pendant le démarrage à froid du conteneur (le clic « Direct » le réveille). */
const val WAKING_LINE: String = "Réveil du GPU A100 40 Go sur Modal…"

/** Nom court du GPU pour la barre d'état : « NVIDIA A100-SXM4-40GB » → « A100-SXM4-40GB ». */
fun shortGpu(name: String): String = name.removePrefix("NVIDIA ").trim()

/**
 * Barre d'état du direct : « Direct · 1,4 ips · 780 ms · 8 objets · A100-SXM4-40GB ».
 * Fonction pure, testée en JVM.
 */
fun formatLiveStatus(
    running: Boolean,
    fps: Float,
    latencyMs: Long,
    gpu: String,
    objects: Int,
    nearestMeters: Float? = null,
): String {
    if (!running) return "Direct arrêté"
    val rate = if (fps <= 0f) "…" else String.format("%.1f ips", fps)
    val latency = if (latencyMs <= 0) "…" else "$latencyMs ms"
    val nearest = nearestMeters?.let { " · plus proche ${DistanceText.format(it)}" }.orEmpty()
    return "Direct · $rate · $latency · $objects objets$nearest · ${gpu.ifBlank { "GPU ?" }}"
}

/** Cadence de mémorisation de la frame pour les questions : une par seconde suffit. */
const val FRAME_KEEP_MS = 1_000L

/**
 * Vrai si la frame courante doit être gardée pour les questions. Indépendant de SAM :
 * même sans détecteur, la dernière image permet de demander « qu'y a-t-il devant ? ».
 */
fun shouldKeepFrame(nowMs: Long, lastKeepAtMs: Long, intervalMs: Long = FRAME_KEEP_MS): Boolean =
    lastKeepAtMs <= 0L || nowMs - lastKeepAtMs >= intervalMs

/** Cadence du prochain tick, bornée par les constantes de [LiveSession]. */
fun nextIntervalMs(session: LiveSession?): Long = session?.intervalMsAdaptive() ?: LiveSession.TARGET_MS

/** Endpoint du direct : saisie manuelle, sinon région des Réglages (EU/US), sinon build. */
fun Settings.liveBaseUrl(): String = samEndpoint()

/** Demi-largeur de la fenêtre demandée autour de « il y a N s » : ±2,5 s. */
const val FOCUS_HALF_SPAN_MS = 2_500L

/** Instants proposés dans l'UI, en secondes écoulées (0 = maintenant). */
val FOCUS_CHOICES = listOf(0, 5, 15, 30)

/** Fenêtre observée pour « il y a [seconds] s », élargie de part et d'autre. */
fun focusWindow(nowMs: Long, seconds: Int, halfSpanMs: Long = FOCUS_HALF_SPAN_MS): LongRange {
    val target = nowMs - seconds.coerceAtLeast(0) * 1_000L
    return (target - halfSpanMs)..(target + halfSpanMs)
}

/** Libellé du point focal, affiché sur les pastilles et envoyé au LLM. */
fun focusLabel(seconds: Int, halfSpanMs: Long = FOCUS_HALF_SPAN_MS): String {
    val half = String.format("%.1f", halfSpanMs / 1_000.0).replace('.', ',')
    return if (seconds <= 0) {
        "À l'instant (dernières $half s)"
    } else {
        "Il y a $seconds s (± $half s)"
    }
}