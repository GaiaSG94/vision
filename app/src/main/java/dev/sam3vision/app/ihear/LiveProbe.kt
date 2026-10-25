package dev.sam3vision.app.ihear

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.ScreenTimings
import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.MASK_ALPHA
import dev.sam3vision.app.maskColorAt
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.app.TRANSPORT_WS_KO
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.Cadence
import dev.sam3vision.core.live.FrameRate
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.mask.colorizedMaskBitmap
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.liveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ce que la page caméra montre de SAM : les masques décodés de la dernière frame,
 * les concepts reconnus, les distances mesurées, et les chiffres qui disent si ça
 * rame (cadence, latence moyenne et p95, durée serveur, taille de frame, GPU).
 *
 * [spoken] est la phrase que la voix doit dire (« Personne à gauche, à 1,4 mètre ») :
 * elle reste dans l'état (rien ne se perd si l'écran se recompose) et change de
 * valeur une seule fois par nouvelle scène.
 */
data class LiveProbeState(
    val endpoint: String = "",
    val masks: List<Bitmap> = emptyList(),
    val labels: List<String> = emptyList(),
    /** Concepts envoyés à SAM (les suivis), pas seulement ceux reconnus. */
    val conceptsSent: List<String> = emptyList(),
    /** « ws » (WebSocket, le plus rapide) ou « http » (repli automatique). */
    val transport: String = TRANSPORT_WS,
    val objects: Int = 0,
    val fps: Float = 0f,
    val latencyMs: Long = 0,
    val p95Ms: Long = 0,
    val serverMs: Long = 0,
    /** Chronologie de la dernière frame (capture → pixel) + étages serveur reçus. */
    val timings: FrameTimings = FrameTimings(),
    val width: Int = 0,
    val height: Int = 0,
    val gpu: String = "",
    val frames: Int = 0,
    val distanceLayer: DistanceLayer? = null,
    val spoken: String? = null,
    /** Le conteneur Modal est en train de démarrer (30-50 s au premier appel). */
    val warming: Boolean = false,
    val error: String? = null,
    val debugVisible: Boolean = true,
) {
    /** Rien à afficher : ni frame analysée, ni erreur. */
    val idle: Boolean get() = frames == 0 && error == null

    /** L'objet le plus proche mesuré sur la dernière frame, `null` si rien n'est calibré. */
    val nearest: DistanceReading? get() = distanceLayer?.readings?.minByOrNull { it.meters }
}

/**
 * Analyse SAM du mode vidéo d'iSee : pendant l'enregistrement, chaque frame part à
 * `/live` (une à la fois, cadence adaptative) et revient en masques, distances et
 * statistiques.
 *
 * Même brique que le Direct ([LiveSession], [HttpLiveClient], [DistanceMeter],
 * [PositionAnnouncer]) : une seule implémentation de l'appel SAM et de la mesure par
 * frame, deux écrans qui la montrent.
 */
class LiveProbe(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(LiveProbeState())
    val state: StateFlow<LiveProbeState> = _state

    private var session: LiveSession? = null

    /**
     * Session du **premier** appel : un conteneur Modal endormi met 30-50 s à démarrer,
     * alors que la cadence du direct abandonne à 10 s. Le premier `/live` a donc un
     * délai long, puis on repasse à la session normale dès qu'une frame est passée.
     */
    private var coldSession: LiveSession? = null

    /** Une frame a-t-elle abouti depuis le début de l'enregistrement ? */
    private var warmed = false

    /** Cadence affichée : moyenne sur les 8 dernières frames, pas l'écart deux frames. */
    private val rate = FrameRate()

    /** Paquets de masques décodées depuis la configuration courante (pour le log). */
    private var decodedFrames = 0
    private var nextTickAt = 0L

    /** Distances (sténopé sur hauteurs connues) : 100 % téléphone, aucun appel en plus. */
    private val distances = DistanceMeter()

    /** Déclencheur des annonces vocales : stabilité, non-répétition, 4 s d'écart. */
    private val announcer = PositionAnnouncer()

/** Chronologie de la frame courante : capture du capteur → pixel affiché. */
    private val chronology = ScreenTimings { SystemClock.elapsedRealtimeNanos() }

    /** Vrai si les Réglages autorisent l'annonce (« Annoncer les objets et leur distance »). */
    private var announcePositions = false

    /** Un réveil `/health` est en cours (un seul à la fois). */
    private var warming = false

    /**
     * Change d'endpoint, de focale ou de voix (Réglages) : nouvelle session, masques et
     * distances précédents effacés.
     */
    fun configure(
        baseUrl: String,
        prompts: List<String> = LivePrompts.Default,
        announce: Boolean = false,
        lens: LensSpec = LensSpec.Default,
        distancesOn: Boolean = true,
        maxObjects: Int = 0,
    ) {
        val clean = baseUrl.trim()
        session = if (clean.isBlank()) null else LiveSession(
            liveClient(clean, marks = chronology),
            prompts = prompts,
            maxObjects = if (maxObjects > 0) maxObjects else DEFAULT_MAX_OBJECTS,
            cadence = Cadence.Fast,
        )
        // Session « froide » : même transport, délai long (60 s) — c'est le réveil du
        // conteneur qui parle, pas la frame. Une fois la première analyse passée, on
        // repart sur la session normale à délai court (10 s).
        coldSession = if (clean.isBlank()) null else LiveSession(
            liveClient(clean, timeoutSeconds = COLD_TIMEOUT_SECONDS, marks = chronology),
            prompts = prompts,
            maxObjects = if (maxObjects > 0) maxObjects else DEFAULT_MAX_OBJECTS,
            cadence = Cadence.Fast,
        )
        warmed = false
        announcePositions = announce
        distances.enabled = distancesOn
        distances.setLens(lens)
        nextTickAt = 0L
        queued = null
        chronology.reset()
        rate.reset()
        announcer.reset()
        _state.update {
            LiveProbeState(
                endpoint = clean,
                gpu = it.gpu,
                conceptsSent = LivePrompts.normalize(prompts),
                transport = TRANSPORT_WS,
                debugVisible = it.debugVisible,
                error = if (clean.isBlank()) "aucun endpoint (Réglages)" else null,
            )
        }
        if (clean.isNotBlank()) wake()
    }

    /** Début d'enregistrement : on repart de zéro, mais l'endpoint et le GPU restent connus. */
    fun begin() {
        session?.stats?.reset()
        session?.serverStats?.reset()
        coldSession?.stats?.reset()
        coldSession?.serverStats?.reset()
        warmed = false
        rate.reset()
        distances.reset()
        announcer.reset()
        nextTickAt = 0L
        queued = null
        chronology.reset()
        _state.update {
            LiveProbeState(
                endpoint = it.endpoint,
                gpu = it.gpu,
                conceptsSent = it.conceptsSent,
                debugVisible = it.debugVisible,
            )
        }
        wake()
    }

    /** Masques et chiffres remis à zéro (sortie d'écran) sans oublier l'endpoint. */
    fun clear() {
        chronology.reset()
        _state.update {
            LiveProbeState(
                endpoint = it.endpoint,
                gpu = it.gpu,
                conceptsSent = it.conceptsSent,
                debugVisible = it.debugVisible,
            )
        }
    }

    fun toggleDebug() = _state.update { it.copy(debugVisible = !it.debugVisible) }

    /**
     * Frame encodée en réserve (une seule) : elle est convertie **pendant** que la
     * requête précédente est en vol, puis part dès que le créneau se libère.
     * Sans elle, chaque cycle payait l'encodage JPEG après la réponse (+30-40 ms).
     * Les horodatages voyagent avec la frame : une frame en file garde sa capture.
     */
    private var queued: QueuedFrame? = null

    /** JPEG en attente, avec l'instant d'exposition et celui de la fin d'encodage (ns). */
    private data class QueuedFrame(val jpegB64: String, val captureAtNs: Long, val encodedAtNs: Long)

    /**
     * Faut-il convertir la frame qui arrive ? Oui si une frame de réserve manque :
     * soit un créneau est libre et la cadence est écoulée, soit une requête est en vol
     * et on prépare la suivante. Un seul buffer : au-delà, l'encodage serait du gâchis.
     */
    fun wantsFrame(): Boolean {
        val active = (if (warmed) session else coldSession) ?: return false
        if (queued != null) return false
        if (active.saturated) return true
        return System.currentTimeMillis() >= nextTickAt
    }

    /**
     * Frame caméra (JPEG base64, 320 px) : mise en réserve si un créneau est pris,
     * envoyée tout de suite sinon. Rien n'est mis en file au-delà d'une frame : la
     * scène a changé, une vieille image n'apprend rien.
     */
    /**
     * Idem avec l'instant d'**exposition** de la frame (`imageInfo.timestamp`, ns
     * monotones) : c'est lui qui rend la latence per\u00e7ue mesurable de bout en bout.
     */
    fun onFrame(jpegB64: String, captureAtNs: Long = 0L) {
        if (jpegB64.isBlank()) return
        val encodedAtNs = SystemClock.elapsedRealtimeNanos()
        val active = (if (warmed) session else coldSession) ?: run {
            android.util.Log.w("sam3vision", "frame ignorée : aucune session SAM (endpoint vide ?)")
            return
        }
        if (active.saturated) {
            queued = QueuedFrame(jpegB64, captureAtNs, encodedAtNs)
            return
        }
        val now = System.currentTimeMillis()
        if (now < nextTickAt) return
        dispatch(active, QueuedFrame(jpegB64, captureAtNs, encodedAtNs), now)
    }

    /**
     * Envoie une frame et note l'instant, pour respecter la cadence adaptative.
     * La cadence affichée se compte **à l'envoi** : deux réponses qui arrivent ensemble
     * après un réveil ne veulent pas dire « 40 images par seconde ».
     */
    private fun dispatch(active: LiveSession, frame: QueuedFrame, at: Long) {
        rate.record(at)
        nextTickAt = at + active.intervalMsAdaptive()
        // Nouvelle frame : la chronologie repart de l'exposition du capteur.
        chronology.beginFrame(frame.captureAtNs, frame.encodedAtNs)
        // Hors du thread principal : la sérialisation de la frame (JSON + base64) et la
        // normalisation des concepts ne doivent jamais tomber dans la boucle de composition.
        scope.launch(Dispatchers.Default) { runFrame(active, frame.jpegB64, at) }
    }

    /**
     * Une réponse vient d'arriver : si une frame attendait, elle part maintenant.
     * C'est ce qui fait tourner le cycle à la vitesse de la requête et non à celle de
     * la requête **plus** l'encodage.
     */
    private fun drain() {
        val next = queued ?: return
        queued = null
        val active = (if (warmed) session else coldSession) ?: return
        if (active.saturated) {
            queued = next
            return
        }
        dispatch(active, next, System.currentTimeMillis())
    }

    private suspend fun runFrame(active: LiveSession, jpegB64: String, at: Long) {
        val result = active.runFrame(jpegB64) ?: return drain()
        // Réponse complète (corps lu et JSON tourné) : le poste « reçu » est posé ici.
        chronology.markReceived()
        result.fold(
            onSuccess = { frame ->
                if (_state.value.frames == 0) {
                    android.util.Log.i(
                        "sam3vision",
                        "première analyse : ${frame.objects.size} objets, ${frame.elapsedMs} ms",
                    )
                }
                publish(active, frame, at)
            },
            // le compte d'objets n'est pas remis à zéro : les masques affichés restent
            // les derniers connus, seul le message d'erreur change.
            onFailure = { error ->
                android.util.Log.w("sam3vision", "live KO: ${error::class.simpleName} ${error.message}")
                _state.update { it.copy(error = failureText(error), timings = chronology.snapshot()) }
            },
        )
        drain()
    }

    /** Publie une frame analysée : masques recolorés, objets, distances, cadence et latences. */
    private suspend fun publish(active: LiveSession, frame: LiveFrame, at: Long) {
        if (!warmed) {
            // La première frame paie le démarrage du conteneur (mesuré : 17 s en WebSocket,
            // 40 s en HTTP). La laisser dans la moyenne afficherait « 17 192 ms » pour un
            // lien chaud à ~180 ms ; elle fausserait aussi le régulateur de créneaux.
            active.stats.reset()
            active.serverStats.reset()
        }
        warmed = true
        val overlays = overlaysFor(frame)
        val readings = readingsOf(frame)
        val phrase = phraseOf(frame, readings, at)
        _state.update { current ->
            current.copy(
                masks = overlays,
                labels = frame.objects.map { it.label }.distinct(),
                objects = frame.objects.size,
                latencyMs = active.stats.averageMs,
                p95Ms = active.stats.p95Ms,
                serverMs = frame.elapsedMs,
                timings = chronology.snapshot(frame),
                width = frame.width,
                height = frame.height,
                frames = current.frames + 1,
                fps = rate.rate,
                distanceLayer = distanceLayerOf(frame, readings),
                spoken = phrase ?: current.spoken,
                transport = transportLabel(active),
                error = null,
            )
        }
    }

    /**
     * Frame effectivement **dessinée** : dernier poste de la chronologie, posé par l'écran
     * après la composition (`withFrameNanos`). Tant que l'écran ne l'appelle pas, la ligne
     * s'arrête aux masques et `pixel` affiche « — ».
     */
    fun noteDrawn() {
        if (!chronology.noteDrawn()) return
        _state.update { current -> current.copy(timings = chronology.snapshot(current.timings)) }
    }

    /** Libellé de transport : ce que le socket fait vraiment, pas ce qu'on voudrait. */
    private fun transportLabel(active: LiveSession): String =
        if (active.socketOpen) TRANSPORT_WS else TRANSPORT_WS_KO

    /** Distances de la frame (sténopé) ; aucun journal de suivi ici : mesure brute. */
    private fun readingsOf(frame: LiveFrame): List<DistanceReading> {
        if (frame.width <= 0 || frame.height <= 0) return emptyList()
        val detections = frame.objects.toDetections(frame.width, frame.height)
        if (detections.isEmpty()) return emptyList()
        return distances.measure(frame.width, frame.height, detections)
    }

    /** Couche des pastilles ; `null` quand aucune distance n'est exploitable. */
    private fun distanceLayerOf(frame: LiveFrame, readings: List<DistanceReading>): DistanceLayer? =
        if (readings.isEmpty() || frame.width <= 0 || frame.height <= 0) null
        else DistanceLayer(frame.width, frame.height, readings)

    /**
     * Phrase à dire, ou `null` pour rester muet : bascule des Réglages coupée, ou scène
     * déjà annoncée. La distance suit la boîte qui l'a produite (aucune indexation fragile).
     */
    private fun phraseOf(frame: LiveFrame, readings: List<DistanceReading>, at: Long): String? {
        if (!announcePositions) return null
        val metersByBox = readings.associate { it.box to it.meters }
        val placed = frame.objects.mapNotNull { obj ->
            PositionText.fromDetection(Detection(obj.label, obj.score, obj.box, frame.width, frame.height))
                ?.copy(meters = metersByBox[obj.box])
        }
        return announcer.onFrame(at, placed)
    }

    /**
     * Message d'échec lisible. Un délai dépassé au premier appel = conteneur Modal
     * endormi (~30 s de réveil) : on le dit, et la frame suivante réessaie d'elle-même.
     */
    private fun failureText(error: Throwable): String =
        if (error is ApiError.Timeout) "réveil du GPU en cours… (nouvel essai à la frame suivante)"
        else error.message ?: "échec de l'appel SAM"

    /**
     * Masques colorisés de la frame, calculés hors du thread principal : décodage selon
     * `frame.maskEncoding` (`bits` = 1 bit + zlib, sinon PNG gris) puis coloriage par table
     * de 256 niveaux — jamais de luminance sur un masque déjà gris.
     *
     * Le coût est mesuré (une ligne tous les [LOG_EVERY_FRAMES] paquets) : c'est du temps
     * mural entre la réponse réseau et ce qui arrive à l'écran, et il n'est pas dans `latence`.
     */
    private suspend fun overlaysFor(frame: LiveFrame): List<Bitmap> = withContext(Dispatchers.Default) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val overlays = frame.objects.mapIndexedNotNull { index, obj ->
            colorizedMaskBitmap(obj.maskPngB64, frame.maskEncoding, maskColorAt(index).toArgb(), MASK_ALPHA)
        }
        chronology.markOverlays()
        logDecoding(android.os.SystemClock.elapsedRealtime() - startedAt, overlays.size)
        overlays
    }

    /** Log périodique du décodage des masques. */
    private fun logDecoding(ms: Long, masks: Int) {
        decodedFrames += 1
        if (decodedFrames % LOG_EVERY_FRAMES != 0) return
        android.util.Log.i("sam3vision", "masques décodés: $masks en ${ms} ms")
    }

    /**
     * Réveil du conteneur Modal : **un seul appel** à `/health`.
     *
     * Avant, on réessayait 6 fois toutes les 10 s : c'est ce qui remplissait les
     * journaux de `/health` et, surtout, qui **gardait la carte A100 allumée en boucle**
     * (une carte qui répond reste un conteneur actif, facturé). Un seul appel suffit :
     * le client `/health` a un délai long (il attend le démarrage du conteneur), et si le
     * réveil échoue, la première frame `/live` réessaiera d'elle-même.
     */
    private fun wake() {
        val base = _state.value.endpoint
        if (base.isBlank() || warming) return
        warming = true
        _state.update { it.copy(warming = true) }
        scope.launch {
            val outcome = liveClient(base).health()
            warming = false
            outcome.fold(
                onSuccess = { info ->
                    _state.update { it.copy(gpu = info.gpu.take(28), warming = false, error = null) }
                },
                onFailure = { error ->
                    _state.update { it.copy(warming = false, error = error.message ?: "GPU injoignable") }
                },
            )
        }
    }

    companion object {
        /** Plafond d'objets quand les Réglages n'en imposent pas (valeur du Direct). */
        private const val DEFAULT_MAX_OBJECTS = 12

        /** Un log de décodage toutes N frames décodées. */
        private const val LOG_EVERY_FRAMES = 20

        /** Délai du premier appel : couvre le démarrage d'un conteneur Modal (30-50 s). */
        const val COLD_TIMEOUT_SECONDS = 60L

    }
}
