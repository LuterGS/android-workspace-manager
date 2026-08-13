package dev.lutergs.android_wm.engine

import android.graphics.Rect
import dev.lutergs.android_wm.model.TilingConfig

interface LayoutStrategy {
    /**
     * Calculate bounds for N tileable tasks within the usable area.
     * @param usableArea  Screen rect minus status bar and nav bar
     * @param taskCount   Number of tasks to tile (always >= 2)
     * @param config      Tiling configuration (master ratio, gap, etc.)
     * @param orientation Portrait or landscape
     * @return Ordered list of Rects, index 0 = master/first task
     */
    fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect>
}
