package github.leavesczy.wifip2p.hub

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.p2p.P2pController
import github.leavesczy.wifip2p.p2p.P2pPeer
import github.leavesczy.wifip2p.p2p.WifiJoiner
import github.leavesczy.wifip2p.service.TransferService
import github.leavesczy.wifip2p.storage.UriSendSource
import github.leavesczy.wifip2p.transfer.ChannelDirection
import github.leavesczy.wifip2p.transfer.ConnectCode
import github.leavesczy.wifip2p.transfer.DuplexLink
import github.leavesczy.wifip2p.transfer.FileIncomingStore
import github.leavesczy.wifip2p.transfer.FileProgress
import github.leavesczy.wifip2p.transfer.FileState
import github.leavesczy.wifip2p.transfer.Formatters
import github.leavesczy.wifip2p.transfer.HistoryEntry
import github.leavesczy.wifip2p.transfer.HistoryLog
import github.leavesczy.wifip2p.transfer.LinkListener
import github.leavesczy.wifip2p.transfer.OutgoingEntry
import github.leavesczy.wifip2p.transfer.OutgoingLedger
import github.leavesczy.wifip2p.transfer.Protocol
import github.leavesczy.wifip2p.transfer.SendSource
import github.leavesczy.wifip2p.transfer.SessionEvents
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
 * One instance per process (see [github.leavesczy.wifip2p.App]). It owns the Wi-Fi Direct controller,
 * the QR join, the two-way transfer link, and the persistent records (outgoing ledger, history,
 * incoming partial files). A screen rotation or leaving the app never interrupts a transfer.
 *
 * Roles:
 *  - HOST creates the Wi-Fi Direct group (on 5 GHz when possible) and shows its QR code.
 *  - JOIN scans that QR code (or searches for the host) and connects.
 * Afterwards both phones are equal: each one sends and receives.
 *
 * Connection problems never lose data: an unfinished file is INTERRUPTED, recorded in the ledger, and
 * continues by itself when the phones meet again. A user can also resume or clean it manually.
 */
class TransferHub(
    private val app: Application,
    private val ledger: OutgoingLedger,
    private val history: HistoryLog,
    private val incoming: FileIncomingStore
) {

    private enum class LinkSource { NONE, P2P, QR }

    /** Everything an outgoing file needs to be sent again. */
    private class OutgoingRef(val uri: Uri, val source: SendSource)

    /** One row of the "unfinished" list: a file on the sending phone (resumable) or a partial file received here. */
    data class UnfinishedRow(
        val key: String,
        val name: String,
        val size: Long,
        val stored: Long,
        val outgoing: Boolean,
        val state: FileState,
        val reason: String,
        val updatedAt: Long
    )

    data class Records(val unfinished: List<UnfinishedRow>, val history: List<HistoryEntry>)

    data class PurgeResult(val outgoing: Int, val incoming: Int)

    private val mutableState = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = mutableState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wfp2p-hub").also { it.isDaemon = true }
    }
    private val itemIds = AtomicLong(1)
    private val messageIds = AtomicLong(1)
    private val lock = Any()

    private val tuning = Tuning()

    // ---- session facts. Derived into the UI state by publish() -----------------------------------
    private var p2p: P2pController? = null
    private var joiner: WifiJoiner? = null

    @Volatile
    private var link: DuplexLink? = null
    private var linkKey: String? = null
    private var linkSource = LinkSource.NONE

    @Volatile
    private var p2pEnabled: Boolean? = null

    @Volatile
    private var groupFormed = false

    @Volatile
    private var creatingGroup = false

    @Volatile
    private var joiningQr = false

    @Volatile
    private var joinMethod = JoinMethod.NONE

    @Volatile
    private var connectingTo: String? = null

    @Volatile
    private var sendReady = false

    @Volatile
    private var receiveReady = false

    @Volatile
    private var sessionPeer: String? = null

    @Volatile
    private var errorText: String? = null

    @Volatile
    private var cancelOutRequested = false

    @Volatile
    private var cancelInRequested = false
    private var groupAttempts = 0
    private var connectTimeout: Runnable? = null
    private var lastInfo: WifiP2pInfo? = null
    private var lastGroup: WifiP2pGroup? = null
    private var qrTarget: ConnectCode? = null

    // Automatic reconnect: the last QR code, or the last device found by search.
    private var lastQrText: String? = null
    private var rejoinAttempts = 0
    private var lastPeerAddress: String? = null
    private var autoReconnectAttempts = 0

    private val ensureGroupRunnable = Runnable { ensureHostGroup() }
    private val groupWatchdog = Runnable {
        // createGroup() never answered (framework hiccup): give up this attempt and try again
        if (creatingGroup) {
            creatingGroup = false
            scheduleEnsureGroup(500)
            publish()
        }
    }

    // ---- transfer bookkeeping (guarded by lock) --------------------------------------------------
    private var pendingShare: List<Uri> = emptyList()
    private val pendingBatches = ArrayDeque<List<Long>>()
    private var batchIdsOut: List<Long> = emptyList()
    private var batchCountOut = 0
    private val receivedIds = HashMap<Int, Long>()
    private var batchCountIn = 0
    private val refsById = HashMap<Long, OutgoingRef>()
    private val itemIdByKey = HashMap<String, Long>()
    private val inFlightKeys = HashSet<String>()
    private val waitingResume = HashSet<String>()

    // =============================================================================================
    // Public API (main thread unless noted)
    // =============================================================================================

    fun begin(newRole: Role) {
        if (newRole == Role.NONE) {
            end()
            return
        }
        if (mutableState.value.role == newRole) {
            return
        }
        if (mutableState.value.role != Role.NONE) {
            end()
        }
        resetFacts()
        mutableState.value = HubState(
            role = newRole,
            phase = Phase.PREPARING,
            selfName = Build.MODEL ?: "",
            fastMode = Settings.fastMode(app),
            pendingShare = pendingShareCount()
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

    /** Ends the session: closes the link, leaves/destroys the Wi-Fi group, stops the service. */
    fun end() {
        main.removeCallbacksAndMessages(null)
        stopLink()
        joiner?.leave()
        joiner = null
        p2p?.stop()
        p2p = null
        resetFacts()
        mutableState.value = HubState(pendingShare = pendingShareCount())
    }

    /** Files shared from another app: they are sent as soon as the send channel is ready. */
    fun addPendingShare(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }
        synchronized(lock) { pendingShare = (pendingShare + uris).distinct() }
        mutableState.update { it.copy(pendingShare = pendingShareCount()) }
        if (sendReady) {
            flushPendingShare()
        }
    }

    fun clearPendingShare() {
        synchronized(lock) { pendingShare = emptyList() }
        mutableState.update { it.copy(pendingShare = 0) }
    }

    // ---- join: search -----------------------------------------------------------------------------

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
        if (mutableState.value.role != Role.JOIN) {
            return
        }
        lastPeerAddress = peer.address
        joinMethod = JoinMethod.SEARCH
        connectingTo = peer.name
        errorText = null
        publish()
        controller.stopDiscovery()
        controller.connect(peer.address) { ok, reason ->
            if (ok) {
                scheduleConnectTimeout()
            } else {
                connectingTo = null
                joinMethod = JoinMethod.NONE
                postMessage(app.getString(R.string.msg_connect_failed, reasonText(reason)))
                publish()
            }
        }
    }

    // ---- join: QR code ----------------------------------------------------------------------------

    /** [text] is the content of a scanned QR code. */
    fun joinWithQr(text: String) {
        if (mutableState.value.role != Role.JOIN) {
            return
        }
        val code = ConnectCode.parse(text)
        if (code == null) {
            postMessage(app.getString(R.string.msg_qr_invalid))
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            postMessage(app.getString(R.string.msg_qr_needs_android10))
            return
        }
        if (p2pEnabled == false) {
            postMessage(app.getString(R.string.banner_wifi_off))
            return
        }
        lastQrText = text
        cancelConnectTimeout()
        stopLink()
        joiner?.leave()
        errorText = null
        joinMethod = JoinMethod.QR
        joiningQr = true
        qrTarget = code
        connectingTo = code.deviceName.ifBlank { app.getString(R.string.unknown_device) }
        publish()
        val newJoiner = WifiJoiner(app, joinerListener)
        joiner = newJoiner
        newJoiner.join(code.ssid, code.passphrase)
    }

    private val joinerListener = object : WifiJoiner.Listener {
        override fun onJoined() {
            val code = qrTarget ?: return
            startLink(host = false, address = code.address, key = "qr:" + code.address, source = LinkSource.QR)
            publish()
        }

        override fun onJoinFailed() {
            joiner?.leave()
            resetJoinFacts()
            postMessage(app.getString(R.string.msg_join_failed))
            publish()
        }

        override fun onLost() {
            if (linkSource == LinkSource.QR) {
                stopLink()
                abortAll(null)
            }
            resetJoinFacts()
            postMessage(app.getString(R.string.msg_wifi_lost))
            scheduleRejoin()
            publish()
        }
    }

    /** The network was lost after it worked: join it again by itself, with growing pauses. */
    private fun scheduleRejoin() {
        val text = lastQrText ?: return
        if (rejoinAttempts >= MAX_REJOIN_ATTEMPTS) {
            postMessage(app.getString(R.string.msg_rejoin_failed))
            return
        }
        rejoinAttempts++
        val delay = minOf(1_000L shl (rejoinAttempts - 1), 15_000L)
        postMessage(app.getString(R.string.msg_reconnecting))
        main.postDelayed({
            if (mutableState.value.role == Role.JOIN && sessionPeer == null && !joiningQr) {
                joinWithQr(text)
            }
        }, delay)
    }

    private fun resetJoinFacts() {
        joiningQr = false
        joinMethod = JoinMethod.NONE
        connectingTo = null
        qrTarget = null
    }

    // ---- both roles -------------------------------------------------------------------------------

    /**
     * Drops the other phone / the connection attempt. A host builds a fresh group (and QR code),
     * a joining phone goes back to "scan or search". Nothing is lost: unfinished files stay resumable.
     */
    fun disconnect() {
        cancelConnectTimeout()
        stopLink()
        joiner?.leave()
        joiner = null
        resetJoinFacts()
        abortAll(null)
        groupFormed = false
        lastInfo = null
        lastGroup = null
        lastQrText = null
        rejoinAttempts = 0
        val role = mutableState.value.role
        if (role == Role.HOST) {
            mutableState.update { it.copy(qr = null, qrUnavailable = false) }
            p2p?.disconnect { scheduleEnsureGroup(500) }
        } else {
            lastPeerAddress = null
            p2p?.disconnect()
        }
        publish()
    }

    /** Host: try to create the group again after an error. */
    fun retryHosting() {
        groupAttempts = 0
        restartLinkAfterError()
        errorText = null
        publish()
        p2p?.refresh()
        ensureHostGroup()
    }

    /**
     * Host: switch between the fast 5 GHz group and the compatibility group (default band, works
     * with phones that only support 2.4 GHz). The group is rebuilt right away.
     */
    fun setFastMode(enabled: Boolean) {
        Settings.setFastMode(app, enabled)
        mutableState.update { it.copy(fastMode = enabled, fiveGhz = false) }
        if (mutableState.value.role != Role.HOST) {
            return
        }
        stopLink()
        groupFormed = false
        creatingGroup = false
        groupAttempts = 0
        errorText = null
        lastInfo = null
        lastGroup = null
        abortAll(null)
        mutableState.update { it.copy(qr = null, qrUnavailable = false) }
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

    /** Queue files for sending. Their size/name are read on a worker thread, then they are sent in order. */
    fun enqueue(uris: List<Uri>) {
        if (mutableState.value.role == Role.NONE || uris.isEmpty()) {
            return
        }
        val unique = uris.distinct()
        worker.execute {
            val sources = ArrayList<SendSource>()
            val ids = ArrayList<Long>()
            val items = ArrayList<TransferItem>()
            for (uri in unique) {
                try {
                    UriSendSource.takePersistable(app, uri)
                    val source = UriSendSource.create(app, uri)
                    val id = itemIds.getAndIncrement()
                    synchronized(lock) {
                        refsById[id] = OutgoingRef(uri, source)
                        itemIdByKey[source.key] = id
                        inFlightKeys.add(source.key)
                    }
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
                synchronized(lock) {
                    pendingBatches.remove(ids)
                    sources.forEach { inFlightKeys.remove(it.key) }
                }
                markFailed(ids.toSet(), app.getString(R.string.msg_not_connected))
            }
        }
    }

    /** Stops what this phone is sending. Unfinished files stay resumable on both phones. */
    fun cancelOut() {
        cancelOutRequested = true
        link?.cancelSending()
    }

    /** Stops what this phone is receiving. The partial data is kept until it is resumed or cleaned. */
    fun cancelIn() {
        cancelInRequested = true
        link?.cancelReceiving()
    }

    fun clearFinished() {
        mutableState.update { s -> s.copy(items = s.items.filter { !it.isFinished }) }
    }

    /** Sends an unfinished outgoing file again, from where it stopped. Waits for the connection if needed. */
    fun resume(key: String) {
        worker.execute {
            val entry = ledger.get(key) ?: return@execute
            startResume(listOf(entry))
        }
    }

    /** Forgets an unfinished outgoing file (and releases the access it was given). */
    fun clean(key: String) {
        worker.execute {
            val removed = ledger.remove(key)
            removed?.let { UriSendSource.releasePersistable(app, Uri.parse(it.uri)) }
            synchronized(lock) {
                waitingResume.remove(key)
            }
        }
    }

    /** Deletes the partial data of a file received here. */
    fun cleanIncoming(key: String) {
        worker.execute {
            incoming.discard(key)
        }
    }

    fun clearHistory() {
        worker.execute {
            history.clear()
        }
    }

    /** Loads the unfinished files and the history. [onLoaded] runs on the main thread. */
    fun loadRecords(onLoaded: (Records) -> Unit) {
        worker.execute {
            val rows = ArrayList<UnfinishedRow>()
            for (e in ledger.all()) {
                rows.add(UnfinishedRow(e.key, e.name, e.size, 0, true, e.state, e.reason, e.updatedAt))
            }
            for (p in incoming.partials()) {
                rows.add(UnfinishedRow(p.key, p.name, p.size, p.stored, false, FileState.INTERRUPTED, "", p.updatedAt))
            }
            val records = Records(rows.sortedByDescending { it.updatedAt }, history.list())
            main.post { onLoaded(records) }
        }
    }

    /**
     * Removes unfinished transfers that were not touched for five days. This runs when the app is opened
     * (not in the background). [onDone] runs on the main thread.
     */
    fun purgeExpired(onDone: (PurgeResult) -> Unit) {
        worker.execute {
            val expiredOutgoing = ledger.purgeOlderThan(EXPIRY_MS)
            expiredOutgoing.forEach { UriSendSource.releasePersistable(app, Uri.parse(it.uri)) }
            val expiredIncoming = incoming.purgeOlderThan(EXPIRY_MS)
            main.post { onDone(PurgeResult(expiredOutgoing.size, expiredIncoming)) }
        }
    }

    private fun pendingShareCount(): Int = synchronized(lock) { pendingShare.size }

    private fun flushPendingShare() {
        val files = synchronized(lock) {
            val copy = pendingShare
            pendingShare = emptyList()
            copy
        }
        mutableState.update { it.copy(pendingShare = 0) }
        if (files.isNotEmpty()) {
            enqueue(files)
        }
    }

    // =============================================================================================
    // Resumable outgoing files
    // =============================================================================================

    /** Sends the given unfinished files again. Runs on the worker thread. */
    private fun startResume(entries: List<OutgoingEntry>) {
        val runner = link
        if (runner == null || !sendReady) {
            synchronized(lock) { entries.forEach { waitingResume.add(it.key) } }
            postMessage(app.getString(R.string.msg_resume_waiting))
            return
        }
        val sources = ArrayList<SendSource>()
        val ids = ArrayList<Long>()
        val newItems = ArrayList<TransferItem>()
        for (entry in entries) {
            val alreadyGoing = synchronized(lock) { !inFlightKeys.add(entry.key) }
            if (alreadyGoing) {
                continue
            }
            try {
                val uri = Uri.parse(entry.uri)
                val source = UriSendSource.create(app, uri, entry.key)
                val existing = synchronized(lock) { itemIdByKey[entry.key] }
                val id = existing ?: itemIds.getAndIncrement()
                synchronized(lock) {
                    refsById[id] = OutgoingRef(uri, source)
                    itemIdByKey[entry.key] = id
                    waitingResume.remove(entry.key)
                }
                sources.add(source)
                ids.add(id)
                if (existing != null) {
                    updateItem(id) { it.copy(state = FileState.QUEUED, message = null) }
                } else {
                    newItems.add(TransferItem(id, entry.name, entry.size, Direction.OUT, FileState.QUEUED))
                }
            } catch (e: Exception) {
                synchronized(lock) { inFlightKeys.remove(entry.key) }
                ledger.put(
                    entry.copy(
                        state = FileState.FAILED,
                        reason = app.getString(R.string.msg_source_gone, describeError(e))
                    )
                )
            }
        }
        if (sources.isEmpty()) {
            return
        }
        addItems(newItems)
        synchronized(lock) { pendingBatches.addLast(ids) }
        if (!runner.send(sources)) {
            synchronized(lock) {
                pendingBatches.remove(ids)
                sources.forEach {
                    inFlightKeys.remove(it.key)
                    waitingResume.add(it.key)
                }
            }
            ids.forEach { id -> updateItem(id) { it.copy(state = FileState.INTERRUPTED) } }
        }
    }

    /** Called when the send channel is (again) ready: continue everything that was interrupted. */
    private fun resumeAfterReconnect() {
        worker.execute {
            val wanted = synchronized(lock) { waitingResume.toSet() }
            val entries = ledger.all().filter { it.state == FileState.INTERRUPTED || it.key in wanted }
            startResume(entries)
        }
    }

    /** Records a finished outgoing file: DONE clears it, anything else keeps it resumable, and history gets it. */
    private fun onOutgoingFinished(id: Long, name: String, state: FileState, message: String?, location: String?) {
        val ref = synchronized(lock) { refsById[id] } ?: return
        val peer = sessionPeer ?: ""
        worker.execute {
            val key = ref.source.key
            synchronized(lock) { inFlightKeys.remove(key) }
            when (state) {
                FileState.DONE -> {
                    ledger.remove(key)
                    UriSendSource.releasePersistable(app, ref.uri)
                    synchronized(lock) { waitingResume.remove(key) }
                }

                FileState.QUEUED, FileState.ACTIVE -> Unit
                else -> ledger.put(
                    OutgoingEntry(
                        key = key,
                        uri = ref.uri.toString(),
                        name = ref.source.name,
                        size = ref.source.size,
                        lastModified = ref.source.lastModified,
                        state = state,
                        reason = message.orEmpty(),
                        updatedAt = 0
                    )
                )
            }
            history.add(
                HistoryEntry(
                    time = System.currentTimeMillis(), outgoing = true, peer = peer, name = name,
                    size = ref.source.size, state = state, location = location ?: ref.uri.toString(),
                    reason = message.orEmpty()
                )
            )
        }
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
                joiner?.leave()
                joiner = null
                resetJoinFacts()
                groupFormed = false
                creatingGroup = false
                lastInfo = null
                lastGroup = null
                abortAll(null)
                mutableState.update {
                    it.copy(peers = emptyList(), discovering = false, qr = null, qrUnavailable = false)
                }
            } else {
                errorText = null
                groupAttempts = 0 // Wi-Fi was switched off/on: a fresh start, as the error text suggests
                p2p?.refresh()
                if (mutableState.value.role == Role.HOST) {
                    ensureHostGroup()
                }
            }
            publish()
        }

        override fun onThisDevice(name: String, address: String) {
            if (name.isNotBlank()) {
                mutableState.update { it.copy(selfName = name) }
                updateQr()
            }
        }

        override fun onPeers(peers: List<P2pPeer>) {
            val sorted = peers.sortedWith(
                compareBy<P2pPeer>({ it.status != WifiP2pDevice.AVAILABLE }, { it.name.lowercase() })
            )
            mutableState.update { it.copy(peers = sorted) }
            // Join side, the connection was lost: reconnect to the same device by itself.
            val wanted = lastPeerAddress
            if (mutableState.value.role == Role.JOIN && wanted != null && !groupFormed && connectingTo == null &&
                sessionPeer == null && autoReconnectAttempts < MAX_AUTO_RECONNECTS
            ) {
                sorted.firstOrNull { it.address == wanted && it.status == WifiP2pDevice.AVAILABLE }?.let {
                    autoReconnectAttempts++
                    connectTo(it)
                }
            }
        }

        override fun onDiscovery(active: Boolean) {
            mutableState.update { it.copy(discovering = active) }
        }

        override fun onConnectionInfo(info: WifiP2pInfo?) {
            handleConnectionInfo(info)
        }

        override fun onGroupInfo(group: WifiP2pGroup?) {
            lastGroup = group
            updateQr()
        }

        override fun onChannelLost() {
            publish()
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        val role = mutableState.value.role
        if (role == Role.NONE) {
            return
        }
        lastInfo = info
        if (info == null) {
            groupFormed = false
            if (linkSource == LinkSource.P2P) {
                stopLink()
                abortAll(null)
            }
            // a QR link does not depend on a Wi-Fi Direct group of this phone
            updateQr()
            if (role == Role.HOST && !creatingGroup) {
                // A host without a group is useless: build it again (debounced).
                scheduleEnsureGroup(1_000)
            }
            if (role == Role.JOIN && lastPeerAddress != null && linkSource != LinkSource.QR) {
                // the group went away: search again, the previous device is reconnected when it shows up
                main.postDelayed({ if (!groupFormed && sessionPeer == null) discoverPeers() }, 2_000)
            }
            publish()
            return
        }
        groupFormed = true
        groupAttempts = 0
        cancelConnectTimeout()
        errorText = null
        if (linkSource != LinkSource.QR && !joiningQr) {
            connectingTo = null
            startLinkFor(info)
        }
        updateQr()
        publish()
    }

    /** Host: build the QR code as soon as group and connection details are known. */
    private fun updateQr() {
        if (mutableState.value.role != Role.HOST) {
            return
        }
        val info = lastInfo
        val group = lastGroup
        if (info == null || !info.isGroupOwner || group == null) {
            mutableState.update { it.copy(qr = null, qrUnavailable = false) }
            return
        }
        val ssid = group.networkName
        val passphrase = group.passphrase
        val address = info.groupOwnerAddress?.hostAddress
        if (ssid.isNullOrBlank() || passphrase.isNullOrBlank() || address.isNullOrBlank()) {
            mutableState.update { it.copy(qr = null, qrUnavailable = true) }
            return
        }
        val payload = ConnectCode(ssid, passphrase, address, Protocol.PORT, mutableState.value.selfName).encode()
        if (ConnectCode.parse(payload) == null) {
            // e.g. an unusual group address: the scanner would reject it, so do not show a useless code
            mutableState.update { it.copy(qr = null, qrUnavailable = true) }
            return
        }
        mutableState.update { it.copy(qr = QrInfo(payload, ssid), qrUnavailable = false) }
    }

    private fun ensureHostGroup() {
        val controller = p2p ?: return
        if (mutableState.value.role != Role.HOST || p2pEnabled != true || groupFormed || creatingGroup) {
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
                joinMethod = JoinMethod.NONE
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
    // Link (two TCP connections on top of the Wi-Fi connection)
    // =============================================================================================

    private fun startLinkFor(info: WifiP2pInfo) {
        val owner = info.isGroupOwner
        val host = info.groupOwnerAddress?.hostAddress
        if (!owner && host == null) {
            return
        }
        startLink(
            host = owner, address = host, key = if (owner) "host" else "guest:$host", source = LinkSource.P2P
        )
    }

    private fun startLink(host: Boolean, address: String?, key: String, source: LinkSource) {
        if (link != null && key == linkKey) {
            return
        }
        stopLink()
        val name = mutableState.value.selfName.ifBlank { Build.MODEL ?: "Android" }
        val runner = DuplexLink(name, tuning, incoming, outEvents, inEvents, linkListener)
        link = runner
        linkKey = key
        linkSource = source
        if (host || address == null) {
            runner.startHost(Protocol.PORT)
        } else {
            runner.startGuest(address, Protocol.PORT)
        }
    }

    private fun stopLink() {
        link?.stop()
        link = null
        linkKey = null
        linkSource = LinkSource.NONE
        sendReady = false
        receiveReady = false
        sessionPeer = null
    }

    private val linkListener = object : LinkListener {

        override fun onInternalError(error: Throwable) {
            android.util.Log.e("WifiP2P", "Recovered from an internal error, the connection was kept", error)
        }

        override fun onChannel(direction: ChannelDirection, ready: Boolean, peerName: String?, detail: String?) {
            if (direction == ChannelDirection.SEND) {
                sendReady = ready
            } else {
                receiveReady = ready
            }
            if (ready) {
                if (direction == ChannelDirection.SEND) {
                    cancelOutRequested = false
                } else {
                    cancelInRequested = false
                }
                if (sessionPeer == null) {
                    val peer = peerName?.takeIf { it.isNotBlank() } ?: app.getString(R.string.unknown_device)
                    sessionPeer = peer
                    errorText = null
                    joiningQr = false
                    connectingTo = null
                    joinMethod = JoinMethod.NONE
                    rejoinAttempts = 0
                    autoReconnectAttempts = 0
                    cancelConnectTimeout()
                    postMessage(app.getString(R.string.msg_connected_to, peer))
                }
                if (direction == ChannelDirection.SEND) {
                    flushPendingShare()
                    resumeAfterReconnect()
                }
            } else {
                if (direction == ChannelDirection.SEND) {
                    abortOut(detail)
                } else {
                    abortIn(detail)
                }
                if (!sendReady && !receiveReady) {
                    val wasConnected = sessionPeer != null
                    sessionPeer = null
                    if (wasConnected && !cancelOutRequested && !cancelInRequested) {
                        postMessage(app.getString(R.string.msg_connection_ended))
                    }
                }
            }
            publish()
        }

        override fun onHostUnreachable() {
            postMessage(app.getString(R.string.msg_unreachable))
        }

        override fun onFailed(detail: String) {
            sessionPeer = null
            sendReady = false
            receiveReady = false
            errorText = detail
            abortAll(detail)
            publish()
        }
    }

    // ---- events of the files this phone sends ----------------------------------------------------

    private val outEvents = object : SessionEvents {

        override fun onBatchStarted(fileCount: Int, totalBytes: Long) {
            synchronized(lock) {
                batchCountOut = fileCount
                batchIdsOut = pendingBatches.removeFirstOrNull() ?: emptyList()
            }
        }

        override fun onFileStarted(index: Int, name: String, size: Long) {
            val id = idOut(index) ?: return
            updateItem(id) { it.copy(state = FileState.ACTIVE, message = null) }
        }

        override fun onProgress(progress: FileProgress) {
            val id = idOut(progress.index) ?: return
            val count = synchronized(lock) { batchCountOut }
            val active = activeOf(id, Direction.OUT, progress, count)
            mutableState.update { it.copy(activeOut = active) }
        }

        override fun onFileFinished(index: Int, name: String, state: FileState, message: String?, location: String?) {
            val id = idOut(index) ?: return
            updateItem(id) { it.copy(state = state, message = message, location = location) }
            onOutgoingFinished(id, name, state, message, location)
        }

        override fun onBatchFinished() {
            val ids = synchronized(lock) { batchIdsOut.toSet() }
            val batchItems = mutableState.value.items.filter { it.id in ids }
            val done = batchItems.count { it.state == FileState.DONE }
            val interrupted = batchItems.count { it.state == FileState.INTERRUPTED }
            mutableState.update { it.copy(activeOut = null) }
            if (interrupted > 0) {
                postMessage(app.getString(R.string.msg_interrupted_continues, interrupted))
            } else if (batchItems.isNotEmpty() && !cancelOutRequested) {
                postMessage(app.getString(R.string.msg_sent_summary, done, batchItems.size))
            }
        }
    }

    // ---- events of the files this phone receives -------------------------------------------------

    private val inEvents = object : SessionEvents {

        override fun onBatchStarted(fileCount: Int, totalBytes: Long) {
            synchronized(lock) {
                batchCountIn = fileCount
                receivedIds.clear()
            }
        }

        override fun onFileStarted(index: Int, name: String, size: Long) {
            val id = itemIds.getAndIncrement()
            synchronized(lock) { receivedIds[index] = id }
            addItems(listOf(TransferItem(id, name, size, Direction.IN, FileState.ACTIVE)))
        }

        override fun onProgress(progress: FileProgress) {
            val id = idIn(progress.index) ?: return
            val count = synchronized(lock) { batchCountIn }
            val active = activeOf(id, Direction.IN, progress, count)
            mutableState.update { it.copy(activeIn = active) }
        }

        override fun onFileFinished(index: Int, name: String, state: FileState, message: String?, location: String?) {
            val id = idIn(index) ?: return
            updateItem(id) { it.copy(state = state, message = message, location = location) }
            val peer = sessionPeer ?: ""
            val size = mutableState.value.items.firstOrNull { it.id == id }?.size ?: 0L
            worker.execute {
                history.add(
                    HistoryEntry(
                        time = System.currentTimeMillis(), outgoing = false, peer = peer, name = name,
                        size = size, state = state, location = location ?: "", reason = message.orEmpty()
                    )
                )
            }
        }

        override fun onBatchFinished() {
            val ids = synchronized(lock) { receivedIds.values.toSet() }
            val batchItems = mutableState.value.items.filter { it.id in ids }
            val done = batchItems.count { it.state == FileState.DONE }
            val interrupted = batchItems.count { it.state == FileState.INTERRUPTED }
            mutableState.update { it.copy(activeIn = null) }
            if (interrupted > 0) {
                postMessage(app.getString(R.string.msg_interrupted_partial, interrupted))
            } else if (batchItems.isNotEmpty() && !cancelInRequested) {
                postMessage(app.getString(R.string.msg_received_summary, done, batchItems.size))
            }
        }
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private fun idOut(index: Int): Long? = synchronized(lock) { batchIdsOut.getOrNull(index) }

    private fun idIn(index: Int): Long? = synchronized(lock) { receivedIds[index] }

    private fun activeOf(id: Long, direction: Direction, progress: FileProgress, fileCount: Int): ActiveTransfer {
        return ActiveTransfer(
            itemId = id,
            name = progress.name,
            direction = direction,
            fileBytes = progress.fileBytes,
            fileSize = progress.fileSize,
            fileNumber = progress.index + 1,
            fileCount = fileCount,
            batchBytes = progress.batchBytes,
            batchTotal = progress.batchTotal,
            bytesPerSecond = progress.bytesPerSecond,
            etaSeconds = Formatters.etaSeconds(progress.batchTotal - progress.batchBytes, progress.bytesPerSecond)
        )
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

    /**
     * The send channel went down: files that were still waiting for their turn are recorded as
     * interrupted (they continue on the next connection). The file being sent is reported by the
     * engine itself.
     */
    private fun abortOut(reason: String?) {
        val cancelled = cancelOutRequested
        val leftovers = synchronized(lock) {
            val ids = pendingBatches.flatten()
            pendingBatches.clear()
            batchIdsOut = emptyList()
            ids
        }
        val state = if (cancelled) FileState.CANCELLED else FileState.INTERRUPTED
        val message = if (cancelled) {
            app.getString(R.string.msg_cancelled)
        } else {
            reason?.takeIf { it.isNotBlank() } ?: app.getString(R.string.msg_interrupted_will_continue)
        }
        mutableState.update { s ->
            s.copy(
                activeOut = null,
                items = s.items.map {
                    if (it.id in leftovers && (it.state == FileState.QUEUED || it.state == FileState.ACTIVE)) {
                        it.copy(state = state, message = message)
                    } else {
                        it
                    }
                }
            )
        }
        worker.execute {
            for (id in leftovers) {
                val ref = synchronized(lock) { refsById[id] } ?: continue
                val key = ref.source.key
                synchronized(lock) { inFlightKeys.remove(key) }
                ledger.put(
                    OutgoingEntry(
                        key, ref.uri.toString(), ref.source.name, ref.source.size, ref.source.lastModified,
                        state, message, 0
                    )
                )
            }
        }
    }

    /** The receive channel went down: whatever was arriving is kept as a partial file. */
    private fun abortIn(reason: String?) {
        val cancelled = cancelInRequested
        synchronized(lock) { receivedIds.clear() }
        val message = if (cancelled) {
            app.getString(R.string.msg_cancelled)
        } else {
            reason?.takeIf { it.isNotBlank() } ?: app.getString(R.string.msg_interrupted_will_continue)
        }
        mutableState.update { s ->
            s.copy(
                activeIn = null,
                items = s.items.map {
                    if (it.direction == Direction.IN && (it.state == FileState.QUEUED || it.state == FileState.ACTIVE)) {
                        it.copy(state = if (cancelled) FileState.CANCELLED else FileState.INTERRUPTED, message = message)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun abortAll(reason: String?) {
        abortOut(reason)
        abortIn(reason)
    }

    private fun postMessage(text: String) {
        mutableState.update { it.copy(message = UiMessage(messageIds.getAndIncrement(), text)) }
    }

    private fun resetFacts() {
        p2pEnabled = null
        groupFormed = false
        creatingGroup = false
        joiningQr = false
        joinMethod = JoinMethod.NONE
        connectingTo = null
        sessionPeer = null
        sendReady = false
        receiveReady = false
        errorText = null
        cancelOutRequested = false
        cancelInRequested = false
        groupAttempts = 0
        connectTimeout = null
        linkKey = null
        linkSource = LinkSource.NONE
        lastInfo = null
        lastGroup = null
        qrTarget = null
        lastQrText = null
        rejoinAttempts = 0
        autoReconnectAttempts = 0
        synchronized(lock) {
            pendingBatches.clear()
            batchIdsOut = emptyList()
            receivedIds.clear()
            batchCountOut = 0
            batchCountIn = 0
        }
    }

    /** Turns the facts above into the state the screens render. */
    private fun publish() {
        mutableState.update { s ->
            if (s.role == Role.NONE) {
                return@update s
            }
            val phase = when {
                sessionPeer != null -> Phase.CONNECTED
                p2pEnabled == false -> Phase.P2P_OFF
                errorText != null -> Phase.ERROR
                p2pEnabled == null -> Phase.PREPARING
                s.role == Role.HOST -> if (groupFormed) Phase.READY else Phase.PREPARING
                else -> if (joiningQr || groupFormed || connectingTo != null || link != null) {
                    Phase.CONNECTING
                } else {
                    Phase.READY
                }
            }
            s.copy(
                phase = phase,
                detail = errorText,
                peerName = sessionPeer,
                connectingTo = connectingTo,
                joinMethod = joinMethod,
                sendReady = sendReady,
                receiveReady = receiveReady
            )
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
        const val MAX_REJOIN_ATTEMPTS = 8
        const val MAX_AUTO_RECONNECTS = 8
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val GROUP_TIMEOUT_MS = 25_000L
        const val EXPIRY_MS = 5L * 24 * 60 * 60 * 1000
    }
}
