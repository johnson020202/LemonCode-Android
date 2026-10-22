package vin.lemon.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
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
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The only native screen. Remote Host / CLI and the shared Web client retain business ownership. */
public final class MainActivity extends Activity {
    private static final String PREFERENCES = "pairing";
    private static final String LAST_PATH = "last_pairing_path";
    private static final int FILE_PICKER = 31;
    private static final int MAX_UPLOAD_FILES = 32;
    private final ExecutorService assetReader = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private View homePanel;
    private View webPanel;
    private FrameLayout webContainer;
    private EditText pairingInput;
    private TextView status;
    private TextView previousAddress;
    private TextView version;
    private Button connectButton;
    private Button reconnectButton;
    private ProgressBar loadProgress;
    private Set<String> packagedFiles = Collections.emptySet();
    private boolean bundleReady;
    private String lastPath;
    private WebView webView;
    private UrlPolicy.PairingLink activeLink; // Always reconstructed from the secret-free path.
    private AlertDialog prompt;
    private ValueCallback<Uri[]> pendingFiles;
    private WebView fileOwner;
    private boolean multipleFiles;
    private boolean pickerInFlight;
    private OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        homePanel = findViewById(R.id.home_panel);
        webPanel = findViewById(R.id.web_panel);
        webContainer = findViewById(R.id.web_container);
        pairingInput = findViewById(R.id.pairing_input);
        status = findViewById(R.id.status);
        previousAddress = findViewById(R.id.previous_address);
        version = findViewById(R.id.version);
        connectButton = findViewById(R.id.connect);
        reconnectButton = findViewById(R.id.reconnect);
        loadProgress = findViewById(R.id.load_progress);
        // Never restore the pasted capability via view hierarchy, autofill or WebView state.
        pairingInput.setSaveEnabled(false);
        pairingInput.setSaveFromParentEnabled(false);
        lastPath = loadLastPath();
        updatePreviousAddress();
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        configureInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        connectButton.setOnClickListener(view -> requestConnection(pairingInput.getText().toString()));
        reconnectButton.setOnClickListener(view -> {
            if (lastPath != null) requestConnection(lastPath);
        });
        pairingInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) {
                requestConnection(pairingInput.getText().toString());
                return true;
            }
            return false;
        });
        findViewById(R.id.home_button).setOnClickListener(view -> showHome(R.string.bundle_ready));
        findViewById(R.id.forget).setOnClickListener(view -> confirmForget());
        loadPackagedClient();
    }

    private void configureInsets() {
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        != Configuration.UI_MODE_NIGHT_YES;
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? appearance : 0, appearance);
            }
        } else {
            // API 26–29: framework decor handles system bars; adjustResize handles the IME.
            root.setFitsSystemWindows(true);
            if (Build.VERSION.SDK_INT >= 27) {
                boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        != Configuration.UI_MODE_NIGHT_YES;
                getWindow().getDecorView().setSystemUiVisibility(light
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            } else {
                // Android 8.0 无深色导航图标，浅色背景会让系统按钮不可见。
                getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
            }
        }
        root.requestApplyInsets();
    }

    private String loadLastPath() {
        String stored = preferences.getString(LAST_PATH, null);
        if (stored == null) return null;
        try {
            String clean = UrlPolicy.parsePairingLink(stored).persistedUrl();
            if (!clean.equals(stored)) preferences.edit().putString(LAST_PATH, clean).apply();
            return clean;
        } catch (IllegalArgumentException exception) {
            preferences.edit().remove(LAST_PATH).apply();
            return null;
        }
    }

    private void updatePreviousAddress() {
        previousAddress.setText(lastPath == null ? getString(R.string.no_previous)
                : getString(R.string.previous_address, lastPath));
        reconnectButton.setEnabled(bundleReady && lastPath != null);
    }

    private void loadPackagedClient() {
        version.setText(getString(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        AssetManager assets = getAssets();
        assetReader.execute(() -> {
            Set<String> found = new HashSet<>();
            boolean ready;
            try {
                collectAssets(assets, "", found);
                // Verify index really opens; a missing package must never fall back to remote app code.
                try (InputStream index = assets.open("www/index.html")) {
                    ready = found.contains("index.html") && index.read() >= 0;
                }
            } catch (IOException exception) {
                ready = false;
            }
            String metadata = readMetadata(assets);
            boolean available = ready;
            Set<String> immutableFiles = Collections.unmodifiableSet(found);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                packagedFiles = immutableFiles;
                bundleReady = available;
                connectButton.setEnabled(bundleReady);
                status.setText(bundleReady ? R.string.bundle_ready : R.string.bundle_missing);
                updatePreviousAddress();
                version.setText(getString(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                        + "\n" + metadata);
            });
        });
    }

    private static void collectAssets(AssetManager assets, String relative, Set<String> result) throws IOException {
        String[] children = assets.list(relative.isEmpty() ? "www" : "www/" + relative);
        if (children == null) return;
        if (children.length == 0 && !relative.isEmpty()) {
            if (UrlPolicy.isSafeAssetPath(relative)) result.add(relative);
            return;
        }
        for (String child : children) {
            String path = relative.isEmpty() ? child : relative + "/" + child;
            if (UrlPolicy.isSafeAssetPath(path)) collectAssets(assets, path, result);
        }
    }

    private String readMetadata(AssetManager assets) {
        try (InputStream source = assets.open("android-build.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > 32768) throw new IOException("Metadata too large");
                output.write(buffer, 0, count);
            }
            JSONObject json = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            return getString(R.string.metadata, displayValue(json.optString("clientVersion", "unknown")),
                    displayValue(json.optString("upstreamSha", "unknown")));
        } catch (Exception exception) {
            return getString(R.string.metadata_unavailable);
        }
    }

    private static String displayValue(String value) {
        return value.matches("[A-Za-z0-9._+/-]{1,100}") ? value : "unknown";
    }

    private void requestConnection(String input) {
        pairingInput.setText("");
        if (!bundleReady) {
            status.setText(R.string.bundle_missing);
            return;
        }
        final UrlPolicy.PairingLink link;
        try {
            link = UrlPolicy.parsePairingLink(input);
        } catch (IllegalArgumentException exception) {
            status.setText(R.string.invalid_link);
            return;
        }
        String lastOrigin = lastPath == null ? null : UrlPolicy.parsePairingLink(lastPath).origin();
        if (link.origin().equals(lastOrigin)) {
            connect(link);
        } else {
            showPrompt(new AlertDialog.Builder(this)
                    .setTitle(R.string.trust_title)
                    .setMessage(getString(R.string.trust_message, link.origin()))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.trust_accept, (dialog, which) -> connect(link)));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void connect(UrlPolicy.PairingLink link) {
        disposeWebView();
        lastPath = link.persistedUrl();
        preferences.edit().putString(LAST_PATH, lastPath).apply();
        updatePreviousAddress();
        activeLink = UrlPolicy.parsePairingLink(lastPath);
        UrlPolicy.PairingLink selected = activeLink;
        Set<String> files = packagedFiles;
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(selected.host())
                .setHttpAllowed(false)
                .addPathHandler("/", path -> {
                    String asset = UrlPolicy.localAssetPath(selected, selected.origin() + "/" + path, files);
                    if (asset != null) return openAsset(asset);
                    return UrlPolicy.isStaticNamespace("/" + path) ? errorResponse(404, "Not Found") : null;
                }).build();

        WebView view = new WebView(this);
        webView = view;
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true); // Required by the real React client; no native JS interface.
        settings.setDomStorageEnabled(true); // Existing per-origin Web device credential store.
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
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        // This preview has no offline service worker contract; prevent it becoming another network loader.
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setAllowContentAccess(false);
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setAllowFileAccess(false);
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(true);
        view.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView ignored, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String scheme = request.getUrl().getScheme();
                if (request.isForMainFrame() && (!"GET".equals(request.getMethod())
                        || UrlPolicy.navigation(selected, url) != UrlPolicy.Navigation.IN_APP)) {
                    return errorResponse(403, "Forbidden");
                }
                if ("http".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)
                        || "content".equalsIgnoreCase(scheme)) return errorResponse(403, "Forbidden");
                boolean selectedHost = "https".equalsIgnoreCase(scheme)
                        && selected.host().equalsIgnoreCase(request.getUrl().getHost())
                        && (request.getUrl().getPort() == -1 || request.getUrl().getPort() == 443);
                if (!selectedHost) return null;
                // API 路径可能包含编码的工作区/文件名；仅静态资源执行 APK 路径校验。
                if (UrlPolicy.isServerPath(request.getUrl().getPath())) return null;
                if (!UrlPolicy.isSelectedOrigin(selected, url)) return errorResponse(403, "Forbidden");
                if (!"GET".equals(request.getMethod())) {
                    return UrlPolicy.localAssetPath(selected, url, files) != null
                            ? errorResponse(405, "Method Not Allowed") : null;
                }
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest request) {
                if (source != webView) return true;
                if (!request.isForMainFrame()) {
                    return UrlPolicy.navigation(selected, request.getUrl().toString()) != UrlPolicy.Navigation.IN_APP;
                }
                return handleNavigation(request.getUrl().toString());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView source, String url) {
                return source != webView || handleNavigation(url);
            }

            @Override public void onPageStarted(WebView source, String url, android.graphics.Bitmap icon) {
                if (source == webView) cancelFileCallback();
                if (source == webView && UrlPolicy.navigation(selected, url) != UrlPolicy.Navigation.IN_APP) {
                    source.stopLoading();
                    failSession(source, R.string.navigation_blocked);
                }
            }

            @Override public void onReceivedSslError(WebView source, SslErrorHandler handler, SslError error) {
                handler.cancel();
                failSession(source, R.string.connection_error);
            }

            @Override public void onReceivedError(WebView source, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) failSession(source, R.string.connection_error);
            }

            @Override public void onReceivedHttpError(WebView source, WebResourceRequest request, WebResourceResponse response) {
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
                return true; // No console/URL logging from the paired credential-bearing document.
            }

            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }

            @Override public boolean onCreateWindow(WebView source, boolean dialog, boolean gesture, Message message) {
                // A real user link can be routed without ever creating a remotely loaded child WebView.
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

            @Override public boolean onShowFileChooser(WebView source, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return chooseFiles(source, callback, params);
            }
        });
        view.setDownloadListener((url, agent, disposition, type, length) -> toast(R.string.download_unsupported));
        webContainer.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ((TextView) findViewById(R.id.current_origin)).setText(selected.origin());
        homePanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.VISIBLE);
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(pairingInput.getWindowToken(), 0);
        view.requestFocus();
        view.loadUrl(link.launchUrl());
    }

    private WebResourceResponse openAsset(String relative) {
        try {
            String extension = relative.substring(relative.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String mime;
            switch (extension) {
                case "js": case "mjs": mime = "text/javascript"; break;
                case "css": mime = "text/css"; break;
                case "html": mime = "text/html"; break;
                case "json": case "map": mime = "application/json"; break;
                case "wasm": mime = "application/wasm"; break;
                case "svg": mime = "image/svg+xml"; break;
                default: mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            }
            if (mime == null) mime = "application/octet-stream";
            boolean text = mime.startsWith("text/") || mime.equals("application/json") || mime.equals("image/svg+xml");
            WebResourceResponse response = new WebResourceResponse(mime, text ? "UTF-8" : null,
                    getAssets().open("www/" + relative, AssetManager.ACCESS_STREAMING));
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Referrer-Policy", "no-referrer");
            if (mime.equals("text/html")) {
                // Bundled inline theme bootstrap / blob workers are allowed, remote frames are not.
                headers.put("Content-Security-Policy", "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval' blob:; "
                        + "worker-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'self'");
            }
            response.setResponseHeaders(headers);
            return response;
        } catch (IOException exception) {
            return errorResponse(404, "Not Found");
        }
    }

    private static WebResourceResponse errorResponse(int status, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", status, reason,
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }

    private boolean handleNavigation(String url) {
        UrlPolicy.Navigation action = UrlPolicy.navigation(activeLink, url);
        if (action == UrlPolicy.Navigation.IN_APP) return false;
        if (action == UrlPolicy.Navigation.EXTERNAL_HTTPS) {
            Uri target = Uri.parse(url);
            String destination = "https://" + target.getHost() + (target.getPort() == -1 ? "" : ":" + target.getPort());
            showPrompt(new AlertDialog.Builder(this).setTitle(R.string.external_title)
                    .setMessage(getString(R.string.external_message, destination))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.external_open, (dialog, which) -> {
                        // Validate again at the boundary; never parse or launch arbitrary intent schemes.
                        if (UrlPolicy.navigation(activeLink, url) != UrlPolicy.Navigation.EXTERNAL_HTTPS) return;
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE));
                        } catch (ActivityNotFoundException | SecurityException exception) {
                            toast(R.string.external_unavailable);
                        }
                    }));
        } else {
            String scheme = Uri.parse(url).getScheme();
            toast("blob".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)
                    ? R.string.download_unsupported : R.string.navigation_blocked);
        }
        return true;
    }

    private boolean chooseFiles(WebView source, ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (pickerInFlight || source != webView || activeLink == null
                || UrlPolicy.navigation(activeLink, source.getUrl()) != UrlPolicy.Navigation.IN_APP
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
                if (type.startsWith(".")) type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                if (type != null && type.matches("[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)")) mimeTypes.add(type);
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
        if (requestCode != FILE_PICKER) return;
        pickerInFlight = false;
        ValueCallback<Uri[]> callback = pendingFiles;
        WebView owner = fileOwner;
        pendingFiles = null;
        fileOwner = null;
        if (callback == null) return;
        if (resultCode != RESULT_OK || data == null || owner != webView || activeLink == null
                || UrlPolicy.navigation(activeLink, owner.getUrl()) != UrlPolicy.Navigation.IN_APP) {
            callback.onReceiveValue(null);
            return;
        }
        Set<Uri> selected = new LinkedHashSet<>();
        ClipData clip = data.getClipData();
        boolean valid = true;
        if (clip != null) {
            if (clip.getItemCount() > MAX_UPLOAD_FILES) valid = false;
            else for (int index = 0; index < clip.getItemCount(); index++) selected.add(clip.getItemAt(index).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty() || (!multipleFiles && selected.size() > 1)) valid = false;
        for (Uri uri : selected) {
            if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) valid = false;
        }
        if (!valid) {
            callback.onReceiveValue(null);
            toast(R.string.upload_invalid);
        } else {
            // No takePersistableUriPermission: the user grants access only for this upload session.
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
                showHome(message);
                toast(message);
            }
        });
    }

    private void showHome(int message) {
        if (prompt != null) prompt.dismiss();
        disposeWebView();
        pairingInput.setText("");
        webPanel.setVisibility(View.GONE);
        homePanel.setVisibility(View.VISIBLE);
        status.setText(bundleReady ? message : R.string.bundle_missing);
        updatePreviousAddress();
    }

    private void disposeWebView() {
        cancelFileCallback();
        WebView old = webView;
        webView = null;
        activeLink = null;
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
            if (previous >= 0 && UrlPolicy.navigation(activeLink, history.getItemAtIndex(previous).getUrl())
                    == UrlPolicy.Navigation.IN_APP) {
                cancelFileCallback();
                webView.goBack();
            } else showHome(R.string.bundle_ready);
        } else finish();
    }

    @Override public void onBackPressed() { navigateBack(); }

    private void confirmForget() {
        showPrompt(new AlertDialog.Builder(this).setTitle(R.string.forget_title)
                .setMessage(R.string.forget_message).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget_confirm, (dialog, which) -> {
                    showHome(R.string.bundle_ready);
                    preferences.edit().clear().apply();
                    lastPath = null;
                    CookieManager.getInstance().removeAllCookies(null);
                    WebStorage.getInstance().deleteAllData();
                    updatePreviousAddress();
                }));
    }

    private void showPrompt(AlertDialog.Builder builder) {
        if (isFinishing() || isDestroyed()) return;
        if (prompt != null && prompt.isShowing()) return;
        AlertDialog next = builder.create();
        prompt = next;
        next.setOnDismissListener(dialog -> { if (prompt == next) prompt = null; });
        next.show();
    }

    private void toast(int message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

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
        assetReader.shutdownNow();
        super.onDestroy();
    }
}
