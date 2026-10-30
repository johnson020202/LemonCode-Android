package vin.lemon.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class OAuthCallbackTest {
    @Test public void acceptsWellFormedCallback() {
        String url = "lcode://oauth/callback?code=abc123&state=def456";
        assertEquals(url, OAuthCallback.accept(url));
        assertEquals("lcode://oauth/callback?error=access_denied",
                OAuthCallback.accept("lcode://oauth/callback?error=access_denied"));
        // scheme/host 大小写不敏感，原样返回给页面。
        assertEquals("LCODE://OAUTH/callback?code=x",
                OAuthCallback.accept("LCODE://OAUTH/callback?code=x"));
    }

    @Test public void rejectsOtherSchemesHostsAndPaths() {
        assertNull(OAuthCallback.accept("https://oauth/callback?code=x"));
        assertNull(OAuthCallback.accept("lcode://other/callback?code=x"));
        assertNull(OAuthCallback.accept("lcode://oauth/other?code=x"));
        assertNull(OAuthCallback.accept("lcode://oauth/callback"));
        assertNull(OAuthCallback.accept("lcode://oauth/callback?"));
        assertNull(OAuthCallback.accept("lcode://user@oauth/callback?code=x"));
        assertNull(OAuthCallback.accept("javascript:alert(1)"));
        assertNull(OAuthCallback.accept(null));
        assertNull(OAuthCallback.accept(""));
        assertNull(OAuthCallback.accept("lcode://oauth/callback?" + "a".repeat(9000)));
    }
}
