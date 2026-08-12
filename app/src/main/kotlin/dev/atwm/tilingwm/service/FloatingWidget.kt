package dev.atwm.tilingwm.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The always-available control: a small draggable puck that expands into a list
 * of saved scenes.
 *
 * Hosted by the AccessibilityService so it can use TYPE_ACCESSIBILITY_OVERLAY and
 * needs no SYSTEM_ALERT_WINDOW grant. The puck and the panel are two separate
 * windows — the panel comes and goes while the puck keeps its position.
 */
class FloatingWidget(
    private val context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun sceneNames(): List<String>
        fun onSaveScene()
        fun onLoadScene(name: String)
        fun onDeleteScene(name: String)
    }

    private companion object {
        const val PUCK_DP = 52
        const val PANEL_WIDTH_DP = 220
        const val PANEL_MAX_HEIGHT_DP = 320
        /** Movement beyond this is a drag, not a tap. */
        const val TAP_SLOP_DP = 8
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var puck: View? = null
    private var puckParams: WindowManager.LayoutParams? = null
    private var panel: View? = null

    fun show() {
        if (puck != null) return
        addPuck()
    }

    fun hide() {
        collapse()
        puck?.let { view -> runCatching { windowManager.removeView(view) } }
        puck = null
        puckParams = null
    }

    /** Rebuilds the panel if it is open, so a saved/deleted scene shows up at once. */
    fun refresh() {
        if (panel != null) {
            collapse()
            expand()
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    // --- Puck ---

    @SuppressLint("ClickableViewAccessibility")
    private fun addPuck() {
        val size = dp(PUCK_DP)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        val view = TextView(context).apply {
            text = "▦"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 33, 33, 33))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
        }

        attachDragAndTap(view, params)
        runCatching { windowManager.addView(view, params) }.onSuccess {
            puck = view
            puckParams = params
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(view: View, params: WindowManager.LayoutParams) {
        val slop = dp(TAP_SLOP_DP)
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragged = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragged && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragged = true
                        // Once it's a drag, the panel would fight the movement.
                        collapse()
                    }
                    if (dragged) {
                        params.x = originX + dx
                        params.y = originY + dy
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) togglePanel()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    // --- Panel ---

    private fun togglePanel() {
        if (panel == null) expand() else collapse()
    }

    private fun collapse() {
        panel?.let { view -> runCatching { windowManager.removeView(view) } }
        panel = null
    }

    private fun expand() {
        val anchor = puckParams ?: return

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(240, 28, 28, 28))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
        }

        content.addView(TextView(context).apply {
            text = "Scenes"
            setTextColor(Color.argb(160, 255, 255, 255))
            textSize = 12f
            setPadding(dp(4), 0, 0, dp(8))
        })

        content.addView(panelButton("＋  Save current") {
            callbacks.onSaveScene()
        })

        val names = callbacks.sceneNames()
        if (names.isEmpty()) {
            content.addView(TextView(context).apply {
                text = "No scenes saved yet"
                setTextColor(Color.argb(120, 255, 255, 255))
                textSize = 12f
                setPadding(dp(4), dp(8), 0, dp(4))
            })
        } else {
            names.forEach { name ->
                // Tap loads; long-press deletes — no room for a second control here.
                content.addView(panelButton(name, onLongClick = {
                    callbacks.onDeleteScene(name)
                }) {
                    callbacks.onLoadScene(name)
                    collapse()
                })
            }
        }

        val scroller = ScrollView(context).apply { addView(content) }

        // A fixed height is what makes the ScrollView actually scroll, so estimate
        // the content and cap it rather than using WRAP_CONTENT.
        val rowCount = 1 + maxOf(1, names.size)
        val estimatedHeight = dp(40) + rowCount * dp(48) + dp(12)

        val params = WindowManager.LayoutParams(
            dp(PANEL_WIDTH_DP),
            minOf(estimatedHeight, dp(PANEL_MAX_HEIGHT_DP)),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchor.x + dp(PUCK_DP + 8)
            y = anchor.y
        }

        runCatching { windowManager.addView(scroller, params) }.onSuccess { panel = scroller }
    }

    private fun panelButton(
        label: String,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.argb(40, 255, 255, 255))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { bottomMargin = dp(6) }

        setOnClickListener { onClick() }
        onLongClick?.let { action ->
            setOnLongClickListener {
                action()
                true
            }
        }
    }
}
