package dev.lutergs.android_wm.engine

import android.graphics.Rect
import dev.lutergs.android_wm.model.Scene
import dev.lutergs.android_wm.model.SceneWindow
import dev.lutergs.android_wm.model.TilingConfig

/**
 * A ready-made arrangement: a layout plus a slot count.
 *
 * Binding apps to the slots turns a preset into a [Scene], which is the only
 * thing the rest of the app deals with — presets and captured arrangements are
 * the same kind of object once created, so nothing downstream needs to know
 * where a scene came from.
 */
data class Preset(
    val id: String,
    val label: String,
    val slotCount: Int,
    val strategy: LayoutStrategy,
    val config: TilingConfig = TilingConfig()
) {
    /**
     * Binds [packages] to this preset's slots in order, producing a scene.
     * Extra packages are ignored; fewer than the slot count simply tiles fewer.
     */
    fun toScene(name: String, packages: List<String>, orientation: Int, area: Rect): Scene? {
        val used = packages.take(slotCount)
        if (used.size < 2) return null

        val bounds = strategy.calculateBounds(area, used.size, config, orientation)
        return Scene(name, used.zip(bounds) { pkg, rect -> SceneWindow.of(pkg, rect, area) })
    }
}

object Presets {
    /**
     * In portrait, MasterStackLayout puts the master down the left and stacks the
     * rest on the right — exactly the "one tall pane plus a stacked column" shape.
     * In landscape it flips to master-on-top, which is the sensible reading there.
     */
    val ALL: List<Preset> = listOf(
        Preset(
            id = "even-2",
            label = "Two panes",
            slotCount = 2,
            strategy = MasterStackLayout(),
            config = TilingConfig(masterRatio = 0.5f)
        ),
        Preset(
            id = "master-2",
            label = "Master + 1",
            slotCount = 2,
            strategy = MasterStackLayout()
        ),
        Preset(
            id = "master-3",
            label = "Master + 2 stacked",
            slotCount = 3,
            strategy = MasterStackLayout()
        ),
        Preset(
            id = "master-4",
            label = "Master + 3 stacked",
            slotCount = 4,
            strategy = MasterStackLayout()
        ),

        // Fixed-axis splits: same shape in portrait and landscape (see SplitLayout).
        Preset(
            id = "split-lr",
            label = "Left / Right",
            slotCount = 2,
            strategy = SplitLayout(SplitLayout.Direction.LEFT_RIGHT, SplitLayout.Subdivide.NONE)
        ),
        Preset(
            id = "split-lr-right-split",
            label = "Left, Right split top+bottom",
            slotCount = 3,
            strategy = SplitLayout(SplitLayout.Direction.LEFT_RIGHT, SplitLayout.Subdivide.SECOND)
        ),
        Preset(
            id = "split-lr-left-split",
            label = "Left split top+bottom, Right",
            slotCount = 3,
            strategy = SplitLayout(SplitLayout.Direction.LEFT_RIGHT, SplitLayout.Subdivide.FIRST)
        ),
        Preset(
            id = "split-grid",
            label = "2×2 Grid",
            slotCount = 4,
            strategy = SplitLayout(SplitLayout.Direction.LEFT_RIGHT, SplitLayout.Subdivide.BOTH)
        ),
        Preset(
            id = "split-tb-top-split",
            label = "Top split left+right, Bottom",
            slotCount = 3,
            strategy = SplitLayout(SplitLayout.Direction.TOP_BOTTOM, SplitLayout.Subdivide.FIRST)
        ),
        Preset(
            id = "split-tb-bottom-split",
            label = "Top, Bottom split left+right",
            slotCount = 3,
            strategy = SplitLayout(SplitLayout.Direction.TOP_BOTTOM, SplitLayout.Subdivide.SECOND)
        )
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
