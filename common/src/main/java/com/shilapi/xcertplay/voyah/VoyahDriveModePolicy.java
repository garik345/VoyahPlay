package com.shilapi.xcertplay.voyah;

import java.util.LinkedHashMap;
import java.util.Map;

/** H97C mode/steering/accelerator tuple, as in VoyahTune's reviewed DriveModeCanPolicy. */
public final class VoyahDriveModePolicy {
    private VoyahDriveModePolicy() {}
    public static Map<String, Integer> plan(int mode, Integer individualSteering, Integer individualAccelerator) {
        final int steering, accelerator;
        switch (mode) {
            case 1: steering=2; accelerator=1; break;
            case 2: steering=2; accelerator=2; break;
            case 3: steering=3; accelerator=3; break;
            case 4: steering=2; accelerator=3; break;
            case 5:
                if (individualSteering == null || individualAccelerator == null
                        || individualSteering < 2 || individualSteering > 3
                        || individualAccelerator < 1 || individualAccelerator > 3) return null;
                steering=individualSteering; accelerator=individualAccelerator; break;
            case 6: steering=2; accelerator=2; break;
            default: return null;
        }
        Map<String, Integer> values=new LinkedHashMap<>();
        values.put("DRIVING_MODE_SET",mode);
        values.put("EPS_MODE_SET",steering);
        values.put("PROP_MODE_SET",accelerator);
        return values;
    }
}
