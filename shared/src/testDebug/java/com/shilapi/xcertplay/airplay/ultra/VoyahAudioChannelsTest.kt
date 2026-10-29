package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahAudioChannelsTest {
    private val nav = UltraAudioChannel.NAVIGATION
    private val phone = UltraAudioChannel.PHONE
    private var now = 10_000L
    private var sample = UltraClimateSample(Double.NaN, 0, audioChannels = mapOf(nav to 4, phone to 7), audioChannelsSampledAtMillis = now)
    private val writes = mutableListOf<Pair<UltraAudioChannel, Int>>()
    private fun accessory() = VoyahClimateAccessory({ sample }, { now }, audioRanges = mapOf(nav to (0..15), phone to (0..20)),
        audioWriter = { c, v ->
            writes += c to v
            sample = sample.copy(audioChannels = sample.audioChannels + (c to v), audioChannelsSampledAtMillis = now)
            UltraClimateWriteResult(true, "readback")
        })
    private fun request(a: VoyahClimateAccessory, f: CafRcsFrame) = CarAccessoryMessages.reader(a.handle(CarAccessoryMessages.reader(f.rcsBody))!!.rcsBody)

    @Test fun declaredChannelsHaveUniqueIidsAndIndependentNativeRanges() {
        val tree = CafConfigTree.decode(accessory().config())
        val all = tree.accessories.flatMap { listOf(it.iid.value) + it.services.flatMap { s -> listOf(s.iid.value) + s.characteristics.map { it.iid.value } } }
        assertEquals(all.size, all.toSet().size)
        val audio = tree.accessories.single { it.iid.value == 2304L }
        assertTrue(audio.services.any { it.iid.value == nav.serviceIid })
        assertTrue(audio.services.any { it.iid.value == phone.serviceIid })
        assertTrue(writes.isEmpty())
    }
    @Test fun eachValidWriteIsEchoedEvenWhenTheValueDidNotChange() {
        val a = accessory()
        request(a, CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(nav.valueIid, phone.valueIid))))
        repeat(2) {
            val reply = request(a, CarAccessoryMessages.writeRequest(0, 2, mapOf(nav.valueIid to 5L)))
            assertEquals(0L, reply.errors()[nav.valueIid])
            val update = CarAccessoryMessages.reader(a.changed()!!.rcsBody).valuesMap()
            assertEquals(5L, update[nav.valueIid])
            assertFalse(update.containsKey(phone.valueIid))
        }
        assertEquals(listOf(nav to 5, nav to 5), writes)
    }
    @Test fun badValuesMetadataAndUnadvertisedChannelsNeverReachTheCar() {
        val a = accessory()
        for (bad in listOf<Any?>(true, null, "5", 16, -1, 1.5, Double.NaN)) {
            val reply = request(a, CarAccessoryMessages.writeRequest(0, 2, mapOf(nav.valueIid to bad)))
            assertEquals(-50L, reply.errors()[nav.valueIid])
        }
        for (iid in listOf(nav.serviceIid + 2, UltraAudioChannel.RINGTONE.valueIid))
            assertEquals(-50L, request(a, CarAccessoryMessages.writeRequest(0, 2, mapOf(iid to 1))).errors()[iid])
        assertTrue(writes.isEmpty())
    }
    @Test fun freshCanAndMainAudioCannotMakeExpiredExtraAudioFresh() {
        val a = accessory()
        now += 6_001
        sample = sample.copy(sampledAtMillis = now, volumeSampledAtMillis = now, vehicleSampledAtMillis = now)
        val reply = request(a, CarAccessoryMessages.readRequest(0, 2, listOf(nav.valueIid)))
        assertEquals(-6727L, reply.errors()[nav.valueIid])
        assertFalse(reply.valuesMap().containsKey(nav.valueIid))
    }
}
