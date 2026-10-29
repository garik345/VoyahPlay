package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class VoyahClusterCompatibilityTest {
    private val ru = "1a394bf793a467dd78b95ca1ed2004df62c280df4b58b043629257889d8e4f38"
    private val pi = "0ecf08b335174bf8f95b0a0aeb69bb05dd06c056f989b634b52a62f7be9fd503"
    private val sdk = "e46d3e5db0cf932cd3a39654a2f95620f2668ada8fb32b904c1684f936eca510"
    @Test fun ota72RequiresItsExactClusterAndSdkPair() {
        val cluster72 = "d5adb6e65175790406b58088bee9b39717cc182d246a09d5c1a628e1a1ad450c"
        val sdk72 = "c178682be164be4872697dbb5accdc6ea28446941b6d681ddb736f2fdb627c07"
        assertEquals("H97-PI-OTA72", VoyahClusterCompatibility.profile(cluster72, sdk72))
        assertNull(VoyahClusterCompatibility.profile(cluster72, sdk))
        for (old in listOf(ru, pi)) assertNull(VoyahClusterCompatibility.profile(old, sdk72))
        assertNull(VoyahClusterCompatibility.profile(cluster72.dropLast(1), sdk72))
    }

    @Test fun bothReviewedContainersUseTheSameSdkWithoutDisablingVerification() {
        assertEquals("H97-RU", VoyahClusterCompatibility.profile(ru, sdk))
        assertEquals("H97-PI", VoyahClusterCompatibility.profile(pi, sdk))
    }
    @Test fun knownClusterDoesNotAuthorizeAnUnknownOrMissingSdk() {
        for (apk in listOf(ru, pi)) {
            assertNull(VoyahClusterCompatibility.profile(apk, ""))
            assertNull(VoyahClusterCompatibility.profile(apk, "0" + sdk.drop(1)))
        }
    }
    @Test fun matchingSdkDoesNotAuthorizeADifferentClusterOrPartialHash() {
        for (apk in listOf("", pi.take(8), "1" + pi.drop(1), "0".repeat(64)))
            assertNull(VoyahClusterCompatibility.profile(apk, sdk))
    }
}
