package vin.lemon.android;

import android.annotation.SuppressLint;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 扫码结果的唯一分流点：拷贝二维码走局域网一次性拉取，镜像链接交给 WebView。
 *
 * <p>纯 Java、无 Android 依赖，便于单元测试。校验只接受电脑侧实际生成的形状，
 * 其余内容一律 UNKNOWN，避免把密钥发到未预期的地址。
 */
public final class ScanPayload {
    private static final int MAX_LENGTH = 8192;
    private static final String COPY_PATH_PREFIX = "/sync/sessions/";
    private static final Pattern IPV4 = Pattern.compile(
            "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,512}");

    public enum Kind { COPY, MIRROR, UNKNOWN }

    private final Kind kind;
    private final String host;
    private final int port;
    private final String sessionId;
    private final String token;
    private final String url;

    private ScanPayload(Kind kind, String host, int port, String sessionId, String token, String url) {
        this.kind = kind;
        this.host = host;
        this.port = port;
        this.sessionId = sessionId;
        this.token = token;
        this.url = url;
    }

    public static ScanPayload parse(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) return unknown();
        try {
            URI uri = new URI(value);
            if (uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return unknown();
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) return parseCopy(uri);
            if ("https".equalsIgnoreCase(uri.getScheme())) return parseMirror(uri, value);
            return unknown();
        } catch (URISyntaxException exception) {
            return unknown();
        }
    }

    // Lint NewApi 误报：decode(String, Charset) 虽在 API 33 才加 Charset 重载，
    // 但这里走的一直是等价的 String 重载（API 1 起就有），且 charset 名固定 UTF-8。
    @SuppressLint("NewApi")
    private static ScanPayload parseCopy(URI uri) {
        if (!IPV4.matcher(uri.getHost()).matches()) return unknown();
        int port = uri.getPort();
        if (port < 1 || port > 65535) return unknown();
        String path = uri.getRawPath();
        if (path == null || !path.startsWith(COPY_PATH_PREFIX)) return unknown();
        String encodedId = path.substring(COPY_PATH_PREFIX.length());
        if (encodedId.isEmpty() || encodedId.indexOf('/') >= 0) return unknown();
        String sessionId = URLDecoder.decode(encodedId, StandardCharsets.UTF_8);
        if (!SESSION_ID.matcher(sessionId).matches()) return unknown();
        String fragment = uri.getRawFragment();
        if (fragment == null || !fragment.startsWith("k=")) return unknown();
        String token = URLDecoder.decode(fragment.substring(2), StandardCharsets.UTF_8);
        if (!TOKEN.matcher(token).matches()) return unknown();
        return new ScanPayload(Kind.COPY, uri.getHost(), port, sessionId, token, null);
    }

    private static ScanPayload parseMirror(URI uri, String value) {
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/p/") || path.length() <= 3) return unknown();
        String fragment = uri.getRawFragment();
        if (fragment == null || !fragment.startsWith("c=") || fragment.length() <= 2) {
            return unknown();
        }
        return new ScanPayload(Kind.MIRROR, uri.getHost(), uri.getPort(), null, null, value);
    }

    private static ScanPayload unknown() {
        return new ScanPayload(Kind.UNKNOWN, null, -1, null, null, null);
    }

    public Kind kind() { return kind; }
    public String host() { return host; }
    public int port() { return port; }
    public String sessionId() { return sessionId; }
    public String token() { return token; }
    public String url() { return url; }

    /** 请求路径里的会话 ID 已通过字符集校验，可直接拼接。 */
    public String requestPath() {
        if (kind != Kind.COPY) throw new IllegalStateException("Not a copy payload");
        return COPY_PATH_PREFIX + sessionId;
    }

    /** 不输出密钥；排障时只能看到分流结果与目标地址。 */
    @Override public String toString() {
        return "ScanPayload{" + kind + ", host=" + host + ", port=" + port
                + ", sessionId=" + sessionId + "}";
    }
}
