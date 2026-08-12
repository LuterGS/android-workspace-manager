package dev.atwm.tilingwm.engine

import android.content.Context
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager

/**
 * The area scenes are expressed relative to: the display minus whatever the
 * system bars (status bar, nav bar) actually take up right now.
 *
 * Queried live via [WindowInsets] rather than a fixed guess — a flat 100px
 * allowance for each bar (the old approach) left a visible gap above and
 * below every scene once rotating to landscape made the mismatch between the
 * guess and the real bar sizes obvious (found by the user, right after the
 * rotation-reflow fix landed — a fixed margin eats proportionally more of a
 * shorter landscape height).
 *
 * Uses [WindowManager.getMaximumWindowMetrics], not [WindowManager.getCurrentWindowMetrics]:
 * the latter is bounded to the *calling* window's own current bounds, which for
 * an Activity context is wrong here whenever that activity itself happens to be
 * a small freeform window (as MainActivity often is) — we want the full
 * display's usable area regardless of what window is doing the asking.
 *
 * Shared by MainActivity (building a scene from a preset) and
 * TilingAccessibilityService (capturing/restoring) so both agree on the same
 * rectangle — capturing in one place and applying from the other must not
 * drift apart.
 */
fun usableArea(context: Context): Rect {
    val windowManager = context.getSystemService(WindowManager::class.java)
    val metrics = windowManager.maximumWindowMetrics
    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
    val bounds = metrics.bounds

    return Rect(
        bounds.left + insets.left,
        bounds.top + insets.top,
        bounds.right - insets.right,
        bounds.bottom - insets.bottom
    )
}
