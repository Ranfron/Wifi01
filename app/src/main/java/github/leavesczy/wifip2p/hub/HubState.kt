package github.leavesczy.wifip2p.hub

import github.leavesczy.wifip2p.p2p.P2pPeer
import github.leavesczy.wifip2p.transfer.FileState

enum class Mode { NONE, SEND, RECEIVE }

/** What the screen should tell the user right now. */
enum class Phase {
    IDLE,

    /** Wi-Fi (and therefore Wi-Fi Direct) is switched off. */
    P2P_OFF,

    /** Receiver: creating the group. Sender: waiting for the Wi-Fi Direct service. */
    PREPARING,

    /** Receiver: group is up, waiting for the sender. Sender: ready to search / pick a device. */
    READY,

    /** Sender: invitation sent / group joined, the app connection is being established. */
    CONNECTING,

    /** Both apps talk to each other - files can flow. */
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

data class HubState(
    val mode: Mode = Mode.NONE,
    val phase: Phase = Phase.IDLE,
    val detail: String? = null,
    val selfName: String = "",
    val peers: List<P2pPeer> = emptyList(),
    val discovering: Boolean = false,
    val connectingTo: String? = null,
    val peerName: String? = null,
    val items: List<TransferItem> = emptyList(),
    val active: ActiveTransfer? = null,
    val message: UiMessage? = null,
    /** The user's choice: ask for a 5 GHz group (fast) instead of the default band. */
    val fastMode: Boolean = true,
    /** The group really was created on the 5 GHz band. */
    val fiveGhz: Boolean = false
)

val TransferItem.isFinished: Boolean
    get() = state == FileState.DONE || state == FileState.FAILED || state == FileState.CANCELLED
