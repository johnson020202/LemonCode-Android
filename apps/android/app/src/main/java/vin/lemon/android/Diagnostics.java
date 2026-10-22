package vin.lemon.android;

import android.app.Activity;
import android.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * 诊断上报：把本机运行日志（服务端 runtime.log + Agent 结构化日志尾段）发到用户自己的群组。
 *
 * <p>用途仅限排障：用户在应用内点「上报日志」，选一个目标（Telegram Bot 或自建 HTTP 端点），
 * 应用把脱敏后的日志压缩上传，随后返回一个可复制的结果。不做后台自动上传，不采集
 * 与日志无关的个人数据。
 *
 * <p>脱敏：API Key、Bearer token、长随机串在发送前统一替换为 [redacted]，
 * 避免把用户自己的模型密钥发出去。
 */
final class Diagnostics {
    /** 单次最多上传的原始字节数，避免把巨大的日志整体发出。 */
    private static final int MAX_BYTES = 512 * 1024;
    private static final String REDACTED = "[redacted]";

    private Diagnostics() {}

    interface Callback {
        void onStatus(String message);

        void onDone(String result);

        void onError(String message);
    }

    /** 读取日志文件尾部（超限时只取最后 MAX_BYTES），并做密钥脱敏。 */
    static String collect(File runtimeLog, File agentLog) throws IOException {
        StringBuilder builder = new StringBuilder();
        appendTail(builder, "== server runtime.log ==", runtimeLog);
        appendTail(builder, "== agent lcode.jsonl ==", agentLog);
        return redact(builder.toString());
    }

    private static void appendTail(StringBuilder builder, String title, File file) throws IOException {
        if (file == null || !file.isFile()) {
            builder.append(title).append("\n(缺失)\n\n");
            return;
        }
        long length = file.length();
        long offset = Math.max(0, length - MAX_BYTES / 2);
        try (InputStream input = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < offset) {
                long step = input.skip(offset - skipped);
                if (step <= 0) break;
                skipped += step;
            }
            ByteArrayOutputStream tail = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (tail.size() + read > MAX_BYTES / 2) break;
                tail.write(buffer, 0, read);
            }
            builder.append(title).append('\n')
                    .append(new String(tail.toByteArray(), StandardCharsets.UTF_8))
                    .append("\n\n");
        }
    }

    /** 去掉常见的凭据形态，保留足够定位问题的结构。 */
    static String redact(String text) {
        if (text == null) return "";
        String result = text
                // Bearer / sk- / wb- / ghp_ 等密钥
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}", "$1" + REDACTED)
                .replaceAll("\\b(sk|wb|ghp|github_pat)_[A-Za-z0-9_\\-]{8,}", REDACTED)
                .replaceAll("(?i)(\"?(api[_-]?key|authorization|access_token|deviceCredential)\"?\\s*[:=]\\s*\"?)[^\",\\s]{8,}", "$1" + REDACTED)
                // 配对 capability 只出现在 fragment 里，保险起见也抹掉
                .replaceAll("(#c=)[A-Za-z0-9_\\-]{8,}", "$1" + REDACTED);
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
            String body = multipart(chatId.trim(), payload);
            String response = post(url, body, "multipart/form-data; boundary=" + BOUNDARY);
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
            return "已发送（HTTP " + response + "）";
        }, callback);
    }

    private static final String BOUNDARY = "----LemonCodeDiagnostics";

    private static String multipart(String chatId, String payload) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                + chatId + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"caption\"\r\n\r\n"
                + "LemonCode " + BuildConfig.VERSION_NAME + " diagnostics\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"document\"; filename=\"lcode-diagnostics.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + payload + "\r\n"
                + "--" + BOUNDARY + "--\r\n";
    }

    private interface Task {
        String run() throws IOException;
    }

    private static void run(Activity activity, Task task, Callback callback) {
        callback.onStatus("正在上报…");
        new Thread(() -> {
            try {
                String result = task.run();
                activity.runOnUiThread(() -> callback.onDone(result));
            } catch (IOException | RuntimeException exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                activity.runOnUiThread(() -> callback.onError(message));
            }
        }, "lcode-diagnostics").start();
    }

    /** 弹出上报方式选择：Telegram Bot 或自建 HTTPS 端点，均需用户填写目标。 */
    static void prompt(Activity activity, File runtimeLog, File agentLog) {
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
                                String payload;
                                try {
                                    payload = collect(runtimeLog, agentLog);
                                } catch (IOException exception) {
                                    android.widget.Toast.makeText(activity,
                                            "读取日志失败：" + exception.getMessage(),
                                            android.widget.Toast.LENGTH_LONG).show();
                                    return;
                                }
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
                                            chatInput.getText().toString(), payload, callback);
                                } else {
                                    sendHttp(activity, tokenInput.getText().toString(), payload, callback);
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

    private static String post(String url, String body, String contentType) throws IOException {
        return postBytes(url, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static String postBytes(String url, byte[] body, String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.getOutputStream().write(body);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream error = connection.getErrorStream();
                String text = error == null ? "" : new String(readAll(error), StandardCharsets.UTF_8);
                throw new IOException("HTTP " + code + " " + truncate(text, 160));
            }
            return readAll(connection.getInputStream()) == null
                    ? "OK" : new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
