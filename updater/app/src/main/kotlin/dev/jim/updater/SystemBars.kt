package dev.jim.updater

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

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

/**
 * Since the activity draws edge-to-edge (setDecorFitsSystemWindows=false), bottom content
 * would otherwise sit under the system navigation area. This pads [view] by the navigation-bar
 * inset — the gesture pill or 3-button bar, whichever the device uses — so content shifts above
 * it when present, and adds nothing when there's no bar. Re-applied on every inset change
 * (rotation, gesture/button nav switch, transient bar show/hide).
 */
fun applyBottomNavBarInset(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        v.updatePadding(bottom = navBar.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(view)
}
