package vin.lemon.android;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * OAuth 深链校验，接受 {@code lcode://oauth/callback}、{@code zcode://oauth/callback}
 * 以及当前共存包专属的 {@link BuildConfig#OAUTH_SCHEME}（如 {@code lcodeplus://oauth/callback}）。
 *
 * <p>官网 /app/oauth/login 把 {@code zcode://} 认作默认 redirect，同时共存安装场景下支持
 * 专属 scheme 无歧义定向唤起本客户端。这里做严格形状校验：协议、主机、路径必须完全匹配且带查询参数；
 * 真正的 state 校验仍由页面内的 OAuth 服务完成。
 */
public final class OAuthCallback {
    private static final String SCHEME = "lcode";
    private static final String LEGACY_SCHEME = "zcode";
    private static final String COEXIST_SCHEME = BuildConfig.OAUTH_SCHEME;
    private static final String HOST = "oauth";
    private static final String PATH = "/callback";
    private static final int MAX_URL_LENGTH = 8192;

    private OAuthCallback() {}

    /** 返回可交给页面的回调地址；不是本应用的 OAuth 回调时返回 null。 */
    public static String accept(String url) {
        return accept(url, COEXIST_SCHEME);
    }

    static String accept(String url, String customScheme) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) return null;
        try {
            URI uri = new URI(url);
            if (uri.isOpaque()) return null;
            String scheme = uri.getScheme();
            if (!isAllowedScheme(scheme, customScheme)) {
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

    private static boolean isAllowedScheme(String scheme, String customScheme) {
        if (scheme == null) return false;
        if (SCHEME.equalsIgnoreCase(scheme) || LEGACY_SCHEME.equalsIgnoreCase(scheme)) {
            return true;
        }
        return customScheme != null && !customScheme.isEmpty()
                && customScheme.equalsIgnoreCase(scheme);
    }
}
