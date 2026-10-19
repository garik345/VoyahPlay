package com.shilapi.xcertplay.voyah

import org.junit.Assert.*
import org.junit.Test

class VoyahScreenGeometryTest {
    @Test fun expandedDriverWindowReservesThePhysicalLeftEdgeAtDisplayDensity() {
        assertEquals(16, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 0, 1920, 1f))
        assertEquals(32, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 0, 1920, 2f))
        assertEquals(16, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 0, 1920, Float.NaN))
    }

    @Test fun windowedAndOtherScreensKeepTheirExistingContentArea() {
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 145, 1775, 1f))
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(0, false, false, 1920, 0, 1920, 1f))
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(0, true, true, 1920, 0, 1920, 1f))
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(1, false, true, 1920, 0, 1920, 1f))
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(2, false, true, 1920, 0, 1920, 1f))
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 0, 1775, 1f))
    }

    @Test fun invalidAndNarrowBoundsCannotReserveTheWholeContent() {
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(0, false, true, 0, 0, 1920, 1f))
        assertEquals(0, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 145, 1920, 1f))
        assertEquals(8, VoyahScreenGeometry.leftGutterPx(0, false, true, 32, 0, 32, 5f))
    }
}
