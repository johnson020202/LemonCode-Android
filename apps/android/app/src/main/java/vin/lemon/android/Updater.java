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

/**
 * 应用内升级：查询本仓库最新 Release，下载 arm64 APK 并校验同发布的 SHA-256，
 * 然后交给系统安装器。
 *
 * <p>Android 不允许应用静默安装自身（需要用户确认，部分系统还要求「安装未知应用」授权），
 * 因此这里只负责检查、下载与校验；最后一步由系统安装界面完成，并明确告知用户。
 * 校验值缺失或不匹配时拒绝安装，避免把半截文件交给安装器。
 */
final class Updater {
    private static final String RELEASES_API =
            "https://api.github.com/repos/johnson020202/LemonCode-Android/releases/latest";
    private static final String APK_SUFFIX = "-arm64.apk";
    private static final String SHA_SUFFIX = "-arm64.apk.sha256";
    private static final long MAX_APK_BYTES = 400L * 1024 * 1024;

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

    /** 解析 GitHub release JSON；没有 arm64 APK 或校验文件时返回 null。 */
    static Release parseLatestRelease(String json) throws IOException {
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
            if (name.endsWith(APK_SUFFIX)) apkUrl = url;
            else if (name.endsWith(SHA_SUFFIX)) shaUrl = url;
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
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(RELEASES_API, "application/vnd.github+json");
                final int statusCode = connection.getResponseCode();
                if (statusCode != 200) {
                    post(activity, () -> callback.onError("无法获取更新信息（HTTP " + statusCode + "）"));
                    return;
                }
                String body = readText(connection);
                Release release = parseLatestRelease(body);
                if (release == null) {
                    post(activity, () -> callback.onError("发布中没有可用的 arm64 安装包或校验文件"));
                    return;
                }
                post(activity, () -> callback.onChecked(release));
            } catch (IOException exception) {
                post(activity, () -> callback.onError("检查更新失败：" + message(exception)));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "lcode-update-check").start();
    }

    static void download(Activity activity, Release release, Callback callback) {
        new Thread(() -> {
            File target = new File(activity.getCacheDir(), "update" + APK_SUFFIX);
            File partial = new File(activity.getCacheDir(), "update" + APK_SUFFIX + ".part");
            HttpURLConnection connection = null;
            try {
                if (!isTrustedReleaseUrl(release.apkUrl) || !isTrustedReleaseUrl(release.shaUrl)) {
                    post(activity, () -> callback.onError("更新地址不在允许的发布域名内"));
                    return;
                }
                post(activity, () -> callback.onStatus("正在下载更新…"));
                connection = open(release.apkUrl, null);
                final int downloadCode = connection.getResponseCode();
                if (downloadCode != 200) {
                    post(activity, () -> callback.onError("下载失败（HTTP " + downloadCode + "）"));
                    return;
                }
                long total = connection.getContentLengthLong();
                if (total > MAX_APK_BYTES) {
                    post(activity, () -> callback.onError("更新包超出大小限制"));
                    return;
                }
                String expected = fetchExpectedSha(release.shaUrl);
                if (expected == null) {
                    post(activity, () -> callback.onError("下载校验文件失败"));
                    return;
                }
                String actual;
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(partial)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[128 * 1024];
                    long written = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        written += read;
                        if (written > MAX_APK_BYTES) {
                            post(activity, () -> callback.onError("更新包超出大小限制"));
                            return;
                        }
                        final long progress = written;
                        if (total > 0) {
                            post(activity, () -> callback.onStatus("正在下载更新… "
                                    + (progress * 100 / total) + "%"));
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
            if (candidate.matches("[a-fA-F0-9]{64}")) return candidate.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String fetchExpectedSha(String url) throws IOException {
        HttpURLConnection connection = open(url, null);
        try {
            if (connection.getResponseCode() != 200) return null;
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
        // Android 7+ 用 file:// 启动安装器会被拒绝（模拟器上 resolve-activity 直接找不到），
        // 必须经 FileProvider 以 content:// 授权单文件读取。
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
        connection.setRequestProperty("User-Agent", "LemonCode-Android");
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

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.ROOT, "%02x", value));
        return builder.toString();
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
