package dev.atwm.tilingwm.service

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import dev.atwm.tilingwm.IWindowTilingService

/**
 * Runs in Shizuku's privileged process (UID 2000).
 * Accesses IActivityTaskManager via hidden API to resize and remode tasks.
 */
@SuppressLint("PrivateApi")
class WindowTilingServiceImpl : IWindowTilingService.Stub() {

    private companion object {
        const val TAG = "TilingWM"
    }

    private val atm: Any by lazy {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "activity_task")

        val atmStubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterface = atmStubClass.getMethod("asInterface", android.os.IBinder::class.java)
        asInterface.invoke(null, binder)!!
    }

    private val atmClass: Class<*> by lazy {
        Class.forName("android.app.IActivityTaskManager")
    }

    // Cached reflection methods
    private val resizeTaskMethod by lazy {
        atmClass.getMethod("resizeTask", Int::class.java, Rect::class.java, Int::class.java)
    }

    private val setTaskWindowingModeMethod by lazy {
        atmClass.getMethod("setTaskWindowingMode", Int::class.java, Int::class.java, Boolean::class.java)
    }

    private val getTasksMethod by lazy {
        atmClass.getMethod("getTasks", Int::class.java, Boolean::class.java, Boolean::class.java, Int::class.java)
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            resizeTaskMethod.invoke(atm, taskId, Rect(left, top, right, bottom), 0)
        } catch (e: Exception) {
            Log.e(TAG, "resizeTask($taskId) failed", e)
        }
    }

    override fun setTaskWindowingMode(taskId: Int, windowingMode: Int, toTop: Boolean) {
        try {
            setTaskWindowingModeMethod.invoke(atm, taskId, windowingMode, toTop)
        } catch (e: Exception) {
            Log.e(TAG, "setTaskWindowingMode($taskId) failed", e)
        }
    }

    override fun getVisibleTaskInfo(): IntArray {
        try {
            @Suppress("UNCHECKED_CAST")
            val tasks = getTasksMethod.invoke(atm, 20, false, false, 0) as List<Any>

            val filteredTasks = tasks.filter { task ->
                val isVisible = task.javaClass.getField("isVisible").getBoolean(task)
                val isRunning = task.javaClass.getField("isRunning").getBoolean(task)
                isVisible && isRunning
            }

            val result = IntArray(filteredTasks.size * 6)
            filteredTasks.forEachIndexed { i, task ->
                val offset = i * 6
                result[offset] = task.javaClass.getField("taskId").getInt(task)

                // TaskInfo exposes no `bounds` field — the rect lives on the task's
                // WindowConfiguration, right next to the windowing mode.
                val configuration = task.javaClass.getField("configuration").get(task)!!
                val windowConfig = configuration.javaClass.getField("windowConfiguration").get(configuration)!!

                val bounds = windowConfig.javaClass.getMethod("getBounds")
                    .invoke(windowConfig) as Rect
                result[offset + 1] = bounds.left
                result[offset + 2] = bounds.top
                result[offset + 3] = bounds.right
                result[offset + 4] = bounds.bottom

                val getWindowingMode = windowConfig.javaClass.getMethod("getWindowingMode")
                result[offset + 5] = getWindowingMode.invoke(windowConfig) as Int
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "getVisibleTaskInfo failed", e)
            return IntArray(0)
        }
    }

    override fun getVisibleTaskPackages(): Array<String> {
        try {
            @Suppress("UNCHECKED_CAST")
            val tasks = getTasksMethod.invoke(atm, 20, false, false, 0) as List<Any>

            val filteredTasks = tasks.filter { task ->
                val isVisible = task.javaClass.getField("isVisible").getBoolean(task)
                val isRunning = task.javaClass.getField("isRunning").getBoolean(task)
                isVisible && isRunning
            }

            return filteredTasks.map { task ->
                val topActivity = task.javaClass.getField("topActivity").get(task)
                if (topActivity != null) {
                    val getPackageName = topActivity.javaClass.getMethod("getPackageName")
                    getPackageName.invoke(topActivity) as? String ?: ""
                } else {
                    ""
                }
            }.toTypedArray()
        } catch (e: Exception) {
            Log.e(TAG, "getVisibleTaskPackages failed", e)
            return emptyArray()
        }
    }

    /**
     * Launch [packageName] in freeform mode and return its task id (-1 on failure).
     *
     * Android 16 removed IActivityTaskManager.setTaskWindowingMode, so there is no
     * API to re-mode a running task. `am start --windowingMode 5` does the job for
     * every case a scene cares about: a dead app starts freeform, and a running
     * fullscreen app is moved to freeform as its task is brought forward. We run in
     * Shizuku's shell-UID process, so `am` is simply available.
     */
    override fun launchInFreeform(packageName: String): Int {
        return try {
            val resolved = shell("cmd package resolve-activity --brief $packageName")
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains('/') && !it.contains(' ') }
                ?: run {
                    Log.e(TAG, "launchInFreeform($packageName): no launchable activity")
                    return -1
                }

            // FLAG_ACTIVITY_NEW_TASK (0x10000000) keeps each app in its own task.
            shell("am start -n $resolved --windowingMode 5 -f 0x10000000")

            // The task id is only knowable after the window exists; the caller polls
            // getVisibleTaskInfo() for it. Report success without inventing an id.
            0
        } catch (e: Exception) {
            Log.e(TAG, "launchInFreeform($packageName) failed", e)
            -1
        }
    }

    private fun shell(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }

    override fun destroy() {
        // Called by Shizuku when unbinding. No cleanup needed.
    }
}
