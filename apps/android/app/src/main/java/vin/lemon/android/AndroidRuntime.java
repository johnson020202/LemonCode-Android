package vin.lemon.android;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 独立运行时：把 APK 内置的 Node、服务端、Agent 与 Web 产物解包到应用私有目录，
 * 并把只能从 nativeLibraryDir 执行的 ELF 以符号链接暴露到 usr/bin 与 usr/lib。
 *
 * <p>Android 10+ 禁止从应用数据目录执行随包文件，唯一允许的位置是安装器解压的
 * {@code nativeLibraryDir}（lib*.so）。因此运行时分成两部分：jniLibs 中的 ELF 与
 * assets 中的数据包，均由 APK 签名保护，运行时不再从网络下载可执行内容。
 */
final class AndroidRuntime {
    private static final String MANIFEST = "runtime/runtime-manifest.json";
    private static final String PAYLOAD = "runtime/runtime-payload.tar";
    private static final String MARKER = ".runtime-digest";
    private static final long START_TIMEOUT_MILLIS = 120_000;
    private static final Pattern PORT_PATTERN =
            Pattern.compile("http://127\\.0\\.0\\.1:(\\d{2,5})");
    private static final Pattern LINK_PATH_PATTERN =
            Pattern.compile("usr/(bin|lib)/[A-Za-z0-9._+-]+");
    private static final Pattern SONAME_PATTERN =
            Pattern.compile("lib[A-Za-z0-9_]+\\.so");
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("[a-f0-9]{64}");

    /**
     * 按安装包隔离的首选回环端口：WebView 的 localStorage 按 origin 隔离，同一应用内保持固定端口
     * 可延续草稿与主题；同时与原版 vin.lemon.android（31234）错开，避免双开冲突。
     */
    static final int PREFERRED_PORT = BuildConfig.PREFERRED_SERVER_PORT;

    /**
     * 当前进程自启动的 Node 服务端句柄（唯一所有者）。
     * 修复跨包串连与僵尸进程隐患：
     * 1) 原实现当首选端口被原版应用占用时，awaitServerQuietly 只检查 /api/server-info 含 serverId，
     *    会误连到原版应用的服务端，导致两个应用共用原版数据库；现仅允许复用本进程亲自持有的存活子进程。
     * 2) 原实现未保存 Process 引用，重置本机数据时旧 Node 服务端仍在占用 SQLite 与回环端口；
     *    现由 stopServer() 统一终止并回收。
     */
    private static final Object PROCESS_LOCK = new Object();
    private static Process activeProcess;
    private static int activePort;

    static final class RuntimeStartException extends Exception {
        RuntimeStartException(String message) {
            super(message);
        }

        RuntimeStartException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private AndroidRuntime() {}

    /** 按 applicationId 选取互不冲突的稳定回环端口（供构建配置与单元测试验证）。 */
    static int preferredPortForPackage(String packageName) {
        if ("vin.lemon.android".equals(packageName)) return 31234;
        if ("vin.lemon.android.plus".equals(packageName)) return 31235;
        if (packageName == null || packageName.isEmpty()) return 31235;
        return 32000 + Math.abs(packageName.hashCode() % 7000);
    }

    /** 解包并准备运行时根目录；已准备且摘要一致时只重建链接。 */
    static File prepare(android.content.Context context) throws IOException {
        File root = new File(context.getFilesDir(), "runtime");
        String manifest = readAssetText(context.getAssets(), MANIFEST);
        String digest = requireField(manifest, "payloadSha256");
        File marker = new File(root, MARKER);
        boolean reusable = root.isDirectory() && marker.isFile() && digest.equals(readText(marker));
        if (reusable) {
            // 覆盖安装会更换 nativeLibraryDir（路径含安装哈希），旧符号链接随即失效。
            // 数据无需重解包，但链接必须按当前安装目录重建，否则运行时报 "bundle is incomplete"。
            linkNativeLibraries(context, root, manifest);
            return root;
        }
        deleteRecursively(root);
        if (!root.mkdirs()) throw new IOException("Cannot create runtime directory");
        extractPayload(context.getAssets(), root);
        // 数据包已含 server/www；若旧包结构不同则回退到顶层 www，避免静默缺少前端。
        if (!new File(root, "server/www").isDirectory() && new File(root, "www").isDirectory()) {
            if (!new File(root, "www").renameTo(new File(root, "server/www"))) {
                throw new IOException("Cannot normalize runtime web assets");
            }
        }
        linkNativeLibraries(context, root, manifest);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(digest.getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private static void extractPayload(android.content.res.AssetManager assets, File root)
            throws IOException {
        try (InputStream raw = assets.open(PAYLOAD);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(raw, 64 * 1024))) {
            TarExtractor.extract(gzip, root);
        }
    }

    /**
     * 建立 usr/bin、usr/lib 指向 nativeLibraryDir 的符号链接。
     * ELF 的 DT_NEEDED 使用 SONAME（如 libz.so.1），因此必须按原文件名可见。
     */
    private static void linkNativeLibraries(android.content.Context context, File root, String manifest)
            throws IOException {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String[] link : parseLinks(manifest)) {
            File target = new File(root, link[0]);
            File source = new File(nativeDir, link[1]);
            if (!source.isFile()) throw new IOException("Missing packaged runtime file for " + link[0]);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create runtime directory for " + link[0]);
            }
            // 覆盖安装后旧链接指向已删除的安装目录：exists() 会跟随链接返回 false，
            // 但目录项本身仍在，必须用 NOFOLLOW_LINKS 检测并删除，否则 symlink 报 EEXIST，
            // 后续读取会沿断链得到 ENOENT。
            if (linkExists(target) && !target.delete()) {
                throw new IOException("Cannot replace runtime link " + link[0]);
            }
            if (!symlink(source.getAbsolutePath(), target.getAbsolutePath())) {
                copyFile(source, target);
            }
            // 原生库由安装器解包并已具备执行位；应用无权对 apk_data_file 做 setattr，
            // 这里只校验结果，不尝试 chmod（否则 SELinux 会拒绝并中断整个准备流程）。
            if (link[0].startsWith("usr/bin/") && !target.canExecute()) {
                throw new IOException("Packaged runtime binary is not executable: " + link[0]);
            }
        }
    }

    /** 不跟随符号链接判断目录项是否存在（用于清理覆盖安装留下的断链）。 */
    private static boolean linkExists(File file) {
        try {
            android.system.Os.lstat(file.getAbsolutePath());
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean symlink(String target, String link) {
        try {
            android.system.Os.symlink(target, link);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new java.io.FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    /** 只解析 manifest 的 links 数组；路径与安装名都按白名单校验。 */
    static List<String[]> parseLinks(String json) throws IOException {
        List<String[]> links = new ArrayList<>();
        int array = json.indexOf("\"links\"");
        int start = array < 0 ? -1 : json.indexOf('[', array);
        int end = start < 0 ? -1 : json.indexOf(']', start);
        if (start < 0 || end < 0) throw new IOException("Runtime manifest is missing links");
        for (String object : json.substring(start + 1, end).split("\\{")) {
            String path = optionalField(object, "path");
            String soname = optionalField(object, "soname");
            if (path == null || soname == null) continue;
            if (!LINK_PATH_PATTERN.matcher(path).matches() || !SONAME_PATTERN.matcher(soname).matches()) {
                throw new IOException("Unsafe runtime entry");
            }
            links.add(new String[]{path, soname});
        }
        return links;
    }

    private static String requireField(String json, String name) throws IOException {
        String value = optionalField(json, name);
        if (value == null || !SHA256_PATTERN.matcher(value).matches()) {
            throw new IOException("Invalid runtime manifest field: " + name);
        }
        return value;
    }

    private static String optionalField(String object, String name) {
        String key = "\"" + name + "\"";
        int at = object.indexOf(key);
        if (at < 0) return null;
        int colon = object.indexOf(':', at + key.length());
        int first = object.indexOf('"', colon + 1);
        int last = first < 0 ? -1 : object.indexOf('"', first + 1);
        return colon < 0 || first < 0 || last < 0 ? null : object.substring(first + 1, last);
    }

    private static String readAssetText(android.content.res.AssetManager assets, String name)
            throws IOException {
        try (InputStream input = assets.open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readText(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        // 符号链接只删除链接本身，绝不跟随到 nativeLibraryDir。
        file.delete();
    }

    /** 停止本进程启动的 Node 服务端（用于清除本机数据并重启前释放文件句柄与端口）。 */
    static void stopServer() {
        Process owned;
        synchronized (PROCESS_LOCK) {
            owned = activeProcess;
            activeProcess = null;
            activePort = 0;
        }
        killQuietly(owned);
    }

    /** 启动本机服务端并等待 /api/server-info 真实可访问；返回监听端口。 */
    static int startServer(android.content.Context context, File root) throws RuntimeStartException {
        // 若本进程自己启动的服务端仍然存活且健康，直接复用同一端口，避免重复拉起子进程。
        synchronized (PROCESS_LOCK) {
            if (activeProcess != null && activeProcess.isAlive() && activePort > 0
                    && isServerHealthy(activePort)) {
                return activePort;
            }
            killQuietly(activeProcess);
            activeProcess = null;
            activePort = 0;
        }

        File node = new File(root, "usr/bin/node");
        File server = new File(root, "server/entry-http.mjs");
        File www = new File(root, "server/www");
        if (!node.isFile() || !server.isFile() || !www.isDirectory()) {
            throw new RuntimeStartException("Runtime bundle is incomplete");
        }
        File workspace = new File(context.getFilesDir(), "workspace");
        // HOME 保持 getFilesDir() 的原始形式：HyperOS 上规范的 /data/data 路径会被
        // 文件系统访问策略拒绝（实测 EACCES），必须在运行时内处理 /data/user/0 别名。
        File home = new File(context.getFilesDir(), "home");
        // 服务端的 LCODE_DATA_BASE_DIR；个人 provider 配置就在其 .lcode/v2 下。
        File dataDir = new File(context.getFilesDir(), "data");
        File log = new File(context.getFilesDir(), "runtime.log");
        if (!workspace.isDirectory() && !workspace.mkdirs()) {
            throw new RuntimeStartException("Cannot create workspace");
        }
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new RuntimeStartException("Cannot create runtime home");
        }
        // Agent 会在 TMPDIR 下创建 Unix socket（znr-*.sock）。目录不存在时
        // listen 直接 EACCES，Agent 随即退出并表现为 stdout_closed。
        File tmpDir = new File(home, "tmp");
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            throw new RuntimeStartException("Cannot create runtime temp directory");
        }
        ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), server.getAbsolutePath());
        builder.directory(root);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", tmpDir.getAbsolutePath());
        builder.environment().put("LCODE_SERVER_PORT", String.valueOf(PREFERRED_PORT));
        builder.environment().put("LCODE_ANDROID_RUNTIME_ROOT", new File(root, "server").getAbsolutePath());
        builder.environment().put("LCODE_WEB_STATIC_ROOT", www.getAbsolutePath());
        builder.environment().put("LCODE_SERVER_WORKSPACE", workspace.getAbsolutePath());
        builder.environment().put("LCODE_DATA_BASE_DIR", dataDir.getAbsolutePath());
        // 官网 /app/oauth/login 只接受 zcode://oauth/callback 作为 redirect，
        // 传 lcode:// 会被判为无效，页面上的「打开 ZCode」按钮点了也没有反应。
        builder.environment().put("LCODE_OAUTH_CALLBACK_URI", "zcode://oauth/callback");
        // 运行时自带 CA 包（Termux ca-certificates）。Node 使用编译期内置根证书，
        // 在该 Android 运行时上会对真实证书报 UNABLE_TO_GET_ISSUER_CERT_LOCALLY。
        // 实测：单独设置 NODE_EXTRA_CA_CERTS 可正常握手；同时设置 SSL_CERT_FILE 反而失败。
        File certs = new File(root, "usr/etc/tls/cert.pem");
        builder.environment().put("NODE_EXTRA_CA_CERTS", certs.getAbsolutePath());
        // Agent 子进程会经 sanitizeLCodeRuntimeEnv 清掉 NODE_EXTRA_CA_CERTS/SSL_CERT_FILE，
        // 因此必须用上游显式 CA 通道：LCODE_AGENT_CA_CERT 不在清洗名单内，
        // Agent 侧 applyCaEnv 会用它设置自身的 CA 目标变量。
        builder.environment().put("LCODE_AGENT_CA_CERT", certs.getAbsolutePath());
        builder.environment().put("PATH", new File(root, "usr/bin").getAbsolutePath() + ":/system/bin");
        builder.environment().put("LD_LIBRARY_PATH", new File(root, "usr/lib").getAbsolutePath());
        // Agent 由随包 Node 执行随包 lcode.cjs：会话、终端与工具都在本机运行，不依赖桌面 Host。
        // 不能使用 .sh 包装器：Android 10+ 禁止从应用数据目录执行脚本（实测 spawn EACCES）。
        builder.environment().put("LCODE_AGENT_SERVER_COMMAND", node.getAbsolutePath());
        builder.environment().put("LCODE_AGENT_SERVER_ARGS_JSON",
                "[\"" + new File(root, "server/lcode.cjs").getAbsolutePath()
                        + "\",\"app-server\",\"--stdio\"]");
        builder.environment().put("LCODE_BUILTIN_PROVIDER_CONFIG_FILE",
                new File(root, "server/lcode-builtin.json").getAbsolutePath());
        // 服务端把个人 provider 配置写在 LCODE_DATA_BASE_DIR/.lcode/v2 下，
        // 而应用数据目录与 home 是两处；Agent 必须读到同一份文件，否则 providerCount=0。
        builder.environment().put("LCODE_PERSONAL_PROVIDER_CONFIG_FILE",
                new File(dataDir, ".lcode/v2/provider_config.json").getAbsolutePath());

        // 启动前清理上一轮残留的 runtime.log，防止 waitForPort 误读上一次运行的旧端口号。
        //noinspection ResultOfMethodCallIgnored
        log.delete();
        Process process = startOnce(builder);
        int port;
        try {
            port = waitForPort(process, log, START_TIMEOUT_MILLIS);
        } catch (RuntimeStartException preferredPortFailure) {
            // 首选端口被同机其它进程（如共存的另一个客户端实例）占用时，绝不盲连外部端口，
            // 而是立即清理失败进程并让内核分配空闲端口（LCODE_SERVER_PORT=0），确保数据完全隔离。
            killQuietly(process);
            //noinspection ResultOfMethodCallIgnored
            log.delete();
            builder.environment().put("LCODE_SERVER_PORT", "0");
            process = startOnce(builder);
            try {
                port = waitForPort(process, log, START_TIMEOUT_MILLIS);
            } catch (RuntimeStartException fallbackFailure) {
                killQuietly(process);
                throw fallbackFailure;
            }
        }
        try {
            awaitServer(process, port, log);
        } catch (RuntimeStartException readinessFailure) {
            killQuietly(process);
            throw readinessFailure;
        }
        synchronized (PROCESS_LOCK) {
            activeProcess = process;
            activePort = port;
        }
        return port;
    }

    /** 检查本进程已持有的服务端端口是否正常响应。 */
    private static boolean isServerHealthy(int port) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + port + "/api/server-info").openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(2000);
            if (connection.getResponseCode() != 200) return false;
            try (InputStream body = connection.getInputStream()) {
                String text = new String(readAll(body), StandardCharsets.UTF_8);
                return text.contains("\"serverId\"");
            }
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static Process startOnce(ProcessBuilder builder) throws RuntimeStartException {
        try {
            return builder.start();
        } catch (IOException exception) {
            throw new RuntimeStartException("Cannot start local runtime", exception);
        }
    }

    private static void killQuietly(Process process) {
        if (process == null) return;
        try {
            process.destroy();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            try {
                process.destroyForcibly();
            } catch (RuntimeException ignored) {}
        } catch (RuntimeException ignored) {
            // 进程可能已自行退出。
        }
    }

    /**
     * 从服务端启动日志中读取实际监听端口。
     *
     * <p>修复启动期崩溃假死：原实现未检查 {@code process.isAlive()}，当端口被占用或二进制报错退出时，
     * 仍会空等满 120 秒。现一旦检测到子进程已退出且日志无端口，立即快速失败并附带退出码。
     * 同时采用增量文件读取，避免每 200ms 全量重读整份日志。
     */
    static int waitForPort(Process process, File log, long timeoutMillis) throws RuntimeStartException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        StringBuilder tailBuffer = new StringBuilder(512);
        long readOffset = 0L;
        while (System.currentTimeMillis() < deadline) {
            readOffset = appendNewLogChunk(log, readOffset, tailBuffer);
            int parsed = extractPort(tailBuffer);
            if (parsed > 0) return parsed;
            if (process != null && !process.isAlive()) {
                appendNewLogChunk(log, readOffset, tailBuffer);
                int finalPort = extractPort(tailBuffer);
                if (finalPort > 0) return finalPort;
                throw new RuntimeStartException("Local runtime exited early (code "
                        + process.exitValue() + ").\n" + tail(log));
            }
            sleepBriefly();
        }
        throw new RuntimeStartException("Local runtime did not report a listening port.\n" + tail(log));
    }

    private static long appendNewLogChunk(File log, long offset, StringBuilder buffer) {
        if (log == null || !log.isFile()) return offset;
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long length = raf.length();
            if (length < offset) {
                offset = 0L;
                buffer.setLength(0);
            }
            if (length == offset) return offset;
            long toRead = Math.min(length - offset, 64 * 1024L);
            raf.seek(offset);
            byte[] chunk = new byte[(int) toRead];
            int read = raf.read(chunk);
            if (read > 0) {
                buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                // 只保留足够匹配 URL 的滑窗，避免长启动日志无界增长。
                if (buffer.length() > 8192) {
                    buffer.delete(0, buffer.length() - 4096);
                }
                return offset + read;
            }
        } catch (IOException ignored) {
            // 日志仍在并发写入；下一拍继续读取。
        }
        return offset;
    }

    static int extractPort(CharSequence text) {
        Matcher matcher = PORT_PATTERN.matcher(text);
        if (matcher.find()) {
            int port = Integer.parseInt(matcher.group(1));
            if (port > 0 && port <= 65535) return port;
        }
        return -1;
    }

    private static void sleepBriefly() throws RuntimeStartException {
        try {
            Thread.sleep(150);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeStartException("Interrupted while starting runtime");
        }
    }

    private static void awaitServer(Process process, int port, File log) throws RuntimeStartException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
        String url = "http://127.0.0.1:" + port + "/api/server-info";
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new RuntimeStartException("Local runtime exited before becoming ready (code "
                        + process.exitValue() + ").\n" + tail(log));
            }
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(2000);
                if (connection.getResponseCode() == 200) {
                    try (InputStream body = connection.getInputStream()) {
                        if (body.read() >= 0) return;
                    }
                }
            } catch (IOException ignored) {
                // 尚未监听；继续等待到截止时间。
            } finally {
                if (connection != null) connection.disconnect();
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeStartException("Interrupted while starting runtime");
            }
        }
        throw new RuntimeStartException("Local runtime did not become ready.\n" + tail(log));
    }

    private static String tail(File log) {
        if (log == null || !log.isFile()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long length = raf.length();
            int size = (int) Math.min(length, 2048L);
            byte[] bytes = new byte[size];
            raf.seek(length - size);
            raf.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() <= 1200 ? text : text.substring(text.length() - 1200);
        } catch (IOException exception) {
            return "";
        }
    }
}
