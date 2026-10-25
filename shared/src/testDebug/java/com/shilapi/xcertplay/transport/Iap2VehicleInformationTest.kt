package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Test

/** The car is declared to the iPhone by name and engines, like LIVI's VehicleInformationComponent. */
class Iap2VehicleInformationTest {
    private val voyah = Iap2VehicleInformation("Voyah Free", listOf(Iap2VehicleInformation.GASOLINE, Iap2VehicleInformation.ELECTRIC))
    private fun identification(info: Iap2VehicleInformation?, evStatus: Boolean = false) = Iap2IdentificationConfig(
        name = "VoyahPlay", modelIdentifier = "VoyahPlay", manufacturer = "Voyah", serialNumber = "1",
        firmwareVersion = "1.0.0", hardwareVersion = "1.0", carPlayUsbInterfaceNumber = 3,
        vehicleStatusEnabled = evStatus, vehicleInformation = info)
    private fun parameters(config: Iap2IdentificationConfig) =
        Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload)

    @Test fun voyahIsDeclaredAsAPetrolAndElectricCarWithoutAStatusComponent() {
        val p = parameters(identification(voyah))
        val vehicle = Iap2ParameterList.parse(p.first(20)!!.payload)
        assertEquals("Voyah Free", String(vehicle.first(1)!!.payload).trimEnd('\u0000'))
        assertEquals(listOf(0, 2), vehicle.all(2).map { it.payload.single().toInt() })
        assertEquals("Voyah Free", String(vehicle.first(6)!!.payload).trimEnd('\u0000'))
        assertEquals("Voyah Free", String(vehicle.first(8)!!.payload).trimEnd('\u0000'))
        assertNull(p.first(21))
        val sent = p.first(6)!!.payload
        assertFalse((0 until sent.size / 2).any { ((sent[it * 2].toInt() and 0xff) shl 8 or (sent[it * 2 + 1].toInt() and 0xff)) == Iap2VehicleStatus.VEHICLE_STATUS_UPDATE })
    }

    @Test fun withoutTheUltraPilotNothingIsDeclared() {
        assertNull(parameters(identification(null)).first(20))
    }

    @Test fun bluetoothBootstrapNeverCarriesTheVehicleButTheWiFiTunnelDoes() {
        val wireless = Iap2WirelessIdentification("02:00:00:00:00:01", "VoyahPlay")
        val config = identification(voyah)
        assertNull(config.forWirelessLink(Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP, wireless).vehicleInformation)
        assertEquals(voyah, config.forWirelessLink(Iap2WirelessLinkRole.RUNTIME_TUNNEL, wireless).vehicleInformation)
    }

    @Test fun invalidEngineTypesAreRefused() {
        try { Iap2VehicleInformation("Voyah Free", listOf(7)); fail() } catch (_: IllegalArgumentException) {}
        try { Iap2VehicleInformation("Voyah Free", emptyList()); fail() } catch (_: IllegalArgumentException) {}
    }
}
