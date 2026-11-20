package github.leavesczy.wifip2p.diagnostics

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import github.leavesczy.wifip2p.caps.BandChoice
import github.leavesczy.wifip2p.caps.DeviceCaps

/**
 * Live telemetry collected during a session.
 * Rule 1 of the theory: Measure first, always.
 */
data class LinkSnapshot(
    val frequencyMhz: Int = 0,
    val band: String = "?",
    val wifiStandard: String = "?",
    val txLinkSpeedMbps: Int = 0,
    val rxLinkSpeedMbps: Int = 0,
    val rssi: Int = 0,
    val transport: String = "TCP",
    val timestamp: Long = System.currentTimeMillis()
) {
    val is5GhzOrAbove: Boolean get() = frequencyMhz in 5000..7125
    val is6Ghz: Boolean get() = frequencyMhz in 5925..7125
}

data class SessionReport(
    val hostCaps: DeviceCaps?,
    val guestCaps: DeviceCaps?,
    val selectedBand: BandChoice,
    val actualFrequencyMhz: Int,
    val snapshots: List<LinkSnapshot>,
    val avgMBps: Float,
    val peakMBps: Float,
    val bottleneck: String,          // "wifi" / "storage" / "cpu" / "thermal" / "unknown"
    val recommendation: String
)

object Diagnostics {

    fun frequencyToBand(freqMhz: Int): String = when {
        freqMhz in 2400..2500 -> "2.4 GHz"
        freqMhz in 5000..5900 -> "5 GHz"
        freqMhz in 5925..7125 -> "6 GHz"
        else -> "unknown"
    }

    fun standardName(standard: Int): String = when (standard) {
        4 -> "Wi-Fi 4"
        5 -> "Wi-Fi 5"
        6 -> "Wi-Fi 6"
        7 -> "Wi-Fi 7"
        else -> "Wi-Fi ?"
    }

    /**
     * Best-effort live link info. Many fields require API 29+.
     * Returns a usable snapshot even on older devices.
     */
    fun capture(context: Context, group: WifiP2pGroup?): LinkSnapshot {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        var freq = 0
        var tx = 0
        var rx = 0
        var rssi = 0
        var std = "?"

        if (group != null && Build.VERSION.SDK_INT >= 29) {
            try {
                freq = group.frequency
            } catch (_: Exception) {}
        }

        if (wifi != null) {
            try {
                @Suppress("DEPRECATION")
                val info: WifiInfo? = wifi.connectionInfo
                if (info != null) {
                    if (freq == 0 && Build.VERSION.SDK_INT >= 21) {
                        freq = info.frequency
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        tx = info.txLinkSpeedMbps
                        rx = info.rxLinkSpeedMbps
                    }
                    rssi = info.rssi
                    if (Build.VERSION.SDK_INT >= 30) {
                        std = when (info.wifiStandard) {
                            WifiInfo.WIFI_STANDARD_LEGACY -> "Wi-Fi 4"
                            WifiInfo.WIFI_STANDARD_11N -> "Wi-Fi 4"
                            WifiInfo.WIFI_STANDARD_11AC -> "Wi-Fi 5"
                            WifiInfo.WIFI_STANDARD_11AX -> "Wi-Fi 6"
                            WifiInfo.WIFI_STANDARD_11AD -> "Wi-Fi 60GHz"
                            6 /* WIFI_STANDARD_11BE */ -> "Wi-Fi 7"
                            else -> "Wi-Fi ?"
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return LinkSnapshot(
            frequencyMhz = freq,
            band = frequencyToBand(freq),
            wifiStandard = std,
            txLinkSpeedMbps = tx,
            rxLinkSpeedMbps = rx,
            rssi = rssi
        )
    }

    fun identifyBottleneck(
        avgMBps: Float,
        linkSpeedMbps: Int,
        storageMBps: Int,
        cpuCores: Int
    ): String {
        val linkMBps = linkSpeedMbps / 8f
        return when {
            linkSpeedMbps > 0 && avgMBps > linkMBps * 0.85f -> "wifi"
            storageMBps > 0 && avgMBps > storageMBps * 0.85f -> "storage"
            cpuCores <= 4 && avgMBps < 25f -> "cpu"
            else -> "unknown"
        }
    }
}
