package dev.sam3vision.core.mask

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Décode un masque PNG gris 8 bits (base64) en Bitmap gris ARGB_8888.
 * `null` si le base64 ou le PNG est invalide.
 */
fun decodeMaskPng(maskPngB64: String): Bitmap? {
    val gray = decodeGrayMask(maskPngB64) ?: return null
    return maskToBitmap(gray)
}

/** Overlay coloré du masque : même dimensions que [src], alpha modulé par la luminance. */
fun colorizeMask(src: Bitmap, argb: Int, alpha: Int): Bitmap {
    val width = src.width
    val height = src.height
    if (width <= 0 || height <= 0) return src
    val pixels = IntArray(width * height)
    src.getPixels(pixels, 0, width, 0, 0, width, height)
    val colorized = colorizeGray(grayFromArgb(pixels, width, height), argb, alpha)
    return Bitmap.createBitmap(colorized, width, height, Bitmap.Config.ARGB_8888)
}

/** Bitmap → masque gris (255 = objet). */
fun decodeGrayMask(maskPngB64: String): GrayMask? {
    val bytes = base64Bytes(maskPngB64) ?: return null
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val width = bmp.width
    val height = bmp.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bmp.getPixels(pixels, 0, width, 0, 0, width, height)
    return grayFromArgb(pixels, width, height)
}

/** Masque gris → Bitmap affichable (niveaux de gris opaques). */
fun maskToBitmap(mask: GrayMask): Bitmap {
    val pixels = IntArray(mask.size) { i ->
        val level = mask.pixels[i].toInt() and 0xFF
        (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }
    return Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
}
