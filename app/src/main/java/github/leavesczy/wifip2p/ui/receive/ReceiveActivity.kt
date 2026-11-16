package github.leavesczy.wifip2p.ui.receive

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivityReceiveBinding
import github.leavesczy.wifip2p.hub.HubState
import github.leavesczy.wifip2p.hub.Mode
import github.leavesczy.wifip2p.hub.Phase
import github.leavesczy.wifip2p.hub.TransferItem
import github.leavesczy.wifip2p.storage.FileOpener
import github.leavesczy.wifip2p.ui.SessionActivity
import github.leavesczy.wifip2p.ui.TransferAdapter
import github.leavesczy.wifip2p.ui.show

/**
 * Receiver screen. There is nothing to press: the Wi-Fi Direct group is created automatically
 * (on 5 GHz when the phone allows it) and every incoming file is saved to Downloads/WifiP2P.
 */
class ReceiveActivity : SessionActivity() {

    override val mode = Mode.RECEIVE
    override val snackbarAnchor: View?
        get() = binding.btnStop

    private lateinit var binding: ActivityReceiveBinding
    private val adapter = TransferAdapter { item -> open(item) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initSession(binding.root, binding.toolbar, binding.banner, binding.transferCard)

        binding.rvTransfers.layoutManager = LinearLayoutManager(this)
        binding.rvTransfers.adapter = adapter
        binding.btnOpenFolder.setOnClickListener {
            if (!FileOpener.openDownloadsFolder(this)) {
                Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnStop.setOnClickListener { confirmLeave() }
        beginWhenPermitted()
    }

    override fun render(state: HubState) {
        val title: String
        val detail: String
        when (state.phase) {
            Phase.P2P_OFF -> {
                title = getString(R.string.receive_status_wifi_off)
                detail = getString(R.string.receive_detail_wifi_off)
            }

            Phase.READY -> {
                title = getString(R.string.receive_status_ready)
                detail = getString(R.string.receive_detail_ready, state.selfName)
            }

            Phase.CONNECTING, Phase.PREPARING, Phase.IDLE -> {
                title = getString(R.string.status_preparing)
                detail = getString(R.string.receive_detail_preparing)
            }

            Phase.CONNECTED -> {
                title = getString(R.string.status_connected_to, state.peerName ?: "")
                detail = getString(R.string.receive_detail_connected)
            }

            Phase.ERROR -> {
                title = getString(R.string.status_error)
                detail = state.detail ?: getString(R.string.error_group_failed)
            }
        }
        binding.tvStatusTitle.text = title
        binding.tvStatusDetail.text = detail
        binding.tvDeviceName.text = state.selfName
        binding.progressBusy.show(state.phase == Phase.PREPARING || state.phase == Phase.CONNECTING)

        // The band can only be chosen on Android 10+
        val showMode = Build.VERSION.SDK_INT >= 29 && (state.phase == Phase.READY || state.phase == Phase.CONNECTED)
        binding.tvMode.show(showMode)
        binding.btnMode.show(showMode)
        binding.tvMode.setText(
            when {
                !state.fastMode -> R.string.mode_compat
                state.fiveGhz -> R.string.mode_fast
                else -> R.string.mode_standard
            }
        )
        binding.btnMode.setText(if (state.fastMode) R.string.mode_switch_to_compat else R.string.mode_switch_to_fast)
        binding.btnMode.setOnClickListener { hub.setFastMode(!state.fastMode) }

        adapter.submitList(state.items)
        binding.rvTransfers.show(state.items.isNotEmpty())
        binding.tvEmpty.show(state.items.isEmpty())
    }

    private fun open(item: TransferItem) {
        val location = item.location ?: return
        if (!FileOpener.open(this, location, item.name)) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }
}
