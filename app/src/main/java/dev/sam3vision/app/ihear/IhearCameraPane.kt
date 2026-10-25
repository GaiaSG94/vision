package dev.sam3vision.app.ihear

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Le vrai pilote CameraX d'iHear, derrière la fenêtre flottante : aperçu + `ImageCapture`
 * + `VideoCapture<Recorder>` tous liés en même temps, et l'implémentation
 * [IhearCameraActions] qu'appelle [IhearViewModel]. Vidéo sans piste micro (le
 * dictaphone reste libre, comme le mode tap).
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

        override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean =
            takePhotoNow(target, onSaved)
    }

    private var boundPreview: PreviewView? = null

    /** Fabrique la `PreviewView` de la fenêtre (une seule fois, conservée côté host). */
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

    /**
     * Photo immédiate du double tap ; `true` = demande acceptée (écriture asynchrone).
     * [onSaved] est rappelé plus tard : `true` si la photo est écrite, `false` sinon.
     */
    fun takePhotoNow(target: File, onSaved: (Boolean) -> Unit): Boolean {
        val capture = imageCapture ?: return false
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        return runCatching {
            capture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        onSaved(target.isFile && target.length() > 0L)
                    }

                    override fun onError(error: ImageCaptureException) {
                        target.delete()
                        onSaved(false)
                    }
                },
            )
            true
        }.getOrDefault(false)
    }

    /** Libère l'executor ; la caméra est déliée par le dispose de la fenêtre. */
    fun release() {
        recording?.let { runCatching { it.stop() } }
        recording = null
        executor.shutdown()
    }

    companion object {
        private const val FINALIZE_TIMEOUT_MS = 4_000L
    }
}
