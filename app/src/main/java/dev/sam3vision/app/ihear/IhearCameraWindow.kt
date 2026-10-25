package dev.sam3vision.app.ihear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import dev.sam3vision.app.CameraSurface
import dev.sam3vision.app.LiveDistanceLayer
import dev.sam3vision.app.FirstPixelWatcher
import dev.sam3vision.app.decodeScaledExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La caméra d'iSee dans une bulle du chat : angles arrondis, un peu moins d'un
 * demi-écran en largeur comme en hauteur. L'aperçu y vit pendant l'enregistrement
 * du tap ; la photo du double tap y apparaît avec un flash, se montre quelques
 * secondes (remplacée à chaque photo d'une rafale, pastilles SAM par-dessus) puis
 * s'évapore en fondu. La liaison est composée en permanence par le chat ; seule
 * cette vue n'existe que dans la bulle média, en bas du fil.
 */
@Composable
internal fun IhearCameraWindow(
    state: IhearUiState,
    glue: CameraGlue,
    viewModel: IhearViewModel,
    modifier: Modifier = Modifier,
) {
    val probe by viewModel.probe.state.collectAsState()
    val preview = glue.preview
    IhearCameraWiring(glue, viewModel, probe.frames)
    val shown = state.recordingVideo || state.photoPath != null
    val windowAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = if (shown) 180 else 900),
        label = "camera-window-alpha",
    )
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp * CAM_WINDOW_WIDTH_SHARE
    val heightDp = configuration.screenHeightDp * CAM_WINDOW_HEIGHT_SHARE
    Box(
        modifier
            .padding(2.dp)
            .width(widthDp.dp)
            .height(heightDp.dp)
            .alpha(windowAlpha),
    ) {
        // La fenêtre clippée d'un côté, la pastille hors clip de l'autre : le point
        // peut déborder du contour sans être rogné par RoundedCornerShape.
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(CAM_WINDOW_CORNER.dp))
                .background(Color.Transparent),
        ) {
            CameraWindowBody(state, probe, glue.granted, glue.failure, preview)
        }
        RecordingDot(
            state.recordingVideo,
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (RECORDING_DOT_DP / 2).dp, y = (RECORDING_DOT_DP / 2).dp),
        )
    }
}

/**
 * Câblage de l'analyseur sur la sonde SAM : les frames (avec leur instant d'exposition,
 * seul point de départ honnête de la latence perçue) dans un sens, l'admission dans
 * l'autre, et le dernier poste de la chronologie posé au frame Compose suivant.
 */
@Composable
private fun IhearCameraWiring(glue: CameraGlue, viewModel: IhearViewModel, frames: Int) {
    LaunchedEffect(glue.host, viewModel) {
        // SAM ne voit que l'enregistrement : hors REC, aucune frame n'est même convertie.
        glue.host.wantsFrame = { viewModel.state.value.recordingVideo && viewModel.probe.wantsFrame() }
        glue.host.onFrame = { jpeg, captureAtNs ->
            if (viewModel.state.value.recordingVideo) viewModel.probe.onFrame(jpeg, captureAtNs)
        }
    }
    FirstPixelWatcher(frames, frames > 0) { viewModel.probe.noteDrawn() }
}

/** Permission + liaison caméra : un seul bloc, rappelé par le visuel. Composée en
 * permanence par le chat (caméra prête au premier geste), la vue ne vit que dans
 * la bulle média. */
internal data class CameraGlue(
    val host: IhearCameraHost,
    val granted: Boolean,
    val failure: String?,
    /** Aperçu créé dès la liaison : la bulle média se contente de le montrer. */
    val preview: PreviewView,
)

@Composable
internal fun rememberCameraGlue(viewModel: IhearViewModel): CameraGlue {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    var failure by remember { mutableStateOf<String?>(null) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    val host = remember { IhearCameraHost(context) }
    // L'aperçu est créé ici, **en permanence** : `bind()` exige une PreviewView déjà
    // attachée, et la bulle média n'existe qu'une fois l'enregistrement lancé. Sans ça,
    // le premier tap répondait « Camera not ready » (mesuré sur le téléphone).
    val preview = remember(host) { host.newPreviewView(context) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(granted) {
        if (granted) {
            failure = host.bind(owner)
            if (failure == null) viewModel.attachCamera(host.actions)
        }
    }
    DisposableEffect(Unit) {
        viewModel.attachCamera(host.actions)
        onDispose {
            host.release()
            viewModel.detachCamera()
        }
    }
    return CameraGlue(host, granted, failure, preview)
}

/** Corps visuel de la fenêtre : aperçu, photo flashée, pastille REC et messages. */
@Composable
private fun CameraWindowBody(
    state: IhearUiState,
    probe: LiveProbeState,
    granted: Boolean,
    failure: String?,
    preview: PreviewView,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14161B))) {
        if (granted) {
            // Aperçu et masques dans la même surface native (sinon les masques passent
            // derrière l'aperçu) ; les pastilles, composées après, restent au-dessus.
            AndroidView(
                factory = { ctx -> CameraSurface(ctx, preview) },
                update = { surface -> surface.setMasks(probe.masks) },
                modifier = Modifier.fillMaxSize(),
            )
            LiveDistanceLayer(probe.distanceLayer, topInset = WindowTopInset, modifier = Modifier.fillMaxSize())
            LiveDebugPanel(probe, Modifier.align(Alignment.TopStart), dense = true)
            // Sans masque, on dit pourquoi : sinon l'écran semble simplement ne rien voir.
            liveProbeNotice(probe)?.let { notice -> ProbeNotice(notice) }
        } else {
            Column(Modifier.align(Alignment.Center).padding(12.dp)) {
                Text("iSee needs the camera.", color = Color.White, fontSize = 12.sp)
            }
        }
        state.photoPath?.let {
            PhotoFlash(it)
            // Une pastille par objet mesuré, posée sur la photo le temps qu'elle reste.
            LiveDistanceLayer(state.distanceLayer, topInset = 0.dp, withLabel = true)
        }
        failure?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA333333), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            )
        }
    }
}

/** Message central tant qu'aucun masque n'est là : réveil du GPU, backend arrêté, attente. */
@Composable
private fun BoxScope.ProbeNotice(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 12.dp)
            .background(Color(0xCC1A1F26), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Photo du double tap : flash court, puis la photo remplit la fenêtre jusqu'à évaporation. */
@Composable
private fun PhotoFlash(path: String) {
    val flash = remember { Animatable(1f) }
    LaunchedEffect(path) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(durationMillis = PHOTO_FLASH_MS, easing = LinearEasing))
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeScaledExact(java.io.File(path)) }
    }
    Box(Modifier.fillMaxSize()) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Photo taken",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
    }
}

/** Point blanc d'enregistrement, à cheval sur l'angle haut-droit de la fenêtre. */
@Composable
private fun RecordingDot(active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    Box(
        modifier
            .size(RECORDING_DOT_DP.dp)
            .border(1.dp, Color(0x44000000), CircleShape)
            .background(Color.White, CircleShape),
    )
}

/** Pas de bandeau de boutons dans la fenêtre : les pastilles peuvent monter haut. */
private val WindowTopInset: Dp = 0.dp

/** Part de largeur / hauteur de l'écran occupée par la fenêtre (surface < moitié). */
private const val CAM_WINDOW_WIDTH_SHARE = 0.44f
private const val CAM_WINDOW_HEIGHT_SHARE = 0.44f
private const val CAM_WINDOW_CORNER = 26
private const val PHOTO_FLASH_MS = 340
/** Diamètre du point d'enregistrement (dp). */
private const val RECORDING_DOT_DP = 14

/** Vrai si la permission caméra est déjà accordée. */
fun cameraGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}
