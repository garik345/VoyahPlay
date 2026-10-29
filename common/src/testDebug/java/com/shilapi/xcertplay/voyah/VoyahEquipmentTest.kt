package com.shilapi.xcertplay.voyah

import android.os.Binder
import android.os.Parcel
import com.shilapi.xcertplay.airplay.ultra.UltraClimateControl
import com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahEquipmentTest {
    @Test fun missingOptionalAmbientFieldsDoNotDiscardSeatAndPowertrainReadings() {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(57, code); data.enforceInterface(VoyahComfortSchema.API)
                assertEquals(1, data.readInt())
                val ordinal = data.readInt(); data.readInt()
                if (ordinal in setOf(171,161,162,165)) return false
                reply!!.writeNoException(); reply.writeInt(2); return true
            }
        }
        val snapshot = VoyahComfortSchema.read(binder, { true }, false)
        assertEquals(2, snapshot.getInt("FRONT_SEAT_MASS_SWITCH_LEFT"))
        assertEquals(2, snapshot.getInt("IVI_SOC_MODESET"))
        assertFalse(snapshot.has("VEHICLE_AMBIENT_LIGHT_SWITCH"))
        assertFalse(snapshot.has("STEER_WHEEL_HEAT_SWITCH"))
    }
    @Test fun piDoesNotAdvertiseAHeatedWheelButRetainsAllFourSeatControls() {
        val ru = "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c"
        val pi = "74d7de6e3d23a05e6519bce57a693dc3517177b885ad56c9be85a73ac54ea952"
        assertTrue(VoyahOemBuilds.hasHeatedWheel(ru))
        assertFalse(VoyahOemBuilds.hasHeatedWheel(pi))
        assertFalse(VoyahOemBuilds.hasHeatedWheel(null))
        val controls = VoyahUltraSettings.comfortControls(true, VoyahOemBuilds.hasHeatedWheel(pi))
        assertEquals(UltraClimateControl.entries.filterNot { it.isWheel }.toSet(), controls)
        assertEquals(UltraClimateControl.entries.toSet(), VoyahUltraSettings.comfortControls(true, true))
        assertTrue(VoyahUltraSettings.comfortControls(false, true).isEmpty())
    }

    @Test fun piSnapshotNeverQueriesTheMissingWheelAndKeepsTheOtherComfortReadings() {
        val queried = mutableListOf<Int>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(57, code)
                data.enforceInterface(VoyahComfortSchema.API)
                assertEquals(1, data.readInt())
                val ordinal = data.readInt(); data.readInt()
                assertEquals(0, data.dataAvail())
                queried += ordinal
                reply!!.writeNoException(); reply.writeInt(2)
                return true
            }
        }
        val pi = VoyahComfortSchema.read(binder, { true }, false)
        assertEquals(36, queried.size)
        assertFalse(queried.any { it in setOf(881, 891, 892) })
        assertFalse(pi.has("STEER_WHEEL_HEAT_SWITCH"))
        assertEquals(2, pi.getInt("FRONT_SEAT_MASS_SWITCH_LEFT"))
        assertEquals(2, pi.getInt("IVI_SOC_MODESET"))
        queried.clear()
        VoyahComfortSchema.read(binder, { true }, true)
        assertEquals(39, queried.size)
        assertTrue(queried.containsAll(listOf(881, 891, 892)))
    }

    @Test fun staleOemWheelValuesCannotCreateAnEquippedPiWheel() {
        val raw = mapOf("STEER_WHEEL_HEAT_SWITCH" to 2,
            "FRONT_SEAT_HEATING_SWITCH_LEFT" to 2, "FRONT_SEAT_HEATING_COMMAND_LEFT" to 3)
        val pi = VoyahComfortValues(raw, true, false)
        assertNull(pi.wheelHeatingLevel())
        assertEquals(3, pi.seatLevel(0, false))
        assertFalse(pi.display().contains("руля"))
        assertEquals(1, VoyahComfortValues(raw, true, true).wheelHeatingLevel())
    }

    @Test fun savedWheelOrdersCannotReinsertItOrRemoveThePiSettingsShortcut() {
        val available = VoyahComfortLayout.availableIds(false)
        for (mode in VoyahComfortLayout.Mode.entries) {
            val result = VoyahComfortLayout.resolve(true, mode,
                VoyahComfortLayout.Edge.entries.associateWith { listOf("wheel") }, "wheel", available)
            assertFalse(result.values.any { "wheel" in it })
            assertTrue(result.values.any { "heat_l" in it })
        }
        val ru = VoyahComfortLayout.resolve(true, VoyahComfortLayout.Mode.BOTTOM, emptyMap(), "wheel")
        assertEquals(listOf("wheel"), ru[VoyahComfortLayout.Edge.BOTTOM])
    }
}
