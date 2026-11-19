package github.leavesczy.wifip2p.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import github.leavesczy.wifip2p.R

object Permissions {

    /** Without these Wi-Fi Direct discovery (or saving the received files) cannot work. */
    fun required(): List<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (Build.VERSION.SDK_INT <= 29) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list
    }

    /** Nice to have: the progress notification of the foreground service. */
    fun optional(): List<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }

    fun missingRequired(context: Context): List<String> {
        return required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAll(context: Context): Boolean = missingRequired(context).isEmpty()
}

object SystemChecks {

    fun isWifiEnabled(context: Context): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        return try {
            wifi.isWifiEnabled
        } catch (_: SecurityException) {
            true
        }
    }

    /** Android 12L and older only report Wi-Fi Direct peers while Location is switched on. */
    fun isLocationRequiredButOff(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return false
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return !LocationManagerCompat.isLocationEnabled(manager)
    }

    fun openWifiSettings(context: Context) = openSettings(context, Intent(Settings.ACTION_WIFI_SETTINGS))

    fun openLocationSettings(context: Context) =
        openSettings(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        openSettings(context, intent)
    }

    private fun openSettings(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // no settings screen available on this device
        }
    }
}

/**
 * Requests every missing permission in one dialog and explains what to do when the user refuses
 * for good. Must be created while the activity is being constructed (before STARTED).
 */
class PermissionFlow(
    private val activity: ComponentActivity,
    private val onGranted: () -> Unit,
    private val onDenied: () -> Unit
) {
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { handleResult() }

    fun ensure() {
        if (Permissions.hasAll(activity)) {
            onGranted()
        } else {
            request()
        }
    }

    private fun request() {
        launcher.launch((Permissions.required() + Permissions.optional()).toTypedArray())
    }

    private fun handleResult() {
        val missing = Permissions.missingRequired(activity)
        if (missing.isEmpty()) {
            onGranted()
            return
        }
        val blockedForGood = missing.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setCancelable(false)
            .setPositiveButton(if (blockedForGood) R.string.open_settings else R.string.try_again) { _, _ ->
                if (blockedForGood) {
                    SystemChecks.openAppSettings(activity)
                    onDenied()
                } else {
                    request()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onDenied() }
            .show()
    }
}
