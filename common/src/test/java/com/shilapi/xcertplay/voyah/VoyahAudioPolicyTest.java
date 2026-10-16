package com.shilapi.xcertplay.voyah;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoyahAudioPolicyTest {
    @Test public void focusRolesMatchQingganNavigationPhoneAndAssistantGroups() {
        assertRole(1, 12, 1, 3);
        assertRole(6, 2, 11, 2);
        assertRole(22, 16, 14, 3);
    }

    @Test public void unrelatedStreamsHaveNoOemFocusPolicy() {
        assertNull(VoyahAudioPolicy.forStream(-1));
        assertNull(VoyahAudioPolicy.forStream(14));
    }

    private void assertRole(int stream, int usage, int content, int gain) {
        VoyahAudioPolicy policy = VoyahAudioPolicy.forStream(stream);
        assertNotNull(policy);
        assertEquals(usage, policy.usage);
        assertEquals(content, policy.content);
        assertEquals(gain, policy.gain);
    }
}
