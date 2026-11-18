package github.leavesczy.wifip2p.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.ByteBuffer

/** A rendered QR code: black modules on a white background, [pixels] are ARGB. */
class QrImage(val width: Int, val height: Int, val pixels: IntArray)

/** Pure ZXing (no Android classes), so it can be unit-tested on the JVM. */
object QrEncoder {
    private const val BLACK = -0x1000000 // 0xFF000000
    private const val WHITE = -0x1 // 0xFFFFFFFF

    fun encode(text: String, size: Int): QrImage {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L // an on-screen code is not damaged: fewer, bigger modules scan better
        hints[EncodeHintType.MARGIN] = 2
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        return QrImage(width, height, pixels)
    }
}

/** Decodes QR codes from camera frames (the luminance / Y plane). Not thread-safe: use from one thread. */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    /** [luminance] holds [width] x [height] bytes, row after row. Returns the text, or null if no QR code is visible. */
    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap, hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }

    companion object {
        /**
         * Camera planes have row padding ([rowStride] > [width]) and sometimes a pixel stride > 1.
         * This returns the tightly packed [width] x [height] luminance image ZXing expects.
         */
        fun packLuminance(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): ByteArray {
            val packed = ByteArray(width * height)
            val limit = buffer.limit()
            for (y in 0 until height) {
                val rowStart = y * rowStride
                if (pixelStride == 1) {
                    val count = minOf(width, limit - rowStart)
                    if (count > 0) {
                        buffer.position(rowStart)
                        buffer.get(packed, y * width, count)
                    }
                } else {
                    for (x in 0 until width) {
                        val index = rowStart + x * pixelStride
                        if (index < limit) {
                            packed[y * width + x] = buffer.get(index)
                        }
                    }
                }
            }
            return packed
        }
    }
}
