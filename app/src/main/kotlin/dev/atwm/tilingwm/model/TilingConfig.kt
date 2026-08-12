package dev.atwm.tilingwm.model

data class TilingConfig(
    val masterRatio: Float = 0.55f,
    /**
     * Relative sizes of the stack windows, in order. Normalised on use, so the
     * values need not sum to 1. An empty list — or one whose size doesn't match
     * the current stack — means "split evenly".
     */
    val stackRatios: List<Float> = emptyList(),
    val statusBarHeight: Int = 100,
    val navBarHeight: Int = 100,
    val windowGap: Int = 0,
    val excludedPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "dev.atwm.tilingwm"
    )
)
