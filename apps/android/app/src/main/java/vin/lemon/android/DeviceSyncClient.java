package vin.lemon.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 把原生层拉到的快照交给本机回环服务端写入会话库。
 *
 * <p>WebView 不持有密钥；原生层只与本机 127.0.0.1 通信，明文策略仍按
 * network_security_config 只放行回环地址。一次性 capability 与 /ws/host 共用。
 */
public final class DeviceSyncClient {
    private static final String CAPABILITY_HEADER = "x-lcode-rpc-host-capability";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String SESSION_ID_PATTERN = "[A-Za-z0-9_-]{1,200}";

    private DeviceSyncClient() {}

    /** 返回新会话 ID；失败时抛出与拷贝相同的错误分类。 */
    public static String importSnapshot(int port, String snapshotJson) throws CopyClient.SyncException {
        if (port < 1 || port > 65535) {
            throw new CopyClient.SyncException(CopyClient.Failure.REJECTED, "Invalid local port");
        }
        if (snapshotJson == null || snapshotJson.isEmpty()) {
            throw new CopyClient.SyncException(CopyClient.Failure.INVALID_RESPONSE, "Empty snapshot");
        }
        String capability = issueCapability(port);
        JSONObject response = post(port, "/api/device-sync/import", capability,
                "{\"snapshot\":" + snapshotJson + "}");
        String sessionId = response.optString("sessionId", "").trim();
        if (!sessionId.matches(SESSION_ID_PATTERN)) {
            throw new CopyClient.SyncException(CopyClient.Failure.INVALID_RESPONSE,
                    "Missing session id");
        }
        return sessionId;
    }

    private static String issueCapability(int port) throws CopyClient.SyncException {
        JSONObject response = post(port, "/api/rpc-host-capability", null, null);
        String capability = response.optString("capability", "").trim();
        if (!capability.matches("[A-Za-z0-9_-]{16,512}")) {
            throw new CopyClient.SyncException(CopyClient.Failure.INVALID_RESPONSE,
                    "Missing host capability");
        }
        return capability;
    }

    private static JSONObject post(int port, String path, String capability, String body)
            throws CopyClient.SyncException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (capability != null) connection.setRequestProperty(CAPABILITY_HEADER, capability);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String text = readLimited(stream);
            if (status == 413) {
                throw new CopyClient.SyncException(CopyClient.Failure.TOO_LARGE, "Snapshot too large");
            }
            if (status < 200 || status >= 300) {
                throw new CopyClient.SyncException(CopyClient.Failure.REJECTED,
                        "Import rejected with status " + status);
            }
            try {
                return new JSONObject(text);
            } catch (JSONException exception) {
                throw new CopyClient.SyncException(CopyClient.Failure.INVALID_RESPONSE,
                        "Invalid import response");
            }
        } catch (IOException exception) {
            throw new CopyClient.SyncException(CopyClient.Failure.REJECTED,
                    "Local import request failed");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("Response too large");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
