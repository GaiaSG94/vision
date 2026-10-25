package dev.sam3vision.app

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.toArgb
import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.shouldPrewarm
import dev.sam3vision.core.mask.colorizedMaskBitmap
import dev.sam3vision.core.model.MASK_ENCODING_PNG
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.net.HttpSamClient
import kotlinx.coroutines.CoroutineScope
import dev.sam3vision.core.net.releaseSam
import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.resolveSamEndpoint
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.usecase.AskUseCase
import dev.sam3vision.core.usecase.AnalyzeUseCase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob

/** Étape de la vue : idle, analyse en cours, prêt, ou échec avec message lisible. */
sealed interface Phase {
    data object Idle : Phase
    data object Analyzing : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

/** État complet observé par les écrans Compose. */
data class UiState(
    val photo: Bitmap? = null,
    val photoB64: String = "",
    val analysis: Analysis? = null,
    val overlays: List<Bitmap> = emptyList(),
    val showMasks: Boolean = true,
    val phase: Phase = Phase.Idle,
    val segmentationActive: Boolean = false,
    val segmentationNote: String? = null,
    val chat: List<ChatTurn> = emptyList(),
    val asking: Boolean = false,
    val settings: Settings = Settings.Default,
    val settingsReady: Boolean = false,
    val statusLine: String? = null,
    val dictation: DictationState = DictationState(),
    val speak: SpeakState = SpeakState.Idle,
) {
    val objects: List<SegObject> get() = analysis?.objects.orEmpty()
    val canAsk: Boolean get() = photoB64.isNotBlank() && settings.llmConfigured && !asking
    val hasPhoto: Boolean get() = photo != null
}

/** Fabrique les dépendances (store + clients) à partir des réglages courants. */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store: SettingsStore = SettingsStoreFactory.create(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Lecteur audio (MediaPlayer) dans le cache privé : un seul fichier à la fois. */
    private val speech by lazy {
        SpeechPlayer(File(getApplication<Application>().cacheDir, "tts"), ::onSpeechFinished, ::onSpeechError)
    }
    private var speakJob: Job? = null

    /** Voix ElevenLabs des bandeaux de dictée (aucun TTS système dans l'app). */
    private val voice = VoiceAnnouncer(app)

    /** Appui long + bouton « Dicter » : toute la mécanique est dans [DictationFeature]. */
    private val dictation = DictationFeature(app, _state, viewModelScope) { settings -> speechClient(settings) }

    init {
        viewModelScope.launch { _state.update { it.copy(settings = seededFromBuild(store.load()), settingsReady = true) } }
        // Une seule règle : chaque nouveau bandeau est lu à voix haute (ElevenLabs).
        viewModelScope.launch {
            _state
                .map { it.dictation.overlay?.sequence to it.dictation.overlay?.spoken }
                .distinctUntilChanged()
                .collect { (_, spoken) ->
                    if (spoken != null) launch { voice.say(_state.value.settings, spoken) }
                }
        }
    }

    override fun onCleared() {
        speakJob?.cancel()
        speech.release()
        voice.release()
        super.onCleared()
    }

    private fun llm(settings: Settings): LlmClient =
        InferHubLlmClient(settings.llmBaseUrl, settings.llmApiKey, settings.llmModel)

    private fun sam(settings: Settings): SamClient? =
        if (!settings.samConfigured()) null else HttpSamClient(settings.samEndpoint())

    /** Une photo capturée ou choisie : on l'affiche tout de suite, puis on l'analyse. */
    fun onPhoto(file: File) = viewModelScope.launch {
        val bitmap = withContext(Dispatchers.IO) { decodeScaledExact(file) }
        if (bitmap == null) {
            _state.update { it.copy(phase = Phase.Failed("Image illisible : $file")) }
            return@launch
        }
        val encoded = withContext(Dispatchers.Default) { bitmapToJpegBase64(bitmap) }
        _state.update {
            UiState(
                photo = bitmap,
                photoB64 = encoded,
                showMasks = it.showMasks,
                settings = it.settings,
                settingsReady = it.settingsReady,
                phase = Phase.Analyzing,
            )
        }
        analyze(encoded)
    }

    private suspend fun analyze(encoded: String) {
        val settings = _state.value.settings
        val result = AnalyzeUseCase(
            llm(settings),
            sam(settings),
            maxObjects = settings.objectsOnScreen,
        ).invoke(encoded)
        result.fold(
            onSuccess = { analysis ->
                val overlays = buildOverlays(_state.value.photo, analysis.objects)
                _state.update {
                    it.copy(
                        analysis = analysis,
                        overlays = overlays,
                        phase = Phase.Ready,
                        segmentationActive = settings.segmentationEnabled && analysis.objects.isNotEmpty(),
                        segmentationNote = segmentationNote(settings, analysis),
                        chat = emptyList(),
                    )
                }
                announcePositions(analysis.objects, _state.value.photo)
            },
            onFailure = { error ->
                _state.update { it.copy(phase = Phase.Failed(error.message ?: "analyse impossible"), segmentationActive = false) }
            },
        )
    }

    /**
     * Dit à voix haute où sont les objets segmentés (« Personne à gauche, chaise au centre »).
     * Même bascule que le direct, même voix ElevenLabs ; silencieux sans objet ou sans clé.
     */
    private fun announcePositions(objects: List<SegObject>, photo: Bitmap?) {
        val settings = _state.value.settings
        if (!settings.announcePositions || photo == null) return
        val placed = objects.mapNotNull {
            PositionText.fromDetection(Detection(it.label, it.score, it.box, photo.width, photo.height))
        }
        val phrase = PositionText.phrase(placed) ?: return
        viewModelScope.launch { voice.say(settings, phrase) }
    }

    private fun segmentationNote(settings: Settings, analysis: Analysis): String? = when {
        !settings.segmentationEnabled -> "Segmentation désactivée — renseigne un endpoint SAM dans Réglages."
        analysis.objects.isEmpty() -> "Aucun objet segmenté sur cette image."
        else -> null
    }

    private suspend fun buildOverlays(photo: Bitmap?, objects: List<SegObject>): List<Bitmap> {
        val base = photo ?: return emptyList()
        return withContext(Dispatchers.Default) {
            // Même helper que le direct : décodage selon l'encodage, coloriage par table.
            objects.mapIndexedNotNull { index, obj ->
                colorizedMaskBitmap(obj.maskPngB64, MASK_ENCODING_PNG, maskArgb(index), MASK_ALPHA)
            }
        }
    }

    private fun maskArgb(index: Int): Int = maskColorAt(index).toArgb()

    fun ask(question: String) = viewModelScope.launch {
        val snapshot = _state.value
        if (snapshot.photoB64.isBlank() || question.isBlank() || !snapshot.settings.llmConfigured) return@launch
        _state.update { it.copy(asking = true, statusLine = null) }
        val answer = AskUseCase(llm(snapshot.settings))
            .invoke(snapshot.photoB64, question.trim(), snapshot.chat)
        answer.fold(
            onSuccess = { text ->
                val useCase = AskUseCase(llm(snapshot.settings))
                _state.update {
                    it.copy(chat = useCase.nextHistory(it.chat, question.trim(), text), asking = false)
                }
                if (snapshot.settings.ttsAutoRead) speak(text)
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        asking = false,
                        statusLine = "Question sans réponse : ${error.message ?: "erreur"}",
                    )
                }
            },
        )
    }

    fun retryAnalysis() = viewModelScope.launch {
        val encoded = _state.value.photoB64
        if (encoded.isNotBlank()) {
            _state.update { it.copy(phase = Phase.Analyzing) }
            analyze(encoded)
        }
    }

    /** Le mode direct alimente le chat sans passer par une capture. */
    fun useLiveFrame(jpegB64: String) {
        if (jpegB64.isBlank()) return
        _state.update { it.copy(photoB64 = jpegB64) }
    }

    fun toggleMasks() = _state.update { it.copy(showMasks = !it.showMasks) }

    fun saveSettings(next: Settings) = viewModelScope.launch {
        val cleaned = withTtsDefaults(next).copy(maxObjects = next.objectsOnScreen)
        store.save(cleaned)
        _state.update { it.copy(settings = cleaned, statusLine = "Réglages enregistrés.") }
    }

    fun testLlm() = viewModelScope.launch {
        val settings = _state.value.settings
        _state.update { it.copy(statusLine = "Test du LLM en cours…") }
        val bitmap = testBitmap()
        val outcome = llm(settings).describe(bitmapToJpegBase64(bitmap), listOf("vert"))
        _state.update {
            it.copy(statusLine = outcome.fold(
                onSuccess = { "LLM OK — « ${it.description.take(70)} »" },
                onFailure = { error -> "LLM KO — ${error.message}" },
            ))
        }
    }

    /**
     * Réveil préventif du GPU : un appel `/health` lance le démarrage du conteneur Modal
     * (30-40 s à froid) sans attendre de frame. Lancé à l'affichage de l'écran caméra,
     * il fait passer ce délai derrière la préparation du cadrage : le tap sur « Direct »
     * trouve alors une carte chaude. Ignoré si un réveil a déjà été tenté dans la fenêtre
     * [PREWARM_WINDOW_MS] (évite de rallumer la carte à chaque navigation).
     */
    fun prewarmGpu() {
        val settings = _state.value.settings
        // Endpoint résolu (saisie manuelle > région EU/US > build) : sans ça, un utilisateur
        // qui a choisi une région sans saisir d'URL n'aurait aucun réveil.
        val endpoint = settings.resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL)
        if (endpoint.isBlank()) return
        val now = System.currentTimeMillis()
        if (!shouldPrewarm(now, lastPrewarmAt, PREWARM_WINDOW_MS)) return
        lastPrewarmAt = now
        viewModelScope.launch {
            // Résultat ignoré : seul le démarrage du conteneur nous intéresse ici.
            HttpSamClient(endpoint).health()
        }
    }

    /** Horodatage du dernier réveil préventif (0 = jamais). */
    private var lastPrewarmAt = 0L

    /**
     * Extinction volontaire du conteneur GPU à la fermeture de l'app.
     *
     * Hors `viewModelScope` **exprès** : à `onDestroy`, le scope du ViewModel est annulé,
     * l'appel serait tué avant de partir. Le scope dédié survit le temps de la requête.
     */
    fun releaseGpu() {
        val endpoint = _state.value.settings
            .resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL)
        if (endpoint.isBlank()) return
        releaseScope.launch {
            releaseSam(endpoint)
        }
    }

    /** Scope d'extinction : indépendant du cycle de vie de l'écran (voir [releaseGpu]). */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * L'app passe derrière : on n'éteint pas tout de suite (un changement d'app de 5 s ne
     * doit pas coûter une restauration), mais on coupe après [BACKGROUND_RELEASE_MS] sans
     * retour. Annulé par [cancelBackgroundRelease] au retour au premier plan.
     */
    fun scheduleBackgroundRelease() {
        backgroundRelease?.cancel()
        backgroundRelease = releaseScope.launch {
            kotlinx.coroutines.delay(BACKGROUND_RELEASE_MS)
            releaseSam(endpointOrBlank()). onFailure {
                android.util.Log.w("sam3vision", "extinction GPU après fond : ${it.message}")
            }
        }
    }

    /** L'app revient : le réveil se fera tout seul au premier appel, on annule la coupure. */
    fun cancelBackgroundRelease() {
        backgroundRelease?.cancel()
        backgroundRelease = null
    }

    private var backgroundRelease: Job? = null

    /** Endpoint résolu (saisie manuelle > région > build), vide si rien à interroger. */
    private fun endpointOrBlank(): String = _state.value.settings
        .resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL)

    fun testSam() = viewModelScope.launch {
        val settings = _state.value.settings
        val url = settings.samEndpoint()
        if (url.isBlank()) {
            _state.update { it.copy(statusLine = "SAM KO — aucune base URL renseignée.") }
            return@launch
        }
        _state.update { it.copy(statusLine = "Test SAM en cours… (${settings.samRegion.code})") }
        val outcome = HttpSamClient(url).health()
        _state.update {
            it.copy(statusLine = outcome.fold(
                onSuccess = { ok -> if (ok) "SAM OK — endpoint joignable." else "SAM KO — /health a répondu faux." },
                onFailure = { error -> "SAM KO — ${error.message}" },
            ))
        }
    }

    // ————— Dictée (appui long partout, bouton « Dicter » dans le chat) —————

    /** Bouton « Dicter » du chat : ouvre le micro jusqu'au prochain Stop. */
    fun startDictation() = dictation.start(hold = false)

    fun stopDictation() = dictation.stop(hold = false)

    /** Appui long maintenu sur la surface de l'app : le micro s'ouvre, sans limite. */
    fun onHoldStart() = dictation.start(hold = true)

    /** Doigt relâché : coupe le micro et envoie l'audio au moteur de dictée. */
    fun onHoldEnd() = dictation.stop(hold = true)

    /** Permission micro refusée au lancement : bandeau d'erreur, lu à la voix. */
    fun onMicDenied() = dictation.onMicDenied()

    fun dismissOverlay() = dictation.dismissOverlay()

    /** Vérifie le moteur de dictée avec 1 s de silence : seule la connexion est testée. */
    fun testAsr() = viewModelScope.launch {
        val client = speechClient(_state.value.settings)
        if (client == null) {
            _state.update { it.copy(statusLine = "ASR KO — aucun moteur de dictée (Réglages).") }
            return@launch
        }
        _state.update { it.copy(statusLine = "Test ASR en cours…") }
        val silence = ByteArray(Wav.SAMPLE_RATE * Wav.BYTES_PER_SAMPLE)
        val outcome = client.transcribe(Wav.pcm16(silence))
        _state.update {
            it.copy(
                statusLine = outcome.fold(
                    onSuccess = { text -> "ASR OK — endpoint joignable (${text.ifBlank { "texte vide" }.take(40)})" },
                    onFailure = { error -> "ASR KO — ${error.message}" },
                ),
            )
        }
    }

    // ————— Voix ElevenLabs (chat, analyse, réglages) —————

    /** Lit [text] à voix haute via ElevenLabs ; une seule phrase active à la fois. */
    fun speak(text: String) {
        val settings = _state.value.settings
        val refusal = speakRefusal(text, settings.ttsConfigured)
        speakJob?.cancel()
        speech.stop()
        if (refusal != null) {
            failSpeech(text.trim(), refusal)
            return
        }
        speakJob = viewModelScope.launch { synthesizeAndPlay(settings, text.trim()) }
    }

    /** Bouton « Tester la voix » des réglages. */
    fun testVoice() = speak(TEST_VOICE_TEXT)

    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        speech.stop()
        _state.update { it.copy(speak = SpeakState.Idle) }
    }

    /** Synthèse bornée dans le temps : jamais d'état « en cours » sans fin. */
    private suspend fun synthesizeAndPlay(settings: Settings, phrase: String) {
        _state.update { it.copy(speak = SpeakState.Synthesizing(phrase)) }
        val audio = withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
            ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId).synthesize(phrase)
        }
        if (audio == null) {
            failSpeech(phrase, SPEAK_TIMEOUT)
            return
        }
        audio.fold(
            onSuccess = { bytes -> playAudio(bytes, phrase) },
            onFailure = { error -> failSpeech(phrase, error.message ?: "voix indisponible") },
        )
    }

    private suspend fun playAudio(bytes: ByteArray, phrase: String) {
        _state.update { it.copy(speak = SpeakState.Playing(phrase)) }
        speech.play(bytes).onFailure { error ->
            failSpeech(phrase, "lecture impossible : ${error.message ?: "erreur"}")
        }
    }

    private fun failSpeech(phrase: String, message: String) =
        _state.update { it.copy(speak = SpeakState.Error(phrase, message)) }

    /** MediaPlayer signale la fin de lecture depuis son propre thread : StateFlow est sûr. */
    private fun onSpeechFinished() {
        _state.update { st ->
            val current = st.speak
            if (current is SpeakState.Playing) st.copy(speak = SpeakState.Done(current.text)) else st
        }
    }

    private fun onSpeechError(message: String) {
        _state.update { st ->
            val current = st.speak
            if (current is SpeakState.Playing) st.copy(speak = SpeakState.Error(current.text, message)) else st
        }
    }

    fun reset() {
        stopSpeaking()
        dictation.abort()
        _state.update { UiState(settings = it.settings, settingsReady = it.settingsReady) }
    }

    private companion object {
        /** Fenêtre anti-rallumage : deux réveils rapprochés ne servent à rien. */
        const val PREWARM_WINDOW_MS = 120_000L

        /** Delai sans retour avant de couper le GPU quand l'app est en arriere-plan. */
        const val BACKGROUND_RELEASE_MS = 60_000L
    }
}
