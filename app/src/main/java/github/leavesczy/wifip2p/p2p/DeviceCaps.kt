package github.leavesczy.wifip2p.p2p

import android.content.Context
import android.net.wifi.WifiManager

/** What this phone's Wi-Fi can do, read from the system. A failure means "not known", never a guess. */
object DeviceCaps {

    private fun wifi(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun supportsFiveGhz(context: Context): Boolean = try {
        wifi(context)?.is5GHzBandSupported() == true
    } catch (_: Exception) {
        false
    }

    /** The frequency of the current Wi-Fi connection in MHz, or -1 when the system does not tell. */
    @Suppress("DEPRECATION")
    fun connectedFrequencyMhz(context: Context): Int = try {
        wifi(context)?.connectionInfo?.frequency ?: -1
    } catch (_: Exception) {
        -1
    }
}
