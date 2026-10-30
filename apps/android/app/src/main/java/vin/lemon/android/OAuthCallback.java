package vin.lemon.android;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * OAuth 深链校验，同时接受 {@code lcode://oauth/callback} 与
 * {@code zcode://oauth/callback}。
 *
 * <p>官网 /app/oauth/login 只把 {@code zcode://} 认作合法 redirect，因此安卓
 * 实际收到的是 zcode scheme；lcode 是品牌迁移后的自有 scheme，旧链接仍会用它。
 * 该 scheme 任何应用都能构造，所以这里只做形状校验：协议、主机、路径必须
 * 完全匹配且带查询参数；真正的 state 校验仍由页面内的 OAuth 服务完成。
 */
public final class OAuthCallback {
    private static final String SCHEME = "lcode";
    private static final String LEGACY_SCHEME = "zcode";
    private static final String HOST = "oauth";
    private static final String PATH = "/callback";
    private static final int MAX_URL_LENGTH = 8192;

    private OAuthCallback() {}

    /** 返回可交给页面的回调地址；不是本应用的 OAuth 回调时返回 null。 */
    public static String accept(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) return null;
        try {
            URI uri = new URI(url);
            if (uri.isOpaque()) return null;
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!SCHEME.equalsIgnoreCase(scheme)
                    && !LEGACY_SCHEME.equalsIgnoreCase(scheme))) {
                return null;
            }
            if (!HOST.equalsIgnoreCase(uri.getHost())) return null;
            if (!PATH.equals(uri.getPath())) return null;
            if (uri.getRawUserInfo() != null) return null;
            // 没有查询参数就没有 code/state 或 error，交给页面也没有意义。
            String query = uri.getRawQuery();
            if (query == null || query.isEmpty()) return null;
            return url;
        } catch (URISyntaxException exception) {
            return null;
        }
    }
}
