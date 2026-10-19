package com.shilapi.xcertplay.voyah

/** Shared by settings rendering and search, before a card or its controls are built. */
internal object VoyahSettingsVisibility {
    // These upstream cards control BYD firmware, not Voyah's native vehicle/cluster services.
    // Keep the section identifiers for upstream merges and category mapping; omit their UI.
    private val unsupportedSections = setOf(
        "BYD_ADB",
        "BYD_NAVIGATION",
        "CLUSTER_MAP",
        "AMBIENT_LIGHTING",
    )

    fun isVisible(section: String): Boolean = section !in unsupportedSections
}
