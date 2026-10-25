package vin.lemon.android;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LocalBridgePolicyTest {
    private static final UrlPolicy.LocalOrigin LOCAL = new UrlPolicy.LocalOrigin(31234);

    @Test public void acceptsOnlyTwoCommandsFromTheMainLocalPage() {
        for (String command : new String[]{"scan", "url"}) {
            assertTrue(LocalBridgePolicy.allows(LOCAL, command,
                    "http://127.0.0.1:31234/", "http://127.0.0.1:31234", true));
            assertTrue(LocalBridgePolicy.allows(LOCAL, command,
                    "http://127.0.0.1:31234/?task=sess_1", "http://127.0.0.1:31234", true));
        }
    }

    @Test public void rejectsOtherOriginsFramesCommandsAndSchemes() {
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31234", false));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "https://code.lemon.vin/p/room", "https://code.lemon.vin", true));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31235", true));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "http://127.0.0.1:31234/", "http://user@127.0.0.1:31234", true));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "http://127.0.0.1:31234/", "https://127.0.0.1:31234", true));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31234/p/room", true));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31234/?x=1", true));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "url",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31234/#x", true));
        for (String command : new String[]{"", "URL", "navigate", "openUrl", null}) {
            assertFalse(LocalBridgePolicy.allows(LOCAL, command,
                    "http://127.0.0.1:31234/", "http://127.0.0.1:31234", true));
        }
        assertFalse(LocalBridgePolicy.allows(null, "scan",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31234", true));
    }
}
