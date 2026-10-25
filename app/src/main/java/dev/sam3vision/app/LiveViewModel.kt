package dev.sam3vision.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.LiveNarrator
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.mask.colorizeMask
import dev.sam3vision.core.mask.decodeMaskPng
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.net.HttpLiveClient
import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.LiveLlmClient
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.WsLiveClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.usecase.AskUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

    /** Dernière frame gardée pour les questions (indépendant de SAM). */
    private var lastKeepAt = 0L

    /** Le conteneur a répondu une fois : la GPU A100 est chaude, les frames peuvent partir. */
    private var warm = false

    /** Journal du direct : 60 s d'objets suivis, identités stables. */
    private val timeline = LiveTimeline()

    /** Distances caméra→objets : sténopé + hauteurs connues, lissées par piste. */
    private val distances = DistanceMeter()
    private var sensorLens: LensSpec? = null

    /** Annonce vocale des positions : une phrase à la fois, jamais superposée. */
    private val announcer = PositionAnnouncer()
    private val voice = VoiceAnnouncer(app, POSITION_VOICE_DIR)
    private val speaking = Mutex()
    private var settings: Settings = Settings.Default
    private var history: List<ChatTurn> = emptyList()

    /** Narration continue : une frame sur deux secondes part au LLM vision. */
    private var narrator: LiveNarrator? = null

    /** Voix du direct : « Parler » pose la question sur le flux, sans clavier. */
    private val liveVoice = LiveVoice(
        app = app,
        scope = viewModelScope,
        settings = { settings },
        onText = { askMemory(it) },
        onState = { spoken -> _state.update { it.copy(voice = spoken) } },
    )

    /** Taille de la dernière frame bien dimensionnée (repli si le backend l'omet). */
    private var lastKnownWidth = 0
    private var lastKnownHeight = 0

    /**
     * Ouvre une session de direct. Par défaut (`liveSamEnabled = false`), l'image part
     * **directement au LLM** : aucun client HTTP SAM, aucune session, aucun réveil GPU.
     * Avec `liveSamEnabled`, la session `/live` est ouverte et le GPU réveillé comme avant.
     * Dans les deux cas, la clé LLM est indispensable.
     */
    fun start(settings: Settings, prompts: List<String> = LivePrompts.Default) {
        this.settings = seededFromBuild(settings)
        if (!this.settings.llmConfigured) {
            _state.update { it.copy(error = LLM_ABSENT_LINE) }
            return
        }
        resetForStart(prompts)
        narrator = LiveNarrator(narratorClient(this.settings))
        if (!this.settings.liveSamEnabled) {
            startVisionOnly()
            return
        }
        val base = this.settings.liveBaseUrl()
        if (base.isBlank()) {
            session = null
            client = null
            _state.update { it.copy(running = true, starting = false, error = SAM_ABSENT_LINE) }
            return
        }
        // WebSocket uniquement : plus aucun POST /live pour les frames. Le client HTTP
        // ne sert plus qu'au /health (une requête GET, une fois par session).
        val live = WsLiveClient(base, http = HttpLiveClient(base))
        client = live
        session = LiveSession(live, prompts = _state.value.prompts, maxObjects = this.settings.objectsOnScreen)
        _state.update {
            it.copy(
                running = true,
                starting = true,
                error = null,
                memory = LiveMemoryState(),
                spoken = null,
                transport = TRANSPORT_WS,
            )
        }
        viewModelScope.launch { wakeGpu() }
    }

    /** Direct en vision LLM seule : ni session, ni client SAM, ni réveil GPU — juste l'image au LLM. */
    private fun startVisionOnly() {
        session = null
        client = null
        _state.update {
            it.copy(running = true, starting = false, error = null, gpu = "", spoken = null)
        }
    }

    /** Remise à zéro commune aux deux démarrages : journal, distances, cadence, concepts. */
    private fun resetForStart(prompts: List<String>) {
        val chosen = LivePrompts.normalize(prompts)
        timeline.clear()
        history = emptyList()
        distances.enabled = settings.distanceOverlayEnabled
        distances.reset()
        distances.setLens(chosenLens())
        warm = false
        nextTickAt = 0L
        lastFrameAt = 0L
        lastKeepAt = 0L
        _state.update { it.copy(prompts = chosen, samEnabled = settings.liveSamEnabled) }
    }

    /** Change les concepts suivis : `[promptsText]` séparé par virgules, vide = défaut. */
    fun restartWith(promptsText: String) {
        val list = promptsText.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        restart(if (list.isEmpty()) LivePrompts.Default else list)
    }

    /** Relance la session avec les concepts déjà actifs (flux arrêté ou en erreur). */
    fun restartActive() = restart(_state.value.prompts.ifEmpty { LivePrompts.Default })

    /**
     * Bascule « Vision seule ↔ SAM » depuis le bandeau du direct : le réglage est
     * persisté, puis la session est relancée dans le nouveau mode.
     */
    fun toggleSam() {
        viewModelScope.launch {
            val next = store.load().let { it.copy(liveSamEnabled = !it.liveSamEnabled) }
            store.save(next)
            start(next, _state.value.prompts.ifEmpty { LivePrompts.Default })
        }
    }

    private fun restart(prompts: List<String>) = viewModelScope.launch { start(store.load(), prompts) }

    /** Le conteneur Modal (A100 40 Go) se réveille en 30-50 s : on retente sans afficher d'erreur. */
    private suspend fun wakeGpu() {
        val http = client ?: return
        for (attempt in 1..WAKE_TRIES) {
            http.health().fold(
                onSuccess = { info ->
                    warm = true
                    _state.update { it.copy(gpu = shortGpu(info.gpu).take(28), starting = false, error = null) }
                    return
                },
                onFailure = { error ->
                    val reason = error.message ?: "GPU injoignable"
                    _state.update {
                        it.copy(
                            starting = attempt < WAKE_TRIES,
                            error = if (attempt < WAKE_TRIES) null else samUnreachableLine(reason),
                        )
                    }
                    if (attempt < WAKE_TRIES) delay(WAKE_DELAY_MS)
                },
            )
        }
    }

    /**
     * Frame venue de l'analyseur caméra. Sans détecteur (session absente ou GPU encore
     * froide), elle est seulement **gardée pour les questions** — les questions marchent
     * donc même quand SAM est injoignable. Sinon elle part au GPU, sauf si une requête est
     * en vol ou si la cadence adaptative n'est pas écoulée (le lien montant sature sinon).
     */
    fun onFrame(jpegB64: String) {
        if (jpegB64.isBlank()) return
        narrate(jpegB64)
        val active = session
        if (active == null || !warm) {
            keepFrame(jpegB64)
            return
        }
        val now = System.currentTimeMillis()
        if (active.inFlight || now < nextTickAt) return
        nextTickAt = now + active.intervalMsAdaptive()
        keepFrame(jpegB64)
        viewModelScope.launch {
            val result = active.runFrame(jpegB64) ?: return@launch
            result.fold(
                onSuccess = { frame -> publish(frame, jpegB64) },
                onFailure = { error ->
                    _state.update { it.copy(error = error.message, objectsCount = 0, transport = transportLabel()) }
                },
            )
        }
    }

    /**
     * Narration continue : la frame part au LLM vision avec les objets suivis et la
     * description précédente, qui dit ce qui change. Cadence et « une seule en vol »
     * sont tenues par [LiveNarrator] — la caméra n'attend jamais le réseau, et la
     * narration fonctionne même sans détecteur SAM.
     */
    private fun narrate(jpegB64: String) {
        val narrator = narrator ?: return
        if (!narrator.due()) return
        val labels = _state.value.frame?.objects?.map { it.label }?.distinct().orEmpty()
        _state.update { it.copy(narrationBusy = true) }
        viewModelScope.launch {
            val result = narrator.narrate(jpegB64, labels)
            _state.update { current ->
                when {
                    result == null -> current.copy(narrationBusy = false)
                    result.isFailure -> current.copy(
                        narrationBusy = false,
                        narrationError = result.exceptionOrNull()?.message ?: "analyse LLM impossible",
                    )
                    else -> current.copy(
                        narration = result.getOrNull().orEmpty(),
                        narrationBusy = false,
                        narrationError = null,
                    )
                }
            }
        }
    }

    /** Garde la frame courante pour les questions : au plus une par [FRAME_KEEP_MS]. */
    private fun keepFrame(jpegB64: String) {
        val now = System.currentTimeMillis()
        if (!shouldKeepFrame(now, lastKeepAt)) return
        lastKeepAt = now
        _state.update { it.copy(lastJpegB64 = jpegB64) }
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
        val detections = frame.objects.toDetections(width, height)
        val seen = timeline.record(now, detections)
        val readings = distances.measure(width, height, detections, seen.map { it?.id })
        announcePositions(frame, width, height, now, readings)
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
                distanceLayer = if (readings.isEmpty()) null else DistanceLayer(width, height, readings),
            )
        }
        if (!warm) wakeGpu()
    }

    /** Masques colorisés de la frame (calcul hors thread principal). */
    private suspend fun overlaysFor(frame: LiveFrame): List<Bitmap> = withContext(Dispatchers.Default) {
        frame.objects.mapIndexedNotNull { index, obj ->
            decodeMaskPng(obj.maskPngB64)?.let { gray -> colorizeMask(gray, maskColorAt(index).toArgb(), MASK_ALPHA) }
        }
    }

    /**
     * Calibrage des distances dès que la caméra est liée : le champ de vision réglé
     * à la main prime, sinon celui lu sur le capteur, sinon le grand-angle de repli.
     */
    fun applyLens(spec: LensSpec?) {
        if (spec != null && spec.usable) sensorLens = spec
        distances.setLens(chosenLens())
    }

    /** Calibrage retenu : réglage utilisateur, puis capteur, puis [LensSpec.Default]. */
    private fun chosenLens(): LensSpec {
        val forced = settings.cameraFovDeg
        if (forced > 0f) return LensSpec.fromHorizontalFov(forced)
        return sensorLens ?: LensSpec.Default
    }

    /**
     * Voix ElevenLabs : dit où sont les objets **et à quelle distance** dès que la
     * scène change (« Personne à gauche, à 1,4 mètre »). Silencieux sans clé, pendant
     * une question sur la mémoire, ou si la bascule des Réglages est coupée.
     */
    private fun announcePositions(
        frame: LiveFrame,
        width: Int,
        height: Int,
        now: Long,
        readings: List<DistanceReading>,
    ) {
        if (!settings.announcePositions || _state.value.memory.asking) return
        // la distance suit la boîte qui l'a produite : aucune indexation fragile
        val metersByBox = readings.associate { it.box to it.meters }
        val placed = frame.objects.mapNotNull { obj ->
            PositionText.fromDetection(Detection(obj.label, obj.score, obj.box, width, height))
                ?.copy(meters = metersByBox[obj.box])
        }
        val phrase = announcer.onFrame(now, placed) ?: return
        _state.update { it.copy(spoken = phrase) }
        viewModelScope.launch { sayPositions(phrase) }
    }

    /**
     * Synthèse + lecture, jamais deux annonces en même temps : une phrase en cours
     * n'est ni coupée ni doublée par la frame suivante.
     */
    private suspend fun sayPositions(phrase: String) {
        if (!speaking.tryLock()) return
        try {
            voice.say(settings, phrase)
        } finally {
            speaking.unlock()
        }
    }

    /** Instant demandé dans l'UI : « il y a N s » (0 = maintenant). */
    fun setFocus(seconds: Int) = _state.update {
        it.copy(memory = it.memory.copy(focusSeconds = seconds.coerceAtLeast(0)))
    }

    /**
     * Question sur le direct : la **dernière image part tout de suite au LLM**, sans
     * dépendre de SAM (ni session, ni journal, ni GPU). L'historique est conservé.
     * Les réglages sont relus à chaque question : une clé saisie pendant le direct
     * s'applique tout de suite.
     */
    fun askMemory(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _state.value.memory.asking) return
        val jpeg = _state.value.lastJpegB64
        if (jpeg.isBlank()) {
            _state.update { it.copy(memory = it.memory.copy(error = NO_FRAME_LINE)) }
            return
        }
        _state.update { it.copy(memory = it.memory.copy(asking = true, question = text, answer = null, error = null)) }
        viewModelScope.launch {
            settings = seededFromBuild(store.load())
            if (!settings.llmConfigured) {
                _state.update { it.copy(memory = it.memory.copy(asking = false, error = LLM_ABSENT_LINE)) }
                return@launch
            }
            askWithContext(text, jpeg, _state.value.memory.focusSeconds)
        }
    }

    /** Envoi au LLM : image + journal (s'il y a des pistes), puis mise à jour de l'état. */
    private suspend fun askWithContext(question: String, jpegB64: String, focusSeconds: Int) {
        val now = System.currentTimeMillis()
        val global = timeline.recent(now, MEMORY_WINDOW_MS)
        val window = focusWindow(now, focusSeconds)
        val focus = timeline.summary(window.first, window.last)
        val context = timelineContextOrNull(global, focus, focusLabel(focusSeconds))
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

    /** Même client que [llm], typé pour la narration continue. */
    private fun narratorClient(s: Settings): LiveLlmClient =
        InferHubLlmClient(s.llmBaseUrl, s.llmApiKey, s.llmModel)

    /** Bouton « Parler » du panneau : le micro s'ouvre pour poser la question à la voix. */
    fun startVoice() = liveVoice.start()

    /** Bouton « Stop » : fin de la parole, transcription puis question automatique. */
    fun stopVoice() = liveVoice.stop()

    fun stop() {
        narrator = null
        liveVoice.cancel()
        client?.close()
        session = null
        client = null
        distances.reset()
        warm = false
        announcer.reset()
        voice.stop()
        _state.update { LiveUiState() }
    }

    override fun onCleared() {
        client?.close()
        voice.release()
        super.onCleared()
    }

    /** « WebSocket » quand le socket tient, « WebSocket KO » dès qu'il est tombé (aucun repli). */
    private fun transportLabel(): String =
        if ((client as? WsLiveClient)?.isConnected == true) TRANSPORT_WS else TRANSPORT_WS_KO

    /** Lit les réglages chiffrés puis démarre la session (appelé à l'entrée à l'écran). */
    fun startFromSettings() = viewModelScope.launch { start(store.load()) }

    private companion object {
        /** Réveil : une requête patiente couvre le démarrage, ces essais couvrent le reste. */
        const val WAKE_TRIES = 3
        const val WAKE_DELAY_MS = 4_000L

        /** Fenêtre du journal envoyée au LLM : 30 s suffisent pour « il y a 5 s ». */
        const val MEMORY_WINDOW_MS = 30_000L

        /** Dossier audio de l'annonce des positions : distinct de celui des bandeaux. */
        const val POSITION_VOICE_DIR = "positions"
    }
}
