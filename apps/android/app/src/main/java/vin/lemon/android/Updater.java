package vin.lemon.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 应用内升级：查询本仓库最新 Release，下载与当前安装包匹配的 arm64 APK 并校验同发布的 SHA-256，
 * 然后交给系统安装器。
 *
 * <p>共存隔离策略：共存包（{@code vin.lemon.android.plus}）只匹配专属后缀
 * {@code -plus-arm64.apk} 与 {@code -plus-arm64.apk.sha256}，绝不回退下载原版
 * {@code -arm64.apk}——否则系统安装器会尝试覆盖安装原版应用或报签名冲突，污染原版软件。
 */
final class Updater {
    private static final String RELEASES_API =
            "https://api.github.com/repos/johnson020202/LemonCode-Android/releases/latest";
    static final String APK_SUFFIX = BuildConfig.UPDATE_ASSET_SUFFIX;
    static final String SHA_SUFFIX = APK_SUFFIX + ".sha256";
    private static final long MAX_APK_BYTES = 400L * 1024 * 1024;
    private static final Pattern SHA256_HEX = Pattern.compile("[a-fA-F0-9]{64}");
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** 防止连点或前后台切换并发拉起多个检查/下载线程。 */
    private static final AtomicBoolean CHECK_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOAD_IN_FLIGHT = new AtomicBoolean(false);

    private Updater() {}

    interface Callback {
        void onStatus(String message);

        void onChecked(Release release);

        void onError(String message);

        void onDownloaded(File apk);
    }

    static final class Release {
        final String tag;
        final String version;
        final String apkUrl;
        final String shaUrl;
        final String notes;

        Release(String tag, String version, String apkUrl, String shaUrl, String notes) {
            this.tag = tag;
            this.version = version;
            this.apkUrl = apkUrl;
            this.shaUrl = shaUrl;
            this.notes = notes;
        }
    }

    static String currentVersion(android.content.Context context) {
        return BuildConfig.VERSION_NAME;
    }

    /** 解析 GitHub release JSON；没有匹配当前包变体的 APK 或校验文件时返回 null。 */
    static Release parseLatestRelease(String json) throws IOException {
        return parseLatestRelease(json, APK_SUFFIX);
    }

    static Release parseLatestRelease(String json, String apkSuffix) throws IOException {
        if (apkSuffix == null || !apkSuffix.endsWith(".apk")) return null;
        String shaSuffix = apkSuffix + ".sha256";
        boolean wantsPlusVariant = apkSuffix.contains("-plus-");
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception exception) {
            throw new IOException("更新信息格式不正确");
        }
        String tag = root.optString("tag_name", "");
        JSONArray assets = root.optJSONArray("assets");
        if (assets == null) return null;
        String apkUrl = null;
        String shaUrl = null;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            // 原版（-arm64.apk）与共存版（-plus-arm64.apk）双向严格区分，互不串包。
            if (!wantsPlusVariant && name.contains("-plus-")) continue;
            if (name.endsWith(apkSuffix)) apkUrl = url;
            else if (name.endsWith(shaSuffix)) shaUrl = url;
        }
        // 没有校验文件就不提供热更新：无法验证的安装包不应被安装。
        if (apkUrl == null || shaUrl == null) return null;
        return new Release(tag, tag.replaceFirst("^android-", ""), apkUrl, shaUrl,
                root.optString("body", ""));
    }

    /** 只接受本仓库 releases 下的 HTTPS 资产地址。 */
    static boolean isTrustedReleaseUrl(String url) {
        if (url == null) return false;
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            return "https".equalsIgnoreCase(parsed.getProtocol())
                    && ("github.com".equalsIgnoreCase(host)
                    || "objects.githubusercontent.com".equalsIgnoreCase(host)
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(host));
        } catch (Exception exception) {
            return false;
        }
    }

    static void check(Activity activity, Callback callback) {
        if (!CHECK_IN_FLIGHT.compareAndSet(false, true)) return;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(RELEASES_API, "application/vnd.github+json");
                final int statusCode = connection.getResponseCode();
                if (statusCode != 200) {
                    String hint = statusCode == 403 ? "（GitHub API 访问受限，请稍后再试）" : "";
                    post(activity, () -> callback.onError("无法获取更新信息（HTTP " + statusCode + "）" + hint));
                    return;
                }
                String body = readText(connection);
                Release release = parseLatestRelease(body);
                if (release == null) {
                    String missingMsg = !"vin.lemon.android".equals(BuildConfig.APPLICATION_ID)
                            ? "当前 Release 暂未附带共存版安装包（" + APK_SUFFIX + "），不会覆盖原版应用"
                            : "发布中没有可用的 arm64 安装包或校验文件";
                    post(activity, () -> callback.onError(missingMsg));
                    return;
                }
                post(activity, () -> callback.onChecked(release));
            } catch (IOException exception) {
                post(activity, () -> callback.onError("检查更新失败：" + message(exception)));
            } finally {
                CHECK_IN_FLIGHT.set(false);
                if (connection != null) connection.disconnect();
            }
        }, "lcode-update-check").start();
    }

    static void download(Activity activity, Release release, Callback callback) {
        if (!DOWNLOAD_IN_FLIGHT.compareAndSet(false, true)) {
            post(activity, () -> callback.onStatus("更新正在下载中…"));
            return;
        }
        new Thread(() -> {
            File target = new File(activity.getCacheDir(), "update" + APK_SUFFIX);
            File partial = new File(activity.getCacheDir(), "update" + APK_SUFFIX + ".part");
            HttpURLConnection connection = null;
            try {
                if (!isTrustedReleaseUrl(release.apkUrl) || !isTrustedReleaseUrl(release.shaUrl)) {
                    post(activity, () -> callback.onError("更新地址不在允许的发布域名内"));
                    return;
                }
                post(activity, () -> callback.onStatus("正在校验发布摘要…"));
                // 先拉取极小的 SHA-256 文件，再打开大体积 APK 连接，避免 APK 流在等待校验文件期间空闲超时。
                String expected = fetchExpectedSha(release.shaUrl);
                if (expected == null) {
                    post(activity, () -> callback.onError("下载校验文件失败"));
                    return;
                }
                post(activity, () -> callback.onStatus("正在下载更新…"));
                connection = open(release.apkUrl, null);
                final int downloadCode = connection.getResponseCode();
                if (!isTrustedReleaseUrl(connection.getURL().toString())) {
                    post(activity, () -> callback.onError("重定向后的更新地址不在受信任域名内"));
                    return;
                }
                if (downloadCode != 200) {
                    post(activity, () -> callback.onError("下载失败（HTTP " + downloadCode + "）"));
                    return;
                }
                long total = connection.getContentLengthLong();
                if (total > MAX_APK_BYTES) {
                    post(activity, () -> callback.onError("更新包超出大小限制"));
                    return;
                }
                String actual;
                try (InputStream input = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                     FileOutputStream output = new FileOutputStream(partial)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[128 * 1024];
                    long written = 0;
                    int lastPercent = -1;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        written += read;
                        if (written > MAX_APK_BYTES) {
                            partial.delete();
                            post(activity, () -> callback.onError("更新包超出大小限制"));
                            return;
                        }
                        if (total > 0) {
                            int percent = (int) (written * 100L / total);
                            // 仅在整数百分比变化时投递 UI 更新，消除数千次主线程消息风暴与重排卡顿。
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                final int shownPercent = percent;
                                post(activity, () -> callback.onStatus("正在下载更新… " + shownPercent + "%"));
                            }
                        }
                    }
                    actual = hex(digest.digest());
                }
                if (!actual.equalsIgnoreCase(expected)) {
                    partial.delete();
                    post(activity, () -> callback.onError("校验失败：下载的文件与发布摘要不一致，已丢弃"));
                    return;
                }
                if (target.exists() && !target.delete()) {
                    post(activity, () -> callback.onError("无法替换旧的更新文件"));
                    return;
                }
                if (!partial.renameTo(target)) {
                    post(activity, () -> callback.onError("无法保存更新包"));
                    return;
                }
                post(activity, () -> callback.onDownloaded(target));
            } catch (IOException | java.security.NoSuchAlgorithmException exception) {
                partial.delete();
                post(activity, () -> callback.onError("下载更新失败：" + message(exception)));
            } finally {
                DOWNLOAD_IN_FLIGHT.set(false);
                if (connection != null) connection.disconnect();
            }
        }, "lcode-update-download").start();
    }

    /** 读取 "<sha256>  <filename>" 格式的校验文件。 */
    static String parseShaFile(String text) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String candidate = trimmed.split("\\s+")[0];
            if (SHA256_HEX.matcher(candidate).matches()) return candidate.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String fetchExpectedSha(String url) throws IOException {
        HttpURLConnection connection = open(url, null);
        try {
            if (connection.getResponseCode() != 200) return null;
            if (!isTrustedReleaseUrl(connection.getURL().toString())) return null;
            return parseShaFile(readText(connection));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 交给系统安装器。Android 8+ 需要 REQUEST_INSTALL_PACKAGES 且用户可能要先授权；
     * 未授权时引导到系统设置，而不是静默失败。
     */
    static void install(Activity activity, File apk) {
        if (!apk.isFile() || apk.length() == 0) {
            toast(activity, "更新包不存在，请重新下载");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("需要安装权限")
                    .setMessage("系统需要你允许本应用安装更新。请在接下来的设置页中开启「安装未知应用」，然后重新点击安装。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去设置", (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception exception) {
                            toast(activity, "无法打开安装权限设置");
                        }
                    })
                    .show();
            return;
        }
        // Android 7+ 用 file:// 启动安装器会被拒绝，必须经 FileProvider 以 content:// 授权单文件读取。
        Uri uri;
        try {
            uri = androidx.core.content.FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".fileprovider", apk);
        } catch (Exception exception) {
            toast(activity, "无法共享更新包：" + message(exception));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
            toast(activity, "请在系统安装界面确认更新");
        } catch (Exception exception) {
            toast(activity, "无法启动系统安装器：" + message(exception));
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "LemonCode-Android/" + BuildConfig.VERSION_NAME);
        if (accept != null) connection.setRequestProperty("Accept", accept);
        return connection;
    }

    private static String readText(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream()) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 512 * 1024) throw new IOException("响应过大");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX_DIGITS[v >>> 4];
            out[i * 2 + 1] = HEX_DIGITS[v & 0x0f];
        }
        return new String(out);
    }

    private static String message(Throwable throwable) {
        String text = throwable.getMessage();
        return text == null ? throwable.getClass().getSimpleName() : text;
    }

    private static void post(Activity activity, Runnable action) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private static void toast(Activity activity, String message) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show();
    }
}
