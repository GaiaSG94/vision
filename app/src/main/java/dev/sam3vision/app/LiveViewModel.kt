package dev.sam3vision.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.live.TimelineText
import dev.sam3vision.core.mask.colorizeMask
import dev.sam3vision.core.mask.decodeMaskPng
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.net.HttpLiveClient
import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.usecase.AskUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestration du direct : une frame à la fois (gate + cadence adaptative), masques
 * recolorés, statistiques — et **mémoire** des objets vus ([LiveTimeline]), qui
 * permet de poser des questions sur ce qui est passé il y a quelques secondes.
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

    /** Journal du direct : 60 s d'objets suivis, identités stables. */
    private val timeline = LiveTimeline()
    private var settings: Settings = Settings.Default
    private var history: List<ChatTurn> = emptyList()

    /** Taille de la dernière frame bien dimensionnée (repli si le backend l'omet). */
    private var lastKnownWidth = 0
    private var lastKnownHeight = 0

    /** Ouvre une session de direct sur l'endpoint configuré (Réglages, sinon build). */
    fun start(settings: Settings, prompts: List<String> = LivePrompts.Default) {
        this.settings = settings
        val base = settings.liveBaseUrl()
        if (base.isBlank()) {
            _state.update { it.copy(error = "Aucun endpoint SAM/live configuré — ouvre Réglages.") }
            return
        }
        val http = HttpLiveClient(base)
        client = http
        val chosen = LivePrompts.normalize(prompts)
        session = LiveSession(http, prompts = chosen)
        timeline.clear()
        history = emptyList()
        _state.update { it.copy(prompts = chosen) }
        healthAttempts = 0
        nextTickAt = 0L
        lastFrameAt = 0L
        _state.update { it.copy(running = true, starting = true, error = null, memory = LiveMemoryState()) }
        viewModelScope.launch { refreshHealth() }
    }

    /** Change les concepts suivis : `[promptsText]` séparé par virgules, vide = défaut. */
    fun restartWith(promptsText: String) {
        val list = promptsText.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        restart(if (list.isEmpty()) LivePrompts.Default else list)
    }

    /** Relance la session avec les concepts déjà actifs (flux arrêté ou en erreur). */
    fun restartActive() = restart(_state.value.prompts.ifEmpty { LivePrompts.Default })

    private fun restart(prompts: List<String>) = viewModelScope.launch { start(store.load(), prompts) }

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
                onSuccess = { frame -> publish(frame, jpegB64) },
                onFailure = { error -> _state.update { it.copy(error = error.message, objectsCount = 0) } },
            )
        }
    }

    /** Publie la frame analysée : masques, statistiques et journal des objets vus. */
    private suspend fun publish(frame: LiveFrame, jpegB64: String) {
        val now = System.currentTimeMillis()
        val width = frame.width.takeIf { it > 0 } ?: lastKnownWidth
        val height = frame.height.takeIf { it > 0 } ?: lastKnownHeight
        if (width > 0) {
            lastKnownWidth = width
            lastKnownHeight = height
        }
        timeline.record(now, frame.objects.toDetections(width, height))
        val overlays = overlaysFor(frame)
        val gap = if (lastFrameAt == 0L) 0L else now - lastFrameAt
        lastFrameAt = now
        val recent = timeline.recent(now, MEMORY_WINDOW_MS)
        _state.update { current ->
            current.copy(
                frame = frame,
                overlays = overlays,
                objectsCount = frame.objects.size,
                latencyMs = session?.stats?.averageMs ?: 0L,
                fps = if (gap in 1..20_000) 1000f / gap else current.fps,
                lastJpegB64 = jpegB64,
                error = null,
                memory = current.memory.copy(tracks = recent.tracks),
            )
        }
        if (_state.value.gpu.isBlank() && healthAttempts < HEALTH_TRIES) refreshHealth()
    }

    /** Masques colorisés de la frame (calcul hors thread principal). */
    private suspend fun overlaysFor(frame: LiveFrame): List<Bitmap> = withContext(Dispatchers.Default) {
        frame.objects.mapIndexedNotNull { index, obj ->
            decodeMaskPng(obj.maskPngB64)?.let { gray -> colorizeMask(gray, maskColorAt(index).toArgb(), MASK_ALPHA) }
        }
    }

    /** Instant demandé dans l'UI : « il y a N s » (0 = maintenant). */
    fun setFocus(seconds: Int) = _state.update {
        it.copy(memory = it.memory.copy(focusSeconds = seconds.coerceAtLeast(0)))
    }

    /**
     * Question sur la mémoire du direct : le journal (positions + heures relatives)
     * et la dernière image partent au LLM, l'historique des questions est conservé.
     * Les réglages sont relus à chaque question : une clé saisie pendant le direct
     * s'applique tout de suite.
     */
    fun askMemory(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _state.value.memory.asking) return
        if (_state.value.lastJpegB64.isBlank()) {
            _state.update { it.copy(memory = it.memory.copy(error = "Le direct n'a pas encore d'image analysée.")) }
            return
        }
        _state.update { it.copy(memory = it.memory.copy(asking = true, question = text, answer = null, error = null)) }
        viewModelScope.launch {
            settings = store.load()
            if (!settings.llmConfigured) {
                _state.update { it.copy(memory = it.memory.copy(asking = false, error = "Clé LLM absente — Réglages.")) }
                return@launch
            }
            val snapshot = _state.value
            askWithContext(text, snapshot.lastJpegB64, snapshot.memory.focusSeconds)
        }
    }

    /** Envoi au LLM : journal global + fenêtre demandée, puis mise à jour de l'état. */
    private suspend fun askWithContext(question: String, jpegB64: String, focusSeconds: Int) {
        val now = System.currentTimeMillis()
        val global = timeline.recent(now, MEMORY_WINDOW_MS)
        val window = focusWindow(now, focusSeconds)
        val focus = timeline.summary(window.first, window.last)
        val context = TimelineText.context(global, focus, focusLabel(focusSeconds))
        val useCase = AskUseCase(llm(settings))
        useCase.invoke(jpegB64, question, history, context).fold(
            onSuccess = { answer ->
                history = useCase.nextHistory(history, question, answer)
                _state.update { it.copy(memory = it.memory.copy(asking = false, answer = answer)) }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(memory = it.memory.copy(asking = false, error = error.message ?: "réponse impossible"))
                }
            },
        )
    }

    private fun llm(s: Settings): LlmClient = InferHubLlmClient(s.llmBaseUrl, s.llmApiKey, s.llmModel)

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

        /** Fenêtre du journal envoyée au LLM : 30 s suffisent pour « il y a 5 s ». */
        const val MEMORY_WINDOW_MS = 30_000L
    }
}