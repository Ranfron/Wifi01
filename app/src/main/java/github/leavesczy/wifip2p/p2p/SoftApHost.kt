package github.leavesczy.wifip2p.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

/**
 * Fallback when Wi-Fi Direct group creation fails or is flaky on older devices.
 *
 * Uses [WifiManager.startLocalOnlyHotspot] (API 26+). The resulting soft-AP is only visible
 * to this app's process (no internet, no system hotspot UI). Client joins with the same
 * SSID+passphrase pattern used by QR ConnectCode, so the rest of the stack stays unchanged.
 *
 * Speed is comparable to 5 GHz Wi-Fi Direct on capable hardware; reliability is often better
 * on OEM firmwares that break P2P.
 */
@SuppressLint("MissingPermission")
class SoftApHost(context: Context, private val listener: Listener) {

    interface Listener {
        /** Soft-AP is up. [ssid] / [passphrase] go into the QR code; [gateway] is the host IP. */
        fun onStarted(ssid: String, passphrase: String, gateway: String)

        fun onFailed(reason: String)

        fun onStopped()
    }

    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var started = false

    val isRunning: Boolean get() = started && reservation != null

    fun start() {
        if (started) return
        val wm = wifi
        if (wm == null || Build.VERSION.SDK_INT < 26) {
            listener.onFailed("LocalOnlyHotspot requires Android 8+")
            return
        }
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    started = true
                    val config = res.wifiConfiguration
                    val ssid = config?.SSID?.trim('"') ?: ("DIRECT-AP-" + token(4))
                    val pass = config?.preSharedKey?.trim('"') ?: token(12)
                    // Soft-AP gateway is almost always 192.168.43.1 on Android
                    val gateway = "192.168.43.1"
                    main.post { listener.onStarted(ssid, pass, gateway) }
                }

                override fun onFailed(reason: Int) {
                    started = false
                    reservation = null
                    val msg = when (reason) {
                        ERROR_NO_CHANNEL -> "No channel available"
                        ERROR_TETHERING_DISALLOWED -> "Tethering disallowed"
                        ERROR_INCOMPATIBLE_MODE -> "Incompatible Wi-Fi mode"
                        else -> "SoftAP failed ($reason)"
                    }
                    main.post { listener.onFailed(msg) }
                }

                override fun onStopped() {
                    started = false
                    reservation = null
                    main.post { listener.onStopped() }
                }
            }, main)
        } catch (e: SecurityException) {
            listener.onFailed("Permission denied for SoftAP")
        } catch (e: Exception) {
            listener.onFailed(e.message ?: "SoftAP error")
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
        started = false
    }

    private companion object {
        const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun token(len: Int): String {
            val r = SecureRandom()
            return buildString(len) { repeat(len) { append(ALPHABET[r.nextInt(ALPHABET.length)]) } }
        }
    }
}
