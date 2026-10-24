package dev.sam3vision.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import dev.sam3vision.app.cleanup.CapturePaths
import kotlin.math.max
import kotlin.math.min

/** Côté client : redimensionne et encode la photo avant envoi (bord max, JPEG, base64). */
const val MAX_IMAGE_EDGE = 1024
const val JPEG_QUALITY = 85

/**
 * Fichier JPEG neuf dans le stockage **privé** de l'app (`Android/data/<pkg>/files/Pictures`),
 * jamais sur le stockage partagé : c'est exactement le périmètre que purge
 * `CaptureCleanupWorker` chaque heure. Le nom garde le préfixe `capture-`.
 */
fun newCacheFile(context: Context): File = CapturePaths.newImageFile(context)

/** Copie l'URI choisi (galerie) vers un fichier du cache interne. */
fun copyUriToCache(context: Context, uri: Uri, target: File): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output, 8192) }
    } != null
}.getOrDefault(false)

/** Décode un fichier image en réduisant l'échantillon pour rester sous [maxEdge]. */
fun decodeScaled(file: File, maxEdge: Int = MAX_IMAGE_EDGE): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val sample = max(1, max(bounds.outWidth, bounds.outHeight) / maxEdge)
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** Décode un fichier image, puis le ramène exactement sous [maxEdge] (puissance de 2 insuffisante). */
fun decodeScaledExact(file: File, maxEdge: Int = MAX_IMAGE_EDGE): Bitmap? {
    val decoded = decodeScaled(file, maxEdge) ?: return null
    val longest = max(decoded.width, decoded.height)
    if (longest <= maxEdge) return decoded
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        decoded,
        max(1, (decoded.width * ratio).toInt()),
        max(1, (decoded.height * ratio).toInt()),
        true,
    )
}

/** Bitmap → base64 JPEG sans préfixe `data:`. */
fun bitmapToJpegBase64(bitmap: Bitmap, quality: Int = JPEG_QUALITY): String {
    val buffer = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
    return android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP)
}

/** Mini image monochrome pour tester un endpoint LLM sans consommer de crédit. */
fun testBitmap(color: Int = 0xFF2E7D32.toInt()): Bitmap =
    Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

/** Échelle d'affichage : l'overlay doit être dessiné dans les mêmes bornes que la photo. */
fun overlayScaleFor(viewportWidth: Float, bitmapWidth: Int): Float =
    if (bitmapWidth <= 0) 1f else min(1f, viewportWidth / bitmapWidth)
