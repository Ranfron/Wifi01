package github.leavesczy.wifip2p.caps

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.security.SecureRandom
import java.util.Base64

/**
 * Measured capability of this phone. Exchanged during handshake so both sides
 * can pick the common best band and transport.
 *
 * Rule: Speed = min(A, B). Never assume the other phone is as strong as yours.
 */
data class DeviceCaps(
    val wifiStandard: Int,          // 4=Wi-Fi4, 5=Wi-Fi5, 6=Wi-Fi6, 7=Wi-Fi7
    val supportedBands: Set<Int>,   // 2, 5, 6
    val maxChannelWidthMhz: Int,    // 20 / 40 / 80 / 160 / 320
    val maxQam: Int,                // 64 / 256 / 1024 / 4096
    val apiLevel: Int,
    val isP2pR2: Boolean,           // Android 16+ Wi-Fi Direct R2
    val storageSpeedMBps: Int,      // rough measured sequential write
    val cpuCores: Int,
    val score: Int,                 // higher = better candidate for Group Owner
    val nonce: String               // 16-byte random, base64url, 2-min lifetime
) {
    fun toCompact(): String {
        // Compact form for QR: std|bands|width|qam|api|r2|storage|cores|score|nonce
        val bands = supportedBands.sorted().joinToString("")
        return listOf(
            wifiStandard,
            bands,
            maxChannelWidthMhz,
            maxQam,
            apiLevel,
            if (isP2pR2) 1 else 0,
            storageSpeedMBps,
            cpuCores,
            score,
            nonce
        ).joinToString("|")
    }

    companion object {
        fun fromCompact(raw: String?): DeviceCaps? {
            if (raw.isNullOrBlank()) return null
            val p = raw.split('|')
            if (p.size < 10) return null
            return try {
                val bands = p[1].map { it.toString().toInt() }.toSet()
                DeviceCaps(
                    wifiStandard = p[0].toInt(),
                    supportedBands = bands,
                    maxChannelWidthMhz = p[2].toInt(),
                    maxQam = p[3].toInt(),
                    apiLevel = p[4].toInt(),
                    isP2pR2 = p[5] == "1",
                    storageSpeedMBps = p[6].toInt(),
                    cpuCores = p[7].toInt(),
                    score = p[8].toInt(),
                    nonce = p[9]
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Measure this device once at startup / before hosting. */
        fun measure(context: Context): DeviceCaps {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val api = Build.VERSION.SDK_INT

            val bands = mutableSetOf(2) // 2.4 always assumed available for P2P
            if (wifi != null) {
                try {
                    if (wifi.is5GHzBandSupported) bands.add(5)
                } catch (_: Exception) {}
                if (api >= 30) {
                    try {
                        if (wifi.is6GHzBandSupported) bands.add(6)
                    } catch (_: Exception) {}
                }
            }

            // Heuristic standard / width / QAM from API level + band support
            val (standard, width, qam) = when {
                api >= 36 && 6 in bands -> Triple(7, 320, 4096)   // Wi-Fi 7 ready
                api >= 30 && 6 in bands -> Triple(6, 160, 1024)   // Wi-Fi 6E
                api >= 29 && 5 in bands -> Triple(6, 160, 1024)   // Wi-Fi 6
                api >= 23 && 5 in bands -> Triple(5, 80, 256)     // Wi-Fi 5
                else -> Triple(4, 40, 64)                        // Wi-Fi 4
            }

            val isR2 = api >= 36 // GROUP_OWNER_BAND_6GHZ + P2P R2 available

            // Very rough storage probe (kept light; real measurement can be expanded later)
            val storageMBps = estimateStorageSpeed()

            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

            // Score for GO selection: higher standard + wider channel + more cores + faster storage
            val score = standard * 100 + width + cores * 5 + (storageMBps / 10)

            return DeviceCaps(
                wifiStandard = standard,
                supportedBands = bands,
                maxChannelWidthMhz = width,
                maxQam = qam,
                apiLevel = api,
                isP2pR2 = isR2,
                storageSpeedMBps = storageMBps,
                cpuCores = cores,
                score = score,
                nonce = generateNonce()
            )
        }

        private fun generateNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return if (Build.VERSION.SDK_INT >= 26) {
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            } else {
                android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            }
        }

        private fun estimateStorageSpeed(): Int {
            // Conservative defaults; real sequential write probe can be added later
            return when {
                Build.VERSION.SDK_INT >= 31 -> 180  // modern UFS
                Build.VERSION.SDK_INT >= 26 -> 90
                else -> 40
            }
        }
    }
}

enum class BandChoice {
    BAND_6GHZ,
    BAND_5GHZ,
    BAND_2_4GHZ,
    AUTO
}
