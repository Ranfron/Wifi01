package github.leavesczy.wifip2p.util

import android.content.Context

/** The few user preferences of the app. */
object Settings {
    private const val FILE = "settings"
    private const val KEY_FAST_MODE = "fast_mode"

    /**
     * Compatibility mode is the safe default: the receiving phone creates a group on the system's
     * default band. 5 GHz remains an opt-in because the receiver cannot know whether every sender
     * supports the selected 5 GHz channels before it creates the group.
     */
    fun fastMode(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_FAST_MODE, false)
    }

    fun setFastMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_FAST_MODE, enabled).apply()
    }
}
