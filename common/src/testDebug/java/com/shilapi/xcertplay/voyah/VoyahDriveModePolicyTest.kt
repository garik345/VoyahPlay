package com.shilapi.xcertplay.voyah

import org.junit.Assert.*
import org.junit.Test

class VoyahDriveModePolicyTest {
    @Test fun changingModePreservesTheReviewedSteeringAndAcceleratorTuple() {
        val targets = mapOf(1 to (2 to 1), 2 to (2 to 2), 3 to (3 to 3), 4 to (2 to 3), 6 to (2 to 2))
        for ((mode, pair) in targets) {
            val plan = VoyahDriveModePolicy.plan(mode, null, null)!!
            assertEquals(mapOf("DRIVING_MODE_SET" to mode, "EPS_MODE_SET" to pair.first, "PROP_MODE_SET" to pair.second), plan)
            assertFalse(plan.keys.any { it.contains("REGEN") || it.contains("SOC") })
        }
    }
    @Test fun individualRequiresTheSavedProfileAndNeverFallsBackToCurrentModeOrDefaults() {
        assertNull(VoyahDriveModePolicy.plan(5, null, null))
        assertNull(VoyahDriveModePolicy.plan(5, 1, 2))
        assertNull(VoyahDriveModePolicy.plan(5, 2, 4))
        val plan = VoyahDriveModePolicy.plan(5, 3, 1)!!
        assertEquals(3, plan["EPS_MODE_SET"])
        assertEquals(1, plan["PROP_MODE_SET"])
        for (bad in listOf(-1, 0, 7)) assertNull(VoyahDriveModePolicy.plan(bad, 2, 2))
    }
}
