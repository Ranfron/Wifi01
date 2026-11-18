package github.leavesczy.wifip2p.qr

import android.graphics.Bitmap

object QrBitmap {

    /** A black-on-white bitmap of the QR code (always light, also in dark mode - scanners need the contrast). */
    fun create(text: String, size: Int): Bitmap {
        val image = QrEncoder.encode(text, size)
        return Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }
}
