package vin.lemon.android;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class CopyClientTest {
    private static final String TOKEN = "abcdefghijklmnop";

    @Test public void fetchesContentLengthSnapshot() throws Exception {
        String body = "{\"title\":\"demo\"}";
        try (StubServer server = new StubServer("HTTP/1.1 200 OK\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body)) {
            assertEquals(body, CopyClient.fetch(payload(server.port())));
            String request = server.requestText();
            assertTrue(request.startsWith("GET /sync/sessions/sess_test HTTP/1.1\r\n"));
            assertTrue(request.contains("Authorization: Bearer " + TOKEN + "\r\n"));
        }
    }

    @Test public void fetchesChunkedSnapshot() throws Exception {
        try (StubServer server = new StubServer("HTTP/1.1 200 OK\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")) {
            assertEquals("hello world", CopyClient.fetch(payload(server.port())));
        }
    }

    @Test public void readsUntilCloseWithoutLength() throws Exception {
        try (StubServer server = new StubServer("HTTP/1.1 200 OK\r\n\r\nplain")) {
            assertEquals("plain", CopyClient.fetch(payload(server.port())));
        }
    }

    @Test public void mapsExpiredAndTooLargeStatuses() throws Exception {
        try (StubServer server = new StubServer("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")) {
            assertFailure(CopyClient.Failure.EXPIRED, payload(server.port()));
        }
        try (StubServer server = new StubServer("HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\n\r\n")) {
            assertFailure(CopyClient.Failure.TOO_LARGE, payload(server.port()));
        }
    }

    private static void assertFailure(CopyClient.Failure expected, ScanPayload payload) {
        try {
            CopyClient.fetch(payload);
            fail("Expected " + expected);
        } catch (CopyClient.SyncException exception) {
            assertEquals(expected, exception.failure());
        }
    }

    private static ScanPayload payload(int port) {
        return ScanPayload.parse("http://127.0.0.1:" + port
                + "/sync/sessions/sess_test#k=" + TOKEN);
    }

    private static final class StubServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread thread;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        StubServer(String response) throws IOException {
            socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try (Socket client = socket.accept()) {
                    InputStream input = client.getInputStream();
                    int value;
                    while ((value = input.read()) >= 0) {
                        request.write(value);
                        if (request.toString(StandardCharsets.ISO_8859_1.name())
                                .endsWith("\r\n\r\n")) {
                            break;
                        }
                    }
                    client.getOutputStream().write(
                            response.getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                } catch (IOException ignored) {
                    // 测试失败时由断言暴露，不在服务线程里抛出。
                }
            });
            thread.start();
        }

        int port() { return socket.getLocalPort(); }

        String requestText() {
            return request.toString(StandardCharsets.ISO_8859_1);
        }

        @Override public void close() throws Exception {
            thread.join(3000);
            socket.close();
        }
    }
}
