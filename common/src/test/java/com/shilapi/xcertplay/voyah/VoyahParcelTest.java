package com.shilapi.xcertplay.voyah;

import android.os.Binder;
import android.os.Parcel;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class VoyahParcelTest {
    private JSONObject read(int code, boolean malformed) throws Exception {
        Binder fake = new Binder() {
            @Override protected boolean onTransact(int transaction, Parcel data, Parcel reply, int flags) {
                assertEquals(code, transaction);
                data.enforceInterface("com.qinggan.canbus.ICanBusService");
                assertEquals(0, data.dataAvail()); // Getters send no vehicle-control arguments.
                reply.writeNoException();
                if (code == 71) reply.writeFloat(49.6f);
                else {
                    reply.writeInt(1);
                    CanSchema.Spec spec = CanSchema.byCode(code);
                    for (int i = 0; i < spec.fields.length; i++) {
                        if (spec.types.charAt(i) == 'f') reply.writeFloat(23f);
                        else reply.writeInt(3);
                    }
                }
                if (malformed) reply.writeInt(123);
                return true;
            }
        };
        Method method = VoyahClient.class.getDeclaredMethod("readCache", android.os.IBinder.class, int.class);
        method.setAccessible(true);
        return (JSONObject) method.invoke(null, fake, code);
    }
    @Test public void parsesKnownWireLayouts() throws Exception {
        assertEquals(3, read(2, false).getInt("rRDoor"));
        assertEquals(3, read(44, false).getInt("rRWindow"));
        assertEquals(49.6, read(71, false).getDouble("value"), 0.01);
        assertEquals(23, read(9, false).getDouble("mPercentage"), 0.01);
        assertEquals(23, read(30, false).getDouble("airRightTemperature"), 0.01);
    }
    @Test public void rejectsUnexpectedParcelTail() throws Exception {
        for (int code : new int[]{71, 9, 30, 2, 44}) {
            try { read(code, true); fail("Accepted unexpected reply bytes"); }
            catch (InvocationTargetException e) { assertTrue(e.getCause() instanceof java.io.IOException); }
        }
    }
}
