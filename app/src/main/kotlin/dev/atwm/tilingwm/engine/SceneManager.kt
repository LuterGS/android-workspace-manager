package dev.atwm.tilingwm.engine

import android.graphics.Rect
import android.os.Handler
import android.util.Log
import dev.atwm.tilingwm.IWindowTilingService
import dev.atwm.tilingwm.model.Scene
import dev.atwm.tilingwm.model.SceneWindow

/**
 * Captures the current window arrangement into a [Scene], and restores one.
 *
 * Restoring is not a single shot: a scene may name apps that are dead, and a cold
 * start takes anywhere from tens of milliseconds to a second. So we launch
 * everything up front, then re-check on a widening schedule, placing each window
 * as its task appears and only giving up on the stragglers.
 */
class SceneManager(
    private val service: () -> IWindowTilingService?,
    private val handler: Handler
) {
    private companion object {
        const val TAG = "TilingWM"
        const val WINDOWING_MODE_FREEFORM = 5
        const val FIELDS_PER_TASK = 6

        /** Widening backoff: fast apps land immediately, slow ones still get caught. */
        val RETRY_DELAYS_MS = longArrayOf(200, 350, 500, 800, 1200, 1500)
    }

    /**
     * Snapshots the freeform windows currently on screen.
     * Returns null if nothing tileable is visible.
     */
    fun capture(name: String, area: Rect, excludedPackages: Set<String>): Scene? {
        val svc = service() ?: return null
        val (info, packages) = readTasks(svc) ?: return null

        val windows = mutableListOf<SceneWindow>()
        val seen = mutableSetOf<String>()

        for (i in packages.indices) {
            val offset = i * FIELDS_PER_TASK
            val pkg = packages[i]
            if (pkg.isEmpty() || pkg in excludedPackages) continue
            if (info[offset + 5] != WINDOWING_MODE_FREEFORM) continue
            // One window per app, by design — keep the frontmost and ignore the rest.
            if (!seen.add(pkg)) continue

            val bounds = Rect(
                info[offset + 1], info[offset + 2],
                info[offset + 3], info[offset + 4]
            )
            // A window dragged mostly or fully past the usable area's edge is
            // still "visible" as far as the task manager is concerned, but it
            // isn't really part of what's on screen — don't let it leak into a
            // newly captured scene.
            if (!Rect.intersects(bounds, area)) continue
            windows.add(SceneWindow.of(pkg, bounds, area))
        }

        if (windows.isEmpty()) {
            Log.w(TAG, "capture('$name'): no freeform windows to save")
            return null
        }
        return Scene(name, windows)
    }

    /**
     * Launches every app in [scene] and places each window as its task shows up.
     * Any other freeform window not in [scene] — leftovers from a previously
     * loaded scene, typically — is minimized first via
     * [IWindowTilingService.minimizeTask], so it can't sit beside or peek out
     * through a gap in the new arrangement. Whatever [scene] itself relaunches
     * un-minimizes normally, the same way tapping a taskbar icon would.
     */
    fun apply(scene: Scene, area: Rect, excludedPackages: Set<String>) {
        val svc = service() ?: return
        Log.d(TAG, "apply('${scene.name}'): ${scene.windows.size} windows")

        minimizeOthers(svc, scene, excludedPackages)

        // Kick every app off first so their cold starts overlap rather than queue.
        scene.windows.forEach { svc.launchInFreeform(it.packageName) }

        schedulePlacement(scene, area, attempt = 0,
            pending = scene.windows.map { it.packageName }.toSet())
    }

    /**
     * Minimizes every visible freeform window [scene] doesn't claim. Best effort
     * and one-shot — unlike placement, nothing here is retried, since there's no
     * "did it actually minimize" signal worth polling for.
     */
    private fun minimizeOthers(svc: IWindowTilingService, scene: Scene, excludedPackages: Set<String>) {
        val keep = scene.windows.map { it.packageName }.toSet()
        val (info, packages) = readTasks(svc) ?: return

        var minimized = 0
        for (i in packages.indices) {
            val pkg = packages[i]
            if (pkg.isEmpty() || pkg in keep || pkg in excludedPackages) continue
            val offset = i * FIELDS_PER_TASK
            if (info[offset + 5] != WINDOWING_MODE_FREEFORM) continue
            svc.minimizeTask(info[offset])
            minimized++
        }
        if (minimized > 0) Log.d(TAG, "apply('${scene.name}'): minimized $minimized leftover window(s)")
    }

    private fun schedulePlacement(
        scene: Scene,
        area: Rect,
        attempt: Int,
        pending: Set<String>
    ) {
        handler.postDelayed({
            val svc = service() ?: return@postDelayed
            val stillPending = place(svc, scene, area, pending)

            when {
                stillPending.isEmpty() ->
                    Log.d(TAG, "apply('${scene.name}'): all windows placed")

                attempt + 1 < RETRY_DELAYS_MS.size ->
                    schedulePlacement(scene, area, attempt + 1, stillPending)

                else ->
                    Log.w(TAG, "apply('${scene.name}'): gave up on $stillPending")
            }
        }, RETRY_DELAYS_MS[attempt])
    }

    /** Places whichever pending windows now have a task; returns those still missing. */
    private fun place(
        svc: IWindowTilingService,
        scene: Scene,
        area: Rect,
        pending: Set<String>
    ): Set<String> {
        val (info, packages) = readTasks(svc) ?: return pending

        val taskIdByPackage = mutableMapOf<String, Int>()
        for (i in packages.indices) {
            // First occurrence wins — getTasks() is ordered front to back.
            taskIdByPackage.putIfAbsent(packages[i], info[i * FIELDS_PER_TASK])
        }

        val missing = mutableSetOf<String>()
        for (window in scene.windows) {
            if (window.packageName !in pending) continue
            val taskId = taskIdByPackage[window.packageName]
            if (taskId == null) {
                missing.add(window.packageName)
                continue
            }
            val bounds = window.toBounds(area)
            svc.resizeTask(taskId, bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        return missing
    }

    /** Current task id for [packageName], or null if it has no visible task right now. */
    fun currentTaskId(packageName: String): Int? {
        val svc = service() ?: return null
        val (info, packages) = readTasks(svc) ?: return null
        for (i in packages.indices) {
            if (packages[i] == packageName) return info[i * FIELDS_PER_TASK]
        }
        return null
    }

    /** Reads task info, guarding against the two arrays disagreeing. */
    private fun readTasks(svc: IWindowTilingService): Pair<IntArray, Array<String>>? {
        return try {
            val info = svc.getVisibleTaskInfo()
            val packages = svc.getVisibleTaskPackages()
            val count = minOf(info.size / FIELDS_PER_TASK, packages.size)
            if (count == 0) null
            else Pair(info, packages.copyOf(count).requireNoNulls())
        } catch (e: Exception) {
            Log.e(TAG, "reading tasks failed", e)
            null
        }
    }
}
