package dev.sam3vision.core.mask

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le `mockable-android.jar` masque javax.imageio : les tests portent donc sur la
 * logique pure (en-têtes PNG synthétiques, pixels en IntArray), pas sur un vrai décodage.
 */
class MaskCoreTest {

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val signature = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A.toByte(), 0x0A,
        )
        val ihdr = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        return signature + ihdr + intBytes(width) + intBytes(height) + byteArrayOf(8, 0, 0, 0, 0)
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    @Test
    fun `base64 tolere le prefixe data et les retours ligne`() {
        val raw = base64Bytes("data:image/png;base64,QUJD\nRA==")
        assertNotNull(raw)
        assertEquals("ABCD", String(raw!!))
        val roundTrip = base64Bytes(Base64.getEncoder().encodeToString("ABCD".toByteArray()))
        assertEquals("ABCD", String(roundTrip!!))
        assertNull(base64Bytes("pas du base64 !!!"))
        assertNull(base64Bytes(""))
    }

    @Test
    fun `parsePngSize lit les dimensions dans l IHDR`() {
        assertEquals(64 to 32, parsePngSize(pngBytes(64, 32)))
        assertEquals(1024 to 768, parsePngSize(pngBytes(1024, 768)))
        assertNull(parsePngSize(byteArrayOf(1, 2, 3)))
        assertNull(parsePngSize("JPEGFAKE".toByteArray() + ByteArray(12)))
    }

    @Test
    fun `grayFromArgb extrait la luminance`() {
        val pixels = IntArray(4) { i -> if (i % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val mask = grayFromArgb(pixels, 2, 2)

        assertEquals(2, mask.width)
        assertEquals(2, mask.height)
        assertEquals(0, mask.levelAt(0, 0))
        assertEquals(255, mask.levelAt(1, 0))
        assertTrue(mask.isObject(1, 1))
        assertTrue(!mask.isObject(0, 1))
        assertEquals(0, mask.levelAt(9, 9))
    }

    @Test
    fun `colorizeGray module l alpha par le niveau du masque`() {
        val mask = GrayMask(3, 1, byteArrayOf(0, 128.toByte(), 255.toByte()))
        val colored = colorizeGray(mask, 0xFF00FF00.toInt(), alpha = 200)

        assertEquals(0, colored[0])
        assertEquals(100, colored[1] ushr 24)
        assertEquals(200, colored[2] ushr 24)
        assertEquals(0x00FF00, colored[2] and 0x00FFFFFF) // G du 0xFF00FF00
    }

    @Test
    fun `colorizeGray ne depasse jamais l alpha demande`() {
        val mask = GrayMask(1, 2, byteArrayOf(255.toByte(), 255.toByte()))
        val colored = colorizeGray(mask, argb = 0x80FF0000.toInt(), alpha = 255)
        assertEquals(128, colored[0] ushr 24)
        assertEquals(128, colored[1] ushr 24)
    }

    @Test
    fun `masque 255 = objet sur toute la surface`() {
        val full = GrayMask(4, 4, ByteArray(16) { 255.toByte() })
        assertTrue((0 until 4).all { x -> (0 until 4).all { y -> full.isObject(x, y) } })
        assertEquals(16, full.size)
    }
}
