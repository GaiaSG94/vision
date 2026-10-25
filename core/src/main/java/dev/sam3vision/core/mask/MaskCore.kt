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

/**
 * Format « M1 » du backend (`imaging.py::encode_mask_bits_b64`) : magic `M1`,
 * largeur uint16, hauteur uint16, puis zlib de lignes empaquetées 1 bit/pixel.
 * Le contrat écrit les tailles en **little-endian** ; la première salve les écrivait en
 * big-endian, donc [decodeBitsMask] tente les deux leçons (une image serveur déjà déployée
 * reste lisible au lieu de rendre un overlay mort).
 */
private const val BITS_MAGIC_0 = 'M'.code.toByte()
private const val BITS_MAGIC_1 = '1'.code.toByte()
private const val BITS_HEADER_SIZE = 6

/** Garde-fou d'allocation : le corps décompressé d'un masque ne dépasse jamais cette taille. */
private const val BITS_MAX_BODY_BYTES = 8 * 1024 * 1024

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

/** Nombre de niveaux d'un masque gris (8 bits) : la table de coloriage a cette taille. */
const val MASK_LEVELS = 256

/**
 * Table des [MASK_LEVELS] niveaux → couleur ARGB, alpha modulé **une fois pour toutes**.
 * Sans elle, chaque pixel payait `baseAlpha * level / 255` (une division) : sur 12 masques
 * de 105 000 pixels, c'était l'essentiel des 29 ms de coloriage mesurés. Le résultat est
 * **identique au pixel près** à l'ancien calcul (`alphaLut` = même formule).
 */
fun alphaLut(argb: Int, alpha: Int): IntArray {
    val rgb = argb and 0x00FFFFFF
    val baseAlpha = ((alpha.coerceIn(0, 255) * ((argb ushr 24) and 0xFF)) / 255).coerceIn(0, 255)
    return IntArray(MASK_LEVELS) { level ->
        if (level == 0) 0 else ((baseAlpha * level / 255) shl 24) or rgb
    }
}

/**
 * Colorie [mask] dans [lut] vers [out] : une lecture de niveau, une lecture de table,
 * une écriture — plus aucune division ni allocation par pixel. `out` doit contenir au
 * moins `mask.size` éléments ; les cases au-delà ne sont pas touchées (l'appelant peut
 * réutiliser un tampon plus grand d'une frame à l'autre).
 */
fun colorizeGrayInto(mask: GrayMask, lut: IntArray, out: IntArray) {
    require(out.size >= mask.size) { "tampon trop petit : ${out.size} < ${mask.size}" }
    val pixels = mask.pixels
    for (i in pixels.indices) out[i] = lut[pixels[i].toInt() and 0xFF]
}

/**
 * Colorie un masque gris : `argb` fournit la couleur, `alpha` l'opacité maximale,
 * le niveau du masque module l'alpha (0 = transparent).
 * Alloue le tableau de sortie : préférer [colorizeGrayInto] quand un tampon existe déjà.
 */
fun colorizeGray(mask: GrayMask, argb: Int, alpha: Int): IntArray {
    val out = IntArray(mask.size)
    colorizeGrayInto(mask, alphaLut(argb, alpha), out)
    return out
}

/**
 * Extrait un masque gris depuis des pixels ARGB (luminance, canal alpha ignoré).
 * **Hors du chemin du direct** : un masque PNG gris se décode directement en niveaux
 * (`decodeGrayMask`, ALPHA_8) ; cette fonction ne sert plus qu'aux images couleur.
 */
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

/**
 * Décode un masque « bits » (format `M1` du backend : magic, largeur uint16, hauteur uint16,
 * puis zlib de lignes empaquetées 1 bit/pixel) en masque gris 0/255.
 * Trois fois plus léger qu'un PNG gris et **sans décodeur d'image** : un `Inflater` suffit.
 * `null` si le base64, l'en-tête ou le flux zlib sont invalides.
 */
fun decodeBitsMask(maskB64: String): GrayMask? {
    val raw = base64Bytes(maskB64) ?: return null
    if (raw.size <= BITS_HEADER_SIZE) return null
    if (raw[0] != BITS_MAGIC_0 || raw[1] != BITS_MAGIC_1) return null
    val little = readUInt16(raw, 2, littleEndian = true) to readUInt16(raw, 4, littleEndian = true)
    val big = readUInt16(raw, 2, littleEndian = false) to readUInt16(raw, 4, littleEndian = false)
    // Little-endian d'abord (contrat) ; big-endian ne sert que de repli, à coût nul.
    return bitsMask(raw, little.first, little.second) ?: bitsMask(raw, big.first, big.second)
}

/** Corps du format M1 pour une taille donnée : `null` si le zlib ne rend pas exactement h lignes. */
private fun bitsMask(raw: ByteArray, width: Int, height: Int): GrayMask? {
    if (width <= 0 || height <= 0) return null
    val rowBytes = (width + 7) / 8
    // Un masque du direct fait au plus 518 px de côté : une taille qui réclamerait plus de
    // 8 Mo est une erreur d'en-tête (ou un en-tête big-endian mal lu), pas une image.
    if (rowBytes * height > BITS_MAX_BODY_BYTES) return null
    val body = inflateExact(raw, BITS_HEADER_SIZE, rowBytes * height) ?: return null
    val out = ByteArray(width * height)
    for (y in 0 until height) {
        val row = y * rowBytes
        val base = y * width
        for (x in 0 until width) {
            val bit = (body[row + (x shr 3)].toInt() shr (7 - (x and 7))) and 1
            out[base + x] = if (bit == 1) 255.toByte() else 0
        }
    }
    return GrayMask(width, height, out)
}

/** uint16 lu à [offset], little-endian (contrat) ou big-endian (repli première salve). */
private fun readUInt16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
    val a = bytes[offset].toInt() and 0xFF
    val b = bytes[offset + 1].toInt() and 0xFF
    return if (littleEndian) (b shl 8) or a else (a shl 8) or b
}

/** Inflate de `raw[offset..]` vers exactement [expected] octets ; `null` si le flux diffère. */
private fun inflateExact(raw: ByteArray, offset: Int, expected: Int): ByteArray? {
    val out = ByteArray(expected)
    val inflater = java.util.zip.Inflater()
    return try {
        inflater.setInput(raw, offset, raw.size - offset)
        var written = 0
        while (written < expected && !inflater.finished()) {
            val n = inflater.inflate(out, written, expected - written)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            written += n
        }
        if (written == expected) out else null
    } catch (e: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}
