package dev.sam3vision.app.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.types.CapturePressType
import dev.sam3vision.core.glasses.CaptureAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Chef d'orchestre des lunettes Ray-Ban Meta : enregistrement auprès de l'app Meta AI,
 * session Device Access Toolkit, caméra et bouton capture.
 *
 * Aucune clé et aucun compte développeur : en Developer Mode le SDK s'enregistre
 * localement (APPLICATION_ID et CLIENT_TOKEN à « 0 » dans le manifeste).
 */
class GlassesController(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(GlassesUiState())
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 8)

    val state: StateFlow<GlassesUiState> = _state
    val events: SharedFlow<GlassesEvent> = _events

    private val mock = GlassesMock(context)
    private var session: DeviceSession? = null
    private var camera: GlassesCamera? = null
    private var inputs: GlassesInputs? = null
    private var activity: Activity? = null
    private var deviceJob: Job? = null
    private var started = false

    /** Appelé depuis [android.app.Activity.onCreate] et `onNewIntent`. */
    fun bind(activity: Activity) {
        this.activity = activity
    }

    /** Initialise le SDK une seule fois, puis suit l'enregistrement et les appareils. */
    fun initialize() {
        if (started) return
        started = true
        Wearables.initialize(context).fold(
            onSuccess = {
                _state.update { current -> current.copy(sdkReady = true, devMode = Wearables.isDevMode) }
                watchRegistration()
                watchDevices()
            },
            onFailure = { error, _ -> note("SDK lunettes indisponible : ${error.description}") },
        )
    }

    private fun watchRegistration() {
        scope.launch {
            Wearables.registrationState.collect { state ->
                _state.update { it.copy(registered = state == RegistrationState.REGISTERED) }
            }
        }
    }

    private fun watchDevices() {
        scope.launch {
            Wearables.devices.collect { identifiers -> bindFirstDevice(identifiers) }
        }
    }

    /** Le SDK peut connaître plusieurs paires : on suit la première, ça suffit ici. */
    private fun bindFirstDevice(identifiers: Set<DeviceIdentifier>) {
        deviceJob?.cancel()
        val flow = identifiers.firstOrNull()?.let { Wearables.devicesMetadata[it] }
        if (flow == null) {
            deviceJob = null
            _state.update { it.copy(deviceName = null, battery = null, worn = false) }
            return
        }
        deviceJob = scope.launch {
            flow.collect { device ->
                _state.update {
                    it.copy(
                        deviceName = device.name,
                        battery = device.batteryLevel.takeIf { level -> level > 0 },
                        worn = device.donState == DonState.DONNED,
                    )
                }
            }
        }
    }

    /** Enregistre l'app auprès de l'app Meta AI : la confirmation se fait côté Meta AI. */
    fun register() {
        initialize()
        val host = activity ?: return note("Reviens dans l'app, puis relance l'enregistrement.")
        Wearables.startRegistration(host)
        note("L'app Meta AI demande confirmation : accepte, puis reviens ici.")
    }

    fun unregister() {
        activity?.let { Wearables.startUnregistration(it) }
    }

    /** Retour d'enregistrement ou d'invocation vocale depuis l'app Meta AI. */
    fun handleIntent(intent: Intent) {
        val host = activity ?: return
        Wearables.handleIntent(intent) { request -> request.continueRegistration(host) }
    }

    fun connect() {
        initialize()
        if (session != null) return
        _state.update { it.copy(phase = SessionPhase.STARTING) }
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = ::startSession,
            onFailure = { error, _ ->
                _state.update { it.copy(phase = SessionPhase.IDLE) }
                note("Session refusée : ${error.description}")
            },
        )
    }

    private fun startSession(created: DeviceSession) {
        session = created
        scope.launch { created.state.collect { state -> onSessionState(created, state) } }
        scope.launch { created.errors.collect { error -> note("Session interrompue : ${error.description}") } }
        created.start()
    }

    private fun onSessionState(created: DeviceSession, state: DeviceSessionState) {
        _state.update { it.copy(phase = state.toPhase()) }
        when (state) {
            DeviceSessionState.STARTED -> scope.launch { attachCapabilities(created) }
            DeviceSessionState.STOPPED -> releaseCapabilities()
            else -> Unit
        }
    }

    private suspend fun attachCapabilities(created: DeviceSession) {
        if (camera != null) return
        if (!ensureCameraPermission()) return
        camera = GlassesCamera(created, scope, ::note) { streaming ->
            _state.update { it.copy(streaming = streaming) }
        }.also { it.start() }
        inputs = GlassesInputs(created, scope, clock, ::onGesture, ::note).also { it.start() }
    }

    private suspend fun ensureCameraPermission(): Boolean {
        val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        val granted = status is PermissionStatus.Granted
        _state.update { it.copy(cameraAllowed = granted) }
        if (!granted) {
            note("Caméra non autorisée : app Meta AI → App connections → SAM Vision → Always allow.")
        }
        return granted
    }

    /** Action décidée par le buffer d'appuis (logique pure, testée en JVM). */
    private fun onGesture(action: CaptureAction) {
        when (action) {
            CaptureAction.PHOTO, CaptureAction.ASK -> capture(action)
            CaptureAction.LIVE -> note("Mode Direct : pas encore porté sur lunettes, il reste sur le téléphone.")
        }
    }

    /** Prend une photo côté lunettes et la pousse à l'app, prête pour la segmentation. */
    fun capture(action: CaptureAction = CaptureAction.PHOTO) {
        val activeCamera = camera
        if (activeCamera == null) {
            note("Aucune session lunettes : démarre la session d'abord.")
            return
        }
        scope.launch {
            val bitmap = activeCamera.capture() ?: return@launch
            val file = writeJpegToCache(context, bitmap)
            _events.emit(GlassesEvent.PhotoTaken(file, action))
        }
    }

    fun disconnect() {
        releaseCapabilities()
        session?.stop()
        session = null
        _state.update { it.copy(phase = SessionPhase.IDLE, streaming = false) }
    }

    private fun releaseCapabilities() {
        inputs?.stop()
        inputs = null
        camera?.stop()
        camera = null
        _state.update { it.copy(streaming = false) }
    }

    /** Lunettes simulées : démo sans matériel, et plan B si l'appairage coince. */
    fun startMockDevice() {
        initialize()
        if (!mock.start()) return note("Appareil simulé indisponible sur ce téléphone.")
        _state.update {
            it.copy(mockActive = true, deviceName = it.deviceName ?: "Ray-Ban Meta (simulées)", registered = true)
        }
        note("Lunettes simulées prêtes : démarre la session, puis simule un appui.")
    }

    fun mockPress(type: CapturePressType = CapturePressType.SHORT_PRESS) {
        if (!mock.press(type)) note("Aucun appareil simulé actif.")
    }

    fun stopMockDevice() {
        mock.stop()
        _state.update { it.copy(mockActive = false) }
    }

    /** Libère session, capacités et coroutines (activité détruite). */
    fun close() {
        releaseCapabilities()
        session?.stop()
        session = null
        mock.stop()
        scope.cancel()
    }

    private fun note(text: String) {
        _state.update { it.copy(message = text) }
        _events.tryEmit(GlassesEvent.Note(text))
    }
}

/** Phase du SDK → phase affichée. `PAUSED` reste « en cours », la session reprend. */
internal fun DeviceSessionState.toPhase(): SessionPhase = when (this) {
    DeviceSessionState.IDLE -> SessionPhase.IDLE
    DeviceSessionState.STARTING -> SessionPhase.STARTING
    DeviceSessionState.STARTED -> SessionPhase.STARTED
    DeviceSessionState.PAUSED -> SessionPhase.STARTING
    DeviceSessionState.STOPPING, DeviceSessionState.STOPPED -> SessionPhase.STOPPED
}