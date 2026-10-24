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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Le téléphone envoie des frames de 512 px maximum, en JPEG léger (lien montant limité). */
const val LIVE_EDGE = 512
const val LIVE_JPEG_QUALITY = 65

/**
 * Mode Direct : aperçu caméra, frames poussées à [LiveViewModel], masques SAM 3.1 superposés,
 * questions au LLM pendant que le flux continue.
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
    val executor = remember { Executors.newSingleThreadExecutor() }
    var attached by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val previewView = rememberLivePreview(context) { attached = it }

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

    LiveStage(state, previewView, draft, { draft = it }, failure, onBack, onAsk, onOpenSettings) {
        viewModel.restartWith(draft)
    }
}

/** Aperçu mémorisé, avec notification d'attachement (sinon l'analyseur n'est jamais lié). */
@Composable
private fun rememberLivePreview(context: Context, onAttached: (Boolean) -> Unit): PreviewView =
    remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = onAttached(true)
                override fun onViewDetachedFromWindow(view: android.view.View) = onAttached(false)
            })
        }
    }

/** Scène : aperçu, masques superposés, barre haute (état + concepts) et barre basse (question). */
@Composable
private fun LiveStage(
    state: LiveUiState,
    previewView: PreviewView,
    draft: String,
    onDraftChange: (String) -> Unit,
    failure: String?,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
    onApplyPrompts: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        state.overlays.forEach { overlay ->
            Image(
                bitmap = overlay.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        LiveTopBar(
            state = state,
            draft = draft,
            onDraftChange = onDraftChange,
            onApply = onApplyPrompts,
            failure = failure,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        LiveBottomBar(state, onAsk, Modifier.align(Alignment.BottomCenter))
    }
}

/** Preview + analyseur « une frame à la fois », résolution capteur réduite. */
private suspend fun bindLiveCamera(
    context: Context,
    previewView: PreviewView,
    executor: ExecutorService,
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

/** ImageProxy → bitmap redressé → JPEG base64. Les frames s'accumulent jamais : gate côté ViewModel. */
private fun pushFrame(proxy: ImageProxy, viewModel: LiveViewModel) {
    try {
        val rotated = rotateBitmap(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
        viewModel.onFrame(bitmapToJpegBase64(scaleToEdge(rotated, LIVE_EDGE), LIVE_JPEG_QUALITY))
    } catch (_: Throwable) {
        // frame illisible : la suivante part tout de suite
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
