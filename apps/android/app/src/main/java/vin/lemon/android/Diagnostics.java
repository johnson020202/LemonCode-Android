package vin.lemon.android;

import android.app.Activity;
import android.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * 诊断上报：把本机运行日志（服务端 runtime.log + Agent 结构化日志尾段）发到用户自己的群组。
 *
 * <p>用途仅限排障：用户在应用内点「上报日志」，选一个目标（Telegram Bot 或自建 HTTP 端点），
 * 应用把脱敏后的日志压缩上传，随后返回一个可复制的结果。不做后台自动上传，不采集
 * 与日志无关的个人数据。
 *
 * <p>脱敏：API Key、Bearer token、URL 敏感查询参数与长随机串在发送前统一替换为 [redacted]，
 * 避免把用户自己的模型密钥发出去。
 */
final class Diagnostics {
    /** 单次最多上传的原始字节数，避免把巨大的日志整体发出。 */
    private static final int MAX_BYTES = 512 * 1024;
    private static final String REDACTED = "[redacted]";
    private static final String BOUNDARY = "----LemonCodeDiagnostics";

    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}");
    private static final Pattern TOKEN_PREFIX_PATTERN =
            Pattern.compile("\\b(?:sk-ant-|sk-proj-|sk-|wb-|ghp_|github_pat_)[A-Za-z0-9_\\-]{8,}");
    private static final Pattern LEGACY_TOKEN_PATTERN =
            Pattern.compile("\\b(sk|wb|ghp|github_pat)_[A-Za-z0-9_\\-]{8,}");
    private static final Pattern KV_SECRET_PATTERN =
            Pattern.compile("(?i)(\"?(?:api[_-]?key|authorization|access_token|refresh_token|deviceCredential|client_secret)\"?\\s*[:=]\\s*\"?)[^\",\\s&]{8,}");
    private static final Pattern QUERY_SECRET_PATTERN =
            Pattern.compile("(?i)([?&](?:token|key|api_key|code|state|secret)=)[^&#\\s]{6,}");
    private static final Pattern FRAGMENT_CAP_PATTERN =
            Pattern.compile("(#[ck]=)[A-Za-z0-9_\\-]{8,}");

    private Diagnostics() {}

    interface Callback {
        void onStatus(String message);

        void onDone(String result);

        void onError(String message);
    }

    /** 读取日志文件尾部（超限时只取最后 MAX_BYTES），并做密钥脱敏。 */
    static String collect(File runtimeLog, File agentLog) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("package=").append(BuildConfig.APPLICATION_ID)
                .append(" version=").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n\n");
        appendTail(builder, "== server runtime.log ==", runtimeLog);
        appendTail(builder, "== agent lcode.jsonl ==", agentLog);
        return redact(builder.toString());
    }

    private static void appendTail(StringBuilder builder, String title, File file) throws IOException {
        if (file == null || !file.isFile()) {
            builder.append(title).append("\n(缺失)\n\n");
            return;
        }
        // 使用 RandomAccessFile.seek O(1) 定位日志尾段，替代原先 FileInputStream 循环 skip。
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            int limit = MAX_BYTES / 2;
            long offset = Math.max(0L, length - limit);
            int toRead = (int) Math.min(limit, length - offset);
            byte[] bytes = new byte[toRead];
            raf.seek(offset);
            raf.readFully(bytes);
            builder.append(title).append('\n')
                    .append(new String(bytes, StandardCharsets.UTF_8))
                    .append("\n\n");
        }
    }

    /** 去掉常见的凭据形态，保留足够定位问题的结构。 */
    static String redact(String text) {
        if (text == null) return "";
        String result = BEARER_PATTERN.matcher(text).replaceAll("$1" + REDACTED);
        result = TOKEN_PREFIX_PATTERN.matcher(result).replaceAll(REDACTED);
        result = LEGACY_TOKEN_PATTERN.matcher(result).replaceAll(REDACTED);
        result = KV_SECRET_PATTERN.matcher(result).replaceAll("$1" + REDACTED);
        result = QUERY_SECRET_PATTERN.matcher(result).replaceAll("$1" + REDACTED);
        result = FRAGMENT_CAP_PATTERN.matcher(result).replaceAll("$1" + REDACTED);
        return result;
    }

    /** 通过 Telegram Bot API 发送（用户自己的 bot + chatId）。 */
    static void sendTelegram(Activity activity, String botToken, String chatId,
                             String payload, Callback callback) {
        run(activity, () -> {
            if (botToken == null || botToken.trim().isEmpty() || chatId == null || chatId.trim().isEmpty()) {
                throw new IOException("请先填写 Bot Token 与 Chat ID");
            }
            String url = "https://api.telegram.org/bot" + botToken.trim() + "/sendDocument";
            byte[] body = multipartBytes(chatId.trim(), payload);
            String response = postBytes(url, body, "multipart/form-data; boundary=" + BOUNDARY);
            if (!response.contains("\"ok\":true")) {
                throw new IOException("Telegram 返回失败：" + truncate(response, 160));
            }
            return "已发送到 Telegram";
        }, callback);
    }

    static void sendTelegram(Activity activity, String botToken, String chatId,
                             File runtimeLog, Supplier<File> agentLogSupplier, Callback callback) {
        run(activity, () -> {
            if (botToken == null || botToken.trim().isEmpty() || chatId == null || chatId.trim().isEmpty()) {
                throw new IOException("请先填写 Bot Token 与 Chat ID");
            }
            String payload = collect(runtimeLog, agentLogSupplier.get());
            String url = "https://api.telegram.org/bot" + botToken.trim() + "/sendDocument";
            byte[] body = multipartBytes(chatId.trim(), payload);
            String response = postBytes(url, body, "multipart/form-data; boundary=" + BOUNDARY);
            if (!response.contains("\"ok\":true")) {
                throw new IOException("Telegram 返回失败：" + truncate(response, 160));
            }
            return "已发送到 Telegram";
        }, callback);
    }

    /** 通过用户自建 HTTP 端点发送（POST，正文为压缩后的文本）。 */
    static void sendHttp(Activity activity, String endpoint, String payload, Callback callback) {
        run(activity, () -> {
            if (endpoint == null || !endpoint.startsWith("https://")) {
                throw new IOException("端点必须是 https:// 地址");
            }
            byte[] compressed = gzip(payload.getBytes(StandardCharsets.UTF_8));
            String response = postBytes(endpoint.trim(), compressed, "application/gzip");
            return "已发送（" + truncate(response, 80) + "）";
        }, callback);
    }

    static void sendHttp(Activity activity, String endpoint, File runtimeLog,
                         Supplier<File> agentLogSupplier, Callback callback) {
        run(activity, () -> {
            if (endpoint == null || !endpoint.startsWith("https://")) {
                throw new IOException("端点必须是 https:// 地址");
            }
            String payload = collect(runtimeLog, agentLogSupplier.get());
            byte[] compressed = gzip(payload.getBytes(StandardCharsets.UTF_8));
            String response = postBytes(endpoint.trim(), compressed, "application/gzip");
            return "已发送（" + truncate(response, 80) + "）";
        }, callback);
    }

    /** 直接按 UTF-8 字节流组装 Multipart，避免把 512KB 日志做多次整段 String 拼接拷贝。 */
    static byte[] multipartBytes(String chatId, String payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.min(MAX_BYTES + 1024, (payload == null ? 0 : payload.length()) + 512));
        writeUtf8(out, "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                + chatId + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"caption\"\r\n\r\n"
                + BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_NAME + " diagnostics\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"document\"; filename=\"lcode-diagnostics.txt\"\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\n");
        if (payload != null) writeUtf8(out, payload);
        writeUtf8(out, "\r\n--" + BOUNDARY + "--\r\n");
        return out.toByteArray();
    }

    private static void writeUtf8(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private interface Task {
        String run() throws IOException;
    }

    private static void run(Activity activity, Task task, Callback callback) {
        callback.onStatus("正在上报…");
        new Thread(() -> {
            try {
                String result = task.run();
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) callback.onDone(result);
                });
            } catch (IOException | RuntimeException exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) callback.onError(message);
                });
            }
        }, "lcode-diagnostics").start();
    }

    /** 弹出上报方式选择：Telegram Bot 或自建 HTTPS 端点；agentLog 延迟到后台线程再枚举目录。 */
    static void prompt(Activity activity, File runtimeLog, Supplier<File> agentLogSupplier) {
        String[] options = {"发送到 Telegram Bot", "发送到自建 HTTPS 端点"};
        new AlertDialog.Builder(activity)
                .setTitle("上报诊断日志")
                .setItems(options, (dialog, which) -> {
                    android.widget.EditText tokenInput = new android.widget.EditText(activity);
                    android.widget.EditText chatInput = new android.widget.EditText(activity);
                    tokenInput.setHint(which == 0 ? "Bot Token（123456:ABC-...）" : "https://你的端点/upload");
                    chatInput.setHint("Chat ID（仅 Telegram 需要）");
                    android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
                    layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                    int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
                    layout.setPadding(pad, pad, pad, pad);
                    layout.addView(tokenInput);
                    if (which == 0) layout.addView(chatInput);
                    new AlertDialog.Builder(activity)
                            .setTitle(which == 0 ? "Telegram 上报" : "自建端点上报")
                            .setMessage("将上传本机服务端与 Agent 日志（已自动抹除密钥）。")
                            .setView(layout)
                            .setNegativeButton("取消", null)
                            .setPositiveButton("上报", (d2, w2) -> {
                                Callback callback = new Callback() {
                                    @Override public void onStatus(String message) {}

                                    @Override public void onDone(String result) {
                                        new AlertDialog.Builder(activity)
                                                .setTitle("上报完成")
                                                .setMessage(result)
                                                .setPositiveButton("好", null)
                                                .show();
                                    }

                                    @Override public void onError(String message) {
                                        new AlertDialog.Builder(activity)
                                                .setTitle("上报失败")
                                                .setMessage(message)
                                                .setPositiveButton("好", null)
                                                .show();
                                    }
                                };
                                if (which == 0) {
                                    sendTelegram(activity, tokenInput.getText().toString(),
                                            chatInput.getText().toString(), runtimeLog, agentLogSupplier, callback);
                                } else {
                                    sendHttp(activity, tokenInput.getText().toString(),
                                            runtimeLog, agentLogSupplier, callback);
                                }
                            })
                            .show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    private static String postBytes(String url, byte[] body, String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream error = connection.getErrorStream();
                String text = error == null ? "" : decodeResponseBytes(readAll(error));
                throw new IOException("HTTP " + code + " " + truncate(text, 160));
            }
            // 修复双重读取 InputStream Bug：
            // 原实现写成 readAll(in) == null ? "OK" : new String(readAll(in))，
            // 第一次 readAll 已把响应流消费到 EOF，第二次 readAll 永远返回空字节数组，
            // 导致 Telegram 上报永远判不出 "\"ok\":true" 而误报失败！现改为只读取一次。
            try (InputStream in = connection.getInputStream()) {
                return decodeResponseBytes(in == null ? null : readAll(in));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String decodeResponseBytes(byte[] responseBytes) {
        if (responseBytes == null || responseBytes.length == 0) return "OK";
        return new String(responseBytes, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > 256 * 1024) break;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
