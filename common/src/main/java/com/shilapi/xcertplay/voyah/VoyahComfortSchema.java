package com.shilapi.xcertplay.voyah;

import android.os.IBinder;
import android.os.Parcel;
import java.io.IOException;
import org.json.JSONObject;

/** Read-only TX57 schema, valid only AFTER verifying the reviewed CAN APK SHA-256. */
public final class VoyahComfortSchema {
    private VoyahComfortSchema() {}
    public static final String API = "com.qinggan.canbus.ICanBusService";
    public static final String HASH = "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";
    private static final String[] NAMES = {"HEAD_LIGHT_STATUS",
        "LOW_BEAM",
        "PARK_LIGHT",
        "FRONT_SEAT_HEATING_COMMAND_RIGHT",
        "FRONT_SEAT_HEATING_COMMAND_LEFT",
        "FRONT_SEAT_VENTILATION_COMMAND_RIGHT",
        "FRONT_SEAT_VENTILATION_COMMAND_LEFT",
        "COMBINATION_LIGHT_SWITCH",
        "FRONT_SEAT_HEATING_BATTERY_STATUS",
        "FRONT_SEAT_HEATING_SWITCH_RIGHT",
        "FRONT_SEAT_HEATING_SWITCH_LEFT",
        "FRONT_SEAT_VENTILATION_SWITCH_RIGHT",
        "FRONT_SEAT_VENTILATION_SWITCH_LEFT",
        "FRONT_SEAT_MASS_SWITCH_RIGHT",
        "FRONT_SEAT_MASS_SWITCH_LEFT",
        "FRONT_SEAT_MASS_COMMAND_RIGHT",
        "FRONT_SEAT_MASS_COMMAND_LEFT",
        "FRONT_SEAT_MASS_INTEN_RIGHT",
        "FRONT_SEAT_MASS_INTEN_LEFT",
        "FCM_SW_REQ",
        "IVI_FRAG_TASTE",
        "IVI_FRAG_TYPE",
        "IVI_FRAG_CONCERNTION",
        "FCM_SW_REQ_FB",
        "IVI_FRAG_TASTE_FB",
        "IVI_FRAG_CONCERNTION_FB",
        "FCM_DURATION_CONTROL",
        "POSITION_LAMP_SWITCH",
        "OUT_LAMP_OFF",
        "AUTO_LAMP_SWITCH",
        "STEER_WHEEL_HEAT_SWITCH",
        "STEER_WHEEL_HEAT_SWITCH_FB",
        "STEER_WHEEL_HEAT_ERROR"};
    private static final int[] ORDINALS = {76,136,137,192,193,194,195,225,228,479,480,481,482,483,484,485,486,487,488,555,556,557,558,746,747,748,850,878,879,880,881,891,892};
    private static final int[] IDS = {160,215,216,260,261,262,263,292,295,697,698,699,700,701,702,703,704,705,706,774,775,776,777,963,964,965,1067,1095,1096,1097,1098,1108,1109};
    public interface Continue { boolean live(); }
    public static JSONObject read(IBinder binder, Continue active) throws Exception {
        JSONObject result = new JSONObject();
        for (int i=0; i<NAMES.length; i++) {
            if (!active.live()) throw new IOException("Comfort read cancelled or timed out");
            Parcel data=Parcel.obtain(), reply=Parcel.obtain();
            try {
                data.writeInterfaceToken(API);
                data.writeInt(1); data.writeInt(ORDINALS[i]); data.writeInt(IDS[i]);
                if (!binder.transact(57,data,reply,0)) throw new IOException("TX57 unsupported");
                reply.readException();
                if (reply.dataAvail()!=4) throw new IOException("Unexpected TX57 reply");
                result.put(NAMES[i], reply.readInt());
            } finally { data.recycle(); reply.recycle(); }
        }
        return result;
    }
}
