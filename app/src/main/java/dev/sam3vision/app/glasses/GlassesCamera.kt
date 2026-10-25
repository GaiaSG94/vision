package dev.sam3vision.app.glasses

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.session.DeviceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Caméra des lunettes : un flux, une photo à la demande, aucun état global. */
internal class GlassesCamera(
    private val session: DeviceSession,
    private val scope: CoroutineScope,
    private val onNote: (String) -> Unit,
    private val onStreaming: (Boolean) -> Unit,
) {
    private var camera: Camera? = null
    private var stream: Stream? = null

    fun start(): Boolean {
        if (camera != null) return true
        return session.addCamera(configuration()).fold(
            onSuccess = { attached ->
                camera = attached
                stream = attached.stream
                watch(attached)
                attached.stream.start().onFailure { error, _ -> onNote("Flux refusé : ${error.description}") }
                true
            },
            onFailure = { error, _ ->
                onNote("Caméra indisponible : ${error.description}")
                false
            },
        )
    }

    /** 15 fps et qualité moyenne : au-delà, le lien Bluetooth Classic casse l'image. */
    private fun configuration() = StreamConfiguration(
        audioCodec = null,
        videoQuality = VideoQuality.MEDIUM,
        frameRate = FRAME_RATE,
        compressVideo = true,
    )

    private fun watch(attached: Camera) {
        scope.launch {
            attached.stream.state.collect { state -> onStreaming(state == StreamState.STREAMING) }
        }
        scope.launch {
            attached.stream.errorStream.collect { error -> onNote("Flux lunettes : ${error.description}") }
        }
    }

    /** Photo des lunettes ; null si le flux n'est pas actif ou si la capture échoue. */
    suspend fun capture(): Bitmap? {
        val active = stream ?: return null
        return active.capturePhoto().fold(
            onSuccess = { data -> data.toBitmapOrNull() },
            onFailure = { error, _ ->
                onNote("Photo refusée : ${error.description}")
                null
            },
        )
    }

    fun stop() {
        stream?.stop()
        if (camera != null) session.removeCamera()
        camera = null
        stream = null
        onStreaming(false)
    }

    private companion object {
        const val FRAME_RATE = 15
    }
}