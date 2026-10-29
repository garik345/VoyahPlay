package com.shilapi.xcertplay.hud

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Exact reviewed H97 cluster/SDK pairs; an OEM signer alone never authorizes another build. */
object VoyahClusterCompatibility {
    private const val PACKAGE = "com.qinggan.cluster"
    private const val SDK = "e46d3e5db0cf932cd3a39654a2f95620f2668ada8fb32b904c1684f936eca510"
    private val pairs = mapOf(
        ("1a394bf793a467dd78b95ca1ed2004df62c280df4b58b043629257889d8e4f38" to SDK) to "H97-RU",
        ("0ecf08b335174bf8f95b0a0aeb69bb05dd06c056f989b634b52a62f7be9fd503" to SDK) to "H97-PI",
        ("d5adb6e65175790406b58088bee9b39717cc182d246a09d5c1a628e1a1ad450c" to
            "c178682be164be4872697dbb5accdc6ea28446941b6d681ddb736f2fdb627c07") to "H97-PI-OTA72",
    )

    /** Both parts are required; this is not acceptance of arbitrary APKs signed by the OEM. */
    fun profile(clusterSha256: String, sdkSha256: String): String? =
        pairs[clusterSha256 to sdkSha256]

    /** Worker only. No binding, native commands or preference changes. */
    fun verify(context: Context): String {
        val info = requireNotNull(context.packageManager.getPackageInfo(PACKAGE, 0).applicationInfo)
        check(info.splitSourceDirs.isNullOrEmpty()) { "Unsupported split cluster APK" }
        val cluster = sha256(File(info.sourceDir))
        val sdk = sha256(File("/system/framework/QGAPI.jar"))
        return checkNotNull(profile(cluster, sdk)) { "Unsupported cluster/SDK: cluster=$cluster QGAPI=$sdk" }
    }

    private fun sha256(file: File): String {
        check(file.isFile && file.length() in 1..(64L * 1024 * 1024)) { "Missing ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
