package github.leavesczy.wifip2p.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.show(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/** Keeps the content clear of the status bar, navigation bar and display cutout (edge-to-edge). */
fun View.applySystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/** Whether the view is currently VISIBLE (androidx.core.view.isVisible equivalent). */
val View.isVisible: Boolean
    get() = visibility == View.VISIBLE
