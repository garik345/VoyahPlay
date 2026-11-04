package com.shilapi.xcertplay.voyah.root;

import android.content.Context;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class ClusterNaviNativeTest {
    @Test public void arFrameAndCancellationUseTheArCategoryWithoutSendingFull() throws Exception {
        Object link = link(); Contract contract = new Contract(link);
        field(link, "contract", Contract.class); field(link, "proxy", contract);
        ClusterNaviTestState.Port port = (ClusterNaviTestState.Port) link;
        port.request(ClusterNaviTestState.AR); port.frame(ClusterNaviTestState.AR); port.hide(ClusterNaviTestState.AR);
        assertEquals(Arrays.asList("request=qg.car.cluster.NAVI_AR:1", "ready=qg.car.cluster.NAVI_AR", "request=qg.car.cluster.NAVI_AR:2"), contract.calls);
    }
    @Test public void missingOrWrongPublishedServiceNeverSuppliesAController() throws Exception {
        for (IBinder binder : new IBinder[]{null, new Binder()}) {
            try { ClusterNaviNative.requireService(binder); fail("Unreviewed binder accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("no state changed")); }
        }
        Binder correct = new Binder();
        correct.attachInterface(null, "com.qinggan.cluster.IInstrumentClusterManagerService");
        assertSame(correct, ClusterNaviNative.requireService(correct));
    }
    @Test public void naviChangesShareTheMutationLockWithStopAndOtherPolicies() {
        for (RootHelperProtocol.Action action : new RootHelperProtocol.Action[]{RootHelperProtocol.Action.CLUSTER_NAVI_GRID,
                RootHelperProtocol.Action.CLUSTER_NAVI_MAP, RootHelperProtocol.Action.CLUSTER_NAVI_READY,
                RootHelperProtocol.Action.CLUSTER_STOP, RootHelperProtocol.Action.TOP_POLICY})
            assertTrue(action.toString(), VoyahRootHelper.changesState(action));
        assertFalse(VoyahRootHelper.changesState(RootHelperProtocol.Action.INFO));
        assertFalse(VoyahRootHelper.changesState(RootHelperProtocol.Action.CLUSTER_STATUS));
    }
    private Object link() throws Exception {
        return link(SystemClock.elapsedRealtime() + 60000);
    }
    private Object link(long deadline) throws Exception {
        Constructor<?> constructor = Class.forName(ClusterNaviNative.class.getName() + "$Link").getDeclaredConstructor(Context.class, long.class);
        constructor.setAccessible(true); return constructor.newInstance(RuntimeEnvironment.getApplication(), deadline);
    }
    private void field(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    public static class Contract {
        final List<String> calls = new ArrayList<>();
        final Object link; int response = 1; boolean closeAfterOpen;
        Contract(Object link) { this.link = link; }
        public void requestPresentation(String category, int value) throws Exception {
            calls.add("request=" + category + ":" + value);
            if (value == 1) {
                presentation(link, ClusterNaviPresentation.CONCERN, category, response);
                if (closeAfterOpen) presentation(link, ClusterNaviPresentation.CONCERN, category, 2);
            }
        }
        public void notifyReadyToShow(String category) { calls.add("ready=" + category); }
        public void syncReadyState(String category, int value) { calls.add("state=" + category + ":" + value); }
    }
    private static void presentation(Object link, String concern, String category, int state) throws Exception {
        Field f = link.getClass().getDeclaredField("listener"); f.setAccessible(true);
        Binder listener = (Binder) f.get(link);
        Method receive = listener.getClass().getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class);
        receive.setAccessible(true);
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.qinggan.cluster.IInstrumentClusterManagerCallback");
            data.writeString(concern); data.writeString(category); data.writeInt(state); data.setDataPosition(0);
            assertEquals(true, receive.invoke(listener, 3, data, null, IBinder.FLAG_ONEWAY));
        } finally { data.recycle(); }
    }
    @Test public void preparationWaitsForTheNativeGrantAndDefersNotificationUntilTheFrame() throws Exception {
        Object link = link(); Contract contract = new Contract(link);
        field(link, "contract", Contract.class); field(link, "proxy", contract);
        ClusterNaviTestState.Port port = (ClusterNaviTestState.Port) link;
        port.ready(true); port.request(ClusterNaviTestState.FULL);
        assertEquals(Arrays.asList("state=qg.car.cluster.NAVI:1", "request=qg.car.cluster.NAVI_FULL:1"), contract.calls);
        port.frame(ClusterNaviTestState.FULL); port.hide(ClusterNaviTestState.FULL);
        assertEquals(Arrays.asList("state=qg.car.cluster.NAVI:1", "request=qg.car.cluster.NAVI_FULL:1",
                "ready=qg.car.cluster.NAVI_FULL", "request=qg.car.cluster.NAVI_FULL:2"), contract.calls);
    }
    @Test public void nativeClosedAndOpenThenClosedCallbacksNeverConfirmAPicture() throws Exception {
        for (boolean revoke : new boolean[]{false, true}) {
            Object link = link(); Contract contract = new Contract(link);
            contract.response = revoke ? 1 : 2; contract.closeAfterOpen = revoke;
            field(link, "contract", Contract.class); field(link, "proxy", contract);
            ClusterNaviTestState.Port port = (ClusterNaviTestState.Port) link;
            try { port.request(ClusterNaviTestState.FULL); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("PRESENTATION=closed")); }
            try { port.frame(ClusterNaviTestState.FULL); fail(); } catch (IOException expected) { }
            assertEquals(Collections.singletonList("request=qg.car.cluster.NAVI_FULL:1"), contract.calls);
        }
    }
    @Test public void anExpiredTestDoesNotSendAnyPresentationRequest() throws Exception {
        Object link = link(SystemClock.elapsedRealtime()); Contract contract = new Contract(link);
        field(link, "contract", Contract.class); field(link, "proxy", contract);
        try { ((ClusterNaviTestState.Port) link).request(ClusterNaviTestState.FULL); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("PRESENTATION=expired")); }
        assertTrue(contract.calls.isEmpty());
    }
    @Test public void aLatePresentationCallbackAfterClosingCannotGrantAFrame() throws Exception {
        Object link = link(); Contract contract = new Contract(link);
        field(link, "contract", Contract.class); field(link, "proxy", contract);
        ClusterNaviTestState.Port port = (ClusterNaviTestState.Port) link; port.request(ClusterNaviTestState.FULL);
        ((AutoCloseable) link).close(); presentation(link, ClusterNaviPresentation.CONCERN, ClusterNaviTestState.FULL, 1);
        try { port.frame(ClusterNaviTestState.FULL); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("PRESENTATION=not_requested")); }
        assertFalse(contract.calls.stream().anyMatch(value -> value.startsWith("ready=")));
    }
    @Test public void callbackSnapshotsOnlyTheNavigationReadinessAndPreservesFalse() throws Exception {
        Object link = link(); Binder listener = (Binder) field(link, "listener");
        Method receive = listener.getClass().getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class);
        receive.setAccessible(true);
        for (String category : new String[]{"qg.car.cluster.MEDIA", "qg.car.cluster.NAVI"}) {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInterfaceToken("com.qinggan.cluster.IInstrumentClusterManagerCallback");
                parcel.writeString("CONCERN_ACTIVITY_STATE"); parcel.writeString(category); parcel.writeInt(1);
                Bundle snapshot = new Bundle(); snapshot.putBoolean("com.qg:activityState.visible", false); snapshot.writeToParcel(parcel, 0);
                parcel.setDataPosition(0); assertEquals(true, receive.invoke(listener, 2, parcel, null, IBinder.FLAG_ONEWAY));
                if (category.endsWith("MEDIA")) assertNull(field(link, "navi"));
                else assertFalse(((Bundle) field(link, "navi")).getBoolean("com.qg:activityState.visible"));
            } finally { parcel.recycle(); }
        }
    }
}
