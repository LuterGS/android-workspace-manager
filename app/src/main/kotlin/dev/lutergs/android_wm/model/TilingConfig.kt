package dev.lutergs.android_wm.model

data class TilingConfig(
    val masterRatio: Float = 0.55f,
    /**
     * Relative sizes of the stack windows, in order. Normalised on use, so the
     * values need not sum to 1. An empty list — or one whose size doesn't match
     * the current stack — means "split evenly".
     */
    val stackRatios: List<Float> = emptyList(),
    val windowGap: Int = 0,
    val excludedPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "dev.lutergs.android_wm" // this app's own applicationId/package — don't tile ourselves
    )
)
