package vin.lemon.android;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure Java trust, navigation and local-resource policy; no Android or network dependencies. */
public final class UrlPolicy {
    private static final Pattern ROOM = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern CAPABILITY = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern ASSET_SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");
    private static final int MAX_URL_LENGTH = 8192;

    private UrlPolicy() {}

    public enum Navigation { IN_APP, EXTERNAL_HTTPS, BLOCK }

    /** 本机运行时只监听回环地址；导航与资源解析都按该精确 origin 判断。 */
    public static final class LocalOrigin {
        private final int port;

        public LocalOrigin(int port) {
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid local port");
            this.port = port;
        }

        public int port() { return port; }
        public String origin() { return "http://127.0.0.1:" + port; }
        public String launchUrl() { return origin() + "/"; }

        public boolean allows(String value) {
            if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH) return false;
            try {
                URI uri = new URI(value);
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()) return false;
                if (uri.getRawUserInfo() != null || uri.getFragment() != null) return false;
                return "127.0.0.1".equals(uri.getHost()) && uri.getPort() == port
                        && uri.getRawPath() != null && uri.getRawPath().startsWith("/");
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
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? Navigation.EXTERNAL_HTTPS : Navigation.BLOCK;
        } catch (URISyntaxException exception) {
            return Navigation.BLOCK;
        }
    }

    public static final class PairingLink {
        private final String host;
        private final String roomId;
        private final String capability;

        private PairingLink(String host, String roomId, String capability) {
            this.host = host;
            this.roomId = roomId;
            this.capability = capability;
        }

        public String host() { return host; }
        public String roomId() { return roomId; }
        public String origin() { return "https://" + host; }
        public String path() { return "/p/" + roomId; }
        public String persistedUrl() { return origin() + path(); }
        public String launchUrl() {
            return persistedUrl() + (capability == null ? "" : "#c=" + capability);
        }
        // Deliberately no toString() containing URL / capability.
    }

    /** Queries are never forwarded; only a single validated upstream #c= token survives. */
    public static PairingLink parsePairingLink(String value) {
        try {
            URI uri = parseHttps(value);
            if (effectivePort(uri) != 443) throw invalid();
            String room = roomFromPath(uri.getRawPath());
            if (room == null) throw invalid();
            String capability = null;
            String fragment = uri.getRawFragment();
            if (fragment != null && !fragment.isEmpty()) {
                for (String entry : fragment.split("&", -1)) {
                    int separator = entry.indexOf('=');
                    String key = decode(separator < 0 ? entry : entry.substring(0, separator));
                    if (!"c".equals(key)) continue;
                    if (capability != null || separator < 0) throw invalid();
                    capability = decode(entry.substring(separator + 1));
                    if (!CAPABILITY.matcher(capability).matches()) throw invalid();
                }
                if (capability == null) throw invalid();
            }
            return new PairingLink(uri.getHost().toLowerCase(Locale.ROOT), room, capability);
        } catch (IllegalArgumentException exception) {
            // Never echo the source URL or preserve an exception whose message contains secrets.
            throw invalid();
        }
    }

    public static Navigation navigation(PairingLink selected, String value) {
        try {
            URI uri = parseHttps(value);
            return sameOrigin(selected, uri) && selected.roomId.equals(roomFromPath(uri.getRawPath()))
                    ? Navigation.IN_APP : Navigation.EXTERNAL_HTTPS;
        } catch (IllegalArgumentException exception) {
            return Navigation.BLOCK;
        }
    }

    public static boolean isSelectedOrigin(PairingLink selected, String value) {
        try {
            return sameOrigin(selected, parseHttps(value));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Return an exact APK asset, or null to leave the request alone. No catch-all SPA routing. */
    public static String localAssetPath(PairingLink selected, String value, Set<String> packagedFiles) {
        try {
            URI uri = parseHttps(value);
            if (!sameOrigin(selected, uri)) return null;
            String path = uri.getRawPath();
            if (isServerPath(path)) return null;
            if (selected.roomId.equals(roomFromPath(path))) return "index.html";
            if (path == null || !path.startsWith("/")) return null;
            String relative = path.substring(1);
            return isSafeAssetPath(relative) && packagedFiles.contains(relative) ? relative : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static boolean isServerPath(String path) {
        return path != null && (path.equals("/api") || path.startsWith("/api/")
                || path.equals("/ws") || path.startsWith("/ws/"));
    }

    public static boolean isStaticNamespace(String path) {
        return path != null && (path.equals("/index.html") || path.startsWith("/assets/")
                || path.startsWith("/material-icons/") || path.startsWith("/cmaps/")
                || path.startsWith("/standard_fonts/") || path.startsWith("/wasm/"));
    }

    public static boolean isSafeAssetPath(String relative) {
        if (relative == null || relative.isEmpty() || relative.startsWith("/") || relative.endsWith("/")) {
            return false;
        }
        for (String segment : relative.split("/", -1)) {
            if (!ASSET_SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String roomFromPath(String path) {
        if (path == null || !path.startsWith("/p/")) return null;
        String room = path.substring(3);
        while (room.endsWith("/")) room = room.substring(0, room.length() - 1);
        return ROOM.matcher(room).matches() ? room : null;
    }

    private static boolean sameOrigin(PairingLink selected, URI uri) {
        return selected != null && selected.host.equalsIgnoreCase(uri.getHost()) && effectivePort(uri) == 443;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private static URI parseHttps(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH || hasUnsafeCharacters(value, true)) {
            throw invalid();
        }
        try {
            URI uri = new URI(value);
            String authority = uri.getRawAuthority();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque() || authority == null
                    || host == null || uri.getRawUserInfo() != null || authority.indexOf('@') >= 0
                    || authority.indexOf('%') >= 0 || authority.indexOf('[') >= 0) throw invalid();
            host = host.toLowerCase(Locale.ROOT);
            validateHost(host);
            // Compare raw authority, not just URI#getPort: ':', leading zeroes and aliases fail closed.
            String expected = host;
            if (uri.getPort() != -1) {
                if (uri.getPort() < 1 || uri.getPort() > 65535) throw invalid();
                expected += ":" + uri.getPort();
            }
            if (!authority.equalsIgnoreCase(expected)) throw invalid();
            if (hasUnsafeCharacters(decode(value.replace("+", "%2B")), false)) throw invalid();
            validatePath(uri.getRawPath());
            return uri;
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    private static void validateHost(String host) {
        if (host.length() > 253 || host.endsWith(".")) throw invalid();
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) throw invalid();
        }
        // WHATWG URLs reinterpret numeric/hexadecimal final labels as IPv4. Accept only canonical IPv4.
        String last = labels[labels.length - 1];
        if (last.matches("[0-9]+") || last.startsWith("0x")) {
            if (labels.length != 4) throw invalid();
            for (String label : labels) {
                if (!label.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(label) > 255) throw invalid();
            }
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return;
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0) {
            throw invalid();
        }
        String remaining = path.substring(1);
        while (remaining.endsWith("/")) remaining = remaining.substring(0, remaining.length() - 1);
        if (!isSafeAssetPath(remaining)) throw invalid();
    }

    private static boolean hasUnsafeCharacters(String value, boolean rejectSpaces) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT
                    || character == '\\' || (rejectSpaces && (Character.isWhitespace(character)
                    || Character.isSpaceChar(character)))) return true;
        }
        return false;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException exception) {
            throw new AssertionError("UTF-8 required by Java");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid pairing URL");
    }
}
