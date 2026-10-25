package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Test

class Iap2CarPlayThemeTest {
    private val theme = Iap2CarPlayTheme(Iap2ClusterAsset("44C9FA31EC", 985), "19.0")
    private val config = Iap2IdentificationConfig("VoyahPlay", "Free", "Voyah", "serial", "1", "1", 3)
    private fun body(payload: ByteArray) = Iap2BodyReader.of(Iap2ParameterList.parse(payload))
    @Test fun themeIsNamedOnUsbBluetoothBootstrapAndRuntimeWithoutChangingVehicleIdentity() {
        val wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "VoyahPlay")
        val wired = config.copy(carPlayTheme = theme)
        val cases = listOf(wired to 16) + Iap2WirelessLinkRole.entries.map { wired.forWirelessLink(it, wireless) to 24 }
        for ((cfg, transport) in cases) {
            val b = body(Iap2IdentificationClient.identificationInformation(cfg).payload)
            assertEquals("44C9FA31EC", b.optionalGroup(transport)!!.optionalString(6))
            assertEquals("VoyahPlay", b.optionalString(0))
            assertEquals("Free", b.optionalString(1))
            assertEquals("Voyah", b.optionalString(2))
        }
        assertNull(body(Iap2IdentificationClient.identificationInformation(config).payload).optionalGroup(16)!!.optionalString(6))
    }
    @Test fun bothStartSessionTransportsCarrySdkAndExactAssetVersion() {
        val wired = Iap2WiredCarPlayEndpoint(listOf("fe80::1"), 7000, "pk", "950.7.1", carPlayTheme = theme)
        val wireless = Iap2WirelessCarPlayEndpoint("VoyahPlay", "password", 36, Iap2WirelessSecurity.WPA_WPA2,
            listOf("fe80::1"), 7000, "dev", "pk", "950.7.1", carPlayTheme = theme)
        for (frame in listOf(Iap2WiredControlClient.carPlayStartSession(wired),
            Iap2WirelessControlClient.carPlayStartSession(wireless))) {
            val b = body(frame.payload)
            assertEquals("19.0", b.optionalString(6))
            assertEquals("44C9FA31EC", b.optionalGroup(7)!!.optionalString(0))
            assertEquals(985L, b.optionalGroup(7)!!.optionalU32(1))
        }
    }
    @Test fun themeRejectsNulAndEmptySdk() {
        assertThrows(IllegalArgumentException::class.java) { Iap2CarPlayTheme(Iap2ClusterAsset("bad\u0000id", 1), "19.0") }
        assertThrows(IllegalArgumentException::class.java) { Iap2CarPlayTheme(theme.asset, "") }
    }
}