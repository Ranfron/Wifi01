package github.leavesczy.wifip2p.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * App language: English (default) or Hindi.
 * Preference is stored in [Settings]; applied via [wrap] in every Activity's attachBaseContext.
 */
object LocaleHelper {

    const val LANG_EN = "en"
    const val LANG_HI = "hi"

    fun wrap(context: Context): Context {
        val tag = Settings.language(context)
        val locale = localeFor(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return if (Build.VERSION.SDK_INT >= 17) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    fun localeFor(tag: String): Locale = when (tag) {
        LANG_HI -> Locale("hi", "IN")
        else -> Locale.ENGLISH
    }

    fun displayName(tag: String): String = when (tag) {
        LANG_HI -> "हिन्दी"
        else -> "English"
    }
}
