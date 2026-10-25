package vin.lemon.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** 设备端验收真实 Activity 回传和同步导入；测试代码不进入正式 APK。 */
@RunWith(AndroidJUnit4.class)
public final class ScanPairingInstrumentedTest {
    private static final int SCAN_REQUEST = 32;

    @Test public void scanResultStartsPairing() throws Exception {
        String url = requiredArgument("scan_url");
        MainActivity main = launchMainActivity();
        awaitRuntimeReady(main);
        ScanActivity scanner = launchScanner(main);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            scanner.setResult(Activity.RESULT_OK,
                    new Intent().putExtra(ScanActivity.EXTRA_RESULT, url));
            scanner.finish();
        });
        awaitWebViewUrl(main, "code.lemon.vin/p/", 30_000);
        String holdMs = InstrumentationRegistry.getArguments().getString("hold_ms", "0");
        Thread.sleep(Long.parseLong(holdMs));
    }

    @Test public void copyScanResultImportsSnapshot() throws Exception {
        String url = requiredArgument("scan_url");
        String expectedText = requiredArgument("expected_text");
        MainActivity main = launchMainActivity();
        awaitRuntimeReady(main);
        ScanActivity scanner = launchScanner(main);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            scanner.setResult(Activity.RESULT_OK,
                    new Intent().putExtra(ScanActivity.EXTRA_RESULT, url));
            scanner.finish();
        });
        awaitWebViewUrl(main, "?task=", 120_000);
        dismissLocalOnboarding(main);
        awaitWebViewText(main, expectedText, 60_000);
    }

    private static String requiredArgument(String name) {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String value = arguments.getString(name);
        assertNotNull("Pass -e " + name + " with a short-lived test URL", value);
        return value;
    }

    private static MainActivity launchMainActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MainActivity main = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return main;
    }

    private static ScanActivity launchScanner(MainActivity main) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                ScanActivity.class.getName(), null, false);
        instrumentation.runOnMainSync(() -> main.startActivityForResult(
                new Intent(main, ScanActivity.class), SCAN_REQUEST));
        Activity scanner = instrumentation.waitForMonitorWithTimeout(monitor, 15_000);
        assertNotNull("ScanActivity was not launched", scanner);
        instrumentation.waitForIdleSync();
        return (ScanActivity) scanner;
    }

    private static void awaitRuntimeReady(MainActivity main) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] ready = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> ready[0] = main.findViewById(R.id.connect).isEnabled());
            if (ready[0]) return;
            Thread.sleep(250);
        }
        fail("Local runtime did not become ready");
    }

    private static void awaitWebViewText(MainActivity main, String expectedText, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String lastText = "";
        while (System.currentTimeMillis() < deadline) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> result =
                    new java.util.concurrent.atomic.AtomicReference<>("");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("webView");
                    field.setAccessible(true);
                    WebView webView = (WebView) field.get(main);
                    if (webView == null) {
                        latch.countDown();
                    } else {
                        webView.evaluateJavascript(
                                "document.body ? document.body.innerText : ''",
                                value -> {
                                    result.set(value);
                                    latch.countDown();
                                });
                    }
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            });
            if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                lastText = result.get();
                if (lastText != null && lastText.contains(expectedText)) return;
            }
            Thread.sleep(250);
        }
        fail("Expected WebView text to contain " + expectedText + ", last=" + lastText);
    }

    private static void dismissLocalOnboarding(MainActivity main) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        boolean clicked = false;
        while (System.currentTimeMillis() < deadline && !clicked) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("webView");
                    field.setAccessible(true);
                    WebView webView = (WebView) field.get(main);
                    if (webView == null) {
                        latch.countDown();
                        return;
                    }
                    webView.evaluateJavascript(
                            "(() => { const b = [...document.querySelectorAll('button')].find(x =>"
                                    + " ['Cancel', 'Exit onboarding'].includes(x.textContent.trim())"
                                    + " || x.getAttribute('aria-label') === 'Exit onboarding'"
                                    + " || x.title === 'Exit onboarding');"
                                    + " if (b) { b.click(); return true; } return false; })()",
                            value -> {
                                found.set(Boolean.parseBoolean(value));
                                latch.countDown();
                            });
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            });
            clicked = latch.await(5, java.util.concurrent.TimeUnit.SECONDS) && found.get();
            if (!clicked) Thread.sleep(300);
        }
        // 不强制要求出现引导；没有弹窗时直接继续。
        Thread.sleep(1500);
    }

    private static void awaitWebViewUrl(MainActivity main, String expectedPart, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String lastUrl = null;
        while (System.currentTimeMillis() < deadline) {
            final String[] current = {null};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("webView");
                    field.setAccessible(true);
                    WebView webView = (WebView) field.get(main);
                    current[0] = webView == null ? null : webView.getUrl();
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            });
            lastUrl = current[0];
            if (lastUrl != null && lastUrl.contains(expectedPart)) return;
            Thread.sleep(250);
        }
        fail("Expected WebView URL to contain " + expectedPart + ", last=" + lastUrl);
    }
}
