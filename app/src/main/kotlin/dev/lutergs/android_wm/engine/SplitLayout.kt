package dev.lutergs.android_wm.engine

import android.graphics.Rect
import dev.lutergs.android_wm.model.TilingConfig

/**
 * A 50/50 split along a fixed axis, optionally with one or both halves split
 * again along the perpendicular axis.
 *
 * Unlike [MasterStackLayout], the split direction never depends on device
 * orientation — [Direction.LEFT_RIGHT] stays left/right in landscape too. These
 * presets are meant to keep the same shape across rotation, which is exactly
 * what MasterStackLayout deliberately does *not* do.
 */
class SplitLayout(
    private val primary: Direction,
    private val subdivide: Subdivide
) : LayoutStrategy {

    enum class Direction { LEFT_RIGHT, TOP_BOTTOM }

    /** Which half (in the order [splitInTwo] returns them) gets split again. */
    enum class Subdivide { NONE, FIRST, SECOND, BOTH }

    override fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect> {
        val gap = config.windowGap
        return when (subdivide) {
            Subdivide.NONE -> splitInTwo(usableArea, primary, gap)
            Subdivide.FIRST -> {
                val (first, second) = splitInTwo(usableArea, primary, gap)
                splitInTwo(first, primary.perpendicular(), gap) + second
            }
            Subdivide.SECOND -> {
                val (first, second) = splitInTwo(usableArea, primary, gap)
                listOf(first) + splitInTwo(second, primary.perpendicular(), gap)
            }
            Subdivide.BOTH -> {
                // A 2x2 grid reads top-left, top-right, bottom-left, bottom-right
                // regardless of `primary` — row-major, not tied to the split axis.
                val (top, bottom) = splitInTwo(usableArea, Direction.TOP_BOTTOM, gap)
                splitInTwo(top, Direction.LEFT_RIGHT, gap) + splitInTwo(bottom, Direction.LEFT_RIGHT, gap)
            }
        }
    }

    private fun splitInTwo(area: Rect, direction: Direction, gap: Int): List<Rect> {
        return if (direction == Direction.LEFT_RIGHT) {
            val leftWidth = (area.width() - gap) / 2
            listOf(
                Rect(area.left, area.top, area.left + leftWidth, area.bottom),
                Rect(area.left + leftWidth + gap, area.top, area.right, area.bottom)
            )
        } else {
            val topHeight = (area.height() - gap) / 2
            listOf(
                Rect(area.left, area.top, area.right, area.top + topHeight),
                Rect(area.left, area.top + topHeight + gap, area.right, area.bottom)
            )
        }
    }

    private fun Direction.perpendicular(): Direction = when (this) {
        Direction.LEFT_RIGHT -> Direction.TOP_BOTTOM
        Direction.TOP_BOTTOM -> Direction.LEFT_RIGHT
    }
}
