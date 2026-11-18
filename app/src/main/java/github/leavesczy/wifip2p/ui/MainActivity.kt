package github.leavesczy.wifip2p.ui

import github.leavesczy.wifip2p.transferHub

import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivityMainBinding
import github.leavesczy.wifip2p.hub.Role
import github.leavesczy.wifip2p.util.PermissionFlow
import github.leavesczy.wifip2p.util.Permissions
import github.leavesczy.wifip2p.util.SystemChecks

/**
 * Home screen. Both phones start here: one shows a QR code, the other scans it. Files can then be
 * sent in both directions. If a file was shared from another app, this screen tells the user that
 * it waits for a connection.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingRole: Role? = null
    private var pendingSearch = false

    private val permissionFlow = PermissionFlow(
        activity = this,
        onGranted = {
            refreshChecks()
            val role = pendingRole
            val search = pendingSearch
            pendingRole = null
            pendingSearch = false
            if (role != null) {
                SessionActivity.start(this, role, startSearch = search)
            }
        },
        onDenied = {
            pendingRole = null
            refreshChecks()
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()

        binding.btnShowQr.setOnClickListener { open(Role.HOST) }
        binding.btnScanQr.setOnClickListener { open(Role.JOIN) }
        binding.btnSearchPhones.setOnClickListener { open(Role.JOIN, search = true) }
        binding.btnClearShare.setOnClickListener { transferHub().clearPendingShare(); refreshChecks() }
        binding.btnGrantPermission.setOnClickListener { permissionFlow.ensure() }
        binding.btnWifiSettings.setOnClickListener { SystemChecks.openWifiSettings(this) }
        binding.btnLocationSettings.setOnClickListener { SystemChecks.openLocationSettings(this) }
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
    }

    private fun open(role: Role, search: Boolean = false) {
        pendingRole = role
        pendingSearch = search
        permissionFlow.ensure()
    }

    private fun refreshChecks() {
        val pending = transferHub().state.value.pendingShare
        binding.cardShare.show(pending > 0)
        binding.tvShareInfo.text = resources.getQuantityString(R.plurals.share_waiting, pending, pending)

        val permissionsOk = Permissions.hasAll(this)
        showCheck(
            binding.ivPermission, binding.tvPermission, permissionsOk,
            R.string.check_permission_ok, R.string.check_permission_missing
        )
        binding.btnGrantPermission.show(!permissionsOk)

        val wifiOk = SystemChecks.isWifiEnabled(this)
        showCheck(binding.ivWifi, binding.tvWifi, wifiOk, R.string.check_wifi_ok, R.string.check_wifi_off)
        binding.btnWifiSettings.show(!wifiOk)

        // Only Android 12L and older need Location for Wi-Fi Direct discovery.
        val locationRelevant = Build.VERSION.SDK_INT < 33
        binding.rowLocation.show(locationRelevant)
        if (locationRelevant) {
            val locationOff = SystemChecks.isLocationRequiredButOff(this)
            showCheck(
                binding.ivLocation, binding.tvLocation, !locationOff,
                R.string.check_location_ok, R.string.check_location_off
            )
            binding.btnLocationSettings.show(locationOff)
        }
    }

    private fun showCheck(icon: ImageView, label: TextView, ok: Boolean, okText: Int, badText: Int) {
        icon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
        icon.setColorFilter(getColor(if (ok) R.color.status_ok else R.color.status_error))
        label.setText(if (ok) okText else badText)
    }
}
