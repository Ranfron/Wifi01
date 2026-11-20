package github.leavesczy.wifip2p.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivityMainBinding
import github.leavesczy.wifip2p.hub.Role
import github.leavesczy.wifip2p.storage.DownloadsMedium
import github.leavesczy.wifip2p.transferHub
import github.leavesczy.wifip2p.util.LocaleHelper
import github.leavesczy.wifip2p.util.PermissionFlow
import github.leavesczy.wifip2p.util.Permissions
import github.leavesczy.wifip2p.util.Settings
import github.leavesczy.wifip2p.util.SystemChecks

/**
 * Home screen. One phone shows a QR code, the other scans it. Language can be English (default)
 * or Hindi. Preference is applied on every Activity via [LocaleHelper.wrap].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingRole: Role? = null
    private var pendingSearch = false
    private var applyingLanguageUi = false

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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()
        binding.tvVersion.text = getString(R.string.app_version, appVersion())

        // Primary actions (big cards + legacy buttons)
        binding.cardReceive.setOnClickListener { open(Role.HOST) }
        binding.cardSend.setOnClickListener { open(Role.JOIN) }
        binding.btnShowQr.setOnClickListener { open(Role.HOST) }
        binding.btnScanQr.setOnClickListener { open(Role.JOIN) }
        binding.btnSearchPhones.setOnClickListener { open(Role.JOIN, search = true) }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnFastAccess.setOnClickListener { openAllFilesAccess() }
        binding.btnClearShare.setOnClickListener {
            transferHub().clearPendingShare()
            refreshChecks()
        }
        binding.btnGrantPermission.setOnClickListener { permissionFlow.ensure() }
        binding.btnWifiSettings.setOnClickListener { SystemChecks.openWifiSettings(this) }
        binding.btnLocationSettings.setOnClickListener { SystemChecks.openLocationSettings(this) }

        binding.btnLanguage.setOnClickListener { cycleLanguage() }
        setupLanguageToggle()

        if (savedInstanceState == null) {
            transferHub().purgeExpired { result ->
                val removed = result.outgoing + result.incoming
                if (removed > 0) {
                    Toast.makeText(this, getString(R.string.msg_expired_removed, removed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
        syncLanguageToggle()
    }

    private fun open(role: Role, search: Boolean = false) {
        pendingRole = role
        pendingSearch = search
        permissionFlow.ensure()
    }

    private fun setupLanguageToggle() {
        syncLanguageToggle()
        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || applyingLanguageUi) return@addOnButtonCheckedListener
            val tag = if (checkedId == R.id.btnLangHi) LocaleHelper.LANG_HI else LocaleHelper.LANG_EN
            if (tag == Settings.language(this)) return@addOnButtonCheckedListener
            Settings.setLanguage(this, tag)
            Toast.makeText(this, getString(R.string.language_changed), Toast.LENGTH_SHORT).show()
            // Recreate so all resources reload in the new locale
            recreate()
        }
    }

    private fun syncLanguageToggle() {
        applyingLanguageUi = true
        val tag = Settings.language(this)
        val id = if (tag == LocaleHelper.LANG_HI) R.id.btnLangHi else R.id.btnLangEn
        binding.toggleLanguage.check(id)
        binding.btnLanguage.text = LocaleHelper.displayName(tag)
        applyingLanguageUi = false
    }

    private fun cycleLanguage() {
        val next = if (Settings.language(this) == LocaleHelper.LANG_HI) {
            LocaleHelper.LANG_EN
        } else {
            LocaleHelper.LANG_HI
        }
        Settings.setLanguage(this, next)
        recreate()
    }

    private fun refreshChecks() {
        refreshFastAccess()
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

    private fun refreshFastAccess() {
        val fast = DownloadsMedium.fastAccess()
        binding.tvFastAccess.text = getString(if (fast) R.string.fast_access_on else R.string.fast_access_off)
        binding.btnFastAccess.show(!fast)
    }

    private fun openAllFilesAccess() {
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun showCheck(icon: ImageView, label: TextView, ok: Boolean, okText: Int, badText: Int) {
        icon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
        icon.setColorFilter(getColor(if (ok) R.color.status_ok else R.color.status_error))
        label.setText(if (ok) okText else badText)
    }
}
