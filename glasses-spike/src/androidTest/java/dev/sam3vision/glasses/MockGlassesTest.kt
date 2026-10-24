package dev.sam3vision.glasses

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumentation du harnais mock : prouve qu'une frame sort du flux simulé
 * sans aucune paire de lunettes physique.
 *
 * Le flux vient d'une vidéo h.265 livrée en asset androidTest (déterministe),
 * pas de la caméra du téléphone : les tests ne dépendent d'aucun capteur.
 */
@RunWith(AndroidJUnit4::class)
class MockGlassesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val log: (String) -> Unit = { line -> logs.add(line) }

    private var session: DeviceSession? = null
    private var stream: Stream? = null

    @After
    fun tearDown() {
        runCatching { stream?.stop() }
        stream = null
        runCatching { session?.stop() }
        session = null
        MockGlassesHarness.disable()
        // Le journal du harnais reste lisible dans logcat même en cas de succès.
        logs.forEach { Log.i(TAG, "harnais : $it") }
    }

    @Test
    fun uneFrameArriveDuFluxSimule() = withLogsOnFailure {
        assertTrue("activation du harnais", MockGlassesHarness.enable(context, log))

        val video = copyAssetToCache(VIDEO_ASSET)
        assertTrue(
            "vidéo h.265 non armée comme flux caméra",
            MockGlassesHarness.useVideoFeed(Uri.fromFile(video), log),
        )

        runBlocking { collectFirstFrame() }
    }

    /** Ouvre une session DAT, démarre le flux et attend la première frame. */
    private suspend fun collectFirstFrame() = coroutineScope {
        // L'enregistrement simulé remonte de façon asynchrone : attendre que le
        // device soit visible évite un NO_ELIGIBLE_DEVICE au premier run.
        withTimeout(SESSION_TIMEOUT_MS) {
            Wearables.devices.first { it.isNotEmpty() }
        }

        val deviceSession = Wearables.createSession(AutoDeviceSelector())
            .getOrThrow()
            .also { session = it }
        deviceSession.start()
        withTimeout(SESSION_TIMEOUT_MS) {
            deviceSession.state.first { it == DeviceSessionState.STARTED }
        }

        val camera = deviceSession.addCamera(StreamConfiguration()).getOrThrow()
        val cameraStream = camera.stream.also { stream = it }
        cameraStream.start().getOrThrow()

        val latch = CountDownLatch(1)
        val collector = launch(Dispatchers.IO) {
            cameraStream.videoStream.first()
            latch.countDown()
        }
        val frameArrived = withContext(Dispatchers.IO) {
            latch.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        collector.cancel()
        cameraStream.stop()
        camera.stop()
        stream = null

        assertTrue("aucune frame reçue en $FRAME_TIMEOUT_SECONDS s", frameArrived)
    }

    /** Les assets androidTest voyagent dans l'APK de test, pas dans celui de l'app. */
    private fun copyAssetToCache(name: String): File {
        val target = File(context.cacheDir, name)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.open(name).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    /** Rejoue l'échec en y joignant le journal du harnais (diagnostic). */
    private inline fun withLogsOnFailure(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            throw AssertionError("échec ; journal du harnais :\n${logs.joinToString("\n")}", t)
        }
    }

    private companion object {
        const val TAG = "MockGlassesTest"
        const val VIDEO_ASSET = "mock_feed.mp4"
        const val SESSION_TIMEOUT_MS = 20_000L
        const val FRAME_TIMEOUT_SECONDS = 20L
    }
}