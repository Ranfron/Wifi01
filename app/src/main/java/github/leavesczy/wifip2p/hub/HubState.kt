package github.leavesczy.wifip2p.hub

import github.leavesczy.wifip2p.p2p.P2pPeer
import github.leavesczy.wifip2p.transfer.FileState

/**
 * HOST: this phone creates the Wi-Fi Direct group and shows the QR code (or waits to be found).
 * JOIN: this phone connects to the host - by scanning its QR code or by searching for it.
 * After connecting both roles are equal: both phones send and receive.
 */
enum class Role { NONE, HOST, JOIN }

enum class JoinMethod { NONE, QR, SEARCH }

/** What the screen should tell the user right now. */
enum class Phase {
    IDLE,

    /** Wi-Fi (and therefore Wi-Fi Direct) is switched off. */
    P2P_OFF,

    /** Host: creating the group / the QR code. Join: waiting for the Wi-Fi Direct service. */
    PREPARING,

    /** Host: group is up, waiting for the other phone. Join: ready to scan or search. */
    READY,

    /** Join: joining the group / waiting for the invitation, the app connection is being established. */
    CONNECTING,

    /** Both apps talk to each other - files can flow in both directions. */
    CONNECTED,
    ERROR
}

enum class Direction { OUT, IN }

data class TransferItem(
    val id: Long,
    val name: String,
    val size: Long,
    val direction: Direction,
    val state: FileState,
    val message: String? = null,
    /** Where a received file was stored (content or file Uri string). */
    val location: String? = null
)

/** Live numbers of the file (and the batch it belongs to) that is moving right now. */
data class ActiveTransfer(
    val itemId: Long,
    val name: String,
    val direction: Direction,
    val fileBytes: Long,
    val fileSize: Long,
    val fileNumber: Int,
    val fileCount: Int,
    val batchBytes: Long,
    val batchTotal: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long
)

/** One-shot message for a Snackbar. The id changes with every message so equal texts show again. */
data class UiMessage(val id: Long, val text: String)

/** The QR code of a hosting phone: [payload] is what gets encoded, [ssid] is shown below it. */
data class QrInfo(val payload: String, val ssid: String)

data class HubState(
    val role: Role = Role.NONE,
    val phase: Phase = Phase.IDLE,
    val detail: String? = null,
    val selfName: String = "",
    val peers: List<P2pPeer> = emptyList(),
    val discovering: Boolean = false,
    val joinMethod: JoinMethod = JoinMethod.NONE,
    val connectingTo: String? = null,
    val peerName: String? = null,
    /** This phone can send files right now / is able to receive files right now. */
    val sendReady: Boolean = false,
    val receiveReady: Boolean = false,
    val items: List<TransferItem> = emptyList(),
    val activeOut: ActiveTransfer? = null,
    val activeIn: ActiveTransfer? = null,
    val message: UiMessage? = null,
    val qr: QrInfo? = null,
    /** The group exists but this phone does not reveal its passphrase, so no QR code can be shown. */
    val qrUnavailable: Boolean = false,
    /** The user's choice: ask for a 5 GHz group (fast) instead of the default band. */
    val fastMode: Boolean = true,
    /** The group really was created on the 5 GHz band. */
    val fiveGhz: Boolean = false,
    /** Selected / actual band label shown to user (2.4 / 5 / 6 GHz / SoftAP). */
    val bandLabel: String = "",
    /** Actual frequency reported by the group (MHz). */
    val frequencyMhz: Int = 0,
    /** Live Tx link speed if available. */
    val linkSpeedMbps: Int = 0,
    /** Live Rx link speed if available. */
    val rxLinkSpeedMbps: Int = 0,
    /** RSSI of the P2P / SoftAP link. */
    val rssi: Int = 0,
    /** Wi-Fi standard name from the radio (Wi-Fi 5/6/7…). */
    val wifiStandardLabel: String = "",
    /** Transport in use (TCP / SMUFF). */
    val transportName: String = "TCP",
    /** Last bottleneck guess after a transfer batch. */
    val bottleneck: String = "",
    /** Number of files shared from another app that wait for a connection. */
    val pendingShare: Int = 0
)

val TransferItem.isFinished: Boolean
    get() = state == FileState.DONE || state == FileState.FAILED || state == FileState.CANCELLED

val HubState.transferring: Boolean
    get() = activeOut != null || activeIn != null
