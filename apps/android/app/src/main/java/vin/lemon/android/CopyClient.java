package vin.lemon.android;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一次性拉取电脑上的会话快照。
 *
 * <p>用原始 socket 而不是 HttpURLConnection：Android 的明文策略会拦住后者，
 * 而本规范只允许这一次局域网明文请求，不能因此全局放开 cleartext。
 * 只支持 HTTP/1.1、固定长度或 chunked 响应，超限直接失败，不截断。
 */
public final class CopyClient {
    public static final int TIMEOUT_MS = 15_000;
    public static final int MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;

    public enum Failure { EXPIRED, TOO_LARGE, UNREACHABLE, REJECTED, INVALID_RESPONSE }

    public static final class SyncException extends Exception {
        private final Failure failure;

        SyncException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }

    private CopyClient() {}

    public static String fetch(ScanPayload payload) throws SyncException {
        if (payload == null || payload.kind() != ScanPayload.Kind.COPY) {
            throw new SyncException(Failure.REJECTED, "Not a copy payload");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(payload.host(), payload.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            String request = "GET " + payload.requestPath() + " HTTP/1.1\r\n"
                    + "Host: " + payload.host() + ":" + payload.port() + "\r\n"
                    + "Authorization: Bearer " + payload.token() + "\r\n"
                    + "Accept: application/json\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            return readResponse(new BufferedInputStream(socket.getInputStream()));
        } catch (IOException exception) {
            throw new SyncException(Failure.UNREACHABLE, "Sync request failed");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败不影响已经读到的结果。
            }
        }
    }

    private static String readResponse(InputStream input) throws IOException, SyncException {
        String statusLine = readLine(input);
        if (statusLine == null) throw new SyncException(Failure.INVALID_RESPONSE, "Empty response");
        String[] statusParts = statusLine.split(" ", 3);
        int status;
        try {
            if (statusParts.length < 2 || !statusParts[0].startsWith("HTTP/")) throw new NumberFormatException();
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException exception) {
            throw new SyncException(Failure.INVALID_RESPONSE, "Invalid status line");
        }
        Map<String, String> headers = readHeaders(input);
        if (status == 401 || status == 404) {
            throw new SyncException(Failure.EXPIRED, "Sync grant expired");
        }
        if (status == 413) {
            throw new SyncException(Failure.TOO_LARGE, "Snapshot too large");
        }
        if (status != 200) {
            throw new SyncException(Failure.REJECTED, "Unexpected status " + status);
        }
        long declaredLength = parseContentLength(headers.get("content-length"));
        if (declaredLength > MAX_SNAPSHOT_BYTES) {
            throw new SyncException(Failure.TOO_LARGE, "Snapshot too large");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(
                declaredLength > 0 ? (int) Math.min(declaredLength, 1024 * 1024) : 8192);
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            readChunked(input, body);
        } else if (declaredLength >= 0) {
            readFixed(input, body, declaredLength);
        } else {
            readToEnd(input, body);
        }
        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException, SyncException {
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        while (true) {
            String line = readLine(input);
            if (line == null) throw new SyncException(Failure.INVALID_RESPONSE, "Truncated headers");
            if (line.isEmpty()) return headers;
            total += line.length();
            if (total > MAX_HEADER_BYTES) {
                throw new SyncException(Failure.INVALID_RESPONSE, "Headers too large");
            }
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim());
        }
    }

    private static long parseContentLength(String value) {
        if (value == null || value.isEmpty()) return -1;
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static void readFixed(InputStream input, ByteArrayOutputStream body, long length)
            throws IOException, SyncException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new SyncException(Failure.INVALID_RESPONSE, "Truncated body");
            append(body, buffer, read);
            remaining -= read;
        }
    }

    private static void readToEnd(InputStream input, ByteArrayOutputStream body)
            throws IOException, SyncException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) append(body, buffer, read);
    }

    private static void readChunked(InputStream input, ByteArrayOutputStream body)
            throws IOException, SyncException {
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            String sizeLine = readLine(input);
            if (sizeLine == null) throw new SyncException(Failure.INVALID_RESPONSE, "Truncated chunk");
            int extension = sizeLine.indexOf(';');
            String sizeText = (extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException exception) {
                throw new SyncException(Failure.INVALID_RESPONSE, "Invalid chunk size");
            }
            if (size < 0) throw new SyncException(Failure.INVALID_RESPONSE, "Invalid chunk size");
            if (size == 0) {
                while (true) {
                    String trailer = readLine(input);
                    if (trailer == null || trailer.isEmpty()) return;
                }
            }
            int remaining = size;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) throw new SyncException(Failure.INVALID_RESPONSE, "Truncated chunk");
                append(body, buffer, read);
                remaining -= read;
            }
            String terminator = readLine(input);
            if (terminator == null || !terminator.isEmpty()) {
                throw new SyncException(Failure.INVALID_RESPONSE, "Invalid chunk terminator");
            }
        }
    }

    private static void append(ByteArrayOutputStream body, byte[] buffer, int read)
            throws SyncException {
        if ((long) body.size() + read > MAX_SNAPSHOT_BYTES) {
            throw new SyncException(Failure.TOO_LARGE, "Snapshot too large");
        }
        body.write(buffer, 0, read);
    }

    private static String readLine(InputStream input) throws IOException, SyncException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(value);
            if (line.size() > MAX_LINE_BYTES) {
                throw new SyncException(Failure.INVALID_RESPONSE, "Header line too long");
            }
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1.name());
    }
}
