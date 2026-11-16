package github.leavesczy.wifip2p

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import github.leavesczy.wifip2p.databinding.ActivityMainBinding
import github.leavesczy.wifip2p.ui.applySystemBarPadding
import github.leavesczy.wifip2p.ui.receive.ReceiveActivity
import github.leavesczy.wifip2p.ui.send.SendActivity
import github.leavesczy.wifip2p.ui.show
import github.leavesczy.wifip2p.util.PermissionFlow
import github.leavesczy.wifip2p.util.Permissions
import github.leavesczy.wifip2p.util.SystemChecks

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingTarget: Class<*>? = null

    private val permissionFlow = PermissionFlow(
        activity = this,
        onGranted = {
            refreshChecks()
            val target = pendingTarget
            pendingTarget = null
            if (target != null) {
                startActivity(Intent(this, target))
            }
        },
        onDenied = {
            pendingTarget = null
            refreshChecks()
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()

        binding.btnSend.setOnClickListener { open(SendActivity::class.java) }
        binding.btnReceive.setOnClickListener { open(ReceiveActivity::class.java) }
        binding.btnGrantPermission.setOnClickListener { permissionFlow.ensure() }
        binding.btnWifiSettings.setOnClickListener { SystemChecks.openWifiSettings(this) }
        binding.btnLocationSettings.setOnClickListener { SystemChecks.openLocationSettings(this) }
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
    }

    private fun open(target: Class<*>) {
        pendingTarget = target
        permissionFlow.ensure()
    }

    private fun refreshChecks() {
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
