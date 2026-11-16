package github.leavesczy.wifip2p.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import github.leavesczy.wifip2p.util.SystemChecks
import java.security.SecureRandom

data class P2pPeer(val name: String, val address: String, val status: Int)

data class GroupResult(val ok: Boolean, val fiveGhz: Boolean, val reason: Int)

/**
 * Thin, lifecycle-independent wrapper around [WifiP2pManager]. Everything is reported to
 * [Listener] on the main thread. Permissions are checked by the UI before this class is used.
 */
@SuppressLint("MissingPermission")
class P2pController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onP2pStateChanged(enabled: Boolean)
        fun onThisDevice(name: String, address: String)
        fun onPeers(peers: List<P2pPeer>)
        fun onDiscovery(active: Boolean)

        /** [info] is null when no group exists. */
        fun onConnectionInfo(info: WifiP2pInfo?)
        /** Reports whether this device currently has a local P2P group, even before a client joins. */
        fun onGroupInfo(exists: Boolean)
        fun onChannelLost()
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val main = Handler(Looper.getMainLooper())

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var started = false
    private var gotState = false
    private var channelResets = 0
    private var refreshSequence = 0L

    val isSupported: Boolean
        get() = manager != null

    fun start() {
        val m = manager ?: return
        if (started) {
            return
        }
        started = true
        log("START api=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        channel = newChannel(m)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handle(intent)
            }
        }
        receiver = r
        ContextCompat.registerReceiver(appContext, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Broadcasts only report CHANGES, so ask for the current situation as well.
        refresh()
        requestOwnDevice()
        // The "P2P enabled" broadcast is sticky; if it still did not arrive, fall back to the Wi-Fi switch.
        main.postDelayed({
            if (started && !gotState) {
                listener.onP2pStateChanged(SystemChecks.isWifiEnabled(appContext))
            }
        }, 1_500)
    }

    fun stop() {
        if (!started) {
            return
        }
        started = false
        refreshSequence++ // invalidate callbacks already in flight
        receiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        receiver = null
        main.removeCallbacksAndMessages(null)
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            try {
                m.stopPeerDiscovery(ch, NOOP)
                m.cancelConnect(ch, NOOP)
                m.removeGroup(ch, NOOP)
            } catch (_: Exception) {
            }
            // give the framework a moment to process the requests before the channel disappears
            if (Build.VERSION.SDK_INT >= 27) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        ch.close()
                    } catch (_: Exception) {
                    }
                }, 2_000)
            }
        }
        channel = null
    }

    fun refresh() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!started) {
            return
        }
        val requestId = ++refreshSequence
        try {
            m.requestConnectionInfo(ch) { info ->
                if (requestId == refreshSequence) {
                    log("CONNECTION_INFO groupFormed=${info?.groupFormed} isGroupOwner=${info?.isGroupOwner} ownerAddress=${info?.groupOwnerAddress}")
                    listener.onConnectionInfo(if (info != null && info.groupFormed) info else null)
                } else {
                    log("ignored stale CONNECTION_INFO callback request=$requestId latest=$refreshSequence")
                }
            }
            m.requestGroupInfo(ch) { group ->
                if (requestId == refreshSequence) {
                    val clients = group?.clientList?.joinToString { "${it.deviceName}:${it.status}" }.orEmpty()
                    log("GROUP_INFO exists=${group != null} isOwner=${group?.isGroupOwner} clients=${group?.clientList?.size ?: 0} members=[$clients] network=${group?.networkName}")
                    listener.onGroupInfo(group != null)
                } else {
                    log("ignored stale GROUP_INFO callback request=$requestId latest=$refreshSequence")
                }
            }
        } catch (e: SecurityException) {
            log("REFRESH securityException=${e.message}")
        }
    }

    fun discover(onResult: (ok: Boolean, reason: Int) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null || !started) {
            onResult(false, WifiP2pManager.ERROR)
            return
        }
        log("DISCOVER requested")
        try {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("DISCOVER action=SUCCESS")
                    onResult(true, 0)
                }
                override fun onFailure(reason: Int) {
                    log("DISCOVER action=FAILURE reason=$reason")
                    onResult(false, reason)
                }
            })
        } catch (e: SecurityException) {
            log("DISCOVER securityException=${e.message}")
            onResult(false, WifiP2pManager.ERROR)
        }
    }

    /**
     * Wait until peer discovery has stopped before submitting the connection request.
     * Some Android Wi-Fi P2P implementations briefly return BUSY while discovery is changing.
     */
    fun stopDiscovery(onComplete: () -> Unit = {}) {
        stopDiscoveryAttempt(onComplete, retriesLeft = 4)
    }

    private fun stopDiscoveryAttempt(onComplete: () -> Unit, retriesLeft: Int) {
        val m = manager
        val ch = channel
        if (m == null || ch == null || !started) {
            onComplete()
            return
        }
        try {
            m.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("STOP_DISCOVERY action=SUCCESS")
                    onComplete()
                }

                override fun onFailure(reason: Int) {
                    log("STOP_DISCOVERY action=FAILURE reason=$reason retriesLeft=$retriesLeft")
                    if (reason == WifiP2pManager.BUSY && retriesLeft > 0) {
                        main.postDelayed({ stopDiscoveryAttempt(onComplete, retriesLeft - 1) }, 350L)
                    } else {
                        // ERROR commonly means discovery is already stopped; do not block connect.
                        onComplete()
                    }
                }
            })
        } catch (e: SecurityException) {
            log("STOP_DISCOVERY securityException=${e.message}")
            onComplete()
        }
    }

    fun connect(address: String, onResult: (ok: Boolean, reason: Int) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null || !started) {
            onResult(false, WifiP2pManager.ERROR)
            return
        }
        log("CONNECT requested peerAddress=$address")
        try {
            m.connect(ch, buildConnectConfig(address), object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Android accepted the connect request; it does NOT mean group formation is complete.
                    log("CONNECT action=SUCCESS peerAddress=$address; waiting for peer/group callback")
                    onResult(true, 0)
                }
                override fun onFailure(reason: Int) {
                    log("CONNECT action=FAILURE peerAddress=$address reason=$reason")
                    onResult(false, reason)
                }
            })
        } catch (e: SecurityException) {
            log("CONNECT securityException=${e.message}")
            onResult(false, WifiP2pManager.ERROR)
        } catch (e: IllegalArgumentException) {
            log("CONNECT illegalArgument=${e.message}")
            onResult(false, WifiP2pManager.ERROR)
        }
    }

    /** Leaves/destroys the current group and abandons a pending connection attempt. */
    fun disconnect(onDone: () -> Unit = {}) {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.cancelConnect(ch, NOOP)
        } catch (_: Exception) {
        }
        removeGroupIfAny { onDone() }
    }

    /**
     * Creates the group this device owns (the receiver does this automatically).
     * On Android 10+ the group is requested on the 5 GHz band, which is the single biggest speed
     * factor of Wi-Fi Direct: 2.4 GHz is typically 3-6x slower. Falls back to the default group if
     * the phone or the current Wi-Fi situation does not allow 5 GHz.
     */
    fun createGroup(prefer5Ghz: Boolean, onDone: (GroupResult) -> Unit) {
        removeGroupIfAny { removed ->
            main.postDelayed({
                if (started) {
                    doCreate(prefer5Ghz, onDone)
                }
            }, if (removed) 800L else 0L)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun newChannel(m: WifiP2pManager): WifiP2pManager.Channel {
        return m.initialize(appContext, Looper.getMainLooper()) {
            // The framework dropped the channel (Wi-Fi service restarted): re-create it once or twice.
            if (started && channelResets < 3) {
                channelResets++
                channel = newChannel(m)
                listener.onChannelLost()
                refresh()
            }
        }
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                log("BROADCAST P2P_STATE_CHANGED")
                gotState = true
                val state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED
                )
                listener.onP2pStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                log("BROADCAST PEERS_CHANGED")
                requestPeers()
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                log("BROADCAST CONNECTION_CHANGED")
                refresh()
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = IntentCompat.getParcelableExtra(
                    intent, WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java
                )
                if (device != null) {
                    log("THIS_DEVICE name=${device.deviceName} address=${device.deviceAddress} status=${device.status}")
                    listener.onThisDevice(device.deviceName.orEmpty(), device.deviceAddress.orEmpty())
                }
            }

            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                val state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                )
                log("BROADCAST DISCOVERY state=$state")
                listener.onDiscovery(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED)
            }
        }
    }

    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestPeers(ch) { list ->
                val devices: Collection<WifiP2pDevice> = list?.deviceList.orEmpty()
                devices.forEach { device ->
                    log("PEER name=${device.deviceName} address=${device.deviceAddress} status=${device.status}")
                }
                listener.onPeers(devices.map {
                    val address = it.deviceAddress.orEmpty()
                    P2pPeer(it.deviceName.orEmpty().ifBlank { address }, address, it.status)
                })
            }
        } catch (e: SecurityException) {
            log("REQUEST_PEERS securityException=${e.message}")
        }
    }

    private fun requestOwnDevice() {
        if (Build.VERSION.SDK_INT < 29) {
            return
        }
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestDeviceInfo(ch) { device ->
                if (device != null) {
                    log("THIS_DEVICE name=${device.deviceName} address=${device.deviceAddress} status=${device.status}")
                    listener.onThisDevice(device.deviceName.orEmpty(), device.deviceAddress.orEmpty())
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private fun buildConnectConfig(address: String): WifiP2pConfig {
        var config: WifiP2pConfig? = null
        if (Build.VERSION.SDK_INT >= 29) {
            config = try {
                WifiP2pConfig.Builder()
                    .setDeviceAddress(MacAddress.fromString(address))
                    .enablePersistentMode(false)
                    .build()
            } catch (_: Exception) {
                null
            }
        }
        if (config == null) {
            config = WifiP2pConfig()
            config.deviceAddress = address
            config.wps.setup = WpsInfo.PBC
        }
        // The receiver owns the group; the sender prefers to be the client.
        config.groupOwnerIntent = 0
        return config
    }

    private fun removeGroupIfAny(done: (removed: Boolean) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            done(false)
            return
        }
        try {
            m.requestGroupInfo(ch) { group ->
                if (group == null) {
                    done(false)
                } else {
                    m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = done(true)
                        override fun onFailure(reason: Int) = done(false)
                    })
                }
            }
        } catch (_: SecurityException) {
            done(false)
        }
    }

    private fun supports5Ghz(): Boolean {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return try {
            wifi.is5GHzBandSupported()
        } catch (_: Exception) {
            false
        }
    }

    private fun doCreate(prefer5Ghz: Boolean, onDone: (GroupResult) -> Unit) {
        log("CREATE_GROUP requested prefer5Ghz=$prefer5Ghz supports5Ghz=${supports5Ghz()}")
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            onDone(GroupResult(false, false, WifiP2pManager.ERROR))
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && prefer5Ghz && supports5Ghz()) {
            val config = try {
                buildGroupConfig5Ghz()
            } catch (_: Exception) {
                null
            }
            if (config != null) {
                try {
                    m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            log("CREATE_GROUP 5GHz action=SUCCESS")
                            onDone(GroupResult(true, true, 0))
                        }
                        override fun onFailure(reason: Int) {
                            log("CREATE_GROUP 5GHz action=FAILURE reason=$reason; using default band")
                            plainCreate(2, onDone)
                        }
                    })
                    return
                } catch (_: Exception) {
                    // fall through to the default group
                }
            }
        }
        plainCreate(2, onDone)
    }

    private fun plainCreate(retriesLeft: Int, onDone: (GroupResult) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null || !started) {
            onDone(GroupResult(false, false, WifiP2pManager.ERROR))
            return
        }
        try {
            m.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("CREATE_GROUP default action=SUCCESS")
                    onDone(GroupResult(true, false, 0))
                }
                override fun onFailure(reason: Int) {
                    log("CREATE_GROUP default action=FAILURE reason=$reason retriesLeft=$retriesLeft")
                    if (reason == WifiP2pManager.BUSY && retriesLeft > 0) {
                        main.postDelayed({ plainCreate(retriesLeft - 1, onDone) }, 1_000)
                    } else {
                        onDone(GroupResult(false, false, reason))
                    }
                }
            })
        } catch (_: SecurityException) {
            onDone(GroupResult(false, false, WifiP2pManager.ERROR))
        }
    }

    @RequiresApi(29)
    private fun buildGroupConfig5Ghz(): WifiP2pConfig {
        val random = SecureRandom()
        fun token(length: Int): String {
            val builder = StringBuilder(length)
            repeat(length) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            return builder.toString()
        }
        return WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-" + token(2) + "-WifiP2P-" + token(4))
            .setPassphrase(token(12))
            .enablePersistentMode(false)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            .build()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "WifiP2P"
        const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        val NOOP = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        }
    }
}
