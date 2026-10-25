package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraPanelsTest {
    private fun info(probe: Boolean, cluster: Boolean = false, theme: Boolean = false): Map<String, Any?> {
        val protocol = VoyahUltraProtocol({ object : UltraClimateSource {
            override fun start(onSample: (UltraClimateSample?) -> Unit) {}
            override fun close() {}
        } }, { 0L }, {}, panelProbe = probe, themeProbe = theme).forSession()
        return AirPlayInfoPlist.build(AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1",
            AirPlayDisplayConfig(1556, 674), cluster = if (cluster) AirPlayDisplayConfig(960, 360) else null,
            protocolExtension = protocol))
    }

    @Test fun optInAddsAPluginPanelWithoutAdvertisingAnotherScreenOrHidDevice() {
        val normal = info(false)
        val probed = info(true)
        assertArrayEquals(BplistCodec.encode(normal - "displayPanels"), BplistCodec.encode(probed - "displayPanels"))
        val panels = probed["displayPanels"] as List<*>
        assertEquals((normal["displayPanels"] as List<*>).single(), panels.first())
        val logical = panels.last() as Map<*, *>
        assertEquals(listOf("showsInstruments"), logical["properties"])
        assertEquals(emptyList<Any>(), logical["initialVideoStreams"])
        val plugin = (logical["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals("${VoyahUltraDisplayPanels.CLUSTER_UID}-plugin", plugin["uid"])
        assertEquals(emptyList<Any>(), plugin["videoStreams"])
    }

    @Test fun aRealAlternateStreamKeepsItsOwnPanelAndSizesTheLogicalPlugin() {
        val value = info(true, cluster = true)
        val panels = value["displayPanels"] as List<*>
        assertEquals(3, panels.size)
        val logical = panels.last() as Map<*, *>
        assertEquals(960, logical["widthPixels"])
        assertEquals(360, logical["heightPixels"])
        val uuids = (value["displays"] as List<*>).map { (it as Map<*, *>)["uuid"] }.toSet()
        for (panel in panels) for (stream in (panel as Map<*, *>)["initialVideoStreams"] as List<*>) {
            assertTrue((stream as Map<*, *>)["uuid"] in uuids)
        }
    }

    @Test fun missingScreensCannotCreateASyntheticVideoSource() {
        assertTrue(VoyahUltraDisplayPanels.build(emptyList<Any>(), true).isEmpty())
        assertTrue(VoyahUltraDisplayPanels.build(listOf(mapOf("type" to 110)), true).isEmpty())
    }

    @Test fun themeUsesCatalogDisplayIdsForScreensHidPanelsAndComposition() {
        val value = info(false, cluster = true, theme = true)
        val displays = (value["displays"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("Center_Display", "Cluster_Display"), displays.map { it["uuid"] })
        for (hid in value["hidDevices"] as List<*>) assertEquals("Center_Display", (hid as Map<*, *>)["displayUUID"])
        val panels = (value["displayPanels"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("Center_Display", "Cluster_Display"), panels.map { it["uid"] })
        val plugin = (panels[1]["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals("Cluster_Display", plugin["uid"])
        assertEquals(listOf("Cluster_Display"), plugin["videoStreams"])
        val command = AirPlayInfoPlist.viewAreaCommand(0, 1, "Center_Display")
        assertEquals("Center_Display", (command["params"] as Map<*, *>)["uuid"])
        assertEquals("test", value["name"])
    }
}
