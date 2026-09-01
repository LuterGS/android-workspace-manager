package dev.lutergs.android_wm

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.lutergs.android_wm.data.SceneStore
import dev.lutergs.android_wm.engine.Preset
import dev.lutergs.android_wm.engine.Presets
import dev.lutergs.android_wm.model.Scene
import dev.lutergs.android_wm.service.ShizukuServiceConnection
import dev.lutergs.android_wm.service.TilingAccessibilityService
import dev.lutergs.android_wm.service.WindowTilingServiceImpl
import dev.lutergs.android_wm.ui.PresetBuilderDialog
import rikka.shizuku.Shizuku

/**
 * Shizuku connection flow, plus the two things the floating widget can't do well:
 * building a new scene from a [Preset] (needs an app picker) and renaming a saved
 * one (needs a focused text field, which an accessibility overlay can't hold).
 */
class MainActivity : AppCompatActivity(),
    Shizuku.OnRequestPermissionResultListener,
    Shizuku.OnBinderReceivedListener,
    Shizuku.OnBinderDeadListener {

    companion object {
        private const val REQUEST_CODE = 1
        /** How long to wait for bindUserService() before showing a retry option. */
        private const val BIND_TIMEOUT_MS = 5000L
    }

    private val serviceConnection = ShizukuServiceConnection()
    private val handler = Handler(Looper.getMainLooper())
    private var widgetVisible = false

    private lateinit var store: SceneStore
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var autoRestoreSwitch: SwitchCompat
    private lateinit var rotationReflowSwitch: SwitchCompat
    private lateinit var presetList: LinearLayout
    private lateinit var sceneList: LinearLayout
    private lateinit var sceneEmptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeInsets()

        store = SceneStore(this)

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        accessibilityButton = findViewById(R.id.accessibility_button)
        autoRestoreSwitch = findViewById(R.id.auto_restore_switch)
        rotationReflowSwitch = findViewById(R.id.rotation_reflow_switch)
        presetList = findViewById(R.id.preset_list)
        sceneList = findViewById(R.id.scene_list)
        sceneEmptyHint = findViewById(R.id.scene_empty_hint)

        accessibilityButton.setOnClickListener { openAccessibilitySettings() }
        autoRestoreSwitch.isChecked = store.autoRestoreEnabled
        autoRestoreSwitch.setOnCheckedChangeListener { _, checked -> store.autoRestoreEnabled = checked }
        rotationReflowSwitch.isChecked = store.rotationReflowEnabled
        rotationReflowSwitch.setOnCheckedChangeListener { _, checked -> store.rotationReflowEnabled = checked }

        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListener(this)
        Shizuku.addBinderDeadListener(this)

        checkShizukuState()

        // Building and naming scenes touches only SceneStore/PackageManager, not
        // Shizuku, so these render regardless of connection state.
        renderPresets()
        renderScenes()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    /**
     * Android 15+ (targetSdk 35) enforces edge-to-edge: without this, content
     * draws behind the system bars and the ActionBar visibly runs into the
     * status bar. Pads the root around the existing 16dp card padding so cards
     * still clear the status/nav bars (and any cutout) on every side.
     */
    private fun applyEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.root_scroll)
        val extraBottom = dp(24)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom + extraBottom)
            windowInsets
        }
    }

    // --- Shizuku connection flow ---

    private fun checkShizukuState() {
        try {
            if (!Shizuku.pingBinder()) {
                statusText.text = getString(R.string.shizuku_not_running)
                actionButton.text = getString(R.string.retry)
                actionButton.setOnClickListener { checkShizukuState() }
                actionButton.visibility = View.VISIBLE
                return
            }
        } catch (e: Exception) {
            statusText.text = getString(R.string.shizuku_not_installed)
            actionButton.visibility = View.GONE
            return
        }

        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindUserService()
        } else {
            statusText.text = getString(R.string.permission_required)
            actionButton.text = getString(R.string.grant_permission)
            actionButton.setOnClickListener { Shizuku.requestPermission(REQUEST_CODE) }
            actionButton.visibility = View.VISIBLE
        }
    }

    /**
     * bindUserService() itself returns immediately — the actual bind happens
     * asynchronously and used to be assumed successful right here, which meant the
     * UI said "Connected" even when the bind silently failed (this is exactly how
     * the R8-stripped-the-service bug went unnoticed for so long; see HANDOFF §10).
     * Now the UI only reflects [ShizukuServiceConnection.isConnected] once it
     * actually changes, via [ShizukuServiceConnection.onConnectionChanged], with a
     * timeout so a bind that never calls back still gets a retry option instead of
     * hanging on "Connecting…" forever.
     */
    private fun bindUserService() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, WindowTilingServiceImpl::class.java.name)
        ).daemon(false).processNameSuffix("tiling").version(BuildConfig.VERSION_CODE)

        serviceConnection.onConnectionChanged = {
            runOnUiThread {
                handler.removeCallbacksAndMessages(null) // the timeout below, if still pending
                updateConnectionUi()
            }
        }
        Shizuku.bindUserService(args, serviceConnection)
        TilingAccessibilityService.serviceConnection = serviceConnection

        if (serviceConnection.isConnected) {
            // Already bound from an earlier visit to this screen — no callback is
            // coming for a bind that already completed, so reflect it right away.
            updateConnectionUi()
            return
        }

        statusText.text = getString(R.string.connecting)
        actionButton.visibility = View.GONE
        accessibilityButton.visibility = View.GONE
        // If nothing has connected by the timeout, updateConnectionUi() falls through
        // to its "still not connected" branch and offers a retry — self-correcting if
        // the bind actually does succeed a little later than that.
        handler.postDelayed({ updateConnectionUi() }, BIND_TIMEOUT_MS)
    }

    /** Reflects the service connection's actual current state — called once it's known,
     *  never assumed ahead of time. */
    private fun updateConnectionUi() {
        if (!serviceConnection.isConnected) {
            statusText.text = getString(R.string.connection_failed)
            actionButton.text = getString(R.string.retry)
            actionButton.setOnClickListener { checkShizukuState() }
            actionButton.visibility = View.VISIBLE
            accessibilityButton.visibility = View.GONE
            return
        }

        // The service may already be running the widget from a previous visit here —
        // reflect its real state rather than assuming it's off.
        widgetVisible = TilingAccessibilityService.isEnabled

        statusText.text = getString(R.string.connected)
        actionButton.text = getString(if (widgetVisible) R.string.hide_widget else R.string.show_widget)
        actionButton.setOnClickListener { toggleWidget() }
        actionButton.visibility = View.VISIBLE
        accessibilityButton.visibility = View.VISIBLE
    }

    private fun toggleWidget() {
        widgetVisible = !widgetVisible
        TilingAccessibilityService.isEnabled = widgetVisible
        store.widgetEnabled = widgetVisible
        actionButton.text = getString(if (widgetVisible) R.string.hide_widget else R.string.show_widget)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQUEST_CODE) {
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                statusText.text = getString(R.string.permission_denied)
                actionButton.text = getString(R.string.grant_permission)
                actionButton.setOnClickListener { Shizuku.requestPermission(REQUEST_CODE) }
            }
        }
    }

    override fun onBinderReceived() {
        checkShizukuState()
    }

    override fun onBinderDead() {
        statusText.text = getString(R.string.shizuku_not_running)
        actionButton.text = getString(R.string.retry)
        actionButton.setOnClickListener { checkShizukuState() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderReceivedListener(this)
        Shizuku.removeBinderDeadListener(this)
        handler.removeCallbacksAndMessages(null)
        // serviceConnection outlives this Activity (TilingAccessibilityService holds a
        // static reference to it), so drop the callback rather than let it keep this
        // destroyed Activity reachable.
        serviceConnection.onConnectionChanged = null
        super.onDestroy()
    }

    // --- Presets: "new layout from a preset" ---

    private fun renderPresets() {
        presetList.removeAllViews()
        Presets.ALL.forEach { preset -> presetList.addView(presetRow(preset)) }
    }

    private fun presetRow(preset: Preset): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = cardShape()
            foreground = selectableRipple()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                PresetBuilderDialog(this@MainActivity, preset, store) { renderScenes() }.show()
            }
        }

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = preset.label
                textSize = 15f
                setTextColor(getColor(R.color.on_background))
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.preset_row_subtitle, preset.slotCount)
                textSize = 12f
                setTextColor(getColor(R.color.on_background_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        })

        row.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(getColor(R.color.on_background_secondary))
        })

        return row
    }

    // --- Scenes: rename / delete ---

    private fun renderScenes() {
        sceneList.removeAllViews()
        val scenes = store.list()
        sceneEmptyHint.visibility = if (scenes.isEmpty()) View.VISIBLE else View.GONE
        scenes.forEach { scene -> sceneList.addView(sceneRow(scene)) }
    }

    private fun sceneRow(scene: Scene): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(getColor(R.color.surface_variant))
            }
            setPadding(dp(16), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = scene.name
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.on_background))
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.scene_row_subtitle, scene.windows.size)
                textSize = 12f
                setTextColor(getColor(R.color.on_background_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        })

        row.addView(iconButton("✎", R.color.on_background_secondary) { showRenameDialog(scene) })
        row.addView(iconButton("✕", R.color.danger) { confirmDelete(scene) })
        return row
    }

    /** A small round tap target used for the rename/delete actions on a scene row. */
    private fun iconButton(glyph: String, colorRes: Int, onClick: () -> Unit): View = TextView(this).apply {
        text = glyph
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(getColor(colorRes))
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(R.color.surface))
        }
        foreground = selectableRipple()
        val size = dp(36)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun cardShape(): Drawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(getColor(R.color.surface_variant))
    }

    /** The theme's standard tap ripple, for views that draw their own background. */
    private fun selectableRipple(): Drawable? {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return if (typedValue.resourceId != 0) getDrawable(typedValue.resourceId) else null
    }

    private fun showRenameDialog(scene: Scene) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(scene.name)
            setSelection(text.length)
            val pad = dp(20)
            setPadding(pad, dp(16), pad, 0)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.rename_button) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() || newName == scene.name -> {}
                    !store.rename(scene.name, newName) ->
                        Toast.makeText(this, R.string.name_taken, Toast.LENGTH_SHORT).show()
                    else -> {
                        renderScenes()
                        TilingAccessibilityService.refreshWidget()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(scene: Scene) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, scene.name))
            .setPositiveButton(R.string.delete_button) { _, _ ->
                store.delete(scene.name)
                renderScenes()
                TilingAccessibilityService.refreshWidget()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
