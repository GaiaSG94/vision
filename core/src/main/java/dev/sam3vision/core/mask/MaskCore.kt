package dev.sam3vision.core.mask

import java.util.Base64

/**
 * Masque gris 8 bits : 255 = objet, 0 = fond. Pur Kotlin (testable sur JVM),
 * la conversion en [android.graphics.Bitmap] vit dans Masks.kt.
 */
data class GrayMask(val width: Int, val height: Int, val pixels: ByteArray) {

    val size: Int get() = width * height

    fun levelAt(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return pixels[y * width + x].toInt() and 0xFF
    }

    fun isObject(x: Int, y: Int): Boolean = levelAt(x, y) >= 128
}

private const val PNG_SIGNATURE_SIZE = 8
private const val IHDR_OFFSET = 12

/** Décode du base64 tolérant (espaces/retours ligne, préfixe `data:...;base64,`). */
fun base64Bytes(text: String): ByteArray? {
    val cleaned = text.substringAfter("base64,", text).filterNot { it.isWhitespace() }
    if (cleaned.isEmpty()) return null
    return try {
        Base64.getDecoder().decode(cleaned)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Lit largeur/hauteur dans l'en-tête IHDR d'un PNG (pur, sans décodage d'image). */
fun parsePngSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < IHDR_OFFSET + 8) return null
    if (bytes[1] != 'P'.code.toByte() || bytes[2] != 'N'.code.toByte() || bytes[3] != 'G'.code.toByte()) {
        return null
    }
    val width = readInt(bytes, IHDR_OFFSET)
    val height = readInt(bytes, IHDR_OFFSET + 4)
    return if (width > 0 && height > 0) width to height else null
}

private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

/**
 * Colorie un masque gris : `argb` fournit la couleur, `alpha` l'opacité maximale,
 * le niveau du masque module l'alpha (0 = transparent).
 */
fun colorizeGray(mask: GrayMask, argb: Int, alpha: Int): IntArray {
    val rgb = argb and 0x00FFFFFF
    val baseAlpha = ((alpha.coerceIn(0, 255) * ((argb ushr 24) and 0xFF)) / 255).coerceIn(0, 255)
    val out = IntArray(mask.size)
    for (i in 0 until mask.size) {
        val level = mask.pixels[i].toInt() and 0xFF
        out[i] = if (level == 0) 0 else ((baseAlpha * level / 255) shl 24) or rgb
    }
    return out
}

/** Extrait un masque gris depuis des pixels ARGB (luminance, canal alpha ignoré). */
fun grayFromArgb(pixels: IntArray, width: Int, height: Int): GrayMask {
    val out = ByteArray(width * height)
    for (i in out.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return GrayMask(width, height, out)
}
