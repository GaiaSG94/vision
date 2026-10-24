package dev.sam3vision.glasses

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Cycle de vie DAT du spike lunettes : enregistrement → session → caméra → flux de frames.
 *
 * `Wearables.initialize` est déjà fait dans [GlassesApp] : on n'y touche pas.
 * La machine à états est pilotée par `Wearables.registrationState` (source de
 * vérité du SDK) : enregistrement si nécessaire, puis session, puis caméra
 * seulement quand la session atteint `STARTED`, puis flux vidéo étranglé à une
 * frame toutes ~1,5 s vers [onFrame].
 *
 * Les coroutines internes vivent dans un `SupervisorJob` enfant du job de
 * [scope] : [stop] les annule sans jamais annuler le scope de l'appelant.
 * Tout ce qui peut bloquer (création de session, handshake caméra, encodage
 * JPEG) est basculé sur `Dispatchers.IO`, donc un [scope] sur le main thread
 * convient. En contrepartie [onFrame] s'exécute sur le dispatcher de [scope] :
 * il est `suspend` et doit basculer son réseau sur IO lui-même.
 *
 * [onLog] est appelé depuis plusieurs threads (collecteurs DAT + nettoyage) :
 * à l'appelant de revenir au main s'il affiche l'état.
 */
class GlassesSession(
    context: Context,
    private val scope: CoroutineScope,
    private val onFrame: suspend (JpegFrame) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val appContext: Context = context.applicationContext

    // Encodage fourni par GlassesFrameEncoder : aucune conversion d'image n'est
    // réécrite ici.
    private val encoder = GlassesFrameEncoder()

    /**
     * Cycle courant, `null` quand rien ne tourne. Volatile parce que [stop] peut
     * être appelé du main thread pendant qu'un collecteur tourne ailleurs.
     */
    @Volatile
    private var runtime: Runtime? = null

    /** Démarre enregistrement + session + caméra. Sans effet si déjà démarré. */
    fun start(activity: Activity) {
        if (runtime != null) {
            onLog("cycle lunettes déjà démarré")
            return
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        val internal = CoroutineScope(scope.coroutineContext + job)
        val rt = Runtime(job, internal, WeakReference(activity))
        runtime = rt
        onLog("démarrage du cycle lunettes")
        rt.launchSafe { observeRegistration(rt) }
    }

    /** Libère flux, caméra puis session. Idempotent : un second appel ne fait rien. */
    fun stop() {
        val rt = runtime ?: return
        runtime = null
        onLog("arrêt du cycle lunettes")
        // On coupe d'abord les collecteurs : plus aucune frame ne part pendant
        // la libération des ressources DAT.
        rt.job.cancel()
        rt.frames.close()
        release(rt.stream, rt.session, rt.camera)
    }

    /**
     * Nettoyage sur un scope jetable dédié : le scope de l'appelant peut être
     * annulé au même instant (fermeture d'écran), la libération doit aboutir.
     */
    private fun release(stream: Stream?, session: DeviceSession?, camera: Camera?) {
        val cleanup = SupervisorJob()
        CoroutineScope(cleanup + Dispatchers.IO).launch {
            try {
                stream?.stop()
                camera?.stop()
                session?.stop()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                onLog("erreur pendant l'arrêt : ${error.message}")
            } finally {
                cleanup.cancel()
            }
        }
    }

    // --- Enregistrement -----------------------------------------------------

    private fun observeRegistration(rt: Runtime) {
        rt.launchSafe {
            Wearables.registrationState.collect { state -> onRegistrationState(state, rt) }
        }
        // Flux d'erreurs séparé : il donne le motif exact d'un échec, que le
        // seul enchaînement d'états ne révèle pas.
        rt.launchSafe {
            Wearables.registrationErrorStream.collect { error ->
                onLog("erreur d'enregistrement : ${describe(error)}")
            }
        }
    }

    private suspend fun onRegistrationState(state: RegistrationState, rt: Runtime) {
        when (state) {
            RegistrationState.REGISTERED -> {
                onLog("lunettes enregistrées")
                openSession(rt)
            }
            RegistrationState.AVAILABLE -> {
                onLog("en attente d'enregistrement")
                requestRegistration(rt)
            }
            RegistrationState.REGISTERING -> onLog("enregistrement en cours")
            RegistrationState.UNREGISTERING -> onLog("désenregistrement en cours")
            RegistrationState.UNAVAILABLE -> onLog("aucune paire de lunettes disponible")
        }
    }

    private fun requestRegistration(rt: Runtime) {
        // Un seul lancement par cycle : startRegistration ouvre une UI, la
        // relancer à chaque émission de l'état serait agressif.
        if (rt.registrationRequested) return
        val activity = rt.activity.get()
        if (activity == null) {
            onLog("activity indisponible : enregistrement impossible")
            return
        }
        rt.registrationRequested = true
        onLog("ouverture de l'enregistrement des lunettes")
        // L'app du compagnon peut être absente : on log sans emporter le collecteur.
        runCatching { Wearables.startRegistration(activity) }
            .onFailure { error -> onLog("enregistrement impossible : ${error.message}") }
    }

    // --- Session ------------------------------------------------------------

    private suspend fun openSession(rt: Runtime) {
        if (rt.sessionRequested) return
        rt.sessionRequested = true
        val result = io { Wearables.createSession(AutoDeviceSelector()) }
        // `fold` du SDK prend des callbacks non suspend : le travail suspendu
        // est relancé sur le scope interne.
        result.fold(
            onSuccess = { session -> rt.launchSafe { runSession(rt, session) } },
            onFailure = { error, _ -> onSessionFailure(rt, error) },
        )
    }

    private fun onSessionFailure(rt: Runtime, error: DatError) {
        // On réarme : un prochain état REGISTERED retentera la création.
        rt.sessionRequested = false
        onLog("session impossible : ${describe(error)}")
    }

    private suspend fun runSession(rt: Runtime, session: DeviceSession) {
        rt.session = session
        // Collecteurs posés AVANT start() : une erreur émise pendant la
        // connexion ne doit pas être perdue.
        rt.launchSafe(start = CoroutineStart.UNDISPATCHED) {
            session.errors.collect { error -> onLog("erreur de session : ${describe(error)}") }
        }
        rt.launchSafe(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { state -> onSessionState(state, rt, session) }
        }
        io { session.start() }
        onLog("session démarrée")
    }

    private suspend fun onSessionState(
        state: DeviceSessionState,
        rt: Runtime,
        session: DeviceSession,
    ) {
        onLog("session : $state")
        // La caméra n'a de sens qu'une fois la session réellement connectée.
        if (state == DeviceSessionState.STARTED) addCamera(rt, session)
    }

    // --- Caméra et flux -----------------------------------------------------

    private suspend fun addCamera(rt: Runtime, session: DeviceSession) {
        if (rt.cameraRequested) return
        rt.cameraRequested = true
        val configuration = StreamConfiguration(
            videoQuality = VideoQuality.MEDIUM,
            frameRate = FRAME_RATE,
        )
        val result = io { session.addCamera(configuration) }
        result.fold(
            onSuccess = { camera -> rt.launchSafe { startStream(rt, camera) } },
            onFailure = { error, _ -> onLog("caméra refusée : ${describe(error)}") },
        )
    }

    private suspend fun startStream(rt: Runtime, camera: Camera) {
        rt.camera = camera
        onLog("caméra ajoutée")
        val stream = camera.stream
        rt.stream = stream
        // Abonnement avant start() : état et erreurs émis pendant le handshake
        // sont ainsi vus plutôt que perdus.
        rt.launchSafe(start = CoroutineStart.UNDISPATCHED) {
            stream.state.collect { state -> onLog("flux : $state") }
        }
        rt.launchSafe(start = CoroutineStart.UNDISPATCHED) {
            stream.errorStream.collect { error -> onStreamError(error) }
        }
        rt.launchSafe { consumeFrames(rt) }
        rt.launchSafe { collectFrames(rt, stream) }
        val result = io { stream.start() }
        result.fold(
            onSuccess = { onLog("flux démarré") },
            onFailure = { error, _ -> onLog("flux refusé : ${describe(error)}") },
        )
    }

    private fun onStreamError(error: StreamError) {
        onLog("erreur de flux : ${describe(error)}")
        // Les autres erreurs sont transitoires (batterie, charnière, thermique) :
        // le flux peut repartir seul, on n'arrête que sur le critique.
        if (error != StreamError.CRITICAL_STREAM_ERROR) return
        onLog("erreur critique du flux : arrêt du cycle")
        stop()
    }

    /**
     * Collecte brute : l'envoi ne suspend jamais, donc une consommation lente
     * ne fait pas exploser la latence du flux.
     */
    private suspend fun collectFrames(rt: Runtime, stream: Stream) {
        stream.videoStream.collect { frame ->
            // Les frames de configuration codec ne portent pas d'image.
            if (frame.isCodecConfig) return@collect
            rt.frames.trySend(frame)
        }
    }

    /**
     * Étranglement : au plus une frame toutes les [FRAME_THROTTLE_MS] vers
     * [onFrame]. L'appel réseau SAM + LLM est cher, ~0,7 image/s suffit.
     */
    private suspend fun consumeFrames(rt: Runtime) {
        var lastSentAt = 0L
        for (frame in rt.frames) {
            val wait = FRAME_THROTTLE_MS - (SystemClock.elapsedRealtime() - lastSentAt)
            if (wait > 0) delay(wait)
            // Pendant l'attente une frame plus récente a pu arriver : elle vaut
            // mieux que celle qu'on tenait depuis 1,5 s.
            val fresh = rt.frames.tryReceive().getOrNull() ?: frame
            lastSentAt = SystemClock.elapsedRealtime()
            val jpeg = io { encoder.toJpeg(fresh) }
            if (jpeg == null) {
                onLog("frame non encodable : ignorée")
                continue
            }
            onLog("frame transmise : ${fresh.width}x${fresh.height}")
            onFrame(jpeg)
        }
    }

    // --- Outils -------------------------------------------------------------

    /**
     * Tout job interne passe par là : une exception du SDK ou d'un callback de
     * l'appelant doit être loggée, pas tuer le cycle ni remonter au scope.
     */
    private fun Runtime.launchSafe(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> Unit,
    ) {
        scope.launch(start = start) {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                onLog("incident interne : ${error.message}")
            }
        }
    }

    /** Les appels DAT peuvent bloquer (négociation, handshake) : jamais sur le main. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Message lisible pour l'UI, avec repli sur la description brute du SDK. */
    private fun describe(error: DatError): String =
        runCatching { error.getLocalizedDescription(appContext) }.getOrElse { error.description }

    /** État mutable d'un cycle : recréé à chaque [start], jeté à chaque [stop]. */
    private class Runtime(
        val job: CompletableJob,
        val scope: CoroutineScope,
        val activity: WeakReference<Activity>,
    ) {
        /** Conflaté : la frame la plus récente écrase l'attente, rien ne s'empile. */
        val frames = Channel<VideoFrame>(Channel.CONFLATED)
        var session: DeviceSession? = null
        var camera: Camera? = null
        var stream: Stream? = null
        var registrationRequested = false
        var sessionRequested = false
        var cameraRequested = false
    }

    private companion object {
        /** 24 i/s : fluide pour du suivi visuel, économe en batterie. */
        const val FRAME_RATE = 24

        /** Le LLM voit une frame toutes les 1,5 s : au-delà, on paie pour rien. */
        const val FRAME_THROTTLE_MS = 1_500L
    }
}