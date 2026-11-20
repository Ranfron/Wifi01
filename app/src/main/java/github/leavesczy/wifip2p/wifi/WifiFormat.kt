package github.leavesczy.wifip2p.wifi

/** How strong the Wi-Fi signal is, from its dBm value. */
enum class SignalLevel { EXCELLENT, GOOD, FAIR, WEAK, UNKNOWN }

/**
 * What this phone's Wi-Fi reports. Every number has a "not known" value (-1, or [WifiFormat.UNKNOWN_RSSI] for the
 * signal) and the screen leaves out what is not known, instead of showing a guess.
 */
data class WifiSnapshot(
    val wifiEnabled: Boolean,
    val connected: Boolean,
    /** The network name, or null when Android did not give it (it needs the Location permission). */
    val networkName: String?,
    val frequencyMhz: Int,
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
    /** WifiInfo's standard code: 0 when unknown, 4 = Wi-Fi 4, 5 = Wi-Fi 5, 6 = Wi-Fi 6, 7 = Wi-Fi 7. */
    val wifiStandard: Int,
    val ipv4: String?,
    val fiveGhzSupported: Boolean,
    val wifiDirectSupported: Boolean
)

/** Pure conversions of what Android reports into what a person can read. */
object WifiFormat {

    const val UNKNOWN_RSSI = -127

    /** The Wi-Fi channel of a frequency in MHz, or null when the frequency is not a Wi-Fi channel. */
    fun channelOf(mhz: Int): Int? = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 && (mhz - 2407) % 5 == 0 -> (mhz - 2407) / 5
        mhz in 5000..5900 && (mhz - 5000) % 5 == 0 -> (mhz - 5000) / 5
        else -> null
    }

    fun signalLevel(rssi: Int): SignalLevel = when {
        rssi == UNKNOWN_RSSI || rssi > 0 || rssi < -127 -> SignalLevel.UNKNOWN
        rssi >= -55 -> SignalLevel.EXCELLENT
        rssi >= -67 -> SignalLevel.GOOD
        rssi >= -75 -> SignalLevel.FAIR
        else -> SignalLevel.WEAK
    }

    /** The Wi-Fi generation (4 to 7) of a standard code, or null for legacy and unknown standards. */
    fun generation(standard: Int): Int? = when (standard) {
        4, 5, 6, 7 -> standard
        else -> null
    }
}
