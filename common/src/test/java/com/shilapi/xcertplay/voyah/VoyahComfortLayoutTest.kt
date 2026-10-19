package com.shilapi.xcertplay.voyah

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.voyah.VoyahComfortLayout.Edge
import com.shilapi.xcertplay.voyah.VoyahComfortLayout.Mode

class VoyahComfortLayoutTest {
    private val orders = mapOf(Edge.BOTTOM to VoyahComfortLayout.ids,Edge.LEFT to listOf("light","wheel","mass_l"),
        Edge.RIGHT to listOf("aroma","mass_r","heat_r"))
    @Test fun bottomUsesItsSavedOrderAndReservesOnlyHeight() {
        val result=VoyahComfortLayout.resolve(true,Mode.BOTTOM,orders,"wheel")
        assertEquals(VoyahComfortLayout.ids,result[Edge.BOTTOM])
        assertEquals(VoyahComfortLayout.Insets(bottom=109),VoyahComfortLayout.insetsFor(result))
    }
    @Test fun bothSidesKeepIndependentButtonsAndLeaveHeightToCarPlay() {
        val result=VoyahComfortLayout.resolve(true,Mode.BOTH,orders,"wheel")
        assertEquals(listOf("aroma","mass_r","heat_r"),result[Edge.RIGHT])
        assertEquals(VoyahComfortLayout.Insets(left=109,right=109),VoyahComfortLayout.insetsFor(result))
    }
    @Test fun rightModeHasAnAccessibleSettingsEntryAfterSwitchFromLeft() {
        val result=VoyahComfortLayout.resolve(true,Mode.RIGHT,orders,"wheel")
        assertEquals(listOf("aroma","mass_r","heat_r","wheel"),result[Edge.RIGHT])
        assertEquals(VoyahComfortLayout.Insets(right=109),VoyahComfortLayout.insetsFor(result))
    }
    @Test fun selectedSettingsKeyIsNeverDuplicatedAcrossPanels() {
        val result=VoyahComfortLayout.resolve(true,Mode.BOTH,orders,"heat_r")
        assertEquals(1,result.values.flatten().count { it=="heat_r" })
    }
    @Test fun allEmptySelectionsStillAllowReturningToSettings() {
        val result=VoyahComfortLayout.resolve(true,Mode.LEFT,emptyMap(),"vent_l")
        assertEquals(mapOf(Edge.LEFT to listOf("vent_l")),result)
    }
    @Test fun disabledPanelsReserveNoArea() {
        assertEquals(VoyahComfortLayout.Insets(),VoyahComfortLayout.insetsFor(
            VoyahComfortLayout.resolve(false,Mode.BOTH,orders,"wheel")))
    }
    @Test fun invalidSavedOrderCannotCreateUnknownOrRepeatedCommands() {
        assertEquals(listOf("heat_r","wheel","light"),VoyahComfortLayout.sanitizeOrder("heat_r,wheel,heat_r,unknown,light",emptyList()))
        assertEquals(emptyList<String>(),VoyahComfortLayout.sanitizeOrder("",listOf("wheel")))
        assertEquals(listOf("wheel"),VoyahComfortLayout.sanitizeOrder(null,listOf("wheel")))
    }
    @Test fun reservedLongPressCommandsCannotBeChosenForSettings() {
        for(id in listOf("light","aroma","mass_l","mass_r")) assertFalse(id in VoyahComfortLayout.settingsKeys)
    }
    @Test fun anObsoleteSettingsKeyFallsBackToWheel() {
        assertEquals(listOf("wheel"),VoyahComfortLayout.resolve(true,Mode.BOTTOM,emptyMap(),"obsolete")[Edge.BOTTOM])
    }
}
