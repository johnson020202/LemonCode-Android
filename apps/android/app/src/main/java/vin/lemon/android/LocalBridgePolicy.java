package vin.lemon.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 本机 WebView 可发送给原生壳的少量命令；不接受数据或任意 URL。 */
final class LocalBridgePolicy {
    private static final int MAX_URL_LENGTH = 8192;

    private LocalBridgePolicy() {}

    /**
     * 允许的命令固定为：扫码、手输地址、发送任务通知、在系统浏览器打开链接、
     * 同步页面主题，以及 OAuth 回调的取件与确认握手。
     * {@code notify}、{@code open}、{@code theme} 带参数，各自再校验一次。
     */
    static boolean isSupportedCommand(String command) {
        return "scan".equals(command) || "url".equals(command)
                || "notify".equals(command) || "open".equals(command)
                || "theme".equals(command)
                || "oauth-pending".equals(command) || "oauth-ack".equals(command);
    }

    /** {@code notify}、{@code open}、{@code theme} 携带参数，其余命令是裸字符串。 */
    static String commandOf(String data) {
        if (data == null) return null;
        if ("notify".equals(data) || data.startsWith("notify ")) return "notify";
        if ("open".equals(data) || data.startsWith("open ")) return "open";
        if ("theme".equals(data) || data.startsWith("theme ")) return "theme";
        return data;
    }

    /**
     * 解析 {@code theme <light|dark>}。
     *
     * <p>App 主题可以与系统主题不同：只按系统 uiMode 决定状态栏图标，
     * 浅色界面会配上深色图标、深色界面配上浅色图标，用户看不到状态栏。
     * 底色由原生按 iOS 系统色决定，不接受页面传入任意色值。
     */
    static Boolean themeIsLight(String data) {
        if (data == null) return null;
        String body = data.substring("theme".length()).trim();
        if ("light".equals(body)) return Boolean.TRUE;
        if ("dark".equals(body)) return Boolean.FALSE;
        return null;
    }

    /**
     * 取出 {@code open <url>} 里的目标地址。
     *
     * <p>只接受纯 HTTPS：页面本身已由 {@link #allows} 限定为本机回环 origin，
     * 这里再排除 userinfo（钓鱼常用形式）与超长地址；非 HTTPS 返回 null。
     */
    static String openTarget(String data) {
        if (data == null) return null;
        String command = commandOf(data);
        if (!"open".equals(command)) return null;
        String value = data.substring("open".length()).trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) return null;
        try {
            URI uri = new URI(value);
            if (uri.isOpaque()) return null;
            if (!"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) return null;
            if (uri.getHost() == null || uri.getRawUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    static boolean allows(UrlPolicy.LocalOrigin origin, String command,
                          String pageUrl, String sourceOrigin, boolean isMainFrame) {
        if (!isMainFrame || origin == null || !origin.allows(pageUrl)) return false;
        if (!isSupportedCommand(command)) return false;
        try {
            URI source = new URI(sourceOrigin);
            String path = source.getRawPath();
            return !source.isOpaque()
                    && "http".equalsIgnoreCase(source.getScheme())
                    && "127.0.0.1".equals(source.getHost())
                    && source.getPort() == origin.port()
                    && source.getRawUserInfo() == null
                    && source.getRawQuery() == null
                    && source.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path));
        } catch (URISyntaxException | NullPointerException exception) {
            return false;
        }
    }
}
