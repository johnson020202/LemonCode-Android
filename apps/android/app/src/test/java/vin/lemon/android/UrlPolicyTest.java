package vin.lemon.android;

import static org.junit.Assert.*;

import java.util.Set;
import org.junit.Test;

public final class UrlPolicyTest {
    private static final String BASE = "https://relay.example/p/room_123";
    private static final String TOKEN = "cap_123456";
    private final UrlPolicy.PairingLink selected = UrlPolicy.parsePairingLink(BASE + "#c=" + TOKEN);

    @Test public void matchesUpstreamRoomAndCapabilityGrammar() {
        assertEquals("room_123", selected.roomId());
        assertEquals(BASE + "#c=" + TOKEN, selected.launchUrl());
        assertEquals(BASE, selected.persistedUrl());
        assertEquals("https://relay.example", selected.origin());
        assertEquals("relay.example", selected.host());
        assertEquals(BASE, UrlPolicy.parsePairingLink(BASE).launchUrl());
        assertEquals(BASE, UrlPolicy.parsePairingLink(BASE + "///").persistedUrl());
        assertEquals(BASE, UrlPolicy.parsePairingLink("https://RELAY.EXAMPLE:443/p/room_123").persistedUrl());
        assertEquals(BASE + "#c=abcdefgh", UrlPolicy.parsePairingLink(BASE + "#c=%61bcdefgh").launchUrl());
    }

    @Test public void secretsNeverEnterPersistedOrCanonicalPath() {
        UrlPolicy.PairingLink link = UrlPolicy.parsePairingLink(BASE + "?token=very-secret&c=wrong_token#ignored=secret&c=" + TOKEN);
        assertEquals(BASE, link.persistedUrl());
        assertEquals(BASE + "#c=" + TOKEN, link.launchUrl());
        assertFalse(link.persistedUrl().contains("?"));
        assertFalse(link.persistedUrl().contains("#"));
        assertEquals(BASE, UrlPolicy.parsePairingLink(BASE + "?c=" + TOKEN).launchUrl());
        assertEquals(BASE, UrlPolicy.parsePairingLink(link.persistedUrl()).launchUrl());
    }

    @Test public void invalidOriginsAndAuthoritiesAreRejected() {
        String[] bad = {"", "not a url", "//relay.example/p/room_123", "http://relay.example/p/room_123",
                "https:///p/room_123", "https:relay.example/p/room_123", "https://relay.example./p/room_123",
                "https://-relay.example/p/room_123", "https://relay..example/p/room_123",
                "https://relay_example/p/room_123", "https://%72elay.example/p/room_123",
                "https://例子.example/p/room_123", "https://[::1]/p/room_123", "https://127.1/p/room_123",
                "https://2130706433/p/room_123", "https://127.000.0.1/p/room_123"};
        for (String value : bad) assertRejected(value);
        assertEquals("https://127.0.0.1", UrlPolicy.parsePairingLink("https://127.0.0.1/p/room_123").origin());
    }

    @Test public void credentialsAndNonDefaultOrAmbiguousPortsAreRejected() {
        for (String authority : new String[]{"user@relay.example", "user:password@relay.example", "@relay.example",
                "relay.example:80", "relay.example:8443", "relay.example:0", "relay.example:65536",
                "relay.example:", "relay.example:0443", "relay.example:+443", "relay.example:443:443"}) {
            assertRejected("https://" + authority + "/p/room_123");
        }
    }

    @Test public void controlCharactersAndBackslashAreRejectedEverywhere() {
        for (String value : new String[]{" " + BASE, BASE + "\n", BASE + "\r", BASE + "\t", BASE + "\u0000",
                BASE + "#c=abcdefgh%0a", BASE + "?x=%00", BASE + "?x=%7F", BASE + "?x=%C2%85",
                BASE + "?x=hello world", "https://relay.example\\@evil.example/p/room_123",
                "https://relay.example/p\\room_123"}) assertRejected(value);
    }

    @Test public void pathEscapeAndMalformedPairingAreRejected() {
        for (String path : new String[]{"/", "/p", "/p/short", "/p/" + "a".repeat(65), "/p/room_123/child",
                "/p/../room_123", "/p/%2e%2e/room_123", "/p/room_123%2f..", "/p/%252e%252e",
                "//p/room_123", "/p//room_123", "/p/room_123;other", "/P/room_123"}) {
            assertRejected("https://relay.example" + path);
        }
    }

    @Test public void malformedOrAmbiguousCapabilitiesAreRejected() {
        for (String hash : new String[]{"#cap", "#c=short", "#c=", "#c=" + "a".repeat(129),
                "#c=abcdefgh&c=ijklmnop", "#c=abcdefgh%2f", "#c=abcdefgh+", "#c=abcdefgh&%63=ijklmnop"}) {
            assertRejected(BASE + hash);
        }
    }

    @Test public void navigationStaysInCurrentOriginAndRoom() {
        assertEquals(UrlPolicy.Navigation.IN_APP, UrlPolicy.navigation(selected, BASE));
        assertEquals(UrlPolicy.Navigation.IN_APP, UrlPolicy.navigation(selected, BASE + "#tab"));
        assertEquals(UrlPolicy.Navigation.IN_APP, UrlPolicy.navigation(selected, "https://relay.example:443/p/room_123/"));
        for (String value : new String[]{"https://other.example/p/room_123", "https://relay.example.evil/p/room_123",
                "https://relay.example/p/another_room", "https://relay.example/api/status", "https://relay.example/",
                "https://relay.example:8443/p/room_123"}) {
            assertEquals(value, UrlPolicy.Navigation.EXTERNAL_HTTPS, UrlPolicy.navigation(selected, value));
        }
    }

    @Test public void externalSchemesAndUnsafeExternalLinksAreBlocked() {
        for (String value : new String[]{"http://example.com", "file:///etc/passwd", "content://provider/file",
                "intent://example.com#Intent;scheme=https;end", "javascript:alert(1)", "data:text/html,hello",
                "blob:https://relay.example/id", "mailto:user@example.com", "//example.com/path",
                "https://user@example.com/path", "https://example.com/a/../b", "https://example.com/%2e%2e/a",
                "https://example.com:65536/path", "https://example.com/path%0a"}) {
            assertEquals(value, UrlPolicy.Navigation.BLOCK, UrlPolicy.navigation(selected, value));
        }
    }

    @Test public void onlyCurrentPairingRouteFallsBackToIndex() {
        Set<String> files = Set.of("index.html", "assets/app.js", "material-icons/java.svg", "NOTICE.html", "api/fake.json");
        assertEquals("index.html", UrlPolicy.localAssetPath(selected, BASE + "?not_saved=1", files));
        assertEquals("assets/app.js", UrlPolicy.localAssetPath(selected, "https://relay.example/assets/app.js?v=1", files));
        assertEquals("material-icons/java.svg", UrlPolicy.localAssetPath(selected, "https://relay.example/material-icons/java.svg", files));
        assertEquals("NOTICE.html", UrlPolicy.localAssetPath(selected, "https://relay.example/NOTICE.html", files));
        for (String path : new String[]{"/api", "/api/status", "/api/fake.json", "/ws", "/ws/room_123",
                "/unknown", "/p/another_room", "/", "/assets/missing.js", "/assets/../index.html", "/assets/%2e%2e/index.html"}) {
            assertNull(path, UrlPolicy.localAssetPath(selected, "https://relay.example" + path, files));
        }
        assertNull(UrlPolicy.localAssetPath(selected, "https://other.example/assets/app.js", files));
        assertNull(UrlPolicy.localAssetPath(selected, "https://relay.example:8443/assets/app.js", files));
    }

    @Test public void missingStaticAssetsAreRecognizedButApiIsNeverLocal() {
        assertTrue(UrlPolicy.isStaticNamespace("/assets/missing.js"));
        assertTrue(UrlPolicy.isStaticNamespace("/material-icons/missing.svg"));
        assertFalse(UrlPolicy.isStaticNamespace("/api/file.js"));
        assertFalse(UrlPolicy.isSafeAssetPath("../outside"));
        assertFalse(UrlPolicy.isSafeAssetPath("assets/%2e%2e/a"));
        assertFalse(UrlPolicy.isSafeAssetPath("assets//app.js"));
        assertFalse(UrlPolicy.isSafeAssetPath("/absolute"));
        assertTrue(UrlPolicy.isSafeAssetPath("assets/app-v1.2_3.js"));
    }

    private static void assertRejected(String value) {
        try {
            UrlPolicy.parsePairingLink(value);
            fail("Should reject input");
        } catch (IllegalArgumentException expected) {
            // Error messages must not echo possibly secret input.
            assertEquals("Invalid pairing URL", expected.getMessage());
        }
    }
}
