package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset

/** One catalog asset, declared consistently in identification and CarPlayStartSession. */
data class Iap2CarPlayTheme(val asset: Iap2ClusterAsset, val sdkVersion: String) {
    init {
        require('\u0000' !in asset.assetId) { "Theme identifier must not contain NUL" }
        require(sdkVersion.isNotBlank() && '\u0000' !in sdkVersion) { "Theme SDK version is required" }
    }
}
