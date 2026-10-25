package dev.sam3vision.app.ihear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.IhearHaptics
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.decodeScaledExact
import dev.sam3vision.app.media.CacheCleanerManager
import dev.sam3vision.app.media.HoldTranscript
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MediaDatabase
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.net.ElevenLabsSttClient
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.net.FallbackSpeechClient
import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.net.MetaAsrClient
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.usecase.AskUseCase
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Les deux pages d'iHear, côte à côte. */
enum class IhearPage { Chat, Camera }

/** Une bulle de l'écran chat : le texte du user, ou celui lu à voix haute. */
sealed interface ChatEntry {
    val id: Long
    val text: String
    val audioPath: String?

    data class UserBubble(override val id: Long, override val text: String, override val audioPath: String?) : ChatEntry
    data class AiBubble(override val id: Long, override val text: String, override val audioPath: String?) : ChatEntry
}

/** État complet des deux panneaux iHear. */
data class IhearUiState(
    val page: IhearPage = IhearPage.Camera,
    val entries: List<ChatEntry> = emptyList(),
    val selected: Int = -1,
    val firstSwipeDownDone: Boolean = false,
    val holding: Boolean = false,
    val transcribing: Boolean = false,
    val thinking: Boolean = false,
    val speakingEntryId: Long? = null,
    val recordingVideo: Boolean = false,
    val status: String? = null,
) {
    val lastSelected: Boolean get() = selected >= entries.lastIndex
}

/** Les gestes que la caméra du panneau vidéo est la seule à pouvoir exécuter. */
interface IhearCameraActions {
    val ready: Boolean
    fun startRecording(file: File): Boolean
    suspend fun stopRecording(): File?
    fun takePhoto(target: File): Boolean
}

/**
 * Toute la mécanique iHear : deux pages (chat + caméra), appui long vocal transcrit
 * par Muse, historique Room `MessageMultimedia`, MP3 de chaque côté en cache, purge
 * A/B/C, voix ElevenLabs. Les écrans existants restent intacts : iHear est un écran
 * d'accueil de plus.
 */
class IhearViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SettingsStore = SettingsStoreFactory.create(app)
    private val dao = MediaDatabase.get(app).messages()
    private val media = MediaCacheStore(app, dao)
    private val cleaner = CacheCleanerManager(dao, media.dir())
    private val haptics = IhearHaptics(app)
    private val mic = HoldTranscript()

    private val player = SpeechPlayer(
        dir = File(app.cacheDir, "ihear-play"),
        onComplete = { _state.update { st -> st.copy(speakingEntryId = null) } },
        onPlaybackError = { _state.update { st -> st.copy(speakingEntryId = null, status = "audio playback failed: $it") } },
    )

    private val _state = MutableStateFlow(IhearUiState())
    val state: StateFlow<IhearUiState> = _state

    /** Vrai quand les panneaux iHear sont à l'écran : les gestes ne touchent qu'eux. */
    @Volatile
    var active: Boolean = false

    /** Caméra du panneau vidéo, câblée par le composable à l'attachement. */
    var camera: IhearCameraActions? = null

    /** Dernière photo du double tap : contexte image de la question suivante. */
    private var photoContext: File? = null

    private var settings: Settings = Settings.Default

    init {
        viewModelScope.launch {
            settings = prefs.load()
            refreshHistory()
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    fun attachCamera(actions: IhearCameraActions) {
        camera = actions
    }

    fun detachCamera() {
        camera = null
    }

    /** Réglages enregistrés depuis l'écran Settings : rechargement brûlant. */
    fun refreshSettings() = viewModelScope.launch {
        settings = prefs.load()
        refreshHistory()
    }

    /** Historique Room reconstruit en bulles (après chaque écriture et chaque purge). */
    private suspend fun refreshHistory() {
        val entries = dao.all().flatMap(::entriesOf)
        _state.update {
            it.copy(entries = entries, selected = if (entries.isEmpty()) -1 else entries.lastIndex)
        }
    }

    /** Une ligne = deux bulles possibles : « user dit… », puis « IA répond… ». */
    private fun entriesOf(row: MessageMultimedia): List<ChatEntry> = buildList {
        row.texteUtilisateur?.takeIf { it.isNotBlank() }?.let {
            add(ChatEntry.UserBubble(bubbleId(row.id, false), it.trim(), row.cheminAudioUtilisateur))
        }
        row.texteIA?.takeIf { it.isNotBlank() }?.let {
            add(ChatEntry.AiBubble(bubbleId(row.id, true), it.trim(), row.cheminAudioElevenLabs))
        }
    }

    // ————— Gestes —————

    /** Appui long maintenu : le micro s'ouvre (dictée Muse), salve haptique dédiée. */
    fun onHoldStart() {
        if (!active) return
        _state.update { it.copy(holding = true, status = null) }
        haptics.holdStart()
        if (!mic.start()) {
            _state.update { it.copy(status = "Microphone unavailable") }
        }
    }

    /** Doigt relâché : micro coupé, transcription, MP3 en cache, réponse IA parlée. */
    fun onHoldEnd() {
        if (!active) return
        haptics.holdEnd()
        if (!_state.value.holding) return
        _state.update { it.copy(holding = false) }
        viewModelScope.launch { transcribeAndAnswer() }
    }

    /** Balayage horizontal : passage chat ⇄ caméra (le doigt va vers la gauche = caméra). */
    fun onHorizontalSwipe(rightToLeft: Boolean) {
        if (!active) return
        _state.update { it.copy(page = if (rightToLeft) IhearPage.Camera else IhearPage.Chat) }
    }

    /** Balayage vertical commencé : vibration distincte tant que le doigt glisse. */
    fun onVerticalDragStart() {
        if (!active) return
        haptics.verticalSwipeStart()
    }

    /** Balayage vertical terminé : marche d'une bulle, ou lue si c'est la première. */
    fun onVerticalSwipeEnd(committed: Boolean, downward: Boolean) {
        if (!active) return
        haptics.verticalSwipeEnd()
        val snapshot = _state.value
        if (!committed || snapshot.recordingVideo || snapshot.entries.isEmpty()) return
        viewModelScope.launch {
            val state = _state.value
            val firstGesture = !state.firstSwipeDownDone
            if (downward) {
                when {
                    firstGesture && state.lastSelected -> {
                        _state.update { it.copy(firstSwipeDownDone = true) }
                        playSelected()
                    }
                    state.selected > 0 -> {
                        _state.update { it.copy(selected = it.selected - 1) }
                        playSelected()
                    }
                }
            } else if (state.selected < state.entries.lastIndex) {
                _state.update { it.copy(selected = it.selected + 1) }
                playSelected()
            }
        }
    }

    /** Tap simple n'importe où : démarre la vidéo (page caméra), ou la ferme. */
    fun onSingleTap() {
        if (!active) return
        if (_state.value.recordingVideo) stopVideo() else startVideo()
    }

    /** Double tap rapide : photo immédiate en cache (contexte visuel du prochain échange). */
    fun onDoubleTap() {
        if (!active || _state.value.recordingVideo) return
        val target = media.newPhotoFile()
        if (camera?.takePhoto(target) == true) photoContext = target
    }

    // ————— Appui long : micro → texte → réponse parlée —————

    private suspend fun transcribeAndAnswer() {
        if (!playerIdle()) return
        _state.update { it.copy(transcribing = true) }
        val client = speechClient()
        val outcome = when {
            client == null -> Result.failure(IllegalStateException("No dictation engine configured (Settings)"))
            else -> mic.stop(client)
        }
        _state.update { it.copy(transcribing = false) }
        val (text, pcm) = outcome.fold(
            onSuccess = { it },
            onFailure = { error ->
                mic.abort()
                _state.update { it.copy(status = "Dictation: ${error.message ?: "failed"}") }
                return
            },
        )
        handleDictated(text, pcm)
    }

    /** Texte transcrit : ligne Room + MP3, puis la question part au LLM avec la photo. */
    private suspend fun handleDictated(text: String, pcm: ByteArray) {
        if (text.isBlank()) {
            mic.abort()
            _state.update { it.copy(status = "Nothing heard") }
            return
        }
        val row = runCatching { media.saveUserMessage(pcm, text) }
            .getOrElse { media.saveUserMessageWithoutAudio(text) }
        enforceFifo()
        refreshHistory()
        ask(row, text)
    }

    /** Question → réponse LLM (image = dernière double-photo) → texte Room → voix. */
    private suspend fun ask(row: MessageMultimedia, question: String) {
        if (!settings.llmConfigured) {
            _state.update { it.copy(status = "No LLM key configured (Settings)") }
            return
        }
        _state.update { it.copy(thinking = true) }
        val outcome = AskUseCase(InferHubLlmClient(settings.llmBaseUrl, settings.llmApiKey, settings.llmModel))
            .invoke(imagePathToB64(), question, buildChatHistory())
        _state.update { it.copy(thinking = false) }
        outcome.fold(
            onSuccess = { answer ->
                media.setAnswerText(row, answer)
                val bytes = synthesize(answer)
                if (bytes.isNotEmpty()) {
                    media.saveReplyAudio(dao.byId(row.id) ?: row, bytes)
                    enforceFifo()
                    refreshHistory()
                    speakLatestAnswer()
                }
            },
            onFailure = { error ->
                _state.update { it.copy(status = "Question failed: ${error.message ?: "error"}") }
            },
        )
    }

    /** Synthèse ElevenLabs du texte ; octets vides si la clé manque (le choix reste dit). */
    private suspend fun synthesize(text: String): ByteArray =
        ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId)
            .synthesize(text)
            .getOrElse { error ->
                _state.update { it.copy(status = "Voice: ${error.message ?: "unavailable"}") }
                ByteArray(0)
            }

    /** La réponse la plus récente est jouée à voix haute dès son arrivée. */
    private suspend fun speakLatestAnswer() {
        val row = dao.all().lastOrNull { !it.texteIA.isNullOrBlank() } ?: return
        playFromRow(row, preferAi = true)
    }

    /** Lit la bulle sélectionnée (audio de la ligne Room, ou synthèse à la volée). */
    private suspend fun playSelected() {
        val entry = _state.value.entries.getOrNull(_state.value.selected) ?: return
        val row = dao.byId(entry.id / BUBBLE_STRIDE) ?: return
        playFromRow(row, preferAi = entry is ChatEntry.AiBubble)
    }

    /** Décide du fichier à lire : audio du user, réponse ElevenLabs, ou synthèse. */
    private suspend fun playFromRow(row: MessageMultimedia, preferAi: Boolean) {
        val existing = when {
            preferAi -> media.replyAudioFile(row) ?: row.cheminAudioUtilisateur?.let(::existingFile)
            else -> row.cheminAudioUtilisateur?.let(::existingFile) ?: media.replyAudioFile(row)
        }
        if (existing != null) return playAudio(existing, row, preferAi)
        val raw = if (preferAi) row.texteIA else row.texteUtilisateur
        val text = raw?.takeIf { it.isNotBlank() } ?: return
        val bytes = synthesize(text)
        if (bytes.isEmpty()) return
        val updated = dao.byId(row.id) ?: row
        media.saveReplyAudio(updated, bytes)
        val saved = media.replyAudioFile(dao.byId(row.id) ?: updated) ?: return
        playAudio(saved, updated, preferAi)
    }

    /** Une seule source sonore à la fois : l'ID de la bulle en cours porte les icônes. */
    private suspend fun playAudio(file: File, row: MessageMultimedia, preferAi: Boolean) {
        val bubbleId = bubbleId(row.id, preferAi)
        _state.update { it.copy(speakingEntryId = bubbleId, status = null) }
        player.playFile(file).onFailure { cause ->
            _state.update { st ->
                st.copy(
                    speakingEntryId = if (st.speakingEntryId == bubbleId) null else st.speakingEntryId,
                    status = "audio playback failed: $cause",
                )
            }
        }
    }

    // ————— Vidéo (tap) —————

    private fun startVideo() {
        val file = media.newVideoFile()
        val actions = camera
        if (actions == null || !actions.ready || !actions.startRecording(file)) {
            _state.update { it.copy(status = "Camera not ready — try again") }
            return
        }
        _state.update { it.copy(recordingVideo = true, page = IhearPage.Camera, status = null) }
    }

    /** Second tap : finalisation du MP4, ligne Room « vidéo d'environnement », FIFO. */
    private fun stopVideo() {
        val actions = camera
        _state.update { it.copy(recordingVideo = false) }
        viewModelScope.launch {
            val file = actions?.stopRecording()
            if (file != null && file.isFile && file.length() > 0L) {
                media.saveEnvironmentVideo(file)
                enforceFifo()
            } else {
                _state.update { it.copy(status = "Recording lost") }
            }
        }
    }

    // ————— Coulisses —————

    private suspend fun buildChatHistory(): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        for (row in dao.all()) {
            row.texteUtilisateur?.takeIf { it.isNotBlank() }?.let { turns.add(ChatTurn(Role.USER, it.trim())) }
            row.texteIA?.takeIf { it.isNotBlank() }?.let { turns.add(ChatTurn(Role.ASSISTANT, it.trim())) }
        }
        return turns.takeLast(CHAT_HISTORY)
    }

    private fun playerIdle(): Boolean =
        _state.value.speakingEntryId == null

    /** Moteur de dictée : Meta d'abord, repli ElevenLabs Scribe dès que la clé existe. */
    private fun speechClient(): SpeechClient? {
        val meta = if (settings.asrConfigured) {
            MetaAsrClient(settings.asrBaseUrl, settings.asrApiKey, settings.asrModel)
        } else {
            null
        }
        val eleven = if (settings.ttsApiKey.isNotBlank()) ElevenLabsSttClient(settings.ttsApiKey) else null
        return when {
            meta != null && eleven != null -> FallbackSpeechClient(primary = meta, fallback = eleven)
            meta != null -> meta
            else -> eleven
        }
    }

    /** La photo du dernier double tap, réencodée en base64 JPEG (contexte visuel). */
    private fun imagePathToB64(): String {
        val file = photoContext ?: return ""
        return decodeScaledExact(file)?.let { bitmapToJpegBase64(it) } ?: ""
    }

    private fun existingFile(path: String): File? = File(path).takeIf { it.isFile }

    private fun bubbleId(rowId: Long, ai: Boolean): Long = rowId * BUBBLE_STRIDE + if (ai) 1L else 0L

    /** Sécurité FIFO silencieuse après chaque écriture de fichier multimédia. */
    private suspend fun enforceFifo() {
        runCatching { cleaner.enforceFifoAfterWrite() }
    }

    /** Purge totale à la fermeture de l'activité (option A) : table + cache. */
    fun onAppClosed() {
        viewModelScope.launch { cleaner.purgeAll() }
    }

    /** L'app part derrière : minuteur d'inactivité de 30 min armé (option C). */
    fun onBackground() {
        cleaner.onPause()
    }

    /** L'app revient au premier plan : minuteur annulé (option C). */
    fun onForeground() {
        cleaner.onResume()
    }

    companion object {
        /** Les bulles (2 possibles par ligne) sont distinguées par un décalage sûr. */
        const val BUBBLE_STRIDE = 1_000_000L
        const val CHAT_HISTORY = 20
    }
}
