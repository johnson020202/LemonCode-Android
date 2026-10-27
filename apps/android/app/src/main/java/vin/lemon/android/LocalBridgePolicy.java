package vin.lemon.android;

import java.net.URI;
import java.net.URISyntaxException;

/** 本机 WebView 可发送给原生壳的少量命令；不接受数据或任意 URL。 */
final class LocalBridgePolicy {
    private LocalBridgePolicy() {}

    /**
     * 允许的命令固定为：扫码、手输地址、发送任务通知。
     * {@code notify} 带内容参数，由 {@link NotificationPayload} 再校验一次。
     */
    static boolean isSupportedCommand(String command) {
        return "scan".equals(command) || "url".equals(command)
                || "notify".equals(command);
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
