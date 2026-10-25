package vin.lemon.android;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ScanPayloadTest {
    private static final String TOKEN = "abcdefghijklmnop";

    @Test public void parsesCopyQr() {
        ScanPayload payload = ScanPayload.parse(
                "http://192.168.1.20:41234/sync/sessions/sess_abc-123#k=" + TOKEN);
        assertEquals(ScanPayload.Kind.COPY, payload.kind());
        assertEquals("192.168.1.20", payload.host());
        assertEquals(41234, payload.port());
        assertEquals("sess_abc-123", payload.sessionId());
        assertEquals(TOKEN, payload.token());
        assertEquals("/sync/sessions/sess_abc-123", payload.requestPath());
        assertFalse(payload.toString().contains(TOKEN));
    }

    @Test public void parsesCopyQrWithEscapedCharacters() {
        ScanPayload payload = ScanPayload.parse(
                "http://192.168.1.20:41234/sync/sessions/sess%5Fabc#k=" + TOKEN);
        assertEquals(ScanPayload.Kind.COPY, payload.kind());
        assertEquals("sess_abc", payload.sessionId());
        assertEquals("/sync/sessions/sess_abc", payload.requestPath());
        assertEquals(TOKEN, payload.token());
    }

    @Test public void parsesMirrorLink() {
        ScanPayload payload = ScanPayload.parse("https://room.example.com/p/room-1#c=capability");
        assertEquals(ScanPayload.Kind.MIRROR, payload.kind());
        assertEquals("https://room.example.com/p/room-1#c=capability", payload.url());
    }

    @Test public void rejectsEverythingElse() {
        for (String value : new String[]{
                null, "", "not a url",
                "http://example.com/sync/sessions/sess_x#k=" + TOKEN,
                "http://192.168.1.20/sync/sessions/sess_x#k=" + TOKEN,
                "http://192.168.1.20:41234/other#k=" + TOKEN,
                "http://192.168.1.20:41234/sync/sessions/#k=" + TOKEN,
                "http://192.168.1.20:41234/sync/sessions/sess_x",
                "http://192.168.1.20:41234/sync/sessions/sess_x#k=short",
                "http://user@192.168.1.20:41234/sync/sessions/sess_x#k=" + TOKEN,
                "https://room.example.com/p/room-1",
                "https://room.example.com/docs#c=capability",
                "http://room.example.com/p/room-1#c=capability"}) {
            assertEquals(String.valueOf(value), ScanPayload.Kind.UNKNOWN, ScanPayload.parse(value).kind());
        }
    }
}
