package com.shilapi.xcertplay.voyah

import org.junit.Assert.*
import org.junit.Test

class VoyahControlIconTest {
    @Test fun disabledFunctionsAreGreyForEveryStoredVariant() {
        for(id in VoyahComfortLayout.ids) for(variant in -1..3)
            assertEquals(VoyahControlIcon.OFF,VoyahControlIcon.tint(id,false,variant))
    }
    @Test fun confirmedSeatAndWheelStatesHaveTheirRequestedColours() {
        for(id in listOf("wheel","heat_l","heat_r")) assertEquals(VoyahControlIcon.ORANGE,VoyahControlIcon.tint(id,true,0))
        for(id in listOf("vent_l","vent_r")) assertEquals(VoyahControlIcon.BLUE,VoyahControlIcon.tint(id,true,0))
        for(id in listOf("mass_l","mass_r")) assertEquals(VoyahControlIcon.PURPLE,VoyahControlIcon.tint(id,true,0))
        assertEquals(VoyahControlIcon.GREEN,VoyahControlIcon.tint("light",true,3))
    }
    @Test fun fragranceUsesTasteAndCannotInventColourForUnknownTaste() {
        assertEquals(VoyahControlIcon.GREEN,VoyahControlIcon.tint("aroma",true,1))
        assertEquals(VoyahControlIcon.PURPLE,VoyahControlIcon.tint("aroma",true,2))
        assertEquals(VoyahControlIcon.BLUE,VoyahControlIcon.tint("aroma",true,3))
        assertEquals(VoyahControlIcon.OFF,VoyahControlIcon.tint("aroma",true,-1))
    }
}
