package vin.lemon.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立扫码页：CameraX 取景 + ML Kit 只识别 QR。
 *
 * <p>手动实现 {@link LifecycleOwner}，避免为单个页面引入 androidx.activity。
 * 识别成功后把原始字符串交回 MainActivity 分流；不在这里解析密钥或发请求。
 */
public final class ScanActivity extends Activity implements LifecycleOwner {
    public static final String EXTRA_RESULT = "vin.lemon.android.SCAN_RESULT";
    private static final int CAMERA_REQUEST = 41;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final AtomicBoolean delivered = new AtomicBoolean();
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private BarcodeScanner scanner;
    private PreviewView previewView;
    private TextView hint;
    private boolean permissionRequested;
    private boolean cameraBound;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        previewView = findViewById(R.id.scan_preview);
        hint = findViewById(R.id.scan_hint);
        findViewById(R.id.scan_cancel).setOnClickListener(view -> finish());
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        scanner = BarcodeScanning.getClient(options);
    }

    @NonNull @Override public Lifecycle getLifecycle() {
        return lifecycle;
    }

    @Override protected void onStart() {
        super.onStart();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        if (!hasCameraPermission()) {
            if (!permissionRequested) {
                permissionRequested = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            } else {
                showPermissionDenied();
            }
            return;
        }
        bindCamera();
    }

    @Override protected void onResume() {
        super.onResume();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    @Override protected void onPause() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
    }

    @Override protected void onStop() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override protected void onDestroy() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        if (scanner != null) scanner.close();
        analysisExecutor.shutdownNow();
        super.onDestroy();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            bindCamera();
        } else {
            showPermissionDenied();
        }
    }

    private void showPermissionDenied() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.scan_camera_required_title)
                .setMessage(R.string.scan_camera_required_message)
                .setCancelable(false)
                .setPositiveButton(R.string.scan_ok, (dialog, which) -> finish())
                .show();
    }

    // analyze 已声明 @ExperimentalGetImage；lint 会对这里的方法引用重复报 opt-in。
    @SuppressLint("UnsafeOptInUsageError")
    private void bindCamera() {
        if (cameraBound || isFinishing() || isDestroyed()) return;
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            if (isFinishing() || isDestroyed()
                    || lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
                return;
            }
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, analysis);
                cameraBound = true;
            } catch (Exception exception) {
                hint.setText(R.string.scan_camera_unavailable);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void analyze(ImageProxy proxy) {
        if (delivered.get()) {
            proxy.close();
            return;
        }
        Image mediaImage = proxy.getImage();
        if (mediaImage == null) {
            proxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(mediaImage,
                proxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
                .addOnSuccessListener(barcodes -> deliver(barcodes))
                .addOnCompleteListener(task -> proxy.close());
    }

    private void deliver(List<Barcode> barcodes) {
        if (delivered.get()) return;
        for (Barcode barcode : barcodes) {
            String value = barcode.getRawValue();
            if (value == null) continue;
            if (ScanPayload.parse(value).kind() == ScanPayload.Kind.UNKNOWN) {
                hint.setText(R.string.scan_unknown);
                continue;
            }
            if (delivered.compareAndSet(false, true)) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, value));
                finish();
            }
            return;
        }
    }
}
