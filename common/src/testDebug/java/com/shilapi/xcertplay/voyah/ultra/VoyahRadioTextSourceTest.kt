package com.shilapi.xcertplay.voyah.ultra

import android.os.Parcel
import com.shilapi.xcertplay.airplay.ultra.VoyahRadioOem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoyahRadioTextSourceTest {
    // Primitive values from the real TX8 reply captured on 25 October 2026.
    // Replay through Parcel writes: ShadowParcel cannot unmarshall Android's native byte format.
    private val captured: List<Any?> = listOf(0, 1, 29201, 1, "ROMANTIK", 9880,
        1, 1, 1, 0, 0, 0, 0, 0, 1, 9880, null, "Bee Gees - Stayin Alive".padEnd(64, '\u0000'), 10)
    private fun decode(fields: List<Any?>): VoyahRadioOem.RadioText? {
        val parcel = Parcel.obtain()
        return try {
            fields.forEach { if (it is Int) parcel.writeInt(it) else parcel.writeString(it as String?) }
            parcel.setDataPosition(0)
            VoyahRadioTextSource.decode(parcel)
        } finally { parcel.recycle() }
    }

    @Test fun actualOemReplyDecodesCurrentFrequencyAndThePaddedRadioText() {
        val text = decode(captured)!!
        assertEquals(VoyahRadioOem.Band.FM, text.band)
        assertEquals(9880L, text.rawFrequency)
        assertEquals("Bee Gees - Stayin Alive", text.text!!.trim { it <= ' ' })
    }

    @Test fun aStationWithoutRdsNeverReusesItsRetainedText() {
        val fields = captured.toMutableList(); fields[3] = 0
        assertNull(decode(fields)!!.text)
    }

    @Test fun truncatedOrExtendedRepliesAreRejected() {
        for (fields in listOf(captured.take(1), captured.dropLast(1), captured + listOf(0))) {
            try { decode(fields); fail("Malformed OEM reply was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
