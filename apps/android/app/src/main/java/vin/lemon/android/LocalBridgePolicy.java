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
        return allows(origin, command, pageUrl, sourceOrigin, isMainFrame, null);
    }

    /**
     * 同上，但额外允许镜像页（远程控制）发送 {@code notify}。
     *
     * <p>镜像页由 Worker 以 https 托管，来源不是本机回环，原实现一律拒绝，导致
     * 「远控模式下的任务通知」永远到不了系统——而远控正是手机端的主要用法。
     * 这里只对 {@code notify} 放开，且要求来源 host 与当前镜像会话 host 完全一致；
     * 扫码、打开浏览器、主题、OAuth 等本地动作仍只认本机回环，避免远程页面驱动原生 UI。
     */
    static boolean allows(UrlPolicy.LocalOrigin origin, String command,
                          String pageUrl, String sourceOrigin, boolean isMainFrame,
                          String mirrorHost) {
        if (!isMainFrame || origin == null || !isSupportedCommand(command)) return false;
        if (isLocalSource(origin, sourceOrigin)) return origin.allows(pageUrl);
        if (isMirrorSource(sourceOrigin, mirrorHost) && isMirrorPage(pageUrl, mirrorHost)) {
            // theme 也必须放行：远程控制页同样是深色界面，若它不能上报主题，
            // 原生只能退回系统 uiMode。系统浅色 + 页面深色时会给深色背景配深色
            // 图标，状态栏上的时间/信号/电量就“消失”了（小米 15 Pro 上复现，
            // 小米 10 Pro 因系统是深色而正常）。theme 只影响系统栏明暗，不放宽任何
            // 原生能力面。
            return "notify".equals(command) || "theme".equals(command);
        }
        return false;
    }

    /** 本机回环页面的来源：必须精确到当前运行时端口，且无 userinfo/query/fragment。 */
    private static boolean isLocalSource(UrlPolicy.LocalOrigin origin, String sourceOrigin) {
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

    /** 镜像会话来源：https + 与当前镜像 host 完全一致 + 无 userinfo。 */
    private static boolean isMirrorSource(String sourceOrigin, String mirrorHost) {
        if (mirrorHost == null || sourceOrigin == null) return false;
        try {
            URI source = new URI(sourceOrigin);
            return !source.isOpaque()
                    && "https".equalsIgnoreCase(source.getScheme())
                    && mirrorHost.equalsIgnoreCase(source.getHost())
                    && source.getPort() == -1
                    && source.getRawUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /** 页面本身也必须属于同一镜像 host。 */
    private static boolean isMirrorPage(String pageUrl, String mirrorHost) {
        if (mirrorHost == null || pageUrl == null) return false;
        try {
            URI page = new URI(pageUrl);
            return !page.isOpaque()
                    && "https".equalsIgnoreCase(page.getScheme())
                    && mirrorHost.equalsIgnoreCase(page.getHost())
                    && page.getRawUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
