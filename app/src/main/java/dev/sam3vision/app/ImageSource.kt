package dev.sam3vision.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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

/**
 * Décode un fichier image, le ramène sous [maxEdge] **et applique l'orientation EXIF**.
 *
 * Une photo CameraX est écrite dans le sens du capteur (paysage) avec un tag EXIF qui dit
 * comment la redresser : sans ce redressement, une photo prise en portrait s'affichait
 * couchée. Le même décodage sert à l'affichage, à SAM et au LLM, donc les boîtes restent
 * dans le repère de l'image montrée.
 */
fun decodeScaledExact(file: File, maxEdge: Int = MAX_IMAGE_EDGE): Bitmap? {
    val decoded = decodeScaled(file, maxEdge) ?: return null
    val longest = max(decoded.width, decoded.height)
    val scaled = if (longest <= maxEdge) {
        decoded
    } else {
        val ratio = maxEdge.toFloat() / longest
        Bitmap.createScaledBitmap(
            decoded,
            max(1, (decoded.width * ratio).toInt()),
            max(1, (decoded.height * ratio).toInt()),
            true,
        )
    }
    return uprightScaled(scaled, exifDegrees(file), maxEdge)
}

/**
 * Rotation EXIF d'un fichier photo, en degrés (0, 90, 180, 270). `0` si le fichier n'en
 * porte pas (PNG, image déjà redressée) ou si le tag est illisible.
 */
fun exifDegrees(file: File): Int = runCatching {
    val orientation = android.media.ExifInterface(file.absolutePath)
        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}.getOrDefault(0)

/**
 * Quart de tour à ajouter pour que l'image prenne l'orientation de l'écran : une photo
 * portrait dans un écran paysage (et inversement) est tournée de 90°. Pure, testée en JVM.
 */
fun screenAlignDegrees(width: Int, height: Int, screenPortrait: Boolean): Int {
    if (width <= 0 || height <= 0) return 0
    return if ((height > width) == screenPortrait) 0 else 90
}

/**
 * Décode la photo redressée (EXIF) puis alignée sur l'orientation de l'écran. Une seule
 * photo à la fois : deux passes de matrice ne coûtent rien ici (ce n'est pas une frame).
 */
fun decodeForScreen(file: File, screenPortrait: Boolean, maxEdge: Int = MAX_IMAGE_EDGE): Bitmap? {
    val upright = decodeScaledExact(file, maxEdge) ?: return null
    val align = screenAlignDegrees(upright.width, upright.height, screenPortrait)
    return if (align == 0) upright else uprightScaled(upright, align, maxEdge)
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

/** Ramène un angle dans `[0, 360)` (les HAL caméra livrent parfois des valeurs négatives). */
fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

/** Vrai si la rotation échange largeur et hauteur (90° ou 270°). */
fun isQuarterTurn(degrees: Int): Boolean = normalizeDegrees(degrees) % 180 != 0

/**
 * Taille finale d'une frame après rotation de [degrees] puis réduction au bord [edge] :
 * largeur et hauteur sont échangées sur un quart de tour, l'image n'est jamais agrandie.
 * Fonction **pure** (aucun `Bitmap`), testée en JVM : c'est elle qui pilote la matrice
 * unique d'[uprightScaled].
 */
fun targetSize(width: Int, height: Int, degrees: Int, edge: Int): Pair<Int, Int> {
    val rotatedWidth = if (isQuarterTurn(degrees)) height else width
    val rotatedHeight = if (isQuarterTurn(degrees)) width else height
    val longest = max(rotatedWidth, rotatedHeight)
    if (longest <= 0 || longest <= edge) return rotatedWidth to rotatedHeight
    val ratio = edge.toFloat() / longest
    return max(1, (rotatedWidth * ratio).toInt()) to max(1, (rotatedHeight * ratio).toInt())
}

/**
 * Redresse **et** réduit en une seule passe : rotation [degrees] et échelle au bord [edge]
 * dans la même matrice, donc un seul rééchantillonnage — là où `rotateBitmap` puis
 * `scaleToEdge` créaient deux bitmaps (≈ 2,7 ms et un alloc par frame économisés).
 * Renvoie [src] tel quel si rien ne change.
 */
fun uprightScaled(src: Bitmap, degrees: Int, edge: Int): Bitmap {
    if (src.width <= 0 || src.height <= 0) return src
    val quarterTurn = isQuarterTurn(degrees)
    // Dimensions dans le repère déjà tourné : c'est là que l'échelle s'applique.
    val turnedWidth = if (quarterTurn) src.height else src.width
    val turnedHeight = if (quarterTurn) src.width else src.height
    val (targetWidth, targetHeight) = targetSize(src.width, src.height, degrees, edge)
    if (normalizeDegrees(degrees) == 0 && targetWidth == turnedWidth && targetHeight == turnedHeight) return src
    val matrix = Matrix().apply {
        postRotate(normalizeDegrees(degrees).toFloat())
        postScale(targetWidth.toFloat() / turnedWidth, targetHeight.toFloat() / turnedHeight)
    }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
