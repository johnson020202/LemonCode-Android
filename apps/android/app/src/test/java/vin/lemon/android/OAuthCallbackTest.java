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

    @Test public void acceptsZcodeSchemeFromTheOfficialLoginPage() {
        // 官网 /app/oauth/login 只接受 zcode:// 作为 redirect，安卓必须两个 scheme 都收。
        String url = "zcode://oauth/callback?code=abc&state=def";
        assertEquals(url, OAuthCallback.accept(url));
        assertEquals("ZCODE://oauth/callback?error=denied",
                OAuthCallback.accept("ZCODE://oauth/callback?error=denied"));
        assertNull(OAuthCallback.accept("zcode://oauth/callback"));
        assertNull(OAuthCallback.accept("zcode://other/callback?code=x"));
        assertNull(OAuthCallback.accept("zcode://user@oauth/callback?code=x"));
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
