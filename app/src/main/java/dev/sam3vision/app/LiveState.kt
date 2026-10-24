package dev.sam3vision.app

import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.settings.Settings

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
) {
    val statusLine: String get() = formatLiveStatus(running, fps, latencyMs, gpu, objectsCount)
}

/**
 * Barre d'état du direct : « Direct · 1,4 ips · 780 ms · 8 objets · A100-SXM4-40GB ».
 * Fonction pure, testée en JVM.
 */
fun formatLiveStatus(running: Boolean, fps: Float, latencyMs: Long, gpu: String, objects: Int): String {
    if (!running) return "Direct arrêté"
    val rate = if (fps <= 0f) "…" else String.format("%.1f ips", fps)
    val latency = if (latencyMs <= 0) "…" else "$latencyMs ms"
    return "Direct · $rate · $latency · $objects objets · ${gpu.ifBlank { "GPU ?" }}"
}

/** Cadence du prochain tick, bornée par les constantes de [LiveSession]. */
fun nextIntervalMs(session: LiveSession?): Long = session?.intervalMsAdaptive() ?: LiveSession.TARGET_MS

/** Endpoint du direct : celui saisi dans Réglages, sinon celui injecté au build. */
fun Settings.liveBaseUrl(): String = samBaseUrl.ifBlank { BuildConfig.SAM_BASE_URL }
