package dev.lutergs.android_wm.data

import android.content.Context
import android.util.Log
import dev.lutergs.android_wm.model.Scene
import dev.lutergs.android_wm.model.SceneWindow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists scenes as JSON in SharedPreferences.
 *
 * Scenes are small and few, so a single key holding the whole collection keeps
 * saving atomic — no chance of a half-written scene list after a crash.
 */
class SceneStore(context: Context) {

    private companion object {
        const val TAG = "TilingWM"
        const val PREFS = "scenes"
        const val KEY = "all"
        const val KEY_AUTO_RESTORE = "auto_restore"
        const val KEY_ROTATION_REFLOW = "rotation_reflow"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Opt-in: when on, a window from the last-loaded scene that dies and comes
     * back gets moved back to its saved pane. Off by default — this runs on a
     * daily-use phone, so auto-behaviour has to be asked for, not assumed.
     */
    var autoRestoreEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTORE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESTORE, value).apply()

    /**
     * On by default: when the display rotates while a scene is active, its
     * windows are reflowed to fit the new orientation. Unlike auto-restore this
     * only ever reacts to an action the user just took (physically rotating the
     * device), so it defaults on — but it's still a toggle, since a mid-rotation
     * reflow is a visible, disruptive move that should be easy to turn off.
     */
    var rotationReflowEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROTATION_REFLOW, true)
        set(value) = prefs.edit().putBoolean(KEY_ROTATION_REFLOW, value).apply()

    fun list(): List<Scene> = read()

    fun names(): List<String> = read().map { it.name }

    fun load(name: String): Scene? = read().firstOrNull { it.name == name }

    /** Saves [scene], replacing any existing scene with the same name. */
    fun save(scene: Scene) {
        val updated = read().filterNot { it.name == scene.name } + scene
        write(updated)
    }

    fun delete(name: String) {
        write(read().filterNot { it.name == name })
    }

    /**
     * Renames [oldName] to [newName] in place, preserving its position in the list.
     * Returns false — leaving the store untouched — if [oldName] doesn't exist or
     * [newName] is already taken by a different scene.
     */
    fun rename(oldName: String, newName: String): Boolean {
        if (newName.isBlank()) return false
        val scenes = read()
        if (newName != oldName && scenes.any { it.name == newName }) return false
        val index = scenes.indexOfFirst { it.name == oldName }
        if (index < 0) return false

        val updated = scenes.toMutableList()
        updated[index] = updated[index].copy(name = newName)
        write(updated)
        return true
    }

    /** "$prefix 1", "$prefix 2", … skipping names already taken. */
    fun nextAvailableName(prefix: String = "Scene"): String {
        val taken = names().toSet()
        var i = 1
        while ("$prefix $i" in taken) i++
        return "$prefix $i"
    }

    private fun read(): List<Scene> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i -> array.getJSONObject(i).toScene() }
        } catch (e: Exception) {
            // Corrupt store — better to start clean than to crash on every launch.
            Log.e(TAG, "scene store unreadable, discarding", e)
            emptyList()
        }
    }

    private fun write(scenes: List<Scene>) {
        val array = JSONArray()
        scenes.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun Scene.toJson() = JSONObject().apply {
        put("name", name)
        put("windows", JSONArray().also { arr ->
            windows.forEach { w ->
                arr.put(JSONObject().apply {
                    put("pkg", w.packageName)
                    put("l", w.left.toDouble())
                    put("t", w.top.toDouble())
                    put("r", w.right.toDouble())
                    put("b", w.bottom.toDouble())
                })
            }
        })
    }

    private fun JSONObject.toScene(): Scene {
        val windows = getJSONArray("windows")
        return Scene(
            name = getString("name"),
            windows = (0 until windows.length()).map { i ->
                val w = windows.getJSONObject(i)
                SceneWindow(
                    packageName = w.getString("pkg"),
                    left = w.getDouble("l").toFloat(),
                    top = w.getDouble("t").toFloat(),
                    right = w.getDouble("r").toFloat(),
                    bottom = w.getDouble("b").toFloat()
                )
            }
        )
    }
}
