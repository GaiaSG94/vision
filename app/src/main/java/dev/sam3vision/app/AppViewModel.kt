package dev.sam3vision.app

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.toArgb
import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.mask.colorizeMask
import dev.sam3vision.core.mask.decodeMaskPng
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.HttpSamClient
import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.MetaAsrClient
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.usecase.AskUseCase
import dev.sam3vision.core.usecase.AnalyzeUseCase
import dev.sam3vision.core.usecase.DictationUseCase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Étape de la vue : idle, analyse en cours, prêt, ou échec avec message lisible. */
sealed interface Phase {
    data object Idle : Phase
    data object Analyzing : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

/** Dictée Meta : micro ouvert, transcription en cours, dernier texte produit. */
data class DictationState(
    val recording: Boolean = false,
    val busy: Boolean = false,
    val text: String = "",
    /** Incrémenté à chaque nouveau texte : l'écran sait qu'il doit l'insérer. */
    val seq: Int = 0,
)

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

    /** Micro réutilisé d'une dictée à l'autre (AudioRecord coûteux à créer). */
    private var recorder: DictationRecorder? = null

    init {
        viewModelScope.launch { _state.update { it.copy(settings = seeded(store.load()), settingsReady = true) } }
    }

    /** Applique les valeurs de build (debug) quand rien n'a encore été saisi. */
    private fun seeded(loaded: Settings): Settings {
        val fromBuild = BuildConfig.LLM_API_KEY
        if (loaded.llmApiKey.isNotBlank() || fromBuild.isBlank()) return loaded
        return loaded.copy(
            llmApiKey = fromBuild,
            llmBaseUrl = loaded.llmBaseUrl.ifBlank { BuildConfig.LLM_BASE_URL },
            llmModel = loaded.llmModel.ifBlank { BuildConfig.LLM_MODEL },
        )
    }

    private fun llm(settings: Settings): LlmClient =
        InferHubLlmClient(settings.llmBaseUrl, settings.llmApiKey, settings.llmModel)

    private fun sam(settings: Settings): SamClient? =
        if (settings.samBaseUrl.isBlank()) null else HttpSamClient(settings.samBaseUrl)

    private fun speech(settings: Settings): SpeechClient? =
        if (!settings.asrConfigured) {
            null
        } else {
            MetaAsrClient(settings.asrBaseUrl, settings.asrApiKey, settings.asrModel)
        }

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
        val result = AnalyzeUseCase(llm(settings), sam(settings)).invoke(encoded)
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
            },
            onFailure = { error ->
                _state.update { it.copy(phase = Phase.Failed(error.message ?: "analyse impossible"), segmentationActive = false) }
            },
        )
    }

    private fun segmentationNote(settings: Settings, analysis: Analysis): String? = when {
        !settings.segmentationEnabled -> "Segmentation désactivée — renseigne un endpoint SAM dans Réglages."
        analysis.objects.isEmpty() -> "Aucun objet segmenté sur cette image."
        else -> null
    }

    private suspend fun buildOverlays(photo: Bitmap?, objects: List<SegObject>): List<Bitmap> {
        val base = photo ?: return emptyList()
        return withContext(Dispatchers.Default) {
            objects.mapIndexedNotNull { index, obj ->
                decodeMaskPng(obj.maskPngB64)?.let { gray -> colorizeMask(gray, maskArgb(index), MASK_ALPHA) }
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

    fun toggleMasks() = _state.update { it.copy(showMasks = !it.showMasks) }

    fun saveSettings(next: Settings) = viewModelScope.launch {
        store.save(next)
        _state.update { it.copy(settings = next, statusLine = "Réglages enregistrés.") }
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

    fun testSam() = viewModelScope.launch {
        val settings = _state.value.settings
        if (settings.samBaseUrl.isBlank()) {
            _state.update { it.copy(statusLine = "SAM KO — aucune base URL renseignée.") }
            return@launch
        }
        _state.update { it.copy(statusLine = "Test SAM en cours…") }
        val outcome = HttpSamClient(settings.samBaseUrl).health()
        _state.update {
            it.copy(statusLine = outcome.fold(
                onSuccess = { ok -> if (ok) "SAM OK — endpoint joignable." else "SAM KO — /health a répondu faux." },
                onFailure = { error -> "SAM KO — ${error.message}" },
            ))
        }
    }

    /** Ouvre le micro (le PCM reste en mémoire, jamais sur disque). */
    fun startDictation() {
        val snapshot = _state.value
        if (snapshot.dictation.recording || snapshot.dictation.busy) return
        if (speech(snapshot.settings) == null) {
            _state.update { it.copy(statusLine = "Dictée KO — clé Meta absente (Réglages).") }
            return
        }
        val mic = recorder ?: DictationRecorder().also { recorder = it }
        if (!mic.start()) {
            _state.update { it.copy(statusLine = "Dictée KO — micro indisponible (permission refusée ?).") }
            return
        }
        _state.update {
            it.copy(
                dictation = it.dictation.copy(recording = true),
                statusLine = "Enregistrement… parle, puis appuie sur Stop.",
            )
        }
    }

    /** Coupe le micro puis transcrit le PCM capturé via Meta Muse. */
    fun stopDictation() = viewModelScope.launch {
        val mic = recorder ?: return@launch
        if (!_state.value.dictation.recording) return@launch
        _state.update {
            it.copy(
                dictation = it.dictation.copy(recording = false, busy = true),
                statusLine = "Transcription Meta en cours…",
            )
        }
        val pcm = withContext(Dispatchers.IO) { mic.stopAndCollect() }
        val client = speech(_state.value.settings)
        val outcome = if (client == null) {
            Result.failure(ApiError.InvalidKey("clé Meta absente"))
        } else {
            DictationUseCase(client).invoke(pcm)
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { text ->
                    current.copy(
                        dictation = current.dictation.copy(busy = false, text = text, seq = current.dictation.seq + 1),
                        statusLine = null,
                    )
                },
                onFailure = { error ->
                    current.copy(
                        dictation = current.dictation.copy(busy = false),
                        statusLine = "Dictée KO — ${error.message}",
                    )
                },
            )
        }
    }

    /** Vérifie l'endpoint Meta avec 1 s de silence : seule la connexion est testée. */
    fun testAsr() = viewModelScope.launch {
        val client = speech(_state.value.settings)
        if (client == null) {
            _state.update { it.copy(statusLine = "ASR KO — clé Meta absente (Réglages).") }
            return@launch
        }
        _state.update { it.copy(statusLine = "Test Meta Muse ASR en cours…") }
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

    fun reset() {
        viewModelScope.launch(Dispatchers.IO) { recorder?.stopAndCollect() }
        _state.update { UiState(settings = it.settings, settingsReady = it.settingsReady) }
    }
}
