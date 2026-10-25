package dev.sam3vision.app.ihear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La page caméra d'iHear : aperçu plein écran toujours vivant (resté attaché même
 * quand le chat le recouvre — le swipe ne coupe jamais la caméra), vidéo du tap
 * (démarrer / fermer) et photo du double tap. Les gestes viennent de [IhearRootLayout],
 * jamais d'une couche tactile Compose : les overlays sont muets.
 */
@Composable
fun IhearCameraPane(state: IhearUiState, viewModel: IhearViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    var failure by remember { mutableStateOf<String?>(null) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    val host = remember { IhearCameraHost(context) }

    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(granted) {
        if (granted) {
            failure = host.bind()
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

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { host.newPreviewView(context) }, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center).padding(28.dp)) {
                Text("iHear needs the camera to show this page.", color = Color.White, fontSize = 15.sp)
            }
        }
        failure?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA333333), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }
        RecordingBadge(state.recordingVideo, Modifier.align(Alignment.TopCenter))
    }
}

/** Pastille d'enregistrement : un point rouge et « REC » pendant la vidéo du tap. */
@Composable
private fun RecordingBadge(active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    Text(
        "● REC",
        color = Color.White,
        fontSize = 13.sp,
        modifier = modifier
            .background(Color(0x99CC2222), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Vrai si la permission caméra est déjà accordée. */
fun cameraGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Le vrai pilote CameraX de la page : aperçu + `ImageCapture` + `VideoCapture<Recorder>`
 * tous liés en même temps, et l'implémentation [IhearCameraActions] qu'appelle
 * [IhearViewModel]. Vidéo sans piste micro (le dictaphone reste libre, comme le mode tap).
 */
class IhearCameraHost(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var videoCapture: androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var finalizing: CompletableDeferred<File>? = null

    /** L'implémentation [IhearCameraActions] exposée au ViewModel. */
    val actions: IhearCameraActions = object : IhearCameraActions {
        override val ready: Boolean get() = videoCapture != null

        override fun startRecording(file: File): Boolean = startVideo(file)

        override suspend fun stopRecording(): File? = stopVideo()

        override fun takePhoto(target: File): Boolean = takePhotoNow(target)
    }

    private var boundPreview: PreviewView? = null

    /** Fabrique la `PreviewView` du panneau (une seule fois, conservée côté host). */
    fun newPreviewView(context: Context): PreviewView {
        val created = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        boundPreview = created
        return created
    }

    /** Lie aperçu + photo + vidéo au cycle de vie attaché à l'aperçu ; message sinon. */
    suspend fun bind(): String? {
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val view = boundPreview ?: return "Preview not attached yet"
        val owner = view.findViewTreeLifecycleOwner() ?: return "Lifecycle unavailable"
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val image = ImageCapture.Builder().build()
        val video = androidx.camera.video.VideoCapture.withOutput(
            androidx.camera.video.Recorder.Builder()
                .setQualitySelector(
                    androidx.camera.video.QualitySelector.from(androidx.camera.video.Quality.HD),
                )
                .build(),
        )
        return runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, image, video)
            imageCapture = image
            videoCapture = video
            null
        }.getOrElse { "Camera binding failed: ${it.message}" }
    }

    /** Démarre l'enregistrement du MP4 dans [file] ; `false` si non prêt ou déjà lancé. */
    fun startVideo(file: File): Boolean {
        val video = videoCapture ?: return false
        if (recording != null) return false
        return runCatching {
            val done = CompletableDeferred<File>()
            finalizing = done
            val options = androidx.camera.video.FileOutputOptions.Builder(file).build()
            val pending = video.output.prepareRecording(context, options)
            recording = pending.start(executor) { event ->
                if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                    if (event.hasError()) done.completeExceptionally(IllegalStateException("video finalize error"))
                    else done.complete(file)
                }
            }
            true
        }.getOrDefault(false)
    }

    /** Stoppe puis attend la finalisation du MP4 (fichier non vide) ou `null`. */
    suspend fun stopVideo(): File? {
        val pending = recording
        recording = null
        pending?.let { runCatching { it.stop() } }
        val done = finalizing
        val saved = runCatching { withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { done?.await() } }.getOrNull()
        finalizing = null
        return saved?.takeIf { it.isFile && it.length() > 0L }
    }

    /** Photo immédiate du double tap ; `true` = demande acceptée (écriture asynchrone). */
    fun takePhotoNow(target: File): Boolean {
        val capture = imageCapture ?: return false
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        return runCatching {
            capture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {}

                    override fun onError(error: ImageCaptureException) {
                        target.delete()
                    }
                },
            )
            true
        }.getOrDefault(false)
    }

    /** Libère l'executor ; la caméra est déliée par le dispose du panneau. */
    fun release() {
        recording?.let { runCatching { it.stop() } }
        recording = null
        executor.shutdown()
    }

    companion object {
        private const val FINALIZE_TIMEOUT_MS = 4_000L
    }
}
