package dev.atwm.tilingwm.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import dev.atwm.tilingwm.data.SceneStore
import dev.atwm.tilingwm.engine.SceneManager
import dev.atwm.tilingwm.engine.usableArea
import dev.atwm.tilingwm.model.Scene
import dev.atwm.tilingwm.model.SceneWindow
import dev.atwm.tilingwm.model.TilingConfig

/**
 * Hosts the floating widget and performs scene capture/restore.
 *
 * Arrangements are applied when the user asks, not continuously — this is not a
 * return to the real-time tiling this project moved away from (see HANDOFF §3).
 * The one exception, gated behind [SceneStore.autoRestoreEnabled] and off by
 * default, is [onAccessibilityEvent]: if a window from the *last-loaded* scene
 * dies and comes back, it is moved back to its saved pane. Nothing else — not
 * other windows, not apps outside that scene, not a window that merely regains
 * focus — is touched.
 */
class TilingAccessibilityService : AccessibilityService(), FloatingWidget.Callbacks {

    companion object {
        private const val TAG = "TilingWM"

        var serviceConnection: ShizukuServiceConnection? = null

        /** Set by MainActivity; controls whether the floating widget is on screen. */
        var isEnabled: Boolean = false
            set(value) {
                field = value
                instance?.applyEnabledState()
            }

        private var instance: TilingAccessibilityService? = null

        /**
         * Rebuilds the widget's panel if it's open, so a scene created, renamed or
         * deleted from MainActivity shows up there without waiting for a close/reopen.
         */
        fun refreshWidget() {
            instance?.widget?.refresh()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val config = TilingConfig()

    private lateinit var store: SceneStore
    private lateinit var scenes: SceneManager
    private var widget: FloatingWidget? = null

    /** The scene most recently sent to [SceneManager.apply], for auto-restore. */
    private var activeScene: Scene? = null

    /**
     * Task id we last placed each active-scene package under. A package whose
     * current task id doesn't match this was relaunched (Android hands out a
     * fresh task id per launch) — a package whose task id is unchanged merely
     * regained focus, which must NOT trigger a reposition.
     */
    private val lastTaskIdByPackage = mutableMapOf<String, Int>()

    /** Orientation as of the last check, so onConfigurationChanged() reacts only
     *  to an actual portrait/landscape flip, not every config change (density,
     *  locale, ...) the system happens to deliver. */
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    override fun onServiceConnected() {
        instance = this
        store = SceneStore(this)
        scenes = SceneManager({ serviceConnection?.service }, handler)
        widget = FloatingWidget(this, this)
        lastOrientation = resources.configuration.orientation
        applyEnabledState()
    }

    /**
     * A [Scene]'s window bounds are fractions of the usable area precisely so
     * they survive the display changing shape (see HANDOFF §4) — but only a
     * *newly applied* scene picks that up automatically. Windows already sitting
     * on screen keep whatever absolute pixel bounds they were last given, and
     * rotating leaves them wherever that no longer makes sense. Re-applying the
     * active scene's bounds against the post-rotation area fixes that; nothing
     * gets relaunched, so this can't steal focus from whatever the user is doing
     * mid-rotation the way a full apply() would.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation

        val scene = activeScene ?: return
        if (serviceConnection?.service == null) return
        // The rotation animation needs a moment to actually finish, or this
        // reads back a usableArea that's still mid-transition.
        handler.postDelayed({
            if (serviceConnection?.service != null) {
                scenes.reapplyBounds(scene, usableArea(this))
            }
        }, 200)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!store.autoRestoreEnabled) return

        val pkg = event.packageName?.toString() ?: return
        val scene = activeScene ?: return
        val window = scene.windows.firstOrNull { it.packageName == pkg } ?: return
        if (serviceConnection?.service == null) return

        repositionIfRelaunched(scene, window)
    }

    /** Moves [window]'s app back to its saved pane, but only if it's a new task. */
    private fun repositionIfRelaunched(scene: Scene, window: SceneWindow, retry: Boolean = true) {
        val taskId = scenes.currentTaskId(window.packageName)
        if (taskId == null) {
            // TYPE_WINDOW_STATE_CHANGED can fire a beat before the task is
            // queryable — one short retry covers that without a full backoff
            // ladder, since (unlike a cold app start) this is a narrow IPC race.
            if (retry) handler.postDelayed({ repositionIfRelaunched(scene, window, retry = false) }, 300)
            return
        }
        if (lastTaskIdByPackage[window.packageName] == taskId) return // same task — just regained focus

        lastTaskIdByPackage[window.packageName] = taskId
        val svc = serviceConnection?.service ?: return
        val bounds = window.toBounds(usableArea(this))
        svc.resizeTask(taskId, bounds.left, bounds.top, bounds.right, bounds.bottom)
        Log.d(TAG, "auto-restored '${window.packageName}' to its pane in '${scene.name}' (task $taskId)")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        widget?.hide()
        widget = null
        instance = null
        super.onDestroy()
    }

    private fun applyEnabledState() {
        handler.post {
            if (isEnabled) widget?.show() else widget?.hide()
        }
    }

    // --- FloatingWidget.Callbacks ---

    override fun sceneNames(): List<String> = store.names()

    override fun onSaveScene() {
        if (serviceConnection?.service == null) {
            toast("Shizuku not connected")
            return
        }

        val name = store.nextAvailableName()
        val scene = scenes.capture(name, usableArea(this), config.excludedPackages)
        if (scene == null) {
            toast("No freeform windows to save")
            return
        }

        store.save(scene)
        widget?.refresh()
        toast("Saved '$name' (${scene.windows.size} windows)")
    }

    override fun onLoadScene(name: String) {
        val scene: Scene? = store.load(name)
        if (scene == null) {
            toast("Scene '$name' is gone")
            widget?.refresh()
            return
        }
        if (serviceConnection?.service == null) {
            toast("Shizuku not connected")
            return
        }

        // Cleared, not pre-filled with the tasks apply() is about to place: the
        // first TYPE_WINDOW_STATE_CHANGED seen for each package after this will
        // have no prior task id to compare against, so it reapplies that pane's
        // bounds once more (harmless — apply() just put it there) and records the
        // task id. Only a later relaunch, with a different task id, acts again.
        activeScene = scene
        lastTaskIdByPackage.clear()
        scenes.apply(scene, usableArea(this), config.excludedPackages)
    }

    override fun onDeleteScene(name: String) {
        store.delete(name)
        widget?.refresh()
        toast("Deleted '$name'")
    }

    private fun toast(message: String) {
        Log.d(TAG, message)
        handler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}
