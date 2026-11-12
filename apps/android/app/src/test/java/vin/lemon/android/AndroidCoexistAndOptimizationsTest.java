package vin.lemon.android;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;

/**
 * 验证与原版软件双开共存隔离边界以及本次全部原生层优化的单元测试。
 */
public final class AndroidCoexistAndOptimizationsTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void preferredPortsNeverCollideBetweenOriginalAndCoexist() {
        int original = AndroidRuntime.preferredPortForPackage("vin.lemon.android");
        int coexist = AndroidRuntime.preferredPortForPackage("vin.lemon.android.plus");
        int custom = AndroidRuntime.preferredPortForPackage("vin.lemon.android.custom");

        assertEquals(31234, original);
        assertEquals(31235, coexist);
        assertNotEquals(original, coexist);
        assertNotEquals(original, custom);
        assertNotEquals(coexist, custom);
        assertTrue(custom >= 32000 && custom < 39000);
    }

    @Test public void waitForPortFailsFastWhenChildProcessExitsImmediately() throws Exception {
        File log = temp.newFile("runtime.log");
        try (FileOutputStream out = new FileOutputStream(log)) {
            out.write("Error: listen EADDRINUSE: address already in use 127.0.0.1:31235\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        Process deadProcess = new ProcessBuilder("sh", "-c", "exit 1").start();
        deadProcess.waitFor();

        long started = System.currentTimeMillis();
        try {
            AndroidRuntime.waitForPort(deadProcess, log, 30_000L);
            fail("Expected RuntimeStartException on early process exit");
        } catch (AndroidRuntime.RuntimeStartException expected) {
            long elapsed = System.currentTimeMillis() - started;
            assertTrue("Must fail fast instead of stalling for 30s, elapsed=" + elapsed,
                    elapsed < 3_000L);
            assertTrue(expected.getMessage().contains("exited early"));
            assertTrue(expected.getMessage().contains("EADDRINUSE"));
        }
    }

    @Test public void waitForPortReadsIncrementallyWrittenPort() throws Exception {
        File log = temp.newFile("runtime-ready.log");
        try (FileOutputStream out = new FileOutputStream(log)) {
            out.write("booting runtime...\nListening on http://127.0.0.1:31235/\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(31235, AndroidRuntime.waitForPort(null, log, 2_000L));
    }

    @Test public void updaterSeparatesOriginalAndPlusReleaseAssets() throws Exception {
        String mixedReleaseJson = "{"
                + "\"tag_name\":\"android-v3.18.0\","
                + "\"body\":\"release notes\","
                + "\"assets\":["
                + "{\"name\":\"LemonCode-android-3.18.0-arm64.apk\","
                + "\"browser_download_url\":\"https://github.com/johnson020202/LemonCode-Android/releases/download/v/LemonCode-android-3.18.0-arm64.apk\"},"
                + "{\"name\":\"LemonCode-android-3.18.0-arm64.apk.sha256\","
                + "\"browser_download_url\":\"https://github.com/johnson020202/LemonCode-Android/releases/download/v/LemonCode-android-3.18.0-arm64.apk.sha256\"},"
                + "{\"name\":\"LemonCode-android-3.18.0-plus-arm64.apk\","
                + "\"browser_download_url\":\"https://github.com/johnson020202/LemonCode-Android/releases/download/v/LemonCode-android-3.18.0-plus-arm64.apk\"},"
                + "{\"name\":\"LemonCode-android-3.18.0-plus-arm64.apk.sha256\","
                + "\"browser_download_url\":\"https://github.com/johnson020202/LemonCode-Android/releases/download/v/LemonCode-android-3.18.0-plus-arm64.apk.sha256\"}"
                + "]}";

        Updater.Release plus = Updater.parseLatestRelease(mixedReleaseJson, "-plus-arm64.apk");
        assertNotNull(plus);
        assertEquals("v3.18.0", plus.version);
        assertTrue(plus.apkUrl.endsWith("-plus-arm64.apk"));
        assertTrue(plus.shaUrl.endsWith("-plus-arm64.apk.sha256"));

        Updater.Release original = Updater.parseLatestRelease(mixedReleaseJson, "-arm64.apk");
        assertNotNull(original);
        assertTrue(original.apkUrl.endsWith("3.18.0-arm64.apk"));
        assertFalse(original.apkUrl.contains("-plus-"));

        // 当某个旧 Release 只有原版 APK 时，共存版绝不能误匹配原版包导致覆盖安装/签名冲突。
        String originalOnlyJson = "{"
                + "\"tag_name\":\"android-v3.17.28\","
                + "\"assets\":["
                + "{\"name\":\"LemonCode-android-3.17.28-arm64.apk\","
                + "\"browser_download_url\":\"https://github.com/r/LemonCode-android-3.17.28-arm64.apk\"},"
                + "{\"name\":\"LemonCode-android-3.17.28-arm64.apk.sha256\","
                + "\"browser_download_url\":\"https://github.com/r/LemonCode-android-3.17.28-arm64.apk.sha256\"}"
                + "]}";
        assertNull(Updater.parseLatestRelease(originalOnlyJson, "-plus-arm64.apk"));
    }

    @Test public void diagnosticsDecodesResponseOnceAndRedactsModernSecrets() throws Exception {
        // 回归：修复 postBytes 双重 readAll 吞空响应流导致 Telegram "{\"ok\":true}" 被读成空串的缺陷。
        assertEquals("{\"ok\":true}",
                Diagnostics.decodeResponseBytes("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("OK", Diagnostics.decodeResponseBytes(new byte[0]));
        assertEquals("OK", Diagnostics.decodeResponseBytes(null));

        String raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\n"
                + "anthropic=sk-ant-api03-abcdef1234567890 openai=sk-proj-1234567890abcdef\n"
                + "url=https://code.lemon.vin/p/room1?token=secretToken123&code=oauthCode999#c=capSecret88888888";
        String redacted = Diagnostics.redact(raw);
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertFalse(redacted.contains("sk-ant-api03-abcdef1234567890"));
        assertFalse(redacted.contains("sk-proj-1234567890abcdef"));
        assertFalse(redacted.contains("secretToken123"));
        assertFalse(redacted.contains("oauthCode999"));
        assertFalse(redacted.contains("capSecret88888888"));
        assertTrue(redacted.contains("[redacted]"));

        byte[] multipart = Diagnostics.multipartBytes("12345", "hello-log");
        String body = new String(multipart, StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"chat_id\"\r\n\r\n12345\r\n"));
        assertTrue(body.contains("hello-log\r\n------LemonCodeDiagnostics--\r\n"));
    }

    @Test public void tarExtractorReplacesDanglingSymlinkAndBlocksTraversal() throws Exception {
        File dest = temp.newFolder("runtime-dest");
        File link = new File(dest, "usr/bin/node");
        assertTrue(link.getParentFile().mkdirs());
        // 先预置一个指向不存在目标的悬空断链（模拟上次解包残留或先链接后目标的归档顺序）。
        Files.createSymbolicLink(link.toPath(), Paths.get(dest.getAbsolutePath(), "usr/lib/missing.so"));
        assertFalse("File.exists() returns false on dangling symlink", link.exists());
        assertTrue(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS));

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        writeTarEntry(tar, "usr/lib/real.txt", '0', "", "ok".getBytes(StandardCharsets.UTF_8));
        writeTarEntry(tar, "usr/bin/node", '2', "usr/lib/real.txt", new byte[0]);
        tar.write(new byte[1024]); // 结束零块

        TarExtractor.extract(new ByteArrayInputStream(tar.toByteArray()), dest);
        assertEquals("ok", new String(Files.readAllBytes(link.toPath()), StandardCharsets.UTF_8));

        // 校验路径逃逸拦截
        File canonical = dest.getCanonicalFile();
        try {
            TarExtractor.resolveInside(canonical, canonical.getPath() + File.separator, "../../etc/passwd");
            fail("Expected escape path rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
    }

    private static void writeTarEntry(ByteArrayOutputStream out, String name, char type,
                                      String linkName, byte[] data) throws IOException {
        byte[] header = new byte[512];
        writeAscii(header, 0, 100, name);
        writeAscii(header, 100, 8, "0000755\0");
        writeAscii(header, 124, 12, String.format("%011o\0", data.length));
        header[156] = (byte) type;
        writeAscii(header, 157, 100, linkName);
        out.write(header);
        out.write(data);
        int rem = data.length % 512;
        if (rem != 0) out.write(new byte[512 - rem]);
    }

    private static void writeAscii(byte[] buf, int offset, int maxLen, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, buf, offset, Math.min(bytes.length, maxLen));
    }
}
