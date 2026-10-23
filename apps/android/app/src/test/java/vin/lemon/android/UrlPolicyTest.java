package vin.lemon.android;

import static org.junit.Assert.*;

import org.junit.Test;

public final class UrlPolicyTest {
    private static final UrlPolicy.LocalOrigin LOCAL = new UrlPolicy.LocalOrigin(41234);

    @Test public void acceptsOnlyTheLocalRuntimeOrigin() {
        assertEquals("http://127.0.0.1:41234", LOCAL.origin());
        assertEquals("http://127.0.0.1:41234/", LOCAL.launchUrl());
        assertTrue(LOCAL.allows("http://127.0.0.1:41234/"));
        assertTrue(LOCAL.allows("http://127.0.0.1:41234/p/room"));
        assertTrue(LOCAL.allows("http://127.0.0.1:41234/assets/app.js?v=1"));
        assertEquals(UrlPolicy.Navigation.IN_APP, UrlPolicy.localNavigation(LOCAL, "http://127.0.0.1:41234/"));
    }

    @Test public void rejectsOtherHostsPortsAndSchemes() {
        for (String value : new String[]{
                "http://127.0.0.1:41235/", "http://127.0.0.1/", "http://localhost:41234/",
                "http://[::1]:41234/", "https://127.0.0.1:41234/", "http://127.0.0.1:41234.evil/",
                "http://user@127.0.0.1:41234/", "http://127.0.0.1:41234/#frag",
                "file:///etc/passwd", "javascript:alert(1)", "data:text/html,x", "content://media/1",
                "intent://x#Intent;end", "", "not a url"}) {
            assertFalse(value, LOCAL.allows(value));
        }
    }

    @Test public void externalHttpsLinksGoToTheSystemBrowser() {
        assertEquals(UrlPolicy.Navigation.EXTERNAL_HTTPS,
                UrlPolicy.localNavigation(LOCAL, "https://example.com/docs"));
        for (String value : new String[]{"file:///data/x", "javascript:alert(1)", "http://example.com/",
                "content://x", "https://user:pass@example.com/"}) {
            assertEquals(value, UrlPolicy.Navigation.BLOCK, UrlPolicy.localNavigation(LOCAL, value));
        }
    }

    @Test public void mirrorPairingLinksStayInTheWebView() {
        assertEquals(UrlPolicy.Navigation.MIRROR,
                UrlPolicy.localNavigation(LOCAL, "https://room.example.com/p/abc#c=token"));
        assertTrue(UrlPolicy.isInApp(LOCAL, "https://room.example.com/p/abc#c=token"));
        assertEquals(UrlPolicy.Navigation.EXTERNAL_HTTPS,
                UrlPolicy.localNavigation(LOCAL, "https://example.com/p/"));
        assertEquals(UrlPolicy.Navigation.EXTERNAL_HTTPS,
                UrlPolicy.localNavigation(LOCAL, "https://example.com/docs"));
        assertEquals(UrlPolicy.Navigation.BLOCK,
                UrlPolicy.localNavigation(LOCAL, "https://user:pass@example.com/p/abc"));
        assertEquals(UrlPolicy.Navigation.BLOCK,
                UrlPolicy.localNavigation(LOCAL, "http://example.com/p/abc"));
        assertFalse(UrlPolicy.isInApp(LOCAL, "https://example.com/docs"));
    }

    @Test public void invalidPortsAndMissingOriginFailClosed() {
        for (int port : new int[]{0, -1, 65536, 100000}) {
            try {
                new UrlPolicy.LocalOrigin(port);
                fail("Should reject port " + port);
            } catch (IllegalArgumentException expected) {
                // 端口范围之外一律拒绝。
            }
        }
        assertEquals(UrlPolicy.Navigation.BLOCK, UrlPolicy.localNavigation(null, "http://127.0.0.1:41234/"));
        assertFalse(LOCAL.allows(null));
        assertFalse(LOCAL.allows("http://127.0.0.1:41234" + "a".repeat(9000)));
    }
}
