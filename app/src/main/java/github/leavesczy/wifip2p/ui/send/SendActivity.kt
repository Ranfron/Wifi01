package github.leavesczy.wifip2p.ui.send

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivitySendBinding
import github.leavesczy.wifip2p.hub.HubState
import github.leavesczy.wifip2p.hub.Mode
import github.leavesczy.wifip2p.hub.Phase
import github.leavesczy.wifip2p.ui.PeerAdapter
import github.leavesczy.wifip2p.ui.SessionActivity
import github.leavesczy.wifip2p.ui.TransferAdapter
import github.leavesczy.wifip2p.ui.show

/**
 * Sender screen. Also the target of the Android "Share" sheet: files shared from the gallery or a
 * file manager are queued and sent as soon as a receiver is connected.
 */
class SendActivity : SessionActivity() {

    override val mode = Mode.SEND
    override val snackbarAnchor: View?
        get() = binding.btnChooseFiles

    private lateinit var binding: ActivitySendBinding
    private val peerAdapter = PeerAdapter { peer -> hub.connectTo(peer) }
    private val transferAdapter = TransferAdapter { }
    private var shared: List<Uri> = emptyList()
    private var lastScrolledItem = -1L

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            hub.enqueue(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initSession(binding.root, binding.toolbar, binding.banner, binding.transferCard)

        binding.rvPeers.layoutManager = LinearLayoutManager(this)
        binding.rvPeers.adapter = peerAdapter
        binding.rvTransfers.layoutManager = LinearLayoutManager(this)
        binding.rvTransfers.adapter = transferAdapter
        binding.btnSearch.setOnClickListener { hub.discoverPeers() }
        binding.btnDisconnect.setOnClickListener { hub.disconnectPeer() }
        binding.btnChooseFiles.setOnClickListener { picker.launch(arrayOf("*/*")) }

        shared = sharedUris(intent)
        beginWhenPermitted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val more = sharedUris(intent)
        if (more.isNotEmpty()) {
            shared = shared + more
            render(hub.state.value)
        }
    }

    override fun render(state: HubState) {
        val connected = state.phase == Phase.CONNECTED
        val connecting = state.phase == Phase.CONNECTING
        val inSession = connected || connecting

        val title: String
        val detail: String
        when (state.phase) {
            Phase.P2P_OFF -> {
                title = getString(R.string.send_status_wifi_off)
                detail = getString(R.string.send_detail_wifi_off)
            }

            Phase.READY -> {
                title = getString(R.string.send_status_ready)
                detail = getString(if (state.discovering) R.string.send_detail_searching else R.string.send_detail_ready)
            }

            Phase.CONNECTING -> {
                title = getString(R.string.send_status_connecting, state.connectingTo ?: "")
                detail = getString(R.string.send_detail_connecting)
            }

            Phase.CONNECTED -> {
                title = getString(R.string.status_connected_to, state.peerName ?: "")
                detail = if (shared.isNotEmpty()) {
                    getString(R.string.send_detail_shared, shared.size)
                } else {
                    getString(R.string.send_detail_connected)
                }
            }

            Phase.ERROR -> {
                title = getString(R.string.status_error)
                detail = state.detail ?: getString(R.string.error_connection_lost)
            }

            Phase.PREPARING, Phase.IDLE -> {
                title = getString(R.string.status_preparing)
                detail = getString(R.string.send_detail_preparing)
            }
        }
        binding.tvStatusTitle.text = title
        binding.tvStatusDetail.text = detail
        binding.progressBusy.show(
            state.discovering || connecting || state.phase == Phase.PREPARING
        )

        binding.btnSearch.show(!inSession)
        binding.btnSearch.isEnabled = state.phase == Phase.READY || state.phase == Phase.ERROR
        binding.btnSearch.setText(if (state.discovering) R.string.searching else R.string.search_devices)
        binding.btnDisconnect.show(inSession)
        binding.btnChooseFiles.isEnabled = connected

        // one list area: found devices before the connection, the transfer queue afterwards
        val showTransfers = inSession
        binding.rvPeers.show(!showTransfers)
        binding.rvTransfers.show(showTransfers)
        peerAdapter.submitList(state.peers)
        transferAdapter.submitList(state.items)
        val empty = if (showTransfers) state.items.isEmpty() else state.peers.isEmpty()
        binding.tvEmpty.show(empty)
        binding.tvEmpty.setText(
            when {
                connecting -> R.string.empty_connecting
                connected -> R.string.empty_send_connected
                state.discovering -> R.string.empty_searching
                else -> R.string.empty_peers
            }
        )

        val activeId = state.active?.itemId
        if (activeId != null && activeId != lastScrolledItem) {
            lastScrolledItem = activeId
            val index = state.items.indexOfFirst { it.id == activeId }
            if (index >= 0) {
                binding.rvTransfers.scrollToPosition(index)
            }
        }

        if (connected && shared.isNotEmpty()) {
            val files = shared
            shared = emptyList()
            hub.enqueue(files)
        }
    }

    private fun sharedUris(intent: Intent?): List<Uri> {
        if (intent == null) {
            return emptyList()
        }
        return when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

            else -> emptyList()
        }
    }
}
