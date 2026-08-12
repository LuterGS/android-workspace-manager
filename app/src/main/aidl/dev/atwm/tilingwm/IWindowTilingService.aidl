package dev.atwm.tilingwm;

interface IWindowTilingService {
    void destroy() = 16777114;

    // Resize a task to the given pixel bounds
    void resizeTask(int taskId, int left, int top, int right, int bottom) = 1;

    // Switch a task between fullscreen (1) and freeform (5).
    // Gone from IActivityTaskManager on Android 16 — prefer launchInFreeform().
    void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop) = 2;

    // Launch (or re-launch) a package in freeform mode, returning its task id,
    // or -1 on failure. Works whether the app is dead, fullscreen, or already
    // freeform — the one call a scene needs to materialise a window.
    int launchInFreeform(String packageName) = 5;

    // Get IDs + bounds + windowing mode of visible tasks on display 0
    // Returns a flattened array: [taskId, left, top, right, bottom, windowingMode, ...]
    int[] getVisibleTaskInfo() = 3;

    // Parallel string array of package names matching getVisibleTaskInfo() entries
    String[] getVisibleTaskPackages() = 4;
}
