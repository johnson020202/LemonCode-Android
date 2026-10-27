package vin.lemon.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** 冷启动恢复记录：只保留可公开的页面形状，不保存 fragment、capability 或设备凭据。 */
public final class RestorablePage {
    private static final Pattern ROOM = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private RestorablePage() {}

    /** 返回持久化值；null 表示当前页面不应自动恢复。 */
    public static String capture(UrlPolicy.LocalOrigin origin, String value) {
        if (origin == null || value == null) return null;
        if (origin.allows(value)) return "local";
        if (UrlPolicy.localNavigation(origin, value) != UrlPolicy.Navigation.MIRROR) return null;
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/p/")) return null;
            String room = path.substring(3).replaceAll("/+$", "");
            if (!ROOM.matcher(room).matches()) return null;
            return new URI("https", null, uri.getHost(), uri.getPort(), "/p/" + room, null, null)
                    .toASCIIString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    /** 将记录绑定到本次运行的实际本机端口；非法记录返回 null。 */
    public static String restore(UrlPolicy.LocalOrigin origin, String record) {
        if (origin == null || record == null) return null;
        if ("local".equals(record)) return origin.launchUrl();
        if (!record.startsWith("https://")) return null;
        return UrlPolicy.localNavigation(origin, record) == UrlPolicy.Navigation.MIRROR
                ? record : null;
    }
}
