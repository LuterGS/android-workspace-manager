package dev.atwm.tilingwm.engine

import android.graphics.Rect
import dev.atwm.tilingwm.model.Scene
import dev.atwm.tilingwm.model.SceneWindow
import dev.atwm.tilingwm.model.TilingConfig

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
        )
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
