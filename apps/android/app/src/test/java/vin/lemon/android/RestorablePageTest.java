package vin.lemon.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class RestorablePageTest {
    private static final UrlPolicy.LocalOrigin LOCAL = new UrlPolicy.LocalOrigin(41234);

    @Test public void localRecordRebindsToTheNewRuntimePort() {
        assertEquals("local", RestorablePage.capture(LOCAL, "http://127.0.0.1:41234/?task=abc"));
        assertEquals("http://127.0.0.1:50000/",
                RestorablePage.restore(new UrlPolicy.LocalOrigin(50000), "local"));
    }

    @Test public void mirrorRecordDropsCapabilityAndFragment() {
        String record = RestorablePage.capture(
                LOCAL, "https://code.lemon.vin/p/abcdefgh#c=secret-capability");
        assertEquals("https://code.lemon.vin/p/abcdefgh", record);
        assertEquals(record, RestorablePage.restore(LOCAL, record));
        assertNull(RestorablePage.capture(LOCAL, "https://example.com/docs"));
        assertNull(RestorablePage.capture(LOCAL, "https://code.lemon.vin/p/short#c=secret"));
        assertNull(RestorablePage.restore(LOCAL, "javascript:alert(1)"));
        assertEquals(record, RestorablePage.capture(null,
                "https://code.lemon.vin/p/abcdefgh#c=secret-capability"));
        assertEquals(record, RestorablePage.restore(null, record));
        // local 不能绑定到猜测的端口，必须等本机运行时确认实际 origin。
        assertNull(RestorablePage.restore(null, "local"));
    }
}
