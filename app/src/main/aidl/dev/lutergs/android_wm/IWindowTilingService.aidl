package dev.lutergs.android_wm;

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

    // Minimizes a single task the same way the caption bar's "-" button does:
    // via Samsung's com.samsung.android.multiwindow.IMultiTaskingBinder,
    // reached through the same "activity_task" binder as everything else here
    // (Samsung's ActivityTaskManagerService answers to both interface
    // descriptors on the one object). A minimized task reports
    // isVisible=false, so it's excluded from getVisibleTaskInfo() /
    // getVisibleTaskPackages() automatically — no separate "is this
    // minimized?" bookkeeping is needed anywhere else.
    //
    // Two things this is NOT, both tried and rejected first — see HANDOFF:
    // IActivityTaskManager.moveTaskToBack() only reorders z-order and does
    // nothing to visibility, so a scene that doesn't tile edge-to-edge lets
    // whatever's behind it show through a gap. Resizing a task off-screen
    // doesn't survive either: One UI's desktop layout clamps stray windows
    // back toward a visible edge on its own schedule. "wm shell desktopmode
    // minimizeAll" (the whole-display version) turned out unreliable too —
    // confirmed working once, then confirmed failing both chained with a
    // launch and called completely alone, apparently depending on desk state
    // this reflection layer doesn't control.
    void minimizeTask(int taskId) = 6;

    // Get IDs + bounds + windowing mode of visible tasks on display 0
    // Returns a flattened array: [taskId, left, top, right, bottom, windowingMode, ...]
    int[] getVisibleTaskInfo() = 3;

    // Parallel string array of package names matching getVisibleTaskInfo() entries
    String[] getVisibleTaskPackages() = 4;
}
