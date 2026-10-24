package dev.sam3vision.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vrai si la permission CAMERA est déjà accordée. */
fun hasCameraPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun mainPost(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
}

/** Écran caméra : aperçu, capture, choix dans la galerie, accès aux réglages. */
@Composable
fun CaptureScreen(onPhoto: (File) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pickFromGallery(context, uri, onPhoto) { error = it }
    }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        bindCamera(context, view.findViewTreeLifecycleOwner(), previewView, imageCapture)
            .onFailure { error = it.message }
    }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            PermissionGate(onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
        error?.let { StatusBanner(it, AppColors.danger, Modifier.align(Alignment.TopCenter)) }
        CaptureControls(
            modifier = Modifier.align(Alignment.BottomCenter),
            enabled = granted,
            onCapture = { captureToFile(imageCapture, cameraExecutor, context, onPhoto) { error = it } },
            onGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onSettings = onOpenSettings,
        )
    }
}

private fun pickFromGallery(
    context: android.content.Context,
    uri: android.net.Uri,
    onPhoto: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val target = newCacheFile(context)
    if (copyUriToCache(context, uri, target)) onPhoto(target) else onError("Impossible de copier l'image choisie.")
}

private suspend fun bindCamera(
    context: android.content.Context,
    owner: androidx.lifecycle.LifecycleOwner?,
    previewView: PreviewView,
    imageCapture: ImageCapture,
): Result<Unit> = runCatching {
    if (owner == null) error("Cycle de vie indisponible")
    val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    provider.unbindAll()
    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
    Unit
}.mapFailure("Aucune caméra accessible")

private fun captureToFile(
    imageCapture: ImageCapture,
    executor: java.util.concurrent.ExecutorService,
    context: android.content.Context,
    onPhoto: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val target = newCacheFile(context)
    val options = ImageCapture.OutputFileOptions.Builder(target).build()
    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = mainPost { onPhoto(target) }

            override fun onError(exception: ImageCaptureException) =
                mainPost { onError("Capture impossible : ${exception.message}") }
        },
    )
}

@Composable
private fun CaptureControls(
    modifier: Modifier,
    enabled: Boolean,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BigButton("Photographier", enabled, AppColors.primary, onCapture)
        Row2(
            left = { BigButton("Galerie", enabled, AppColors.surfaceHigh, onGallery, 0.5f) },
            right = { BigButton("Réglages", true, AppColors.surfaceHigh, onSettings, 0.5f) },
        )
    }
}

@Composable
private fun Row2(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

private fun <T> Result<T>.mapFailure(prefix: String): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(IllegalStateException("$prefix : ${it.message}")) })
