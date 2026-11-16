package github.leavesczy.wifip2p.hub

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.p2p.P2pController
import github.leavesczy.wifip2p.p2p.P2pPeer
import github.leavesczy.wifip2p.service.TransferService
import github.leavesczy.wifip2p.storage.DownloadsSinkFactory
import github.leavesczy.wifip2p.storage.UriSendSource
import github.leavesczy.wifip2p.transfer.FileProgress
import github.leavesczy.wifip2p.transfer.FileState
import github.leavesczy.wifip2p.transfer.Formatters
import github.leavesczy.wifip2p.transfer.LinkEvents
import github.leavesczy.wifip2p.transfer.LinkRunner
import github.leavesczy.wifip2p.transfer.LinkStatus
import github.leavesczy.wifip2p.transfer.Protocol
import github.leavesczy.wifip2p.transfer.SendSource
import github.leavesczy.wifip2p.transfer.Tuning
import github.leavesczy.wifip2p.transfer.describeError
import github.leavesczy.wifip2p.util.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * One instance per process (see [github.leavesczy.wifip2p.App]). It owns the Wi-Fi Direct
 * controller and the transfer link, so a screen rotation or leaving the app never interrupts a
 * transfer. Screens only observe [state] and call the public functions on the main thread.
 *
 * Roles:
 *  - Receiver: creates the Wi-Fi Direct group itself (on 5 GHz when possible) and waits.
 *  - Sender: searches for devices, connects to the receiver and sends any number of batches.
 * Which phone ends up as "group owner" does not matter, see [LinkRunner].
 */
class TransferHub(private val app: Application) {

    private val mutableState = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = mutableState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wfp2p-hub").also { it.isDaemon = true }
    }
    private val itemIds = AtomicLong(1)
    private val messageIds = AtomicLong(1)
    private val lock = Any()

    private val sinks by lazy { DownloadsSinkFactory(app) }
    private val tuning = Tuning()

    // ---- session facts. Derived into the UI state by publish() -----------------------------------
    private var p2p: P2pController? = null

    @Volatile
    private var link: LinkRunner? = null
    private var linkKey: String? = null

    @Volatile
    private var p2pEnabled: Boolean? = null

    @Volatile
    private var groupFormed = false

    @Volatile
    private var creatingGroup = false

    @Volatile
    private var connectingTo: String? = null

    @Volatile
    private var sessionPeer: String? = null

    @Volatile
    private var errorText: String? = null

    @Volatile
    private var cancelRequested = false
    private var groupAttempts = 0
    private var connectTimeout: Runnable? = null
    private val ensureGroupRunnable = Runnable { ensureReceiverGroup() }
    private val groupWatchdog = Runnable {
        // createGroup() never answered (framework hiccup): give up this attempt and try again
        if (creatingGroup) {
            creatingGroup = false
            scheduleEnsureGroup(500)
            publish()
        }
    }

    // ---- transfer bookkeeping (guarded by lock) --------------------------------------------------
    private val pendingBatches = ArrayDeque<List<Long>>()
    private var batchIds: List<Long> = emptyList()
    private var batchFileCount = 0
    private val receivedIds = HashMap<Int, Long>()

    // =============================================================================================
    // Public API (main thread)
    // =============================================================================================

    fun begin(newMode: Mode) {
        if (newMode == Mode.NONE) {
            end()
            return
        }
        if (mutableState.value.mode == newMode) {
            return
        }
        if (mutableState.value.mode != Mode.NONE) {
            end()
        }
        resetFacts()
        mutableState.value = HubState(
            mode = newMode,
            phase = Phase.PREPARING,
            selfName = Build.MODEL ?: "",
            fastMode = Settings.fastMode(app)
        )
        startService()
        val controller = P2pController(app, p2pListener)
        p2p = controller
        if (!controller.isSupported) {
            errorText = app.getString(R.string.error_p2p_unsupported)
            publish()
            return
        }
        controller.start()
    }

    /** Ends the session: closes the link, leaves/destroys the Wi-Fi Direct group, stops the service. */
    fun end() {
        main.removeCallbacksAndMessages(null)
        stopLink()
        p2p?.stop()
        p2p = null
        resetFacts()
        mutableState.value = HubState()
    }

    fun discoverPeers() {
        val controller = p2p ?: return
        restartLinkAfterError()
        errorText = null
        mutableState.update { it.copy(discovering = true) }
        publish()
        controller.discover { ok, reason ->
            if (!ok) {
                mutableState.update { it.copy(discovering = false) }
                postMessage(app.getString(R.string.msg_search_failed, reasonText(reason)))
            }
        }
    }

    fun connectTo(peer: P2pPeer) {
        val controller = p2p ?: return
        if (mutableState.value.mode != Mode.SEND) {
            return
        }
        connectingTo = peer.name
        errorText = null
        publish()
        controller.stopDiscovery()
        controller.connect(peer.address) { ok, reason ->
            if (ok) {
                scheduleConnectTimeout()
            } else {
                connectingTo = null
                postMessage(app.getString(R.string.msg_connect_failed, reasonText(reason)))
                publish()
            }
        }
    }

    /** Sender: leave the group / abandon the pending invitation. */
    fun disconnectPeer() {
        cancelConnectTimeout()
        stopLink()
        connectingTo = null
        groupFormed = false
        abortUnfinished(null)
        p2p?.disconnect()
        publish()
    }

    /** Receiver: try to create the group again after an error. */
    fun retryReceiving() {
        groupAttempts = 0
        restartLinkAfterError()
        errorText = null
        publish()
        p2p?.refresh()
        ensureReceiverGroup()
    }

    /**
     * Receiver: switch between the fast 5 GHz group and the compatibility group (default band,
     * works with phones that only support 2.4 GHz). The group is rebuilt right away.
     */
    fun setFastMode(enabled: Boolean) {
        Settings.setFastMode(app, enabled)
        mutableState.update { it.copy(fastMode = enabled, fiveGhz = false) }
        if (mutableState.value.mode != Mode.RECEIVE) {
            return
        }
        stopLink()
        groupFormed = false
        creatingGroup = false
        groupAttempts = 0
        errorText = null
        abortUnfinished(null)
        main.removeCallbacks(ensureGroupRunnable)
        main.removeCallbacks(groupWatchdog)
        publish()
        p2p?.disconnect { scheduleEnsureGroup(500) }
    }

    /** A link that died with an error is started again from the current group information. */
    private fun restartLinkAfterError() {
        if (errorText != null && link != null) {
            stopLink()
            p2p?.refresh()
        }
    }

    /** Sender: queue files. Their size/name are read on a worker thread, then they are sent in order. */
    fun enqueue(uris: List<Uri>) {
        if (mutableState.value.mode != Mode.SEND || uris.isEmpty()) {
            return
        }
        val unique = uris.distinct()
        worker.execute {
            val sources = ArrayList<SendSource>()
            val ids = ArrayList<Long>()
            val items = ArrayList<TransferItem>()
            for (uri in unique) {
                try {
                    val source = UriSendSource.create(app, uri)
                    val id = itemIds.getAndIncrement()
                    sources.add(source)
                    ids.add(id)
                    items.add(TransferItem(id, source.name, source.size, Direction.OUT, FileState.QUEUED))
                } catch (e: Exception) {
                    items.add(
                        TransferItem(
                            itemIds.getAndIncrement(), uri.lastPathSegment ?: "?", 0,
                            Direction.OUT, FileState.FAILED, describeError(e)
                        )
                    )
                }
            }
            if (items.isEmpty()) {
                return@execute
            }
            addItems(items)
            if (sources.isEmpty()) {
                return@execute
            }
            synchronized(lock) { pendingBatches.addLast(ids) }
            val runner = link
            if (runner == null || !runner.send(sources)) {
                synchronized(lock) { pendingBatches.remove(ids) }
                markFailed(ids.toSet(), app.getString(R.string.msg_not_connected))
            }
        }
    }

    /** Stops the running transfer(s). The connection restarts by itself a moment later. */
    fun cancelTransfers() {
        cancelRequested = true
        link?.cancelTransfers()
    }

    fun clearFinished() {
        mutableState.update { s -> s.copy(items = s.items.filter { !it.isFinished }) }
    }

    // =============================================================================================
    // Wi-Fi Direct
    // =============================================================================================

    private val p2pListener = object : P2pController.Listener {

        override fun onP2pStateChanged(enabled: Boolean) {
            p2pEnabled = enabled
            if (!enabled) {
                cancelConnectTimeout()
                stopLink()
                groupFormed = false
                creatingGroup = false
                connectingTo = null
                abortUnfinished(null)
                mutableState.update { it.copy(peers = emptyList(), discovering = false) }
            } else {
                errorText = null
                groupAttempts = 0 // Wi-Fi was switched off/on: a fresh start, as the error text suggests
                p2p?.refresh()
                if (mutableState.value.mode == Mode.RECEIVE) {
                    ensureReceiverGroup()
                }
            }
            publish()
        }

        override fun onThisDevice(name: String, address: String) {
            if (name.isNotBlank()) {
                mutableState.update { it.copy(selfName = name) }
            }
        }

        override fun onPeers(peers: List<P2pPeer>) {
            val sorted = peers.sortedWith(
                compareBy<P2pPeer>({ it.status != WifiP2pDevice.AVAILABLE }, { it.name.lowercase() })
            )
            mutableState.update { it.copy(peers = sorted) }
        }

        override fun onDiscovery(active: Boolean) {
            mutableState.update { it.copy(discovering = active) }
        }

        override fun onConnectionInfo(info: WifiP2pInfo?) {
            handleConnectionInfo(info)
        }

        override fun onChannelLost() {
            publish()
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        val mode = mutableState.value.mode
        if (mode == Mode.NONE) {
            return
        }
        if (info == null) {
            groupFormed = false
            stopLink()
            abortUnfinished(null)
            if (mode == Mode.RECEIVE && !creatingGroup) {
                // A receiver without a group is useless: build it again (debounced).
                scheduleEnsureGroup(1_000)
            }
            publish()
            return
        }
        groupFormed = true
        groupAttempts = 0
        connectingTo = null
        cancelConnectTimeout()
        errorText = null
        startLinkFor(info)
        publish()
    }

    private fun ensureReceiverGroup() {
        val controller = p2p ?: return
        if (mutableState.value.mode != Mode.RECEIVE || p2pEnabled != true || groupFormed || creatingGroup) {
            return
        }
        if (groupAttempts >= MAX_GROUP_ATTEMPTS) {
            errorText = app.getString(R.string.error_group_failed)
            publish()
            return
        }
        groupAttempts++
        creatingGroup = true
        errorText = null
        publish()
        main.removeCallbacks(groupWatchdog)
        main.postDelayed(groupWatchdog, GROUP_TIMEOUT_MS)
        controller.createGroup(prefer5Ghz = mutableState.value.fastMode) { result ->
            main.removeCallbacks(groupWatchdog)
            if (!creatingGroup) {
                return@createGroup // the watchdog already gave up on this attempt
            }
            creatingGroup = false
            if (result.ok) {
                mutableState.update { it.copy(fiveGhz = result.fiveGhz) }
                controller.refresh()
                main.postDelayed({ controller.refresh() }, 1_500)
            } else {
                scheduleEnsureGroup(1_500)
            }
            publish()
        }
    }

    private fun scheduleEnsureGroup(delayMs: Long) {
        main.removeCallbacks(ensureGroupRunnable)
        main.postDelayed(ensureGroupRunnable, delayMs)
    }

    private fun scheduleConnectTimeout() {
        cancelConnectTimeout()
        val runnable = Runnable {
            if (!groupFormed && connectingTo != null) {
                connectingTo = null
                p2p?.disconnect()
                postMessage(app.getString(R.string.msg_connect_timeout))
                publish()
            }
        }
        connectTimeout = runnable
        main.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
    }

    private fun reasonText(reason: Int): String {
        return app.getString(
            when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> R.string.p2p_error_unsupported
                WifiP2pManager.BUSY -> R.string.p2p_error_busy
                else -> R.string.p2p_error_generic
            }
        )
    }

    // =============================================================================================
    // Link (TCP session on top of the group)
    // =============================================================================================

    private fun startLinkFor(info: WifiP2pInfo) {
        val owner = info.isGroupOwner
        val host = info.groupOwnerAddress?.hostAddress
        if (!owner && host == null) {
            return
        }
        val key = if (owner) "host" else "guest:$host"
        if (link != null && key == linkKey) {
            return
        }
        stopLink()
        val mode = mutableState.value.mode
        val role = if (mode == Mode.SEND) Protocol.ROLE_SENDER else Protocol.ROLE_RECEIVER
        val name = mutableState.value.selfName.ifBlank { Build.MODEL ?: "Android" }
        val runner = LinkRunner(
            role = role,
            deviceName = name,
            tuning = tuning,
            sinks = if (role == Protocol.ROLE_RECEIVER) sinks else null,
            events = linkEvents
        )
        link = runner
        linkKey = key
        if (owner) {
            runner.startHost(Protocol.PORT)
        } else {
            runner.startGuest(host!!, Protocol.PORT)
        }
    }

    private fun stopLink() {
        link?.stop()
        link = null
        linkKey = null
        sessionPeer = null
    }

    private val linkEvents = object : LinkEvents {

        override fun onLinkStatus(status: LinkStatus, detail: String?) {
            when (status) {
                LinkStatus.READY -> {
                    cancelRequested = false
                    val peer = detail?.takeIf { it.isNotBlank() } ?: app.getString(R.string.unknown_device)
                    sessionPeer = peer
                    errorText = null
                    postMessage(app.getString(R.string.msg_connected_to, peer))
                }

                LinkStatus.DISCONNECTED -> {
                    val wasConnected = sessionPeer != null
                    sessionPeer = null
                    abortUnfinished(detail)
                    if (wasConnected && !cancelRequested) {
                        postMessage(app.getString(R.string.msg_connection_ended))
                    }
                }

                LinkStatus.FAILED -> {
                    sessionPeer = null
                    errorText = detail ?: app.getString(R.string.error_connection_lost)
                    abortUnfinished(detail)
                }

                LinkStatus.CONNECTING -> {
                    if (detail == LinkRunner.UNREACHABLE) {
                        postMessage(app.getString(R.string.msg_unreachable))
                    }
                }

                LinkStatus.LISTENING, LinkStatus.HANDSHAKE -> Unit
            }
            publish()
        }

        override fun onBatchStarted(fileCount: Int, totalBytes: Long) {
            synchronized(lock) {
                batchFileCount = fileCount
                if (mutableState.value.mode == Mode.SEND) {
                    batchIds = pendingBatches.removeFirstOrNull() ?: emptyList()
                } else {
                    receivedIds.clear()
                }
            }
        }

        override fun onFileStarted(index: Int, name: String, size: Long) {
            if (mutableState.value.mode == Mode.SEND) {
                val id = idFor(index) ?: return
                updateItem(id) { it.copy(state = FileState.ACTIVE, message = null) }
            } else {
                val id = itemIds.getAndIncrement()
                synchronized(lock) { receivedIds[index] = id }
                addItems(listOf(TransferItem(id, name, size, Direction.IN, FileState.ACTIVE)))
            }
        }

        override fun onProgress(progress: FileProgress) {
            val id = idFor(progress.index) ?: return
            val count = synchronized(lock) { batchFileCount }
            val direction = if (mutableState.value.mode == Mode.SEND) Direction.OUT else Direction.IN
            val active = ActiveTransfer(
                itemId = id,
                name = progress.name,
                direction = direction,
                fileBytes = progress.fileBytes,
                fileSize = progress.fileSize,
                fileNumber = progress.index + 1,
                fileCount = count,
                batchBytes = progress.batchBytes,
                batchTotal = progress.batchTotal,
                bytesPerSecond = progress.bytesPerSecond,
                etaSeconds = Formatters.etaSeconds(progress.batchTotal - progress.batchBytes, progress.bytesPerSecond)
            )
            mutableState.update { it.copy(active = active) }
        }

        override fun onFileFinished(index: Int, name: String, state: FileState, message: String?, location: String?) {
            val id = idFor(index) ?: return
            updateItem(id) { it.copy(state = state, message = message, location = location) }
        }

        override fun onBatchFinished() {
            val sending = mutableState.value.mode == Mode.SEND
            val ids: Set<Long> = synchronized(lock) {
                if (sending) batchIds.toSet() else receivedIds.values.toSet()
            }
            val batchItems = mutableState.value.items.filter { it.id in ids }
            val done = batchItems.count { it.state == FileState.DONE }
            mutableState.update { it.copy(active = null) }
            if (batchItems.isNotEmpty() && !cancelRequested) {
                postMessage(
                    app.getString(
                        if (sending) R.string.msg_sent_summary else R.string.msg_received_summary,
                        done, batchItems.size
                    )
                )
            }
        }
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private fun idFor(index: Int): Long? = synchronized(lock) {
        if (mutableState.value.mode == Mode.SEND) batchIds.getOrNull(index) else receivedIds[index]
    }

    private fun addItems(items: List<TransferItem>) {
        mutableState.update { s -> s.copy(items = (s.items + items).takeLast(MAX_ITEMS)) }
    }

    private fun updateItem(id: Long, change: (TransferItem) -> TransferItem) {
        mutableState.update { s ->
            s.copy(items = s.items.map { if (it.id == id) change(it) else it })
        }
    }

    private fun markFailed(ids: Set<Long>, message: String) {
        mutableState.update { s ->
            s.copy(items = s.items.map {
                if (it.id in ids) it.copy(state = FileState.FAILED, message = message) else it
            })
        }
    }

    /** Everything still waiting or running cannot continue: close it with a precise reason. */
    private fun abortUnfinished(reason: String?) {
        val cancelled = cancelRequested
        val message = if (cancelled) {
            app.getString(R.string.msg_cancelled)
        } else {
            reason?.takeIf { it.isNotBlank() } ?: app.getString(R.string.error_connection_lost)
        }
        synchronized(lock) {
            pendingBatches.clear()
            batchIds = emptyList()
            receivedIds.clear()
        }
        mutableState.update { s ->
            s.copy(
                active = null,
                items = s.items.map {
                    if (it.state == FileState.QUEUED || it.state == FileState.ACTIVE) {
                        it.copy(state = if (cancelled) FileState.CANCELLED else FileState.FAILED, message = message)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun postMessage(text: String) {
        mutableState.update { it.copy(message = UiMessage(messageIds.getAndIncrement(), text)) }
    }

    private fun resetFacts() {
        p2pEnabled = null
        groupFormed = false
        creatingGroup = false
        connectingTo = null
        sessionPeer = null
        errorText = null
        cancelRequested = false
        groupAttempts = 0
        connectTimeout = null
        linkKey = null
        synchronized(lock) {
            pendingBatches.clear()
            batchIds = emptyList()
            receivedIds.clear()
            batchFileCount = 0
        }
    }

    /** Turns the facts above into the state the screens render. */
    private fun publish() {
        mutableState.update { s ->
            if (s.mode == Mode.NONE) {
                return@update s
            }
            val phase = when {
                sessionPeer != null -> Phase.CONNECTED
                p2pEnabled == false -> Phase.P2P_OFF
                errorText != null -> Phase.ERROR
                p2pEnabled == null -> Phase.PREPARING
                s.mode == Mode.RECEIVE -> if (groupFormed) Phase.READY else Phase.PREPARING
                else -> if (groupFormed || connectingTo != null) Phase.CONNECTING else Phase.READY
            }
            s.copy(phase = phase, detail = errorText, peerName = sessionPeer, connectingTo = connectingTo)
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java))
        } catch (_: Exception) {
            // The transfer still works while the app is visible; only surviving in the background is lost.
        }
    }

    private companion object {
        const val MAX_ITEMS = 1_500
        const val MAX_GROUP_ATTEMPTS = 4
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val GROUP_TIMEOUT_MS = 25_000L
    }
}
