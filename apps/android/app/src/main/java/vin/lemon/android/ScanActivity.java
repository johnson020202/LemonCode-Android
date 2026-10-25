package vin.lemon.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.net.Uri;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final int GALLERY_REQUEST = 42;
    private static final int MAX_BITMAP_PIXELS = 16_000_000;
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;

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
        findViewById(R.id.scan_gallery).setOnClickListener(view -> chooseGallery());
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
        // 拒绝相机权限不等于无法连接：保留扫码页，允许改从相册识别二维码截图。
        hint.setText(R.string.scan_camera_permission_gallery_hint);
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

    private void chooseGallery() {
        if (delivered.get()) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"), GALLERY_REQUEST);
        } catch (android.content.ActivityNotFoundException exception) {
            hint.setText(R.string.scan_gallery_failed);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != GALLERY_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        findViewById(R.id.scan_gallery).setEnabled(false);
        hint.setText(R.string.scan_gallery_loading);
        analysisExecutor.execute(() -> readGalleryImage(uri));
    }

    private void readGalleryImage(Uri uri) {
        try {
            byte[] encoded;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Unable to open selected image");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > MAX_IMAGE_BYTES) {
                        throw new IOException("Selected image is too large");
                    }
                    bytes.write(buffer, 0, read);
                }
                encoded = bytes.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("Unsupported image");
            }
            int sample = 1;
            while (bounds.outWidth / sample * (bounds.outHeight / sample) > MAX_BITMAP_PIXELS) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            if (bitmap == null) throw new IOException("Unable to decode image");
            scanner.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(barcodes -> {
                        if (barcodes.isEmpty()) showGalleryMessage(R.string.scan_gallery_empty);
                        else deliver(barcodes);
                    })
                    .addOnFailureListener(exception -> showGalleryMessage(R.string.scan_gallery_empty))
                    .addOnCompleteListener(task -> {
                        bitmap.recycle();
                        restoreGalleryChoice();
                    });
        } catch (IOException | RuntimeException exception) {
            showGalleryMessage(R.string.scan_gallery_failed);
            restoreGalleryChoice();
        }
    }

    private void showGalleryMessage(int message) {
        runOnUiThread(() -> {
            if (!delivered.get()) hint.setText(message);
        });
    }

    private void restoreGalleryChoice() {
        runOnUiThread(() -> {
            if (!delivered.get()) findViewById(R.id.scan_gallery).setEnabled(true);
        });
    }

    private void deliver(List<Barcode> barcodes) {
        // ML Kit 回调可能在 Binder 线程；setResult/finish 与提示更新必须回到主线程。
        runOnUiThread(() -> {
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
        });
    }
}
