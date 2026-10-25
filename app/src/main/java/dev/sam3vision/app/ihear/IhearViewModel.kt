package dev.sam3vision.app.ihear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sam3vision.app.IhearHaptics
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.decodeScaledExact
import dev.sam3vision.app.speechClient
import dev.sam3vision.app.isIntroDisablePhrase
import dev.sam3vision.app.seededFromBuild
import dev.sam3vision.app.media.CacheCleanerManager
import dev.sam3vision.app.media.HoldTranscript
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MediaDatabase
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.usecase.AskUseCase
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Toute la mécanique de l'écran unique (chat, caméra dans une bulle : vidéo du tap, photo
 * du double tap) : appui long vocal transcrit, historique Room, MP3 en cache, purge A/B/C,
 * voix ElevenLabs. L'intro embarquée est dite à chaque ouverture complète tant que le user
 * ne l'a pas coupée par la voix ; son texte reste le premier message.
 */
class IhearViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SettingsStore = SettingsStoreFactory.create(app)
    private val dao = MediaDatabase.get(app).messages()
    private val media = MediaCacheStore(app, dao)
    private val cleaner = CacheCleanerManager(dao, media.dir())
    private val haptics = IhearHaptics(app)

    /** Distances de la photo du double tap : une pastille par objet mesuré. */
    private val photoDistances = IhearPhotoDistances()
    private val mic = HoldTranscript()

    private val player = SpeechPlayer(
        dir = File(app.cacheDir, "ihear-play"),
        onComplete = {
            // L'intro reste posée à l'écran : seule la coupure par la voix la retire.
            _state.update { st -> st.copy(speakingEntryId = null) }
        },
        onPlaybackError = { _state.update { st -> st.copy(speakingEntryId = null) } },
    )

    private val _state = MutableStateFlow(IhearUiState())
    val state: StateFlow<IhearUiState> = _state

    /** SAM pendant l'enregistrement : masques, distances, latence et phrase à dire. */
    val probe = LiveProbe(viewModelScope)

    /** Voix de proximité : dit les objets et leur distance une fois par scène. */
    private val proximity = ProximityVoice(player) { settings }

    /** Voix de l'intro (le texte écrit, lui, ne part jamais). */
    private val intro = IhearIntro(getApplication(), player, { settings }, { settings = it }, prefs, { fn -> _state.update(fn) })

    /** Vrai quand l'écran iSee est à l'écran : les gestes ne touchent que lui. */
    @Volatile
    var active: Boolean = false

    /** Caméra de la fenêtre flottante, câblée par le composable à l'attachement. */
    var camera: IhearCameraActions? = null

    /** Photo-contexte de la question suivante : frame vivante de l'appui long ou gel du double tap. */
    private val photoCtx = IhearPhotoContext()

    /** Minuteur d'évaporation de la photo (relancé à chaque photo en rafale). */
    private var photoEvaporation: Job? = null

    private var settings: Settings = Settings.Default

    init {
        // Le texte d'intro est le premier message une fois pour toutes.
        _state.update { it.copy(introVisible = true) }
        viewModelScope.launch {
            settings = seededFromBuild(prefs.load())
            syncProbe()
            refreshHistory()
            intro.playIfPending()
        }
        probe.observeSpoken(viewModelScope, proximity) { playerIdle() }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    fun attachCamera(actions: IhearCameraActions) {
        camera = actions
        photoDistances.setLens(actions.lensSpec)
    }

    fun detachCamera() {
        camera = null
    }

    /** Réglages enregistrés depuis l'écran Settings : rechargement brûlant. */
    fun refreshSettings() = viewModelScope.launch {
        settings = seededFromBuild(prefs.load())
        syncProbe()
        refreshHistory()
    }

    /** Les Réglages pilotent la sonde SAM (endpoint, voix, focale, distances). */
    private fun syncProbe() = probe.configureFrom(settings)

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

    /** Appui long : la frame courante devient le contexte (sauf photo gelée), puis le micro s'ouvre. */
    fun onHoldStart() {
        if (!active) return
        // Parler coupe la lecture en cours (sinon la tenue est jetée, le micro reste pris).
        if (!playerIdle()) {
            player.stop()
            _state.update { it.copy(speakingEntryId = null) }
        }
        photoCtx.captureForHold(camera, media::newPhotoFile)
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

    /** Tenue annulée (app en arrière-plan ou geste perdu) : micro relâché, rien de transcrit. */
    fun onHoldCancel() {
        if (!_state.value.holding) return
        mic.abort()
        haptics.holdEnd()
        _state.update { it.copy(holding = false) }
    }

    /** Balayage vertical commencé : vibration tant que le doigt glisse, sauf à bulle unique. */
    fun onVerticalDragStart() {
        if (!active) return
        // L'intro n'est sélectionnable que seule : la compter faisait vibrer un swipe inutile.
        if (!hasBubbleChoice(_state.value.entries.size)) return
        haptics.verticalSwipeStart()
    }

    /** Début du glissement vertical : la marche se joue ici (un geste = une bulle), pas
     * au relâchement — un balayage court emporte son effet. */
    fun onVerticalDragMove(downward: Boolean) {
        if (!active) return
        if (_state.value.recordingVideo) return
        viewModelScope.launch {
            val state = _state.value
            val total = state.entries.size
            if (state.entries.isEmpty()) {
                if (state.introVisible) intro.replay()
                return@launch
            }
            when (val step = swipeStep(state.entries.size, state.selected, state.firstSwipeDownDone, downward)) {
                is SwipeStep.Move -> {
                    _state.update { it.copy(selected = step.selected) }
                    playSelected()
                }
                is SwipeStep.Replay -> {
                    _state.update { it.copy(firstSwipeDownDone = step.firstSwipeDownDone) }
                    playSelected()
                }
                // Bulle unique ou bord atteint : pas de vibration, relecture seule.
                SwipeStep.NoMove -> {
                    if (!hasBubbleChoice(total)) playSelected()
                }
            }
        }
    }

    /** Relâchement : la marche a déjà été jouée au début du glissement ; on coupe
     * juste l'haptique — même en dehors d'iSee, pour ne pas laisser la vibration collée. */
    fun onVerticalSwipeEnd(committed: Boolean, downward: Boolean) {
        haptics.verticalSwipeEnd()
        if (!active) return
    }

    /** Tap simple n'importe où : démarre la vidéo (fenêtre flottante), ou la ferme. */
    fun onSingleTap() {
        if (!active) return
        if (_state.value.recordingVideo) stopVideo() else startVideo()
    }

    /** Double tap rapide : photo immédiate (texture, flash, évaporation en fenêtre). */
    fun onDoubleTap() {
        if (!active || _state.value.recordingVideo) return
        val target = media.newPhotoFile()
        val asked = camera?.takePhoto(target) { saved -> onPhotoSaved(target, saved) }
        if (asked != true) _state.update { it.copy(status = "Camera not ready — try again") }
    }

    /** Photo écrite : elle se montre dans la fenêtre, puis s'évapore (en rafale : remplace). */
    private fun onPhotoSaved(target: File, saved: Boolean) {
        if (!saved) return
        photoCtx.freeze(target)
        _state.update { it.copy(photoPath = target.path, distanceLayer = null, status = null) }
        armEvaporation(target, PHOTO_SHOW_MS)
        measurePhoto(target)
    }

    /** Minuteur d'évaporation de la photo ; remplacé à chaque photo d'une rafale. */
    private fun armEvaporation(target: File, showMs: Long) {
        photoEvaporation?.cancel()
        photoEvaporation = viewModelScope.launch {
            delay(showMs)
            if (_state.value.photoPath == target.path) {
                _state.update { it.copy(photoPath = null, distanceLayer = null) }
            }
        }
    }

    /** Distances de la photo : les pastilles arrivent après coup, la photo reste plus
     *  longtemps à l'écran, et chaque échec est dit dans le chat au lieu de rester muet. */
    private fun measurePhoto(target: File) {
        viewModelScope.launch {
            when (val outcome = photoDistances.measureOutcome(target, settings)) {
                is PhotoMeasure.Measured -> {
                    if (_state.value.photoPath != target.path) return@launch
                    _state.update { it.copy(distanceLayer = outcome.layer) }
                    armEvaporation(target, PHOTO_MEASURED_MS)
                }
                PhotoMeasure.OverlayOff ->
                    photoStatus(target, "Photo: distance overlay off (Settings)")
                PhotoMeasure.NoEndpoint ->
                    photoStatus(target, "Photo: SAM endpoint missing (Settings → Region)")
                PhotoMeasure.Unreadable ->
                    photoStatus(target, "Photo: image unreadable")
                is PhotoMeasure.Failed ->
                    photoStatus(target, "Photo analysis failed: ${outcome.reason}")
                PhotoMeasure.NoObjects ->
                    photoStatus(target, "Photo: no measurable objects")
            }
        }
    }

    /** Motif posé en état seulement si la photo est encore à l'écran. */
    private fun photoStatus(target: File, message: String) {
        if (_state.value.photoPath == target.path) {
            _state.update { it.copy(status = message) }
        }
    }

    // ————— Appui long : micro → texte → réponse parlée —————

    /** Fin de la tenue : transcription, coupe d'intro éventuelle, puis réponse IA. */
    private suspend fun transcribeAndAnswer() {
        if (!playerIdle()) {
            // Tenue pendant une lecture : capture jetée, micro jamais bloqué, et dit.
            mic.abort()
            _state.update { it.copy(status = "Busy speaking — try again") }
            return
        }
        _state.update { it.copy(transcribing = true) }
        val client = speechClient(settings)
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
        if (isIntroDisablePhrase(text)) {
            mic.abort()
            intro.disable()
            return
        }
        handleDictated(text, pcm)
    }

    /** Texte transcrit : coupe d'intro possible, ligne Room + MP3, puis question au LLM. */
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
        photoCtx.releaseFreeze()
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

    /** Synthèse ElevenLabs ; octets vides si la clé manque ou s'il n'y a rien à dire. */
    private suspend fun synthesize(text: String): ByteArray {
        if (!speakableText(text)) return ByteArray(0)
        return ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId)
            .synthesize(text)
            .getOrElse { error ->
                _state.update { it.copy(status = "Voice: ${error.message ?: "unavailable"}") }
                ByteArray(0)
            }
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

    // ————— Vidéo du tap (fenêtre flottante) —————

    private fun startVideo() {
        val file = media.newVideoFile()
        val actions = camera
        if (actions == null || !actions.ready || !actions.startRecording(file)) {
            _state.update { it.copy(status = "Camera not ready — try again") }
            return
        }
        probe.begin()
        proximity.reset()
        _state.update { it.copy(recordingVideo = true, status = null) }
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

    /** La photo-contexte (frame de l'appui long ou gel du double tap), réencodée en base64 JPEG. */
    private fun imagePathToB64(): String =
        photoCtx.file?.let { decodeScaledExact(it) }?.let { bitmapToJpegBase64(it) } ?: ""

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

        /** Une photo du double tap reste à l'écran avant de s'évaporer. */
        const val PHOTO_SHOW_MS = 2_600L

        /** Photo portant des pastilles de distance : laissée plus longtemps, le temps de lire. */
        const val PHOTO_MEASURED_MS = 6_000L
    }
}
