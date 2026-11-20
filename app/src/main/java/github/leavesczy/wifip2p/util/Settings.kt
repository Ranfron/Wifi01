package github.leavesczy.wifip2p.util

import android.content.Context

/** The few user preferences of the app. */
object Settings {
    private const val FILE = "settings"
    private const val KEY_FAST_MODE = "fast_mode"
    private const val KEY_LANGUAGE = "language"

    /**
     * Receiver: ask for a Wi-Fi Direct group on the 5 GHz band (Android 10+). It is several times
     * faster than 2.4 GHz, but an old phone that only supports 2.4 GHz cannot join it - switching
     * this off ("compatibility mode") lets such a phone connect.
     */
    fun fastMode(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_FAST_MODE, true)
    }

    fun setFastMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_FAST_MODE, enabled).apply()
    }

    /** App UI language tag: "en" (default) or "hi". */
    fun language(context: Context): String {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LocaleHelper.LANG_EN) ?: LocaleHelper.LANG_EN
    }

    fun setLanguage(context: Context, tag: String) {
        val safe = if (tag == LocaleHelper.LANG_HI) LocaleHelper.LANG_HI else LocaleHelper.LANG_EN
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, safe).apply()
    }
}
