package dev.sam3vision.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Le téléphone envoie des frames de 640 px maximum, en JPEG léger. */
const val LIVE_EDGE = 640
const val LIVE_JPEG_QUALITY = 70

/**
 * Mode Direct : aperçu caméra, frames poussées à [LiveViewModel], masques SAM 3.1
 * superposés, questions au LLM pendant que le flux continue.
 */
@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var failure by remember { mutableStateOf<String?>(null) }
    var attached by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.startFromSettings() }
    LaunchedEffect(attached) {
        if (!attached) return@LaunchedEffect
        runCatching { bindLiveCamera(context, previewView, executor) { proxy -> pushFrame(proxy, viewModel) } }
            .onFailure { failure = "Caméra indisponible : ${it.message}" }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
            onRelease = { attached = false },
            update = { attached = it.isAttachedToWindow },
        )
        state.overlays.forEach { overlay ->
            Image(
                bitmap = overlay.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        LiveTopBar(state, failure, onBack, onOpenSettings, Modifier.align(Alignment.TopCenter))
        LiveBottomBar(state, onAsk, Modifier.align(Alignment.BottomCenter))
    }
}

/** Preview + analyseur « une frame à la fois », résolution réduite côté capteur. */
private suspend fun bindLiveCamera(
    context: Context,
    previewView: PreviewView,
    executor: java.util.concurrent.Executor,
    onImage: (ImageProxy) -> Unit,
) {
    val owner = previewView.findViewTreeLifecycleOwner() ?: error("Cycle de vie indisponible")
    val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(LIVE_EDGE, LIVE_EDGE), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .build()
        .also { it.setAnalyzer(executor) { proxy -> onImage(proxy) } }
    provider.unbindAll()
    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
}

/** ImageProxy → bitmap redressé → JPEG base64. Une frame en vol au maximum, sans file. */
private fun pushFrame(proxy: ImageProxy, viewModel: LiveViewModel) {
    try {
        val rotated = rotateBitmap(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
        viewModel.onFrame(bitmapToJpegBase64(scaleToEdge(rotated, LIVE_EDGE), LIVE_JPEG_QUALITY))
    } catch (_: Throwable) {
        // frame illisible : la suivante arrive tout de suite
    } finally {
        proxy.close()
    }
}

/** Rotation d'un bitmap (l'analyseur caméra livre toujours de l'horizontal). */
fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return src
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}

/** Réduit un bitmap pour que son plus grand côté fasse [edge], sans jamais agrandir. */
fun scaleToEdge(bitmap: Bitmap, edge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= edge) return bitmap
    val ratio = edge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}
