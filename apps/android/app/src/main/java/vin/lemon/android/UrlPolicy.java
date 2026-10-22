package vin.lemon.android;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 纯 Java 的本机来源策略；无 Android 依赖，可直接单元测试。
 *
 * <p>独立运行版本只允许 WebView 访问手机内回环地址上的本地运行时，
 * 其他 https 链接交给系统浏览器，其余协议一律拒绝。
 */
public final class UrlPolicy {
    private static final int MAX_URL_LENGTH = 8192;

    private UrlPolicy() {}

    public enum Navigation { IN_APP, EXTERNAL_HTTPS, BLOCK }

    /** 本机运行时 origin；端口由内核分配后在启动时传入。 */
    public static final class LocalOrigin {
        private final int port;

        public LocalOrigin(int port) {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid local port");
            this.port = port;
        }

        public int port() { return port; }
        public String origin() { return "http://127.0.0.1:" + port; }
        public String launchUrl() { return origin() + "/"; }

        /** 只接受本 origin 的 http GET 页面；拒绝 userinfo、fragment 与其它主机。 */
        public boolean allows(String value) {
            if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH) return false;
            try {
                URI uri = new URI(value);
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()) return false;
                if (uri.getRawUserInfo() != null || uri.getFragment() != null) return false;
                if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != port) return false;
                String path = uri.getRawPath();
                return path != null && path.startsWith("/");
            } catch (URISyntaxException exception) {
                return false;
            }
        }
    }

    public static Navigation localNavigation(LocalOrigin origin, String value) {
        if (origin == null) return Navigation.BLOCK;
        if (origin.allows(value)) return Navigation.IN_APP;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && !uri.isOpaque()
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    ? Navigation.EXTERNAL_HTTPS : Navigation.BLOCK;
        } catch (URISyntaxException exception) {
            return Navigation.BLOCK;
        }
    }
}
