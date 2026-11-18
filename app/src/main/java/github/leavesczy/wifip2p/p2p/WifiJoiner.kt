package github.leavesczy.wifip2p.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * Joins the Wi-Fi Direct group of the phone that showed the QR code, as an ordinary Wi-Fi client
 * (SSID + passphrase from the code). Android asks the user once ("Connect to DIRECT-...?"); the
 * other phone does not have to accept anything and there is no device discovery.
 *
 * The network is only available to this app. While the connection is up the whole process is bound
 * to it, so plain sockets reach the group owner. [leave] undoes everything.
 */
@RequiresApi(29)
class WifiJoiner(context: Context, private val listener: Listener) {

    interface Listener {
        /** Connected to the group (callbacks arrive on the main thread). */
        fun onJoined()

        /** The user said no, the network was not found or nobody answered in time. */
        fun onJoinFailed()

        /** The connection was lost after it had been established. */
        fun onLost()
    }

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var joined = false

    fun join(ssid: String, passphrase: String) {
        leave()
        val manager = connectivity
        if (manager == null) {
            listener.onJoinFailed()
            return
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                manager.bindProcessToNetwork(network)
                if (!joined) {
                    joined = true
                    listener.onJoined()
                }
            }

            override fun onUnavailable() {
                listener.onJoinFailed()
            }

            override fun onLost(network: Network) {
                if (joined) {
                    joined = false
                    listener.onLost()
                }
            }
        }
        callback = networkCallback
        try {
            manager.requestNetwork(request, networkCallback, main, JOIN_TIMEOUT_MS)
        } catch (_: RuntimeException) {
            callback = null
            listener.onJoinFailed()
        }
    }

    /** Disconnects from the group and gives the phone's normal Wi-Fi connection back. */
    fun leave() {
        val manager = connectivity
        val networkCallback = callback
        callback = null
        joined = false
        if (manager == null || networkCallback == null) {
            return
        }
        try {
            manager.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        try {
            manager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 45_000
    }
}
