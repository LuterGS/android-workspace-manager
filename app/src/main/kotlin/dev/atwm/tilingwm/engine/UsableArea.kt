package dev.atwm.tilingwm.engine

import android.content.Context
import android.graphics.Rect
import dev.atwm.tilingwm.model.TilingConfig

/**
 * The area scenes are expressed relative to: the display minus the status/nav
 * bar allowances in [config].
 *
 * Shared by MainActivity (building a scene from a preset) and
 * TilingAccessibilityService (capturing/restoring) so both agree on the same
 * rectangle for a given [config] — capturing in one place and applying from the
 * other must not drift apart.
 */
fun usableArea(context: Context, config: TilingConfig): Rect {
    val metrics = context.resources.displayMetrics
    return Rect(
        0,
        config.statusBarHeight,
        metrics.widthPixels,
        metrics.heightPixels - config.navBarHeight
    )
}
