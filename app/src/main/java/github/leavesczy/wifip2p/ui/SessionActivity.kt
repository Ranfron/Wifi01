package github.leavesczy.wifip2p.ui

import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.LayoutBannerBinding
import github.leavesczy.wifip2p.databinding.LayoutTransferCardBinding
import github.leavesczy.wifip2p.hub.Direction
import github.leavesczy.wifip2p.hub.HubState
import github.leavesczy.wifip2p.hub.Mode
import github.leavesczy.wifip2p.hub.Phase
import github.leavesczy.wifip2p.hub.TransferHub
import github.leavesczy.wifip2p.transfer.Formatters
import github.leavesczy.wifip2p.transferHub
import github.leavesczy.wifip2p.util.PermissionFlow
import github.leavesczy.wifip2p.util.SystemChecks
import kotlinx.coroutines.launch

/**
 * Common behaviour of the Send and Receive screens: permission gate, session lifetime, live
 * progress card, system warnings (Wi-Fi / Location off), messages and the "leave?" confirmation.
 * The real state lives in [TransferHub], so rotating the screen never touches a running transfer.
 */
abstract class SessionActivity : AppCompatActivity() {

    protected abstract val mode: Mode
    protected val hub: TransferHub
        get() = transferHub()

    /** The Snackbar is shown above this view (the bottom buttons). */
    protected open val snackbarAnchor: View? = null

    private lateinit var permissionFlow: PermissionFlow
    private lateinit var rootView: View
    private lateinit var banner: LayoutBannerBinding
    private lateinit var card: LayoutTransferCardBinding
    private var sessionStarted = false
    private var lastMessageId = 0L
    private var lastBannerKey: Any? = null

    protected abstract fun render(state: HubState)

    /** Called once the permissions are granted and the session has begun. */
    protected open fun onSessionStarted() {}

    protected fun initSession(
        root: View,
        toolbar: MaterialToolbar,
        banner: LayoutBannerBinding,
        card: LayoutTransferCardBinding
    ) {
        rootView = root
        this.banner = banner
        this.card = card
        root.applySystemBarPadding()
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmLeave()
            }
        })
        card.btnCancelTransfer.setOnClickListener { hub.cancelTransfers() }
        permissionFlow = PermissionFlow(
            activity = this,
            onGranted = { startSession() },
            onDenied = { finish() }
        )
    }

    protected fun beginWhenPermitted() {
        permissionFlow.ensure()
    }

    override fun onResume() {
        super.onResume()
        if (sessionStarted) {
            lastBannerKey = null
            renderBanner(hub.state.value) // the user may have switched Wi-Fi / Location on in Settings
        }
    }

    override fun onDestroy() {
        if (isFinishing && sessionStarted) {
            hub.end()
        }
        super.onDestroy()
    }

    protected fun confirmLeave() {
        if (hub.state.value.active == null) {
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

    private fun startSession() {
        if (sessionStarted) {
            return
        }
        sessionStarted = true
        hub.begin(mode)
        lastMessageId = hub.state.value.message?.id ?: 0L
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.state.collect { onState(it) }
            }
        }
        onSessionStarted()
    }

    private fun onState(state: HubState) {
        if (state.mode == Mode.NONE) {
            // Ended from outside (the "Stop" button of the notification).
            if (!isFinishing) {
                finish()
            }
            return
        }
        // keep the screen on while data is moving, otherwise the phone may sleep and slow the radio down
        if (state.active != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        render(state)
        renderBanner(state)
        renderCard(state)
        showMessage(state)
    }

    private fun renderBanner(state: HubState) {
        // Cheap guard: this runs for every progress tick, the system checks below are binder calls.
        val key = Triple(state.phase, state.detail, state.peerName != null)
        if (key == lastBannerKey) {
            return
        }
        lastBannerKey = key
        when {
            !SystemChecks.isWifiEnabled(this) || state.phase == Phase.P2P_OFF ->
                showBanner(R.string.banner_wifi_off, R.string.open_settings) { SystemChecks.openWifiSettings(this) }

            SystemChecks.isLocationRequiredButOff(this) ->
                showBanner(R.string.banner_location_off, R.string.open_settings) { SystemChecks.openLocationSettings(this) }

            mode == Mode.RECEIVE && state.phase == Phase.ERROR ->
                showBanner(state.detail ?: getString(R.string.error_group_failed), getString(R.string.try_again)) {
                    hub.retryReceiving()
                }

            else -> banner.root.show(false)
        }
    }

    private fun showBanner(text: Int, action: Int, onClick: () -> Unit) {
        showBanner(getString(text), getString(action), onClick)
    }

    private fun showBanner(text: String, action: String, onClick: () -> Unit) {
        banner.root.show(true)
        banner.tvBannerText.text = text
        banner.btnBannerAction.text = action
        banner.btnBannerAction.setOnClickListener { onClick() }
    }

    private fun renderCard(state: HubState) {
        val active = state.active
        card.root.show(active != null)
        if (active == null) {
            return
        }
        val permille = if (active.batchTotal > 0) {
            (active.batchBytes * 1000 / active.batchTotal).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        val filePercent = if (active.fileSize > 0) (active.fileBytes * 100 / active.fileSize).toInt() else 100
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
        Snackbar.make(rootView, message.text, Snackbar.LENGTH_SHORT)
            .setAnchorView(snackbarAnchor)
            .show()
    }
}
