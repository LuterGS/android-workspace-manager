package dev.atwm.tilingwm.ui

import android.content.Intent
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.atwm.tilingwm.R
import dev.atwm.tilingwm.data.SceneStore
import dev.atwm.tilingwm.engine.Preset
import dev.atwm.tilingwm.engine.usableArea
import dev.atwm.tilingwm.model.TilingConfig
import dev.atwm.tilingwm.service.TilingAccessibilityService

/**
 * Walks the user through turning a [Preset] into a saved scene: pick an app for
 * each pane, name the result, save it.
 *
 * This needs no Shizuku connection — building a [dev.atwm.tilingwm.model.Scene] is
 * pure layout math over whichever packages the user picks — so it can run any time
 * MainActivity is open, connected or not.
 */
class PresetBuilderDialog(
    private val activity: AppCompatActivity,
    private val preset: Preset,
    private val store: SceneStore,
    private val onCreated: () -> Unit
) {
    private data class AppEntry(val packageName: String, val label: String)

    private val config = TilingConfig()
    private val slots = arrayOfNulls<AppEntry>(preset.slotCount)
    private val slotButtons = mutableListOf<Button>()
    private lateinit var createButton: Button
    private var dialog: AlertDialog? = null

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics
    ).toInt()

    fun show() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        for (index in 0 until preset.slotCount) {
            content.addView(TextView(activity).apply {
                text = activity.getString(R.string.preset_slot_label, index + 1)
                textSize = 12f
                alpha = 0.7f
                setPadding(0, if (index == 0) 0 else dp(14), 0, dp(4))
            })
            val button = Button(activity).apply {
                text = activity.getString(R.string.choose_app)
                isAllCaps = false
                setOnClickListener { showAppPicker { entry -> onSlotPicked(index, entry) } }
            }
            content.addView(button)
            slotButtons.add(button)
        }

        createButton = Button(activity).apply {
            text = activity.getString(R.string.create_layout)
            isAllCaps = false
            isEnabled = false
            setPadding(0, dp(20), 0, 0)
            setOnClickListener { promptNameAndSave() }
        }
        content.addView(createButton)

        val scroller = ScrollView(activity).apply { addView(content) }

        dialog = AlertDialog.Builder(activity)
            .setTitle(preset.label)
            .setView(scroller)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onSlotPicked(index: Int, entry: AppEntry) {
        slots[index] = entry
        slotButtons[index].text = entry.label
        createButton.isEnabled = slots.count { it != null } >= 2
    }

    /** Lists launchable apps (minus this app and the usual system exclusions) for a slot. */
    private fun showAppPicker(onPicked: (AppEntry) -> Unit) {
        val pm = activity.packageManager
        val excluded = config.excludedPackages + activity.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolved ->
                val pkg = resolved.activityInfo.packageName
                if (pkg in excluded) null else AppEntry(pkg, resolved.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        // Two-line rows (label + package) so look-alike app names stay unambiguous —
        // a stock platform layout, so this needs no resource file of its own.
        val adapter = object : ArrayAdapter<AppEntry>(
            activity, android.R.layout.simple_list_item_2, android.R.id.text1, apps
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.findViewById<TextView>(android.R.id.text1).text = apps[position].label
                view.findViewById<TextView>(android.R.id.text2).text = apps[position].packageName
                return view
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.pick_app_title)
            .setAdapter(adapter) { _, index -> onPicked(apps[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptNameAndSave() {
        val packages = slots.filterNotNull().map { it.packageName }
        if (packages.size < 2) return // the Create button is disabled below this count

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(store.nextAvailableName())
            setSelection(text.length)
            val pad = dp(20)
            setPadding(pad, dp(16), pad, 0)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.name_layout_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                trySave(packages, input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun trySave(packages: List<String>, name: String) {
        if (name.isEmpty()) {
            Toast.makeText(activity, R.string.name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (store.names().contains(name)) {
            // Saving would silently clobber an existing layout — confirm first.
            AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.overwrite_confirm, name))
                .setPositiveButton(R.string.overwrite) { _, _ -> save(packages, name) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            save(packages, name)
        }
    }

    private fun save(packages: List<String>, name: String) {
        val area = usableArea(activity, config)
        val orientation = activity.resources.configuration.orientation
        val scene = preset.toScene(name, packages, orientation, area)
        if (scene == null) {
            Toast.makeText(activity, R.string.need_two_apps, Toast.LENGTH_SHORT).show()
            return
        }

        store.save(scene)
        TilingAccessibilityService.refreshWidget()
        Toast.makeText(activity, activity.getString(R.string.layout_saved, name), Toast.LENGTH_SHORT).show()
        dialog?.dismiss()
        onCreated()
    }
}
