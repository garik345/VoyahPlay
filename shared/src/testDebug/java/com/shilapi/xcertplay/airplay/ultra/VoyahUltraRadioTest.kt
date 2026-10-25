package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraRadioTest {
    private var now = 1_000L
    private val station = VoyahRadioOem.station("10170", "FM 101.7 MHz", "RDS Name", 3)!!
    private var radio = UltraRadioSnapshot(true, VoyahRadioOem.decode(mapOf(
        VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to 10170L,
        VoyahRadioOem.TITLE to "FM 101.7 MHz", VoyahRadioOem.STATION_NAME to "RDS Name"), 3), listOf(station))
    private var sample = UltraClimateSample(23.0, now, radio = radio)
    private fun accessory(writer: ((UltraRadioRequest) -> UltraClimateWriteResult)? = null) =
        VoyahClimateAccessory({ sample }, { now }, radioSupported = true, radioWriter = writer)
    private fun reader(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)

    @Test fun liviMediaAndNowPlayingAppearOnlyWithTheOemBackend() {
        val tree = CafConfigTree.decode(accessory().config())
        val media = tree.accessories.single { it.type.value == 0x03000001L }
        assertEquals(0x14000006L, media.services.single().type.value)
        val playing = tree.accessories.single { it.type.value == 0x0F000001L }.services.single()
        assertEquals(0x14000001L, playing.type.value)
        assertTrue(playing.controls.all { it.sender == CafControlSender.DEVICE && it.hasResponse })
        assertEquals(setOf(0x0F000032L, 0x0F000033L, 0x0F000035L, 0x0F000036L, 0x0F00004BL, 0x0F00004DL),
            playing.controls.map { it.type.value }.toSet())
        assertFalse("No unsupported TuneToFrequency", playing.controls.any { it.type.value == 0x0F00004CL })
        assertFalse("Do not copy DAB=6 into an FM/AM car", media.services.single().characteristics.any { it.type.value == 0x32000025L })
        val base = CafConfigTree.decode(VoyahClimateAccessory({ sample }, { now }).config())
        assertFalse(base.accessories.any { it.type.value == 0x03000001L || it.type.value == 0x0F000001L })
    }

    @Test fun realStationsAndReadbackReachRegisterAndUpdateNotifications() {
        val a = accessory()
        val initial = reader(a.handle(reader(CarAccessoryMessages.registerRequest(0, 7,
            CafRegistration.Identifiers(listOf(4100L, 4102L, 4103L, 4355L, 4356L, 4359L)))))!!)
        assertEquals(false, initial.valuesMap()[4100])
        assertEquals("FM:10170", initial.valuesMap()[4102])
        val items = initial.valuesMap()[4103] as List<*>
        assertEquals("FM:10170", (items.single() as Map<*, *>)["identifier"])
        assertEquals("RDS Name · FM 101.7 MHz", (items.single() as Map<*, *>)["mediaItemName"])
        assertEquals("", initial.valuesMap()[4355])
        assertEquals("", initial.valuesMap()[4356])
        assertEquals(1L, initial.valuesMap()[4359])
        assertNull(a.changed())
        sample = sample.copy(radio = radio.copy(state = radio.state!!.copy(playing = false)))
        assertEquals(mapOf(4359L to 0L), reader(a.changed()!!).valuesMap())
        sample = sample.copy(radio = UltraRadioSnapshot())
        val gone = reader(a.changed()!!).valuesMap()
        assertEquals(true, gone[4100]); assertEquals("", gone[4102]); assertEquals(emptyList<Any>(), gone[4103])
    }

    @Test fun disconnectedAndExpiredValuesDoNotBecomeAPlayingStation() {
        val a = accessory()
        now += 6_001
        val response = reader(a.handle(reader(CarAccessoryMessages.readRequest(0, 1, listOf(4100, 4102, 4103, 4359))))!!)
        assertEquals(true, response.valuesMap()[4100])
        assertEquals("", response.valuesMap()[4102])
        assertFalse(response.valuesMap().containsKey(4359L))
        assertEquals(VoyahClimateAccessory.UNAVAILABLE, response.errors()[4359])
    }

    @Test fun radioTextUpdatesTheLowerLineWithoutDuplicatingTheStationHeading() {
        val a = accessory()
        val state = radio.state!!
        sample = sample.copy(radio = radio.copy(state = VoyahRadioOem.withRadioText(state,
            VoyahRadioOem.RadioText(VoyahRadioOem.Band.FM, 10170, "Artist - Track\u0000\u0000"))))
        val values = reader(a.handle(reader(CarAccessoryMessages.registerRequest(0, 1,
            CafRegistration.Identifiers(listOf(4103L, 4355L, 4356L)))))!!).valuesMap()
        assertEquals("RDS Name · FM 101.7 MHz", ((values[4103] as List<*>).single() as Map<*, *>)["mediaItemName"])
        assertEquals("", values[4355])
        assertEquals("Artist - Track", values[4356])
        sample = sample.copy(radio = radio.copy(state = VoyahRadioOem.withRadioText(state, null)))
        assertEquals(mapOf(4356L to ""), reader(a.changed()!!).valuesMap())
    }

    @Test fun commandsAreAnsweredOnlyWithOemConfirmationAndKeepTheOpaqueTransaction() {
        val calls = arrayListOf<UltraRadioRequest>()
        val a = accessory { calls += it; UltraClimateWriteResult(it.command == VoyahRadioOem.Command.PLAY, "readback") }
        val result = reader(a.handle(reader(CarAccessoryMessages.controlRequest(0, Long.MIN_VALUE + 19,
            linkedMapOf(4360L to null, 4361L to emptyMap<String, Any>(), 9999L to null))))!!)
        assertEquals(Long.MIN_VALUE + 19, result.transactionId)
        assertTrue(result.valuesMap().containsKey(4360L)); assertNull(result.valuesMap()[4360])
        assertEquals(VoyahClimateAccessory.UNAVAILABLE, result.errors()[4361])
        assertEquals(-6735L, result.errors()[9999])
        assertEquals(listOf(VoyahRadioOem.Command.PLAY, VoyahRadioOem.Command.PAUSE), calls.map { it.command })
    }

    @Test fun malformedForeignAndUnsupportedCommandsCannotTuneTheCar() {
        var writes = 0
        val a = accessory { writes++; UltraClimateWriteResult(true, "unexpected") }
        val cases = listOf(
            4364L to mapOf("identifier" to "FM:10170", "sourceIdentifier" to "DAB"),
            4364L to mapOf("identifier" to "FM:9999", "sourceIdentifier" to VoyahRadioDescription.SOURCE),
            4364L to mapOf("identifier" to 10170, "sourceIdentifier" to VoyahRadioDescription.SOURCE),
            4366L to mapOf("identifier" to "iPhone"),
            4360L to mapOf("unexpected" to true), 4365L to mapOf("frequency" to 101700000L))
        for ((iid, parameters) in cases) {
            val response = reader(a.handle(reader(CarAccessoryMessages.controlRequest(0, 1, mapOf(iid to parameters))))!!)
            assertTrue(response.errors().containsKey(iid))
        }
        assertEquals(0, writes)
    }

    @Test fun stationSelectionUsesAnExplicitSourceAndVerifiedPlaylistIdentifier() {
        val requests = arrayListOf<UltraRadioRequest>()
        val a = accessory { requests += it; UltraClimateWriteResult(true, "OEM station readback") }
        val response = reader(a.handle(reader(CarAccessoryMessages.controlRequest(0, 9, mapOf(4364L to mapOf(
            "identifier" to station.id, "sourceIdentifier" to VoyahRadioDescription.SOURCE)))))!!)
        assertTrue(response.errors().isEmpty())
        assertEquals(UltraRadioRequest(VoyahRadioOem.Command.TUNE, "FM:10170"), requests.single())
    }

    @Test fun queueItemParsingPreservesTheOriginalOemIndexAndBand() {
        assertEquals(3, station.oemIndex)
        assertEquals("AM:999", VoyahRadioOem.station("999", "AM 999 KHz", "", 7)!!.id)
        assertNull(VoyahRadioOem.station("bad", "FM 101.7 MHz", "", 0))
        assertNull(VoyahRadioOem.station("10170", "Unknown", "", 0))
        assertNull(VoyahRadioOem.station("10170", "FM 101.7 MHz", "", 512))
    }

    @Test fun aLiveRdsNameUpdatesTheListEvenWhenItsQueueEntryHasNoNameYet() {
        val a = accessory()
        sample = sample.copy(radio = radio.copy(state = radio.state!!.copy(stationName = null),
            stations = listOf(VoyahRadioOem.station("10170", "FM 101.7 MHz", "", 3)!!)))
        val initial = reader(a.handle(reader(CarAccessoryMessages.registerRequest(0, 1,
            CafRegistration.Identifiers(listOf(4102L, 4103L, 4355L, 4356L)))))!!)
        fun label(values: Map<Long, Any?>) = ((values[4103L] as List<*>).single() as Map<*, *>)["mediaItemName"]
        assertEquals("FM 101.7 MHz", label(initial.valuesMap()))
        sample = sample.copy(radio = sample.radio!!.copy(state = radio.state!!.copy(stationName = "Маяк")))
        val update = reader(a.changed()!!).valuesMap()
        assertEquals("Маяк · FM 101.7 MHz", label(update))
        assertFalse("PS name belongs in the heading, not the RadioText line", update.containsKey(4356L))
        assertFalse(update.containsKey(4102L))
        assertFalse(update.containsKey(4355L))
        assertEquals(3, sample.radio!!.items().single().oemIndex)
    }

    @Test fun stationNamesStayWithTheirOwnBandAndFrequencyAndDoNotDuplicateFallbackTitles() {
        val other = VoyahRadioOem.station("10200", "FM 102.0 MHz", "Other station", 7)!!
        val am = VoyahRadioOem.station("10170", "AM 10170 KHz", "AM station", 8)!!
        val items = radio.copy(state = radio.state!!.copy(stationName = "Live RDS"),
            stations = listOf(other, station, am)).items()
        assertEquals(listOf("Live RDS · FM 101.7 MHz", "Other station · FM 102.0 MHz", "AM station · AM 10170 KHz"), items.map { it.listTitle })
        assertEquals(listOf(3, 7, 8), items.map { it.oemIndex })
        for (name in listOf("", "  ", "FM 101.7 MHz", "fm 101.7 mhz"))
            assertEquals("FM 101.7 MHz", station.copy(shortName = name).listTitle)
    }

    @Test fun aCurrentStationOutsideTheQueueStillShowsItsFrequencyAlongsideTheName() {
        val missing = radio.copy(stations = emptyList(), state = radio.state!!.copy(title = null)).items().single()
        assertEquals("RDS Name · FM 101.7 MHz", missing.listTitle)
        assertEquals("FM:10170", missing.id)
        assertNull(missing.oemIndex)
        val amState = VoyahRadioOem.decode(mapOf(VoyahRadioOem.BAND to "AM", VoyahRadioOem.FREQUENCY to 999L,
            VoyahRadioOem.STATION_NAME to "AM station"), 3)
        assertEquals("AM station · AM 999 kHz", UltraRadioSnapshot(true, amState).items().single().listTitle)
    }

    @Test fun numericFrequencySortCrossesNinetyAndOneHundredWithoutRetargetingOemIndices() {
        val entries = listOf(10170 to "Alpha", 8910 to "Zulu", 9870 to "Beta", 8750 to "Station")
            .mapIndexed { index, (frequency, name) ->
                VoyahRadioOem.station(frequency.toString(), "FM ${frequency / 100.0} MHz", name, index)!!
            }
        val ordered = radio.copy(stations = entries).items()
        assertEquals(listOf(8750L, 8910L, 9870L, 10170L), ordered.map { it.rawFrequency })
        assertEquals(listOf(3, 1, 2, 0), ordered.map { it.oemIndex })
        val wire = VoyahRadioDescription.value(VoyahRadioDescription.ITEMS, radio.copy(stations = entries)) as List<*>
        assertEquals(listOf(87_500_000L, 89_100_000L, 98_700_000L, 101_700_000L), wire.map { (it as Map<*, *>)["frequency"] })
        assertEquals(listOf("FM:8750", "FM:8910", "FM:9870", "FM:10170"), wire.map { (it as Map<*, *>)["identifier"] })
    }

    @Test fun numericControlSendersMatchLiviWithoutAcceptingOtherNumbers() {
        assertEquals(CafControlSender.DEVICE, CafControlSender.fromWireValue(0L))
        assertEquals(CafControlSender.ACCESSORY, CafControlSender.fromWireValue(1L))
        assertEquals(CafControlSender.DEVICE, CafControlSender.fromWireValue("device"))
        assertThrows(CafProtocolException::class.java) { CafControlSender.fromWireValue(2L) }
        assertThrows(CafProtocolException::class.java) { CafControlSender.fromWireValue(0.5) }
    }
}
