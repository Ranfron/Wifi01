package github.leavesczy.wifip2p.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import github.leavesczy.wifip2p.p2p.DeviceCaps
import java.net.Inet4Address

/** Reads what this phone's Wi-Fi reports right now. Nothing is cached; every call asks the system again. */
object WifiReader {

    @Suppress("DEPRECATION")
    fun read(context: Context): WifiSnapshot {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val wifiNetwork = try {
            connectivity?.allNetworks?.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {
            null
        }
        val info = try {
            wifi?.connectionInfo
        } catch (_: Exception) {
            null
        }
        val ipv4 = wifiNetwork?.let { connectivity?.getLinkProperties(it) }
            ?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address }
            ?.hostAddress

        // Android shows the network name only to an app that has the Location permission (this app does not ask for it)
        val nameAllowed = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val rawName = if (nameAllowed) info?.ssid?.trim('"') else null
        val networkName = rawName?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

        return WifiSnapshot(
            wifiEnabled = try {
                wifi?.isWifiEnabled == true
            } catch (_: Exception) {
                false
            },
            connected = wifiNetwork != null,
            networkName = networkName,
            frequencyMhz = info?.frequency?.takeIf { it > 0 } ?: -1,
            rssiDbm = info?.rssi ?: WifiFormat.UNKNOWN_RSSI,
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 } ?: -1,
            wifiStandard = if (android.os.Build.VERSION.SDK_INT >= 30) info?.wifiStandard ?: 0 else 0,
            ipv4 = ipv4,
            fiveGhzSupported = DeviceCaps.supportsFiveGhz(app),
            wifiDirectSupported = app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        )
    }
}
