package dev.atwm.tilingwm.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import dev.atwm.tilingwm.data.SceneStore
import dev.atwm.tilingwm.engine.SceneManager
import dev.atwm.tilingwm.model.Scene
import dev.atwm.tilingwm.model.TilingConfig

/**
 * Hosts the floating widget and performs scene capture/restore.
 *
 * Nothing here reacts to window events any more — arrangements are applied when
 * the user asks, not continuously. It stays an AccessibilityService purely to host
 * TYPE_ACCESSIBILITY_OVERLAY windows without a SYSTEM_ALERT_WINDOW grant, and to
 * leave the door open for opt-in auto-rearranging later.
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private val config = TilingConfig()

    private lateinit var store: SceneStore
    private lateinit var scenes: SceneManager
    private var widget: FloatingWidget? = null

    override fun onServiceConnected() {
        instance = this
        store = SceneStore(this)
        scenes = SceneManager({ serviceConnection?.service }, handler)
        widget = FloatingWidget(this, this)
        applyEnabledState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Intentionally idle: scenes are applied on demand, not on every window change.
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

    /**
     * The area scenes are expressed relative to. Recomputed on each use so a fold,
     * unfold or rotation is picked up without needing a listener.
     */
    private fun usableArea(): Rect {
        val metrics = resources.displayMetrics
        return Rect(
            0,
            config.statusBarHeight,
            metrics.widthPixels,
            metrics.heightPixels - config.navBarHeight
        )
    }

    // --- FloatingWidget.Callbacks ---

    override fun sceneNames(): List<String> = store.names()

    override fun onSaveScene() {
        if (serviceConnection?.service == null) {
            toast("Shizuku not connected")
            return
        }

        val name = nextSceneName()
        val scene = scenes.capture(name, usableArea(), config.excludedPackages)
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
        scenes.apply(scene, usableArea())
    }

    override fun onDeleteScene(name: String) {
        store.delete(name)
        widget?.refresh()
        toast("Deleted '$name'")
    }

    /** "Scene 1", "Scene 2", … skipping names already taken. */
    private fun nextSceneName(): String {
        val taken = store.names().toSet()
        var i = 1
        while ("Scene $i" in taken) i++
        return "Scene $i"
    }

    private fun toast(message: String) {
        Log.d(TAG, message)
        handler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}
