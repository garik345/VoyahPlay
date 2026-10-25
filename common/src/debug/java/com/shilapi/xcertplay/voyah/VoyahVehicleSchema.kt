package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.airplay.ultra.VoyahVehicleOem

/** A VoyahClient snapshot as the phone's vehicle readings; the conversions live in [VoyahVehicleOem]. */
internal object VoyahVehicleSchema {
    fun readings(values: VoyahValues, volumeLevel: Long?, muted: Boolean?) =
        VoyahVehicleOem.readings(values.fuel, values.odometerValues(), values.tireValues(), volumeLevel, muted,
            values.battery, values.comfortValues()?.driveMode())
}
