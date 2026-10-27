package vin.lemon.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerController;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 唯一的原生界面。启动时解包内置运行时、在手机内启动上游本地服务端，
 * 然后由 WebView 直接访问 127.0.0.1 上的完整共享客户端。
 *
 * <p>与远控模式的区别：这里没有桌面 Host，Agent、终端、Git、文件与插件都由本机
 * 运行时执行；不保存任何一次性配对凭据。
 */
public final class MainActivity extends Activity {
    private static final String PREFERENCES = "runtime";
    private static final String LAST_ORIGIN = "last_origin";
    private static final String LAST_PAGE = "last_page";
    private static final int FILE_PICKER = 31;
    private static final int SCAN_REQUEST = 32;
    private static final int NOTIFICATION_PERMISSION = 33;
    private static final int MAX_UPLOAD_FILES = 32;

    private SharedPreferences preferences;
    private View homePanel;
    private View webPanel;
    private android.widget.FrameLayout webContainer;
    private TextView status;
    private TextView version;
    private Button connectButton;
    private Button scanButton;
    private Button reconnectButton;
    private TextView previousOrigin;
    private ProgressBar loadProgress;
    private WebView webView;
    private UrlPolicy.LocalOrigin activeOrigin;
    private int activePort;
    private AlertDialog prompt;
    private ValueCallback<Uri[]> pendingFiles;
    private WebView fileOwner;
    private boolean multipleFiles;
    private boolean pickerInFlight;
    private OnBackInvokedCallback backCallback;
    private Thread starter;
    private boolean syncing;
    private String pendingRestore;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        // 仅恢复白名单页面；新运行时端口就绪后自动打开一次，失败则留在选择页。
        pendingRestore = preferences.getString(LAST_PAGE, null);
        homePanel = findViewById(R.id.home_panel);
        webPanel = findViewById(R.id.web_panel);
        webContainer = findViewById(R.id.web_container);
        status = findViewById(R.id.status);
        version = findViewById(R.id.version);
        connectButton = findViewById(R.id.connect);
        scanButton = findViewById(R.id.scan_sync);
        reconnectButton = findViewById(R.id.reconnect);
        previousOrigin = findViewById(R.id.previous_address);
        loadProgress = findViewById(R.id.load_progress);
        // 排障通道：默认关闭。以 `--ez debug_webview true` 启动时打开 WebView 调试，
        // 便于在真机上查看 DOM、控制台错误与 WebSocket 帧（仅本机 adb 可连）。
        boolean debugWebView = BuildConfig.DEBUG
                || getIntent().getBooleanExtra("debug_webview", false);
        WebView.setWebContentsDebuggingEnabled(debugWebView);
        // release 包无法用 run-as 读取应用私有目录；排障模式下把日志复制到
        // external files（adb 可读），否则真机上的 agent 错误只能靠猜。
        if (debugWebView) exportDiagnostics();
        configureInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        connectButton.setOnClickListener(view -> launchRuntime());
        scanButton.setOnClickListener(view -> startScan());
        reconnectButton.setOnClickListener(view -> launchRuntime());
        findViewById(R.id.forget).setOnClickListener(view -> confirmReset());
        version.setText(getString(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        findViewById(R.id.check_update).setOnClickListener(view -> checkForUpdates(true));
        findViewById(R.id.report_logs).setOnClickListener(view -> Diagnostics.prompt(
                this,
                new java.io.File(getFilesDir(), "runtime.log"),
                agentLogFile()));
        startRuntime();
        // 启动后静默检查一次；只有真正发现新版本才提示，不打断使用。
        checkForUpdates(false);
        requestNotificationPermission();
    }

    /**
     * 任务提醒所需的通知权限。用户拒绝后不重复打扰：任务本身在应用内仍正常完成，
     * 只是不再发系统通知。
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            TaskNotifications.ensureChannels(this);
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            TaskNotifications.ensureChannels(this);
            return;
        }
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION);
    }

    /**
     * 应用内升级入口。manual=false 时静默检查：无更新或失败都不打扰用户；
     * 有更新才弹出下载确认，下载完成后由系统安装器要求用户确认。
     */
    private void checkForUpdates(boolean manual) {
        final TextView updateStatus = findViewById(R.id.update_status);
        if (manual) updateStatus.setText(R.string.update_checking);
        Updater.check(this, new Updater.Callback() {
            @Override public void onStatus(String message) {
                updateStatus.setText(message);
            }

            @Override public void onChecked(Updater.Release release) {
                String current = Updater.currentVersion(MainActivity.this);
                if (!AppVersion.isNewer(release.version, current)) {
                    updateStatus.setText(getString(R.string.update_latest, current));
                    if (manual) toast(getString(R.string.update_latest, current));
                    return;
                }
                updateStatus.setText(getString(R.string.update_available, release.version, current));
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.update_available, release.version, current))
                        .setMessage(release.notes.trim().isEmpty()
                                ? getString(R.string.update_install) : release.notes.trim())
                        .setNegativeButton(R.string.update_later, null)
                        .setPositiveButton(R.string.update_install,
                                (dialog, which) -> downloadUpdate(release))
                        .show();
            }

            @Override public void onError(String message) {
                updateStatus.setText(getString(R.string.update_failed, message));
                if (manual) toast(message);
            }

            @Override public void onDownloaded(java.io.File apk) {
                updateStatus.setText(R.string.update_idle);
                Updater.install(MainActivity.this, apk);
            }
        });
    }

    private void downloadUpdate(Updater.Release release) {
        final TextView updateStatus = findViewById(R.id.update_status);
        showPrompt(new AlertDialog.Builder(this)
                .setTitle(R.string.update_install)
                .setMessage(getString(R.string.update_available, release.version,
                        Updater.currentVersion(this)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.update_install, (dialog, which) ->
                        Updater.download(this, release, new Updater.Callback() {
                            @Override public void onStatus(String message) {
                                updateStatus.setText(message);
                            }

                            @Override public void onChecked(Updater.Release ignored) {
                                // 下载阶段不重复检查。
                            }

                            @Override public void onError(String message) {
                                updateStatus.setText(getString(R.string.update_failed, message));
                                toast(message);
                            }

                            @Override public void onDownloaded(java.io.File apk) {
                                updateStatus.setText(R.string.update_idle);
                                Updater.install(MainActivity.this, apk);
                            }
                        })));
    }

    private java.io.File agentLogFile() {
        java.io.File dir = new java.io.File(getFilesDir(), "home/.lcode/cli/log");
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".jsonl"));
        if (files == null || files.length == 0) return new java.io.File(dir, "missing.jsonl");
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files[0];
    }


    /**
     * 排障模式下把服务端与 Agent 日志复制到 external files，供 adb pull。
     * release 包没有 run-as 权限，不导出就无法读取真机上的真实错误。
     */
    private void exportDiagnostics() {
        new Thread(() -> {
            try {
                java.io.File out = new java.io.File(getExternalFilesDir(null), "diagnostics");
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
                copyIfPresent(new java.io.File(getFilesDir(), "runtime.log"),
                        new java.io.File(out, "runtime.log"));
                copyIfPresent(agentLogFile(), new java.io.File(out, "agent.jsonl"));
                writePathFacts(new java.io.File(out, "paths.txt"));
            } catch (IOException | RuntimeException ignored) {
                // 导出失败不阻断启动；排障通道本就不是必需能力。
            }
        }, "lcode-diagnostics-export").start();
    }

    private static void copyIfPresent(java.io.File source, java.io.File target) throws IOException {
        if (source == null || !source.isFile()) return;
        try (java.io.InputStream input = new java.io.FileInputStream(source);
             java.io.OutputStream output = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    /**
     * 记录 App、HOME、记忆目录的真实文件系统身份。
     * Android 上 /data/user/0 与 /data/data 互为别名，Node 的 realpath 结果取决于厂商实现；
     * 只凭日志里的一条 “must not be a symlink” 无法判断是哪一段路径触发的。
     */
    private void writePathFacts(java.io.File out) throws IOException {
        java.util.List<java.io.File> dirs = new java.util.ArrayList<>();
        dirs.add(getFilesDir());
        dirs.add(new java.io.File(getFilesDir(), "home"));
        dirs.add(new java.io.File(getFilesDir(), "home/.lcode"));
        dirs.add(new java.io.File(getFilesDir(), "home/.lcode/cli"));
        dirs.add(new java.io.File(getFilesDir(), "home/.lcode/cli/memories"));
        dirs.add(new java.io.File(getFilesDir(), "home/.lcode/cli/memories/projects"));
        StringBuilder builder = new StringBuilder();
        builder.append("androidDataDir=").append(getDataDir().getAbsolutePath()).append('\n');
        builder.append("filesDir=").append(getFilesDir().getAbsolutePath()).append('\n');
        for (java.io.File dir : dirs) {
            builder.append(dir.getAbsolutePath())
                    .append(" exists=").append(dir.exists())
                    .append(" canonical=").append(canonicalOrSelf(dir))
                    .append('\n');
        }
        try (java.io.OutputStream output = new java.io.FileOutputStream(out)) {
            output.write(builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String canonicalOrSelf(java.io.File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException | RuntimeException exception) {
            return "<error:" + exception.getClass().getSimpleName() + ">";
        }
    }

    private void startRuntime() {
        if (starter != null) return;
        connectButton.setEnabled(false);
        scanButton.setEnabled(false);
        status.setText(R.string.starting_runtime);
        starter = new Thread(() -> {
            try {
                File root = AndroidRuntime.prepare(this);
                final int port = AndroidRuntime.startServer(this, root);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    activePort = port;
                    activeOrigin = new UrlPolicy.LocalOrigin(port);
                    preferences.edit().putString(LAST_ORIGIN, activeOrigin.origin()).apply();
                    connectButton.setEnabled(true);
                    scanButton.setEnabled(true);
                    reconnectButton.setEnabled(true);
                    previousOrigin.setText(getString(R.string.local_origin, activeOrigin.origin()));
                    status.setText(R.string.runtime_ready);
                    starter = null;
                    // 首次使用仍停留选择页；只有此前确实进入过工作区才恢复一次。
                    if (pendingRestore != null) {
                        String target = RestorablePage.restore(activeOrigin, pendingRestore);
                        pendingRestore = null;
                        if (target != null) launchRuntime(target);
                    }
                });
            } catch (Exception exception) {
                final String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    starter = null;
                    connectButton.setEnabled(true);
                    status.setText(getString(R.string.runtime_failed, message));
                });
            }
        }, "lcode-runtime-start");
        starter.start();
    }

    private void configureInsets() {
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                boolean light = (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? appearance : 0, appearance);
            }
        } else {
            root.setFitsSystemWindows(true);
            if (Build.VERSION.SDK_INT >= 27) {
                boolean light = (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
                getWindow().getDecorView().setSystemUiVisibility(light
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            } else {
                // Android 8.0 无深色导航图标；浅色背景会让系统按钮不可见。
                getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
            }
        }
        root.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void launchRuntime() {
        launchRuntime(null);
    }

    /**
     * initialUrl 为空时打开本机工作区；镜像配对链接与拷贝完成后的
     * `?task=` 页面由调用方传入，仍按同一套来源策略校验。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void launchRuntime(String initialUrl) {
        if (activeOrigin == null) {
            startRuntime();
            return;
        }
        UrlPolicy.LocalOrigin origin = activeOrigin;
        String target = initialUrl == null ? origin.launchUrl() : initialUrl;
        if (!UrlPolicy.isInApp(origin, target)) {
            toast(R.string.navigation_blocked);
            return;
        }
        disposeWebView();
        // 每次重新加载页面都要重置文件选择器状态，避免残留拒绝。
        pickerInFlight = false;
        pendingFiles = null;
        fileOwner = null;
        // 记录镜像会话的 host，供后续同源文件选择放行；本机页面重置为 null。
        if (initialUrl != null && UrlPolicy.localNavigation(origin, initialUrl) == UrlPolicy.Navigation.MIRROR) {
            try {
                mirrorSessionHost = new java.net.URI(initialUrl).getHost();
            } catch (java.net.URISyntaxException ignored) {
                mirrorSessionHost = null;
            }
        } else if (initialUrl == null) {
            mirrorSessionHost = null;
        }
        WebView view = new WebView(this);
        webView = view;
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true); // 真实 React 客户端需要；不注册任何原生 JS 桥。
        settings.setDomStorageEnabled(true); // 客户端自身状态与会话草稿。
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        // 本地运行时已由应用自己启动；Service Worker 不作为额外的代码加载通道。
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setAllowContentAccess(false);
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setAllowFileAccess(false);
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(false);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "lemonAndroid",
                    java.util.Collections.singleton(origin.origin()),
                    (source, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (source != webView || message.getType() != WebMessageCompat.TYPE_STRING) return;
                        String data = message.getData();
                        String command = bridgeCommand(data);
                        if (!LocalBridgePolicy.allows(origin, command, source.getUrl(),
                                String.valueOf(sourceOrigin), isMainFrame)) return;
                        // WebMessageListener 可能在 Binder 线程回调；原生 UI 操作统一切回主线程。
                        runOnUiThread(() -> {
                            if ("scan".equals(command)) startScan();
                            else if ("url".equals(command)) showRemoteUrlDialog();
                            else TaskNotifications.post(this, NotificationPayload.parse(data));
                        });
                    });
        }
        view.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView ignored, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)
                        || "content".equalsIgnoreCase(scheme)) {
                    if (request.isForMainFrame() && !origin.allows(request.getUrl().toString())) {
                        return errorResponse(403, "Forbidden");
                    }
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest request) {
                if (source != webView) return true;
                return handleNavigation(request.getUrl().toString());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView source, String url) {
                return source != webView || handleNavigation(url);
            }

            @Override public void onPageStarted(WebView source, String url, android.graphics.Bitmap icon) {
                if (source == webView && !UrlPolicy.isInApp(origin, url)) {
                    source.stopLoading();
                    failSession(source, R.string.navigation_blocked);
                }
            }

            @Override public void onPageFinished(WebView source, String url) {
                if (source == webView) rememberPage(url);
            }

            @Override public void onReceivedSslError(WebView source, SslErrorHandler handler, SslError error) {
                handler.cancel();
                failSession(source, R.string.connection_error);
            }

            @Override public void onReceivedError(
                    WebView source, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) failSession(source, R.string.connection_error);
            }

            @Override public boolean onRenderProcessGone(WebView source, RenderProcessGoneDetail detail) {
                failSession(source, R.string.renderer_failed);
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView source, int progress) {
                if (source != webView) return;
                loadProgress.setProgress(progress);
                loadProgress.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                return true; // 不把可能含路径或凭据的页面日志写入系统日志。
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }

            @Override public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }

            @Override public boolean onCreateWindow(WebView source, boolean dialog, boolean gesture, Message message) {
                // 外部链接经系统浏览器打开，绝不创建远程子 WebView。
                WebView.HitTestResult target = source.getHitTestResult();
                if (gesture && source == webView && target != null
                        && (target.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE
                        || target.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                        && target.getExtra() != null) {
                    String url = target.getExtra();
                    if (!handleNavigation(url)) source.loadUrl(url);
                } else {
                    toast(R.string.popup_unsupported);
                }
                return false;
            }

            @Override public boolean onShowFileChooser(
                    WebView source, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return chooseFiles(source, callback, params);
            }
        });
        view.setDownloadListener((url, agent, disposition, type, length) -> toast(R.string.download_unsupported));
        webContainer.addView(view, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        homePanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.VISIBLE);
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        view.requestFocus();
        pendingRestore = null;
        rememberPage(target);
        view.loadUrl(target);
    }

    private static WebResourceResponse errorResponse(int status, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", status, reason,
                java.util.Collections.singletonMap("Cache-Control", "no-store"),
                new java.io.ByteArrayInputStream(new byte[0]));
    }

    /** {@code notify} 携带 JSON 参数，其余命令是裸字符串。 */
    private static String bridgeCommand(String data) {
        if (data == null) return null;
        if ("notify".equals(data) || data.startsWith("notify ")) return "notify";
        return data;
    }

    private boolean handleNavigation(String url) {
        UrlPolicy.Navigation action = UrlPolicy.localNavigation(activeOrigin, url);
        if (action == UrlPolicy.Navigation.IN_APP || action == UrlPolicy.Navigation.MIRROR) {
            return false;
        }
        if (action == UrlPolicy.Navigation.EXTERNAL_HTTPS) {
            Uri target = Uri.parse(url);
            String destination = "https://" + target.getHost()
                    + (target.getPort() == -1 ? "" : ":" + target.getPort());
            showPrompt(new AlertDialog.Builder(this).setTitle(R.string.external_title)
                    .setMessage(getString(R.string.external_message, destination))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.external_open, (dialog, which) -> {
                        if (UrlPolicy.localNavigation(activeOrigin, url)
                                != UrlPolicy.Navigation.EXTERNAL_HTTPS) return;
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, target)
                                    .addCategory(Intent.CATEGORY_BROWSABLE));
                        } catch (ActivityNotFoundException | SecurityException exception) {
                            toast(R.string.external_unavailable);
                        }
                    }));
        } else {
            toast(R.string.navigation_blocked);
        }
        return true;
    }

    private void showRemoteUrlDialog() {
        if (activeOrigin == null) {
            toast(R.string.scan_runtime_not_ready);
            startRuntime();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint(R.string.remote_url_hint);
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setPadding(getResources().getDimensionPixelSize(R.dimen.space_standard),
                0, getResources().getDimensionPixelSize(R.dimen.space_standard), 0);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.remote_url_title)
                .setMessage(R.string.remote_url_message)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remote_url_connect, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = input.getText().toString().trim();
                if (ScanPayload.parse(value).kind() == ScanPayload.Kind.UNKNOWN) {
                    input.setError(getString(R.string.remote_url_invalid));
                    input.requestFocus();
                    return;
                }
                dialog.dismiss();
                handleScan(value);
            });
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        showPrompt(dialog);
    }

    private void startScan() {
        if (activeOrigin == null) {
            toast(R.string.scan_runtime_not_ready);
            startRuntime();
            return;
        }
        startActivityForResult(new Intent(this, ScanActivity.class), SCAN_REQUEST);
    }

    /** 扫码结果只在这里分流；无法识别的内容不发起任何连接。 */
    private void handleScan(String value) {
        ScanPayload payload = ScanPayload.parse(value);
        if (payload.kind() == ScanPayload.Kind.UNKNOWN) {
            toast(R.string.scan_unknown);
            return;
        }
        if (payload.kind() == ScanPayload.Kind.MIRROR) {
            launchRuntime(payload.url());
            return;
        }
        startCopySync(payload);
    }

    private void startCopySync(ScanPayload payload) {
        if (activeOrigin == null || activePort <= 0) {
            toast(R.string.scan_runtime_not_ready);
            return;
        }
        if (syncing) return;
        syncing = true;
        scanButton.setEnabled(false);
        final int port = activePort;
        toast(R.string.scan_syncing);
        new Thread(() -> {
            try {
                String snapshot = CopyClient.fetch(payload);
                String sessionId = DeviceSyncClient.importSnapshot(port, snapshot);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    syncing = false;
                    scanButton.setEnabled(true);
                    UrlPolicy.LocalOrigin current = activeOrigin;
                    if (current == null) {
                        toast(R.string.sync_failed);
                        return;
                    }
                    toast(R.string.sync_completed);
                    launchRuntime(current.launchUrl() + "?task=" + Uri.encode(sessionId));
                });
            } catch (CopyClient.SyncException exception) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    syncing = false;
                    scanButton.setEnabled(true);
                    toast(messageFor(exception.failure()));
                });
            }
        }, "lcode-device-sync").start();
    }

    private static int messageFor(CopyClient.Failure failure) {
        switch (failure) {
            case EXPIRED:
                return R.string.sync_expired;
            case TOO_LARGE:
                return R.string.sync_too_large;
            case UNREACHABLE:
                return R.string.sync_unreachable;
            default:
                return R.string.sync_failed;
        }
    }

    private String mirrorSessionHost;

    private boolean isFileChooserSourceTrusted(String url) {
        if (url == null || activeOrigin == null) return false;
        UrlPolicy.Navigation action = UrlPolicy.localNavigation(activeOrigin, url);
        if (action == UrlPolicy.Navigation.IN_APP || action == UrlPolicy.Navigation.MIRROR) {
            return true;
        }
        // 镜像 SPA 的 history.pushState 会把路径改出 /p/ 前缀，但同一 host 的远控页仍可信。
        if (mirrorSessionHost != null) {
            try {
                java.net.URI uri = new java.net.URI(url);
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && uri.getHost().equals(mirrorSessionHost)) {
                    return true;
                }
            } catch (java.net.URISyntaxException ignored) {}
        }
        return false;
    }

    private boolean chooseFiles(WebView source, ValueCallback<Uri[]> callback,
                                WebChromeClient.FileChooserParams params) {
        if (pickerInFlight || source != webView || activeOrigin == null
                || !isFileChooserSourceTrusted(source.getUrl())
                || (params.getMode() != WebChromeClient.FileChooserParams.MODE_OPEN
                && params.getMode() != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)) {
            callback.onReceiveValue(null);
            toast(R.string.upload_unavailable);
            return true;
        }
        cancelFileCallback();
        pendingFiles = callback;
        fileOwner = source;
        multipleFiles = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multipleFiles);
        List<String> mimeTypes = new ArrayList<>();
        for (String accept : params.getAcceptTypes()) {
            if (accept == null) continue;
            for (String part : accept.split(",")) {
                String type = part.trim().toLowerCase(Locale.ROOT);
                if (type.startsWith(".")) {
                    type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                }
                if (type != null && type.matches("[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)")) {
                    mimeTypes.add(type);
                }
            }
        }
        if (!mimeTypes.isEmpty() && mimeTypes.size() <= 32) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
        }
        try {
            pickerInFlight = true;
            startActivityForResult(intent, FILE_PICKER);
        } catch (ActivityNotFoundException | SecurityException exception) {
            pickerInFlight = false;
            cancelFileCallback();
            toast(R.string.upload_unavailable);
        }
        return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCAN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                handleScan(data.getStringExtra(ScanActivity.EXTRA_RESULT));
            }
            return;
        }
        if (requestCode != FILE_PICKER) return;
        pickerInFlight = false;
        ValueCallback<Uri[]> callback = pendingFiles;
        WebView owner = fileOwner;
        pendingFiles = null;
        fileOwner = null;
        if (callback == null) return;
        if (resultCode != RESULT_OK || data == null || owner != webView || activeOrigin == null
                || !isFileChooserSourceTrusted(owner.getUrl())) {
            callback.onReceiveValue(null);
            return;
        }
        Set<Uri> selected = new LinkedHashSet<>();
        ClipData clip = data.getClipData();
        boolean valid = true;
        if (clip != null) {
            if (clip.getItemCount() > MAX_UPLOAD_FILES) valid = false;
            else for (int index = 0; index < clip.getItemCount(); index++) {
                selected.add(clip.getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        if (selected.isEmpty() || (!multipleFiles && selected.size() > 1)) valid = false;
        for (Uri uri : selected) {
            if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) valid = false;
        }
        if (!valid) {
            callback.onReceiveValue(null);
            toast(R.string.upload_invalid);
        } else {
            callback.onReceiveValue(selected.toArray(new Uri[0]));
        }
    }

    private void cancelFileCallback() {
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        pendingFiles = null;
        fileOwner = null;
    }

    private void failSession(WebView source, int message) {
        runOnUiThread(() -> {
            if (!isDestroyed() && webView == source) {
                showHome();
                toast(message);
            }
        });
    }

    private void showHome() {
        if (prompt != null) prompt.dismiss();
        pendingRestore = null;
        preferences.edit().remove(LAST_PAGE).apply();
        disposeWebView();
        webPanel.setVisibility(View.GONE);
        homePanel.setVisibility(View.VISIBLE);
        status.setText(activeOrigin == null ? R.string.runtime_failed_hint : R.string.runtime_ready);
    }

    private void rememberPage(String url) {
        String record = RestorablePage.capture(activeOrigin, url);
        if (record == null) return;
        preferences.edit().putString(LAST_PAGE, record).apply();
    }

    private void disposeWebView() {
        cancelFileCallback();
        WebView old = webView;
        webView = null;
        if (old == null) return;
        old.stopLoading();
        old.setWebChromeClient(null);
        old.setWebViewClient(new WebViewClient());
        webContainer.removeView(old);
        old.clearHistory();
        old.removeAllViews();
        old.destroy();
    }

    private void navigateBack() {
        if (webView != null) {
            WebBackForwardList history = webView.copyBackForwardList();
            int previous = history.getCurrentIndex() - 1;
            if (previous >= 0 && UrlPolicy.isInApp(activeOrigin,
                    history.getItemAtIndex(previous).getUrl())) {
                cancelFileCallback();
                webView.goBack();
            } else if (UrlPolicy.localNavigation(activeOrigin, webView.getUrl())
                    == UrlPolicy.Navigation.MIRROR) {
                // 关闭镜像页回到本机客户端；两个 origin 的存储互不可见。
                launchRuntime();
            } else {
                showHome();
            }
        } else {
            finish();
        }
    }

    @Override public void onBackPressed() {
        navigateBack();
    }

    private void confirmReset() {
        showPrompt(new AlertDialog.Builder(this).setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset_confirm, (dialog, which) -> {
                    disposeWebView();
                    CookieManager.getInstance().removeAllCookies(null);
                    WebStorage.getInstance().deleteAllData();
                    preferences.edit().clear().apply();
                    activeOrigin = null;
                    activePort = 0;
                    previousOrigin.setText(R.string.no_previous);
                    // 运行时的数据目录与解包目录一起清除，避免残留旧会话数据库。
                    AndroidRuntime.deleteRecursively(new File(getFilesDir(), "data"));
                    AndroidRuntime.deleteRecursively(new File(getFilesDir(), "runtime"));
                    AndroidRuntime.deleteRecursively(new File(getFilesDir(), "workspace"));
                    status.setText(R.string.starting_runtime);
                    startRuntime();
                }));
    }

    private void showPrompt(AlertDialog.Builder builder) {
        showPrompt(builder.create());
    }

    private void showPrompt(AlertDialog next) {
        if (isFinishing() || isDestroyed()) return;
        if (prompt != null && prompt.isShowing()) return;
        prompt = next;
        next.setOnDismissListener(dialog -> {
            if (prompt == next) prompt = null;
        });
        next.show();
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onDestroy() {
        if (prompt != null) prompt.dismiss();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        disposeWebView();
        super.onDestroy();
    }
}
