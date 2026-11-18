package github.leavesczy.wifip2p.transfer

import github.leavesczy.wifip2p.qr.QrDecoder
import github.leavesczy.wifip2p.qr.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** The whole QR chain with the real ZXing library: payload -> image -> (camera-like) luminance -> payload. */
class QrCodeTest {

    private val code = ConnectCode("DIRECT-ab-WifiP2P-x7Kq", "pa55W0rdXyZ9", "192.168.49.1", 1995, "Pixel 8")

    /** ARGB pixels -> 8-bit luminance, like the Y plane of a camera frame. */
    private fun luminance(pixels: IntArray): ByteArray = ByteArray(pixels.size) { (if (pixels[it] == -0x1) 255 else 0).toByte() }

    private fun rotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
        return out
    }

    private fun scaleDown(src: ByteArray, w: Int, h: Int, factor: Int): Triple<ByteArray, Int, Int> {
        val nw = w / factor
        val nh = h / factor
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var sum = 0
            for (dy in 0 until factor) for (dx in 0 until factor) sum += src[(y * factor + dy) * w + x * factor + dx].toInt() and 0xFF
            out[y * nw + x] = (sum / (factor * factor)).toByte()
        }
        return Triple(out, nw, nh)
    }

    @Test
    fun payloadSurvivesEncodeAndDecode() {
        val image = QrEncoder.encode(code.encode(), 600)
        val text = QrDecoder().decode(luminance(image.pixels), image.width, image.height)
        assertEquals(code.encode(), text)
        assertEquals(code, ConnectCode.parse(text))
    }

    @Test
    fun cameraRotationDoesNotMatter() {
        val image = QrEncoder.encode(code.encode(), 500)
        var y = luminance(image.pixels)
        val decoder = QrDecoder()
        for (turn in 1..4) {
            assertEquals("rotation ${turn * 90}", code.encode(), decoder.decode(y, image.width, image.height))
            y = rotate90(y, image.width, image.height)
        }
    }

    @Test
    fun lowResolutionFramesAreStillReadable() {
        // a camera sees the code at roughly 300 px: ~8 px per module for a typical payload
        val image = QrEncoder.encode(code.encode(), 640)
        val (small, w, h) = scaleDown(luminance(image.pixels), image.width, image.height, 2) // 320 px
        assertEquals(code.encode(), QrDecoder().decode(small, w, h))
        val (tiny, tw, th) = scaleDown(luminance(image.pixels), image.width, image.height, 3) // 213 px
        assertEquals(code.encode(), QrDecoder().decode(tiny, tw, th))
    }

    @Test
    fun cameraRowPaddingIsHandled() {
        val image = QrEncoder.encode(code.encode(), 480)
        val y = luminance(image.pixels)
        val rowStride = image.width + 32 // cameras pad every row
        val padded = ByteArray(rowStride * image.height) { 0x55 }
        for (row in 0 until image.height) System.arraycopy(y, row * image.width, padded, row * rowStride, image.width)
        val packed = QrDecoder.packLuminance(ByteBuffer.wrap(padded), rowStride, 1, image.width, image.height)
        assertTrue(y.contentEquals(packed))
        assertEquals(code.encode(), QrDecoder().decode(packed, image.width, image.height))
    }

    @Test
    fun pixelStrideLargerThanOneIsHandled() {
        val w = 40
        val h = 30
        val src = ByteArray(w * h) { (it % 251).toByte() }
        val pixelStride = 2
        val rowStride = w * pixelStride + 6
        val buffer = ByteArray(rowStride * h)
        for (row in 0 until h) for (col in 0 until w) buffer[row * rowStride + col * pixelStride] = src[row * w + col]
        assertTrue(src.contentEquals(QrDecoder.packLuminance(ByteBuffer.wrap(buffer), rowStride, pixelStride, w, h)))
    }

    @Test
    fun framesWithoutQrCodeGiveNull() {
        val blank = ByteArray(300 * 200) { 128.toByte() }
        assertNull(QrDecoder().decode(blank, 300, 200))
        val noise = ByteArray(300 * 200).also { java.util.Random(5).nextBytes(it) }
        assertNull(QrDecoder().decode(noise, 300, 200))
    }

    @Test
    fun aForeignQrCodeIsDecodedButNotAccepted() {
        val image = QrEncoder.encode("https://example.com/not-our-code", 400)
        val text = QrDecoder().decode(luminance(image.pixels), image.width, image.height)
        assertNotNull(text)
        assertNull(ConnectCode.parse(text))
    }

    @Test
    fun qrStaysSmallEnoughToScanReliably() {
        // the longest possible payload: 32-byte SSID, 63-character passphrase, longest name
        val worst = ConnectCode("D".repeat(32), "p".repeat(63), "192.168.255.255", 65535, "n".repeat(40)).encode()
        val image = QrEncoder.encode(worst, 800)
        assertEquals(worst, QrDecoder().decode(luminance(image.pixels), image.width, image.height))
        // the payload the app really produces stays small (about 90 characters) = big, easy modules
        assertTrue("typical payload is ${code.encode().length} chars", code.encode().length < 100)
    }
}
