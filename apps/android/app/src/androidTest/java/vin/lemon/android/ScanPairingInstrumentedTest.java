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

    static MainActivity launchMainActivity() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MainActivity main = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return main;
    }

    static ScanActivity launchScanner(MainActivity main) throws Exception {
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

    static void awaitRuntimeReady(MainActivity main) throws Exception {
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

    static void awaitWebViewText(MainActivity main, String expectedText, long timeoutMs)
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

    static void clickWebElement(MainActivity main, String javascript) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("webView");
                field.setAccessible(true);
                WebView webView = (WebView) field.get(main);
                if (webView == null) {
                    error.set("WebView is null");
                    latch.countDown();
                    return;
                }
                webView.evaluateJavascript("(() => { try { " + javascript
                        + "; return 'ok'; } catch (e) { return String(e); } })()", result -> {
                    if (!"\"ok\"".equals(result)) error.set(result);
                    latch.countDown();
                });
            } catch (ReflectiveOperationException exception) {
                error.set(exception.toString());
                latch.countDown();
            }
        });
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS) || error.get() != null) {
            fail("Web element action failed: " + error.get());
        }
    }

    static void awaitNativeText(MainActivity main, String expectedText, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] found = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> found[0] = containsText(dialogRoot(main), expectedText));
            if (found[0]) return;
            Thread.sleep(250);
        }
        fail("Native text not found: " + expectedText);
    }

    private static android.view.View dialogRoot(MainActivity main) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("prompt");
            field.setAccessible(true);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.get(main);
            if (dialog != null && dialog.isShowing()) return dialog.getWindow().getDecorView();
            return main.findViewById(android.R.id.content);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean containsText(android.view.View view, String expected) {
        if (view instanceof android.widget.TextView
                && ((android.widget.TextView) view).getText().toString().contains(expected)) {
            return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }

    static void setNativeEditorText(MainActivity main, String value) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.widget.EditText input = findEditText(dialogRoot(main));
            if (input == null) throw new AssertionError("Remote URL input not found");
            input.setText(value);
            latch.countDown();
        });
        if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            fail("Unable to set remote URL input");
        }
    }

    static void awaitNativeEditorError(MainActivity main, String expectedText) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] matches = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                android.widget.EditText input = findEditText(dialogRoot(main));
                matches[0] = input != null && input.getError() != null
                        && input.getError().toString().contains(expectedText);
            });
            if (matches[0]) return;
            Thread.sleep(250);
        }
        fail("Remote URL error not shown: " + expectedText);
    }

    private static android.widget.EditText findEditText(android.view.View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = findEditText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    static void clickNativeButton(MainActivity main, String label) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.widget.Button button = findButton(dialogRoot(main), label);
            if (button == null) throw new AssertionError("Button not found: " + label);
            button.performClick();
            latch.countDown();
        });
        if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            fail("Unable to click native button: " + label);
        }
    }

    private static android.widget.Button findButton(android.view.View view, String label) {
        if (view instanceof android.widget.Button
                && ((android.widget.Button) view).getText().toString().equals(label)) {
            return (android.widget.Button) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
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

    static void awaitWebViewUrl(MainActivity main, String expectedPart, long timeoutMs)
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
