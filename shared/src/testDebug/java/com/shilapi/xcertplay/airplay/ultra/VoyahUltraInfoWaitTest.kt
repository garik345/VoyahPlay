package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraInfoWaitTest {
    private val now = 1_000L
    private val reading = UltraClimateSample(22.5, now, hvacValues = mapOf(UltraHvacControl.DRIVER_TARGET to 22.5,
        UltraHvacControl.PASSENGER_TARGET to 23.0, UltraHvacControl.POWER to true))

    private class LateSource(private val delayMillis: Long?, private val value: UltraClimateSample) : UltraClimateSource {
        override val supportedHvac = UltraHvacControl.entries.toSet()
        override fun start(onSample: (UltraClimateSample?) -> Unit) {
            onSample(null) // An empty cache first, as the OEM source does before its first read.
            if (delayMillis != null) Thread { Thread.sleep(delayMillis); onSample(value) }.apply { isDaemon = true }.start()
        }
        override fun close() {}
    }

    private fun driverTarget(info: Map<String, Any?>): Any? {
        val vsp = info["vehicleStateProtocolInfo"] as Map<*, *>
        val plugin = (vsp["pluginConfigs"] as List<*>).single() as Map<*, *>
        val tree = CafConfigTree.decode(mapOf("accessories" to plugin["accessories"]))
        return tree.accessories.flatMap { it.services }.flatMap { it.characteristics }
            .first { it.iid.value == UltraHvacControl.DRIVER_TARGET.iid }.initialValue
    }

    @Test fun infoWaitsForTheFirstVehicleReadingAndCarriesItsValues() {
        val p = VoyahUltraProtocol({ LateSource(200, reading) }, { now }, {})
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        val info = mutableMapOf<String, Any?>(); p.decorateInfo(info)
        assertEquals(CafFormatValue.Floating(22.5), driverTarget(info))
        p.close()
    }

    @Test fun infoIsNotHeldLongerThanTheLimitWhenTheCarNeverAnswers() {
        val p = VoyahUltraProtocol({ LateSource(null, reading) }, { now }, {}, infoWaitMillis = 150)
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        val started = System.nanoTime()
        val info = mutableMapOf<String, Any?>(); p.decorateInfo(info)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $elapsed ms", elapsed in 100..1_000)
        assertNull(driverTarget(info))
        p.close()
    }

    @Test fun infoBeforeNegotiationDoesNotWait() {
        val p = VoyahUltraProtocol({ LateSource(null, reading) }, { now }, {})
        val started = System.nanoTime()
        p.decorateInfo(mutableMapOf())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
        p.close()
    }

    @Test fun aRadioCallbackDoesNotShortenTheWaitForActualClimateValues() {
        val source = object : UltraClimateSource {
            override val supportsRadio = true
            override fun start(onSample: (UltraClimateSample?) -> Unit) {
                onSample(UltraClimateSample(Double.NaN, 0, radio = UltraRadioSnapshot(connected = true), radioSampledAtMillis = now))
                Thread { Thread.sleep(150); onSample(reading) }.apply { isDaemon = true; start() }
            }
            override fun close() {}
        }
        val p = VoyahUltraProtocol({ source }, { now }, {}, infoWaitMillis = 1_000)
        try {
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
            val info = mutableMapOf<String, Any?>(); p.decorateInfo(info)
            assertEquals(CafFormatValue.Floating(22.5), driverTarget(info))
        } finally { p.close() }
    }

    private fun reader(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)

    @Test fun aRouteValueThePhoneWroteIsConfirmedOnceWithAnUpdate() {
        var sample: UltraClimateSample? = reading
        val a = VoyahClimateAccessory({ sample }, { now })
        a.handle(reader(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(262, 271, 11)))))
        val response = reader(a.handle(reader(CarAccessoryMessages.writeRequest(0, 2, mapOf(262L to true, 271L to "Навигатор"))))!!)
        assertEquals(CafCommand.WRITE_RESPONSE, response.command)
        // Without this update the phone discards its write after 0.5 s (iPhone log 22.10.2026).
        assertEquals(mapOf(262L to true, 271L to "Навигатор"), reader(a.changed()!!).valuesMap())
        assertNull("sent once, not on every publish", a.changed())
        sample = reading.copy(hvacValues = reading.hvacValues + (UltraHvacControl.DRIVER_TARGET to 24.0))
        assertEquals(mapOf(11L to 24.0), reader(a.changed()!!).valuesMap())
    }

    @Test fun aWriteOnOneStreamIsConfirmedOnTheStreamThatFollowsTheValue() {
        val store = UltraPhoneValues()
        val following = VoyahClimateAccessory({ reading }, { now }, phoneValues = store)
        val writing = VoyahClimateAccessory({ reading }, { now }, phoneValues = store)
        following.handle(reader(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(258, 262)))))
        writing.handle(reader(CarAccessoryMessages.writeRequest(0, 2, mapOf(258L to true))))
        assertNull("the writing stream does not follow 258", writing.changed())
        assertEquals(mapOf(258L to true), reader(following.changed()!!).valuesMap())
    }

    @Test fun aRewriteOfTheSameValueIsStillConfirmed() {
        val a = VoyahClimateAccessory({ reading }, { now })
        a.handle(reader(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(262)))))
        a.handle(reader(CarAccessoryMessages.writeRequest(0, 2, mapOf(262L to true))))
        assertEquals(mapOf(262L to true), reader(a.changed()!!).valuesMap())
        // iPhone 23:52: userEnabled written again with the value the car holds, then reverted after 0.5 s.
        a.handle(reader(CarAccessoryMessages.writeRequest(0, 3, mapOf(262L to true))))
        assertEquals(mapOf(262L to true), reader(a.changed()!!).valuesMap())
        assertNull(a.changed())
    }

    @Test fun aWriteBeforeTheStreamFollowedTheValueIsNotRepeated() {
        val store = UltraPhoneValues()
        val early = VoyahClimateAccessory({ reading }, { now }, phoneValues = store)
        early.handle(reader(CarAccessoryMessages.writeRequest(0, 1, mapOf(262L to false))))
        val later = VoyahClimateAccessory({ reading }, { now }, phoneValues = store)
        val registered = reader(later.handle(reader(CarAccessoryMessages.registerRequest(0, 2, CafRegistration.Identifiers(listOf(262)))))!!)
        assertEquals(mapOf(262L to false), registered.valuesMap())
        assertNull("the register response already carried it", later.changed())
    }

    @Test fun aRouteValueIsNeverACarCommand() {
        val commands = mutableListOf<Any>()
        val a = VoyahClimateAccessory({ reading }, { now }, supportedHvac = UltraHvacControl.entries.toSet(),
            hvacWriter = { control, value -> commands += control to value; UltraClimateWriteResult(true, "ok") })
        a.handle(reader(CarAccessoryMessages.writeRequest(0, 3, mapOf(258L to true, 262L to false, 270L to 1L))))
        assertTrue(commands.isEmpty())
    }
}
