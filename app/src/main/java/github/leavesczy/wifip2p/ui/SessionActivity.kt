package github.leavesczy.wifip2p.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivitySessionBinding
import github.leavesczy.wifip2p.databinding.LayoutTransferCardBinding
import github.leavesczy.wifip2p.hub.ActiveTransfer
import github.leavesczy.wifip2p.hub.Direction
import github.leavesczy.wifip2p.hub.HubState
import github.leavesczy.wifip2p.hub.JoinMethod
import github.leavesczy.wifip2p.hub.Phase
import github.leavesczy.wifip2p.hub.Role
import github.leavesczy.wifip2p.hub.TransferHub
import github.leavesczy.wifip2p.hub.TransferItem
import github.leavesczy.wifip2p.hub.transferring
import github.leavesczy.wifip2p.p2p.P2pPeer
import github.leavesczy.wifip2p.qr.QrBitmap
import github.leavesczy.wifip2p.storage.FileOpener
import github.leavesczy.wifip2p.transfer.Formatters
import github.leavesczy.wifip2p.transferHub
import github.leavesczy.wifip2p.util.PermissionFlow
import github.leavesczy.wifip2p.util.SystemChecks
import kotlinx.coroutines.launch

/**
 * One screen for both roles. HOST shows the QR code; JOIN scans it (or searches). Once the two
 * phones are connected the screen is the same for both: pick files to send, see both directions
 * live, open received files. The state lives in [TransferHub], so rotation never disturbs a transfer.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var permissionFlow: PermissionFlow
    private lateinit var cardOut: LayoutTransferCardBinding
    private lateinit var cardIn: LayoutTransferCardBinding

    private val hub: TransferHub
        get() = transferHub()

    private var role: Role = Role.NONE
    private var sessionStarted = false
    private var lastMessageId = 0L
    private var lastBannerKey: Any? = null
    private var lastQrPayload: String? = null
    private var lastScrolledItem = -1L

    private val peerAdapter = PeerAdapter { peer -> hub.connectTo(peer) }
    private val transferAdapter = TransferAdapter { item -> open(item) }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            hub.enqueue(uris)
        }
    }

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val payload = result.data?.getStringExtra(ScanActivity.EXTRA_PAYLOAD)
        if (result.resultCode == RESULT_OK && payload != null) {
            hub.joinWithQr(payload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cardOut = LayoutTransferCardBinding.bind(binding.cardOut.root)
        cardIn = LayoutTransferCardBinding.bind(binding.cardIn.root)

        // Opened from the Share sheet there is no role: the sending phone joins the other one.
        val sharing = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
        role = intent.getStringExtra(EXTRA_ROLE)
            ?.let { runCatching { Role.valueOf(it) }.getOrNull() }
            ?.takeIf { it != Role.NONE }
            ?: if (sharing) Role.JOIN else Role.NONE
        if (role == Role.NONE) {
            finish()
            return
        }

        binding.root.applySystemBarPadding()
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.title = getString(if (role == Role.HOST) R.string.host_title else R.string.join_title)
        // Showing the QR code is the host's job; a phone that opened the share sheet joins.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmLeave()
            }
        })

        binding.rvPeers.layoutManager = LinearLayoutManager(this)
        binding.rvPeers.adapter = peerAdapter
        binding.rvTransfers.layoutManager = LinearLayoutManager(this)
        binding.rvTransfers.adapter = transferAdapter

        binding.btnScan.setOnClickListener { scanner.launch(Intent(this, ScanActivity::class.java)) }
        binding.btnSearch.setOnClickListener { hub.discoverPeers() }
        binding.btnDisconnect.setOnClickListener { hub.disconnect() }
        binding.btnSendFiles.setOnClickListener { picker.launch(arrayOf("*/*")) }
        binding.btnMode.setOnClickListener { hub.setFastMode(!hub.state.value.fastMode) }
        cardOut.btnCancelTransfer.setOnClickListener { hub.cancelOut() }
        cardIn.btnCancelTransfer.setOnClickListener { hub.cancelIn() }
        binding.banner.btnBannerAction.setOnClickListener { }

        // Files shared from another app arrive with the Intent that started this screen.
        addShared(intent)
        beginWhenPermitted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        addShared(intent)
    }

    private fun beginWhenPermitted() {
        permissionFlow = PermissionFlow(
            activity = this,
            onGranted = { startSession() },
            onDenied = { finish() }
        )
        permissionFlow.ensure()
    }

    private fun startSession() {
        if (sessionStarted) {
            return
        }
        sessionStarted = true
        hub.begin(role)
        lastMessageId = hub.state.value.message?.id ?: 0L
        if (intent.getBooleanExtra(EXTRA_SEARCH, false) && role == Role.JOIN) {
            hub.discoverPeers()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (sessionStarted) {
            lastBannerKey = null
            renderBanner(hub.state.value)
        }
    }

    override fun onDestroy() {
        if (isFinishing && sessionStarted) {
            hub.end()
        }
        super.onDestroy()
    }

    private fun confirmLeave() {
        if (!hub.state.value.transferring) {
            leave()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.leave_title)
            .setMessage(R.string.leave_message)
            .setPositiveButton(R.string.leave_confirm) { _, _ -> leave() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun leave() {
        hub.end()
        finish()
    }

    // =============================================================================================
    // Rendering
    // =============================================================================================

    private fun render(state: HubState) {
        if (state.role == Role.NONE) {
            // ended from outside, e.g. the "Stop" button of the notification
            if (!isFinishing) {
                finish()
            }
            return
        }
        val connected = state.phase == Phase.CONNECTED
        val busy = state.transferring
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        renderStatus(state)
        renderBanner(state)
        renderConnectArea(state, connected)
        renderSessionArea(state, connected)
        renderQr(state)
        renderTransfers(state)
        showMessage(state)
    }

    private fun renderStatus(state: HubState) {
        val title: String
        val detail: String
        when (state.phase) {
            Phase.P2P_OFF -> {
                title = getString(R.string.status_wifi_off)
                detail = getString(R.string.detail_wifi_off)
            }

            Phase.PREPARING, Phase.IDLE -> {
                title = getString(R.string.status_preparing)
                detail = getString(
                    if (state.role == Role.HOST) R.string.detail_host_preparing else R.string.detail_join_preparing
                )
            }

            Phase.READY -> if (state.role == Role.HOST) {
                title = getString(R.string.status_host_ready)
                detail = getString(R.string.detail_host_ready)
            } else {
                title = getString(R.string.status_join_ready)
                detail = getString(
                    if (state.discovering) R.string.detail_searching else R.string.detail_join_ready
                )
            }

            Phase.CONNECTING -> {
                title = getString(R.string.status_connecting, state.connectingTo ?: "")
                detail = getString(
                    if (state.joinMethod == JoinMethod.QR) R.string.detail_joining_qr else R.string.detail_connecting
                )
            }

            Phase.CONNECTED -> {
                title = getString(R.string.status_connected_to, state.peerName ?: "")
                detail = getString(R.string.detail_connected)
            }

            Phase.ERROR -> {
                title = getString(R.string.status_error)
                detail = state.detail ?: getString(R.string.error_connection_lost)
            }
        }
        binding.tvStatusTitle.text = title
        binding.tvStatusDetail.text = detail
        binding.progressBusy.show(
            state.discovering || state.phase == Phase.PREPARING || state.phase == Phase.CONNECTING
        )
        binding.toolbar.subtitle = if (state.phase == Phase.CONNECTED) {
            getString(R.string.subtitle_speed_ready)
        } else {
            null
        }
    }

    private fun renderBanner(state: HubState) {
        // Cheap guard: this runs for every progress tick, the system checks below are binder calls.
        val key = Triple(state.phase, state.detail, state.role)
        if (key == lastBannerKey) {
            return
        }
        lastBannerKey = key
        val banner = binding.banner
        when {
            !SystemChecks.isWifiEnabled(this) || state.phase == Phase.P2P_OFF ->
                showBanner(getString(R.string.banner_wifi_off), getString(R.string.open_settings)) {
                    SystemChecks.openWifiSettings(this)
                }

            SystemChecks.isLocationRequiredButOff(this) ->
                showBanner(getString(R.string.banner_location_off), getString(R.string.open_settings)) {
                    SystemChecks.openLocationSettings(this)
                }

            state.role == Role.HOST && state.phase == Phase.ERROR ->
                showBanner(state.detail ?: getString(R.string.error_group_failed), getString(R.string.try_again)) {
                    hub.retryHosting()
                }

            else -> banner.root.show(false)
        }
    }

    private fun showBanner(text: String, action: String, onClick: () -> Unit) {
        val banner = binding.banner
        banner.root.show(true)
        banner.tvBannerText.text = text
        banner.btnBannerAction.text = action
        banner.btnBannerAction.setOnClickListener { onClick() }
    }

    /** Before the connection: QR code (host) or scan / search buttons (join). */
    private fun renderConnectArea(state: HubState, connected: Boolean) {
        val show = !connected
        binding.svConnect.show(show)
        binding.llSession.show(connected)
        binding.rowBottom.show(connected)
        if (!show) {
            return
        }
        val host = state.role == Role.HOST
        binding.rowJoin.show(!host)
        binding.rowMode.show(host && Build.VERSION.SDK_INT >= 29)
        binding.tvMode.setText(
            when {
                !state.fastMode -> R.string.mode_compat
                state.fiveGhz -> R.string.mode_fast
                else -> R.string.mode_standard
            }
        )
        binding.btnMode.setText(if (state.fastMode) R.string.mode_switch_to_compat else R.string.mode_switch_to_fast)
        binding.btnSearch.isEnabled = !host && (state.phase == Phase.READY || state.phase == Phase.ERROR)
        binding.btnSearch.setText(if (state.discovering) R.string.searching else R.string.search_devices)
        binding.btnScan.isEnabled = !host && state.phase == Phase.READY

        val peers = !host && state.peers.isNotEmpty()
        binding.rvPeers.show(peers)
        peerAdapter.submitList(if (host) emptyList() else state.peers)

        binding.tvEmpty.show(!host && state.peers.isEmpty() && state.phase != Phase.CONNECTING)
        binding.tvEmpty.setText(
            when {
                state.discovering -> R.string.empty_searching
                else -> R.string.empty_join
            }
        )
        if (state.phase == Phase.CONNECTING) {
            binding.btnDisconnect.show(false)
        }
    }

    /** Host: shows the QR code as soon as the group is ready. Join: no QR. */
    private fun renderQr(state: HubState) {
        val qr = state.qr
        val showCard = state.role == Role.HOST && qr != null && state.phase != Phase.CONNECTED
        binding.cardQr.show(showCard)
        if (qr != null && state.role == Role.HOST) {
            if (qr.payload != lastQrPayload) {
                lastQrPayload = qr.payload
                binding.ivQr.setImageBitmap(QrBitmap.create(qr.payload, QR_SIZE_PX))
            }
            binding.tvQrCaption.setText(R.string.qr_caption)
            binding.tvQrNetwork.text = getString(R.string.qr_network, qr.ssid)
        }
        if (state.role == Role.HOST && state.qrUnavailable && state.phase != Phase.CONNECTED) {
            binding.tvEmpty.show(true)
            binding.tvEmpty.setText(R.string.empty_host_no_qr)
        }
    }

    /** Connected: both directions live, the file list and the actions. */
    private fun renderSessionArea(state: HubState, connected: Boolean) {
        if (!connected) {
            return
        }
        binding.btnDisconnect.show(true)
        binding.btnSendFiles.isEnabled = state.sendReady
        binding.btnSendFiles.setText(
            if (state.sendReady) R.string.send_files else R.string.send_waiting
        )
        binding.tvSessionEmpty.show(state.items.isEmpty())
    }

    private fun renderTransfers(state: HubState) {
        cardOut.root.show(state.activeOut != null)
        state.activeOut?.let { showCard(cardOut, it, R.string.label_sending) }
        cardIn.root.show(state.activeIn != null)
        state.activeIn?.let { showCard(cardIn, it, R.string.label_receiving) }

        transferAdapter.submitList(state.items)
        binding.rvTransfers.show(state.items.isNotEmpty())
        binding.tvSessionEmpty.show(state.items.isEmpty() && state.phase == Phase.CONNECTED)

        // keep the most recent transfer in view
        val activeId = state.activeOut?.itemId ?: state.activeIn?.itemId
        if (activeId != null && activeId != lastScrolledItem) {
            lastScrolledItem = activeId
            val index = state.items.indexOfFirst { it.id == activeId }
            if (index >= 0) {
                binding.rvTransfers.scrollToPosition(index)
            }
        }
    }

    private fun showCard(card: LayoutTransferCardBinding, active: ActiveTransfer, label: Int) {
        val permille = if (active.batchTotal > 0) {
            (active.batchBytes * 1000 / active.batchTotal).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        val filePercent = if (active.fileSize > 0) (active.fileBytes * 100 / active.fileSize).toInt() else 100
        card.tvCardLabel.setText(label)
        card.ivDirection.setImageResource(
            if (active.direction == Direction.OUT) R.drawable.ic_upload else R.drawable.ic_download
        )
        card.tvFileName.text = active.name
        card.progressBar.setProgressCompat(permille, true)
        card.tvSpeed.text = getString(
            R.string.speed_and_eta,
            Formatters.speed(active.bytesPerSecond), Formatters.duration(active.etaSeconds)
        )
        card.tvFileProgress.text = getString(
            R.string.file_progress,
            Formatters.size(active.fileBytes), Formatters.size(active.fileSize), filePercent
        )
        card.tvBatchProgress.text = getString(
            R.string.batch_progress,
            active.fileNumber, active.fileCount,
            Formatters.size(active.batchBytes), Formatters.size(active.batchTotal)
        )
    }

    private fun showMessage(state: HubState) {
        val message = state.message ?: return
        if (message.id == lastMessageId) {
            return
        }
        lastMessageId = message.id
        Snackbar.make(binding.root, message.text, Snackbar.LENGTH_SHORT)
            .setAnchorView(if (binding.rowBottom.isVisible) binding.rowBottom else null)
            .show()
    }

    // =============================================================================================
    // Actions
    // =============================================================================================

    private fun addShared(intent: Intent?) {
        if (intent == null) {
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            hub.addPendingShare(uris)
            Toast.makeText(this, getString(R.string.msg_shared_waiting, uris.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(item: TransferItem) {
        val location = item.location ?: return
        if (!FileOpener.open(this, location, item.name)) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_ROLE = "role"
        const val EXTRA_SEARCH = "search"
        private const val QR_SIZE_PX = 800

        fun start(context: android.content.Context, role: Role, startSearch: Boolean = false) {
            context.startActivity(
                Intent(context, SessionActivity::class.java)
                    .putExtra(EXTRA_ROLE, role.name)
                    .putExtra(EXTRA_SEARCH, startSearch)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
