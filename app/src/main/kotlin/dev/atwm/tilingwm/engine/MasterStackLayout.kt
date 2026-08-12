package dev.atwm.tilingwm.engine

import android.content.res.Configuration
import android.graphics.Rect
import dev.atwm.tilingwm.model.TilingConfig

class MasterStackLayout : LayoutStrategy {
    override fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect> {
        val gap = config.windowGap
        val stackCount = taskCount - 1
        val ratios = normalisedStackRatios(config, stackCount)
        val results = mutableListOf<Rect>()

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            val masterWidth = ((usableArea.width() - gap) * config.masterRatio).toInt()

            // Master
            results.add(Rect(
                usableArea.left,
                usableArea.top,
                usableArea.left + masterWidth,
                usableArea.bottom
            ))

            // Stack — divide the right column by stackRatios
            val stackLeft = usableArea.left + masterWidth + gap
            val totalHeight = usableArea.height() - gap * (stackCount - 1)
            var top = usableArea.top
            for (i in 0 until stackCount) {
                val bottom = if (i == stackCount - 1) usableArea.bottom
                             else top + (totalHeight * ratios[i]).toInt()
                results.add(Rect(stackLeft, top, usableArea.right, bottom))
                top = bottom + gap
            }
        } else {
            // Landscape: master on top, stack splits bottom
            val masterHeight = ((usableArea.height() - gap) * config.masterRatio).toInt()

            // Master
            results.add(Rect(
                usableArea.left,
                usableArea.top,
                usableArea.right,
                usableArea.top + masterHeight
            ))

            // Stack — divide the bottom row by stackRatios
            val stackTop = usableArea.top + masterHeight + gap
            val totalWidth = usableArea.width() - gap * (stackCount - 1)
            var left = usableArea.left
            for (i in 0 until stackCount) {
                val right = if (i == stackCount - 1) usableArea.right
                            else left + (totalWidth * ratios[i]).toInt()
                results.add(Rect(left, stackTop, right, usableArea.bottom))
                left = right + gap
            }
        }

        return results
    }

    /** Always returns [stackCount] positive ratios summing to 1. */
    private fun normalisedStackRatios(config: TilingConfig, stackCount: Int): List<Float> {
        if (stackCount <= 0) return emptyList()
        val given = config.stackRatios
        if (given.size != stackCount || given.any { it <= 0f }) {
            return List(stackCount) { 1f / stackCount }
        }
        val sum = given.sum()
        return if (sum <= 0f) List(stackCount) { 1f / stackCount } else given.map { it / sum }
    }
}
