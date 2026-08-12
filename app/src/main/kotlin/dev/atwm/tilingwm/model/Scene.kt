package dev.atwm.tilingwm.model

import android.graphics.Rect

/**
 * One window in a saved layout.
 *
 * Bounds are stored as fractions of the usable area rather than pixels: this
 * device's screen changes size entirely when folded (1584x2160 vs 1918x822), and
 * rotation swaps the axes. Fractions let a scene captured on one geometry be
 * restored on another.
 *
 * Windows are identified by package alone — one window per app is assumed.
 */
data class SceneWindow(
    val packageName: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toBounds(area: Rect): Rect = Rect(
        area.left + (area.width() * left).toInt(),
        area.top + (area.height() * top).toInt(),
        area.left + (area.width() * right).toInt(),
        area.top + (area.height() * bottom).toInt()
    )

    companion object {
        fun of(packageName: String, bounds: Rect, area: Rect): SceneWindow {
            val w = area.width().toFloat()
            val h = area.height().toFloat()
            if (w <= 0f || h <= 0f) return SceneWindow(packageName, 0f, 0f, 1f, 1f)
            return SceneWindow(
                packageName = packageName,
                left = (bounds.left - area.left) / w,
                top = (bounds.top - area.top) / h,
                right = (bounds.right - area.left) / w,
                bottom = (bounds.bottom - area.top) / h
            )
        }
    }
}

/** A named window arrangement — either captured from the screen or built from a preset. */
data class Scene(
    val name: String,
    val windows: List<SceneWindow>
)
