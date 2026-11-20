package github.leavesczy.wifip2p.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.databinding.ActivitySettingsBinding
import github.leavesczy.wifip2p.storage.DownloadsMedium
import github.leavesczy.wifip2p.transferHub
import github.leavesczy.wifip2p.util.Settings

/** Language, connection and storage choices. Changing the language recreates the screens in the new language. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rgLanguage.check(
            when (Settings.language(this)) {
                Settings.LANGUAGE_HINDI -> R.id.rbHindi
                Settings.LANGUAGE_HINGLISH -> R.id.rbHinglish
                else -> R.id.rbEnglish
            }
        )
        binding.rgLanguage.setOnCheckedChangeListener { _, id ->
            val tag = when (id) {
                R.id.rbHindi -> Settings.LANGUAGE_HINDI
                R.id.rbHinglish -> Settings.LANGUAGE_HINGLISH
                else -> Settings.LANGUAGE_ENGLISH
            }
            if (tag != Settings.language(this)) {
                Settings.setLanguage(this, tag)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }

        binding.cbFastMode.isChecked = Settings.fastMode(this)
        binding.cbFastMode.setOnCheckedChangeListener { _, checked ->
            if (checked != Settings.fastMode(this)) {
                Settings.setFastMode(this, checked)
                transferHub().setFastMode(checked)
            }
        }

        binding.btnFastAccess.setOnClickListener { openAllFilesAccess() }
        binding.tvVersion.text = getString(R.string.app_version, appVersion())
    }

    override fun onResume() {
        super.onResume()
        val fast = DownloadsMedium.fastAccess()
        binding.tvFastAccess.text = getString(if (fast) R.string.fast_access_on else R.string.fast_access_off)
        binding.btnFastAccess.visibility = if (fast) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun openAllFilesAccess() {
        val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
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
}
