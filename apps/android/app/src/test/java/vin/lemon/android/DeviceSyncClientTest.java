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
import java.util.ArrayList;
import java.util.List;

public final class DeviceSyncClientTest {
    private static final String CAPABILITY = "capability_1234567890";

    @Test public void importsSnapshotWithFreshCapability() throws Exception {
        try (StubServer server = new StubServer(
                jsonResponse("{\"capability\":\"" + CAPABILITY + "\"}"),
                jsonResponse("{\"sessionId\":\"sess_phone_1\"}"))) {
            String sessionId = DeviceSyncClient.importSnapshot(server.port(), "{\"title\":\"demo\"}");
            assertEquals("sess_phone_1", sessionId);

            List<String> requests = server.requests();
            assertEquals(2, requests.size());
            assertTrue(requests.get(0).startsWith("POST /api/rpc-host-capability HTTP/1.1\r\n"));
            assertTrue(requests.get(1).startsWith("POST /api/device-sync/import HTTP/1.1\r\n"));
            assertTrue(requests.get(1).contains(
                    "x-lcode-rpc-host-capability: " + CAPABILITY + "\r\n"));
            assertTrue(requests.get(1).endsWith("{\"snapshot\":{\"title\":\"demo\"}}"));
        }
    }

    @Test public void rejectsMissingSessionId() throws Exception {
        try (StubServer server = new StubServer(
                jsonResponse("{\"capability\":\"" + CAPABILITY + "\"}"),
                jsonResponse("{\"sessionId\":\"bad id\"}"))) {
            assertFailure(CopyClient.Failure.INVALID_RESPONSE, server.port());
        }
    }

    @Test public void mapsImportErrorStatus() throws Exception {
        try (StubServer server = new StubServer(
                jsonResponse("{\"capability\":\"" + CAPABILITY + "\"}"),
                "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n")) {
            assertFailure(CopyClient.Failure.REJECTED, server.port());
        }
    }

    private static void assertFailure(CopyClient.Failure expected, int port) {
        try {
            DeviceSyncClient.importSnapshot(port, "{}");
            fail("Expected " + expected);
        } catch (CopyClient.SyncException exception) {
            assertEquals(expected, exception.failure());
        }
    }

    private static String jsonResponse(String body) {
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private static final class StubServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread thread;
        private final List<String> requests = new ArrayList<>();

        StubServer(String... responses) throws IOException {
            socket = new ServerSocket(0, responses.length, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try {
                    for (String response : responses) {
                        try (Socket client = socket.accept()) {
                            requests.add(readRequest(client.getInputStream()));
                            client.getOutputStream().write(
                                    response.getBytes(StandardCharsets.ISO_8859_1));
                            client.getOutputStream().flush();
                        }
                    }
                } catch (IOException ignored) {
                    // 测试失败时由断言暴露，不在服务线程里抛出。
                }
            });
            thread.start();
        }

        int port() { return socket.getLocalPort(); }

        synchronized List<String> requests() { return new ArrayList<>(requests); }

        @Override public void close() throws Exception {
            thread.join(3000);
            socket.close();
        }

        private static String readRequest(InputStream input) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) >= 0) {
                header.write(value);
                String text = header.toString(StandardCharsets.ISO_8859_1.name());
                if (text.endsWith("\r\n\r\n")) break;
            }
            String head = header.toString(StandardCharsets.ISO_8859_1.name());
            int length = 0;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = input.read(body, offset, length - offset);
                if (read < 0) break;
                offset += read;
            }
            return head + new String(body, 0, offset, StandardCharsets.UTF_8);
        }
    }
}
