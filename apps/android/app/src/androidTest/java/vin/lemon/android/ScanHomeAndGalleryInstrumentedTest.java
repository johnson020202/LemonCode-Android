package vin.lemon.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** 首页选择停留与相册截图识别的设备端验收。 */
@RunWith(AndroidJUnit4.class)
public final class ScanHomeAndGalleryInstrumentedTest {
    @Test public void homeWaitsForUserChoice() throws Exception {
        MainActivity main = ScanPairingInstrumentedTest.launchMainActivity();
        ScanPairingInstrumentedTest.awaitRuntimeReady(main);
        Thread.sleep(1500);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals("Runtime ready must not auto-open the workspace",
                    View.VISIBLE, main.findViewById(R.id.home_panel).getVisibility());
            assertEquals(View.GONE, main.findViewById(R.id.web_panel).getVisibility());
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> main.findViewById(R.id.connect).performClick());
        ScanPairingInstrumentedTest.awaitWebViewUrl(main, "127.0.0.1", 30_000);
    }

    @Test public void welcomeRemoteEntriesAndUrlValidation() throws Exception {
        MainActivity main = ScanPairingInstrumentedTest.launchMainActivity();
        ScanPairingInstrumentedTest.awaitRuntimeReady(main);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> main.findViewById(R.id.connect).performClick());
        ScanPairingInstrumentedTest.awaitWebViewText(main, "Scan to connect computer", 90_000);

        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                ScanActivity.class.getName(), null, false);
        ScanPairingInstrumentedTest.clickWebElement(main,
                "document.querySelector('[data-testid=\\\"login-scan-remote\\\"]').click()");
        android.app.Activity scanner = instrumentation.waitForMonitorWithTimeout(monitor, 15_000);
        assertNotNull("Welcome scan button must open the native scanner", scanner);
        instrumentation.runOnMainSync(scanner::finish);

        ScanPairingInstrumentedTest.clickWebElement(main,
                "document.querySelector('[data-testid=\\\"login-enter-remote-url\\\"]').click()");
        ScanPairingInstrumentedTest.awaitNativeText(main, "填写远程网址", 10_000);
        ScanPairingInstrumentedTest.setNativeEditorText(main, "https://example.com");
        ScanPairingInstrumentedTest.clickNativeButton(main, "连接");
        ScanPairingInstrumentedTest.awaitNativeEditorError(main, "链接不完整或格式不正确");
        ScanPairingInstrumentedTest.clickNativeButton(main, "取消");
    }

    @Test public void galleryScreenshotIsRecognized() throws Exception {
        MainActivity main = ScanPairingInstrumentedTest.launchMainActivity();
        ScanPairingInstrumentedTest.awaitRuntimeReady(main);
        ScanActivity scanner = ScanPairingInstrumentedTest.launchScanner(main);
        Uri uri = Uri.parse("content://vin.lemon.android.test.gallery/pairing-qr.png");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> scanner.onActivityResult(42, Activity.RESULT_OK, new Intent().setData(uri)));
        ScanPairingInstrumentedTest.awaitWebViewUrl(main, "code.lemon.vin/p/", 60_000);
        assertTrue("ScanActivity should finish after decoding the gallery QR",
                scanner.isFinishing() || scanner.isDestroyed());
    }
}
