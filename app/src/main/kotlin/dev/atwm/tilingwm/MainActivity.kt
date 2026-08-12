package dev.atwm.tilingwm

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
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
import dev.atwm.tilingwm.data.SceneStore
import dev.atwm.tilingwm.engine.Preset
import dev.atwm.tilingwm.engine.Presets
import dev.atwm.tilingwm.model.Scene
import dev.atwm.tilingwm.service.ShizukuServiceConnection
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.service.WindowTilingServiceImpl
import dev.atwm.tilingwm.ui.PresetBuilderDialog
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
    }

    private val serviceConnection = ShizukuServiceConnection()
    private var widgetVisible = false

    private lateinit var store: SceneStore
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var autoRestoreSwitch: SwitchCompat
    private lateinit var presetList: LinearLayout
    private lateinit var sceneList: LinearLayout
    private lateinit var sceneEmptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = SceneStore(this)

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        accessibilityButton = findViewById(R.id.accessibility_button)
        autoRestoreSwitch = findViewById(R.id.auto_restore_switch)
        presetList = findViewById(R.id.preset_list)
        sceneList = findViewById(R.id.scene_list)
        sceneEmptyHint = findViewById(R.id.scene_empty_hint)

        accessibilityButton.setOnClickListener { openAccessibilitySettings() }
        autoRestoreSwitch.isChecked = store.autoRestoreEnabled
        autoRestoreSwitch.setOnCheckedChangeListener { _, checked -> store.autoRestoreEnabled = checked }

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

    private fun bindUserService() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, WindowTilingServiceImpl::class.java.name)
        ).daemon(false).processNameSuffix("tiling").version(BuildConfig.VERSION_CODE)

        Shizuku.bindUserService(args, serviceConnection)
        TilingAccessibilityService.serviceConnection = serviceConnection

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
        super.onDestroy()
    }

    // --- Presets: "new layout from a preset" ---

    private fun renderPresets() {
        presetList.removeAllViews()
        Presets.ALL.forEach { preset -> presetList.addView(presetRow(preset)) }
    }

    private fun presetRow(preset: Preset): View = Button(this).apply {
        text = getString(R.string.preset_row_format, preset.label, preset.slotCount)
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        setOnClickListener {
            PresetBuilderDialog(this@MainActivity, preset, store) { renderScenes() }.show()
        }
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
            setPadding(0, dp(8), 0, dp(8))
        }

        row.addView(TextView(this).apply {
            text = getString(R.string.scene_row_format, scene.name, scene.windows.size)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        row.addView(Button(this).apply {
            text = getString(R.string.rename_button)
            isAllCaps = false
            setOnClickListener { showRenameDialog(scene) }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.delete_button)
            isAllCaps = false
            setOnClickListener { confirmDelete(scene) }
        })
        return row
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
