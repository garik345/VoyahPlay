package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoyahManeuverCodesTest {
    @Test fun usesObservedVoyahIconsRatherThanQingganEnumOrdinals() {
        val expected = mapOf(1 to 7, 2 to 3, 3 to 1, 13 to 8, 14 to 2,
            47 to 6, 48 to 4, 4 to 5, 6 to 9, 7 to 9, 28 to 9, 46 to 9)
        expected.forEach { (apple, voyah) -> assertEquals(voyah, VoyahManeuverCodes.icon(apple, 0)) }
    }
    @Test fun doesNotInventArrivalOrUnsupportedOppositeDirectionIcons() {
        for (type in listOf(0, 10, 12, 24, 25, 27, 255)) assertEquals(0, VoyahManeuverCodes.icon(type, 0))
        for (type in listOf(4, 18, 19, 26, 6, 7, 28, 46)) assertEquals(0, VoyahManeuverCodes.icon(type, 1))
    }
    @Test fun parsedRouteClearsOnEndAndStaleness() {
        var now=0L
        val route=BydHudRouteState(nanoTime={ now }, keepAcrossNoRoute=false)
        fun tlv(id: Int, vararg bytes: Int): ByteArray =
            byteArrayOf(0, (bytes.size+4).toByte(), 0, id.toByte()) + bytes.map { it.toByte() }.toByteArray()
        fun populate() {
            route.accept(0x5202, tlv(1,0,1)+tlv(3,2)+tlv(4,82,111,97,100))
            route.accept(0x5201, tlv(1,1)+tlv(10,0,0,1,44)+tlv(13,0,1))
        }
        populate()
        val turn=requireNotNull(route.currentApple())
        assertEquals(3,VoyahManeuverCodes.icon(turn.type,turn.drivingSide))
        assertEquals(300,turn.distanceMeters)
        assertEquals("Road",turn.road)
        route.accept(0x5201,tlv(1,0))
        assertNull(route.currentApple())
        populate()
        now=30_000_000_000L
        assertNull(route.currentApple())
        populate()
        route.accept(0x5201,tlv(1,2))
        assertNull(route.currentApple())
    }
}
