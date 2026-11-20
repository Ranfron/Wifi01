package github.leavesczy.wifip2p.band

/**
 * The Wi-Fi band of the group. [AUTO] means the system chose it and the QR code makes no promise about it: that is
 * the case on Android 9 and older, where an app cannot choose the band.
 */
enum class Band(val code: String) {
    FIVE_GHZ("5"),
    TWO_POINT_FOUR_GHZ("2"),
    AUTO("a");

    companion object {
        fun fromCode(code: String?): Band? = entries.firstOrNull { it.code == code }

        /** The band of a Wi-Fi frequency in MHz, or null when it is neither 2.4 nor 5 GHz. */
        fun ofFrequency(mhz: Int): Band? = when (mhz) {
            in 2400..2500 -> TWO_POINT_FOUR_GHZ
            in 4900..5900 -> FIVE_GHZ
            else -> null
        }
    }
}

/** A fresh, unguessable token for one QR code. A code that carries an older token is refused by the host. */
object Nonces {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private const val LENGTH = 10
    private val random = java.security.SecureRandom()

    fun create(): String = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    fun isValid(text: String): Boolean = text.length in 6..16 && text.all { it in ALPHABET || it in '0'..'9' }
}
