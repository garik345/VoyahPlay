package com.shilapi.xcertplay.voyah.root;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class VoyahFullscreenRootTest {
    private static PackageInfo pkg(String name, Signature... signatures) {
        PackageInfo p = new PackageInfo(); p.packageName = name; p.signatures = signatures; return p;
    }
    private static PackageInfo own(Signature... signatures) { return pkg(RootHelperProtocol.PACKAGE, signatures); }
    private final Signature a = new Signature(new byte[]{1, 2}), b = new Signature(new byte[]{3, 4});

    @Test public void uidMigrationRequiresExactPackageAndCertificateSet() {
        assertTrue(RootHelperIdentity.sameSigner(own(a), own(new Signature(new byte[]{1, 2}))));
        assertTrue(RootHelperIdentity.sameSigner(own(a, b), own(b, a)));
        assertFalse(RootHelperIdentity.sameSigner(own(a), own(b)));
        assertFalse(RootHelperIdentity.sameSigner(own(a), pkg("other.package", a)));
        assertFalse(RootHelperIdentity.sameSigner(pkg("other.package", a), own(a)));
        assertFalse(RootHelperIdentity.sameSigner(own(a, b), own(a)));
    }
    @Test public void uidMigrationRejectsMissingAndDuplicatedCertificates() {
        assertFalse(RootHelperIdentity.sameSigner(null, own(a)));
        assertFalse(RootHelperIdentity.sameSigner(own(a), null));
        assertFalse(RootHelperIdentity.sameSigner(own(), own()));
        assertFalse(RootHelperIdentity.sameSigner(own((Signature[]) null), own(a)));
        assertFalse(RootHelperIdentity.sameSigner(own(a), own((Signature) null)));
        assertFalse(RootHelperIdentity.sameSigner(own(a, a), own(a, a)));
    }
    private static JSONObject probe() throws Exception {
        return new JSONObject().put("version", "1.0").put("pid", 989).put("now_ms", 9000).put("active", true).put("error", "");
    }
    @Test public void topAcknowledgementRequiresFreshCurrentSystemProcess() throws Exception {
        assertTrue(TopBoundsRoot.ready(probe().toString(), 10000, 989, true));
        assertFalse(TopBoundsRoot.ready(probe().toString(), 10000, 990, true));
        assertFalse(TopBoundsRoot.ready(probe().toString(), 12000, 989, true));
        assertFalse(TopBoundsRoot.ready(probe().toString(), 8000, 989, true));
        assertFalse(TopBoundsRoot.ready(probe().put("now_ms", -1).toString(), 1000, 989, true));
    }
    @Test public void topAcknowledgementRejectsInactiveMalformedAndErrorReports() throws Exception {
        assertFalse(TopBoundsRoot.ready(probe().put("active", false).toString(), 10000, 989, true));
        assertFalse(TopBoundsRoot.ready(probe().put("error", "hook failed").toString(), 10000, 989, true));
        assertFalse(TopBoundsRoot.ready(probe().put("version", "old").toString(), 10000, 989, true));
        assertFalse(TopBoundsRoot.ready("{}", 10000, 989, true));
        assertFalse(TopBoundsRoot.ready("null", 10000, 989, true));
        assertFalse(TopBoundsRoot.ready(null, 10000, 989, true));
        assertFalse(TopBoundsRoot.ready(new String(new char[4097]), 10000, 989, true));
    }
    @Test public void newCommandPreservesWireOrdinalsAndRejectsOtherPeers() throws Exception {
        assertEquals(13, RootHelperProtocol.Action.TOP_POLICY.ordinal());
        assertEquals(10, RootHelperProtocol.Action.TWEAKS_POLICY.ordinal());
        assertTrue(RootHelperProtocol.authorized(10114, 10114, 10114, RootHelperProtocol.Action.TOP_POLICY));
        assertFalse(RootHelperProtocol.authorized(10115, 10114, 10114, RootHelperProtocol.Action.TOP_POLICY));
        assertFalse(RootHelperProtocol.authorized(10114, 10114, 10115, RootHelperProtocol.Action.TOP_POLICY));
        assertFalse(RootHelperProtocol.authorized(0, 10114, 10114, RootHelperProtocol.Action.TOP_POLICY));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RootHelperProtocol.writeRequest(out, new RootHelperProtocol.Request(RootHelperProtocol.Action.TOP_POLICY, true, false));
        assertTrue(RootHelperProtocol.readRequest(new ByteArrayInputStream(out.toByteArray())).active);
        try { new RootHelperProtocol.Request(RootHelperProtocol.Action.TOP_POLICY, true, true); fail("hidden must be false"); }
        catch (IllegalArgumentException expected) { }
    }
}
