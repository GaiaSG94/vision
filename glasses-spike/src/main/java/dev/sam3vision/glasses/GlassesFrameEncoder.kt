package dev.sam3vision.glasses

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

// Mêmes bornes que le chemin téléphone (`:app` ImageSource.kt) : recopiées ici
// parce que :glasses-spike ne dépend pas de :app et ne doit pas en dépendre.
/** Bord le plus long de l'image envoyée, en pixels. */
const val MAX_IMAGE_EDGE = 1024

/** Qualité JPEG des frames envoyées à l'API. */
const val JPEG_QUALITY = 85

/**
 * Convertit une frame vidéo des lunettes en [JpegFrame] (base64 sans préfixe
 * `data:`), seule représentation que SAM / le LLM acceptent.
 *
 * Ce que le SDK livre réellement (vérifié par `javap` sur `mwdat-camera`) :
 * ```
 * data class VideoFrame(
 *     buffer: ByteBuffer, width: Int, height: Int,
 *     presentationTimeUs: Long, isCompressed: Boolean, isCodecConfig: Boolean,
 * )
 * ```
 * Aucun `format`, aucun `rowStride` : la disposition YUV est déduite par
 * [inferYuv420Layout]. Le SDK négocie côté décodeur `color-format = 19`
 * (`COLOR_FormatYUV420Planar`, I420), c'est donc le cas nominal traité ici.
 *
 * Non couvert : les frames **compressées**. Voir [toJpeg].
 */
class GlassesFrameEncoder {

    /**
     * @return la frame encodée, ou **null** si elle est inexploitable (frame vide,
     *   dimensions nulles, disposition YUV impossibles à déduire, décodage ou
     *   encodage en échec). Ne lève jamais.
     */
    fun toJpeg(frame: VideoFrame): JpegFrame? {
        // isCompressed = true → bitstream HEVC/H.264 (codec par défaut du SDK :
        // VideoFormat.getDefaultFormat() = H265, 504x896, 30 fps). Le décoder
        // exigerait un MediaCodec complet (csd-0, flush, rendu de surface) : hors
        // périmètre du spike, on renvoie null plutôt que de prétendre le faire.
        // Chemin nominal : StreamConfiguration(compressVideo = false) → le SDK
        // décode lui-même et livre des frames brutes (isCompressed = false).
        if (frame.isCompressed) return null
        // Frame de config / trame-clé (le SDK y met le drapeau isKeyFrame) : pas d'image.
        if (frame.isCodecConfig) return null
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return null
        val bytes = frame.buffer.readRemainingBytes() ?: return null
        val layout = inferYuv420Layout(bytes.size, width, height) ?: return null
        val argb = IntArray(width * height)
        if (!YuvConverter.yuv420ToArgb(bytes, layout, argb)) return null
        return runCatching { encode(argb, frame) }.getOrNull()
    }

    /** ARGB → Bitmap → réduction sous [MAX_IMAGE_EDGE] → JPEG → base64. */
    private fun encode(argb: IntArray, frame: VideoFrame): JpegFrame? {
        val source = Bitmap.createBitmap(argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val scaled = scaleDown(source)
        try {
            val buffer = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) return null
            val encoded = buffer.toByteArray()
            if (encoded.isEmpty()) return null
            val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
            return JpegFrame(base64, scaled.width, scaled.height, capturedAtMs(frame))
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    /** Réduit l'image pour que son plus grand côté fasse au plus [MAX_IMAGE_EDGE]. */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_IMAGE_EDGE) return bitmap
        val ratio = MAX_IMAGE_EDGE.toFloat() / longest
        val targetWidth = max(1, (bitmap.width * ratio).roundToInt())
        val targetHeight = max(1, (bitmap.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Horodatage de la frame si le SDK en fournit un (microsecondes → millisecondes),
     * sinon l'horloge monotone du téléphone.
     *
     * À VÉRIFIER : `presentationTimeUs` vient de l'horloge média (les lunettes ou
     * le décodeur), pas de `System.currentTimeMillis()`. Les deux branches ne sont
     * donc pas dans le même référentiel — à unifier si l'aval compare ces valeurs.
     */
    private fun capturedAtMs(frame: VideoFrame): Long {
        val presentationTimeUs = frame.presentationTimeUs
        return if (presentationTimeUs > 0L) presentationTimeUs / 1000L else SystemClock.elapsedRealtime()
    }
}

/**
 * Copie les octets encore lisibles du buffer, sans déplacer la position du
 * buffer d'origine (le SDK peut le réutiliser). Renvoie null si le buffer est vide.
 */
private fun ByteBuffer.readRemainingBytes(): ByteArray? {
    val view = duplicate()
    view.rewind()
    val size = view.remaining()
    if (size <= 0) return null
    val bytes = ByteArray(size)
    view.get(bytes)
    return bytes
}
