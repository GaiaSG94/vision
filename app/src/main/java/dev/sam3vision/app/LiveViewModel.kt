package dev.sam3vision.app

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.mask.colorizeMask
import dev.sam3vision.core.mask.decodeMaskPng
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.net.HttpLiveClient
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestration du direct : une frame à la fois (gate + cadence adaptative), masques
 * recolorés, statistiques. L'écran caméra pousse des frames, jamais l'inverse.
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStoreFactory.create(app)
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state
    private var session: LiveSession? = null
    private var client: LiveClient? = null
    private var nextTickAt = 0L
    private var lastFrameAt = 0L
    private var healthAttempts = 0

    /** Ouvre une session de direct sur l'endpoint configuré (Réglages, sinon build). */
    fun start(settings: Settings, prompts: List<String> = LivePrompts.Default) {
        val base = settings.liveBaseUrl()
        if (base.isBlank()) {
            _state.update { it.copy(error = "Aucun endpoint SAM/live configuré — ouvre Réglages.") }
            return
        }
        val http = HttpLiveClient(base)
        client = http
        session = LiveSession(http, prompts = LivePrompts.normalize(prompts))
        healthAttempts = 0
        nextTickAt = 0L
        lastFrameAt = 0L
        _state.update { it.copy(running = true, starting = true, error = null) }
        viewModelScope.launch { refreshHealth() }
    }

    /** Le conteneur Modal se réveille en ~30 s : on retente la santé sans afficher d'erreur. */
    private suspend fun refreshHealth() {
        val http = client ?: return
        http.health().fold(
            onSuccess = { info -> _state.update { it.copy(gpu = info.gpu.take(28), starting = false, error = null) } },
            onFailure = { error ->
                healthAttempts += 1
                _state.update {
                    it.copy(
                        starting = false,
                        error = if (healthAttempts < HEALTH_TRIES) "Réveil du GPU A100 en cours…" else error.message,
                    )
                }
                if (healthAttempts < HEALTH_TRIES) {
                    delay(HEALTH_DELAY_MS)
                    refreshHealth()
                }
            },
        )
    }

    /**
     * Frame venue de l'analyseur caméra : jetée si une requête est en vol ou si la cadence
     * adaptative n'est pas écoulée (le lien montant du téléphone sature sinon).
     */
    fun onFrame(jpegB64: String) {
        val active = session ?: return
        val now = System.currentTimeMillis()
        if (jpegB64.isBlank() || active.inFlight || now < nextTickAt) return
        nextTickAt = now + active.intervalMsAdaptive()
        _state.update { it.copy(lastJpegB64 = jpegB64) }
        viewModelScope.launch {
            val result = active.runFrame(jpegB64) ?: return@launch
            result.fold(
                onSuccess = { frame -> publish(frame) },
                onFailure = { error -> _state.update { it.copy(error = error.message, objectsCount = 0) } },
            )
        }
    }

    private suspend fun publish(frame: LiveFrame) {
        val overlays = withContext(Dispatchers.Default) {
            frame.objects.mapIndexedNotNull { index, obj ->
                decodeMaskPng(obj.maskPngB64)?.let { gray -> colorizeMask(gray, maskColorAt(index).toArgb(), MASK_ALPHA) }
            }
        }
        val now = System.currentTimeMillis()
        val gap = if (lastFrameAt == 0L) 0L else now - lastFrameAt
        lastFrameAt = now
        val latency = session?.stats?.averageMs ?: 0L
        _state.update { current ->
            current.copy(
                frame = frame,
                overlays = overlays,
                objectsCount = frame.objects.size,
                latencyMs = latency,
                fps = if (gap in 1..20_000) 1000f / gap else current.fps,
                error = null,
            )
        }
        if (_state.value.gpu.isBlank() && healthAttempts < HEALTH_TRIES) refreshHealth()
    }

    fun stop() {
        session = null
        client = null
        _state.update { LiveUiState() }
    }

    /** Lit les réglages chiffrés puis démarre la session (appelé à l'entrée à l'écran). */
    fun startFromSettings() = viewModelScope.launch { start(store.load()) }

    private companion object {
        const val HEALTH_TRIES = 6
        const val HEALTH_DELAY_MS = 6_000L
    }
}
