package dev.jim.updater

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the status bar for THIS activity's own window (supported, no permissions, no root).
 * The bar slides back temporarily on a swipe from the edge, then re-hides.
 * Pass includeNavBar = true to also hide the bottom navigation bar.
 */
fun Activity.hideSystemBars(includeNavBar: Boolean = false) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    val target =
        if (includeNavBar) WindowInsetsCompat.Type.systemBars()
        else WindowInsetsCompat.Type.statusBars()
    controller.hide(target)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
