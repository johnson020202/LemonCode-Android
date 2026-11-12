# Android 共存隔离与原生层代码优化规范

## 1. 目标与边界

本分支在不改动上游 Web / CLI 协议契约的前提下，完成两项交付：

1. **与原版软件完全共存（安装期 + 运行期 + 升级期全链路隔离）**：生成的 APK 可与原版 `vin.lemon.android` 在同一部手机上同时安装、同时打开、各自独立持有本机 Node 服务端、会话数据库、WebView `localStorage`、通知与文件共享通道，且不会在应用内检查更新时误拉原版签名 APK 导致签名冲突报错。
2. **Android 原生层全量代码优化**：消除子进程泄漏、端口与跨包串连隐患、重复 I/O 与无界内存读取、并发重复请求、符号链接覆盖缺陷以及死代码，并补齐对应单测与 CI 门禁。

## 2. 共存冲突面与唯一所有者设计

原版与共存分支若只改 `applicationId`，在双开同时运行时仍会在回环端口、更新资产和深链上互相污染：

```text
[原版 APK: vin.lemon.android]                 [优化共存版 APK: vin.lemon.android.plus]
 ├─ 数据目录: /data/user/0/vin.lemon.android   ├─ 数据目录: /data/user/0/vin.lemon.android.plus
 ├─ 固定回环端口: 127.0.0.1:31234              ├─ 独立回环端口: 127.0.0.1:31235（可经构建参数覆盖）
 ├─ FileProvider: vin.lemon.android.fileprovider├─ FileProvider: ${applicationId}.fileprovider
 ├─ 测试 Provider: vin.lemon.android.test...   ├─ 测试 Provider: ${applicationId}.test.gallery
 ├─ 更新资产后缀: *-arm64.apk                  ├─ 更新资产后缀: *-plus-arm64.apk（严禁回退匹配原版包）
 ├─ 桌面名称: LemonCode                        ├─ 桌面名称: LemonCode Plus / LemonCode Plus (debug)
 └─ WakeLock: vin.lemon.android:mirror-keepalive└─ WakeLock: ${packageName}:mirror-keepalive
```

### 2.1 安装与系统组件隔离
- **可配置包名**：`apps/android/app/build.gradle` 默认 `applicationId` 设为 `vin.lemon.android.plus`，同时支持通过环境变量 `ANDROID_APPLICATION_ID` 覆盖（校验合法 Android 包名格式），无需手改构建脚本即可切换包名。
- **桌面与系统设置标识**：Release 显示 `LemonCode Plus`，Debug 显示 `LemonCode Plus (debug)`，原生首页标题同步读取 `@string/preview_title`，在桌面、应用管理、通知设置与安装器中一眼区分。
- **Provider 与 Intent 隔离**：
  - 主包 `FileProvider` 使用 `${applicationId}.fileprovider`。
  - `androidTest` 中的 `GalleryImageProvider` 改为 `${applicationId}.test.gallery`，测试代码从 `InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()` 动态构造 URI，消除与原版测试 APK 的 authority 冲突。
  - `ScanActivity.EXTRA_RESULT` 保持常量协议语义，内部组件跳转全部使用显式 `Intent(context, Class)`，不跨包泄露。
  - OAuth 回调：保留 `zcode://` 与 `lcode://` 兼容官网登录回调的同时，新增 `${oauthScheme}://oauth/callback`（默认 `lcodeplus`）专属独立 scheme，避免两个客户端同时安装时深链无法定向唤起。

### 2.2 运行期回环服务端隔离（关键 Bug 修复）
- **原缺陷**：`AndroidRuntime` 硬编码 `PREFERRED_PORT = 31234`，且当 `31234` 已被占用时，`awaitServerQuietly(31234)` 只要看到 HTTP 200 且响应含 `"serverId"` 就直接复用该端口！若用户先打开原版 `vin.lemon.android`（占用 `31234`），再打开共存版，共存版会**杀死自己的子进程并静默连上原版的本机服务端**，导致两个 App 读写同一套原版会话库，彻底破坏数据隔离。
- **修复与所有权不变量**：
  1. **端口按包隔离**：通过 `BuildConfig.PREFERRED_SERVER_PORT` 为共存版分配独立固定端口 `31235`（原版 `vin.lemon.android` 为 `31234`，亦支持 `ANDROID_SERVER_PORT` 构建期指定或按 `applicationId` 派生稳定端口），既保留同一应用重启后的 WebView `localStorage` origin 连续性，又与原版天然错开。
  2. **进程所有权严格绑定**：`AndroidRuntime` 作为本进程内 Node `Process` 的唯一所有者，持有 `activeProcess` 与 `activePort`。仅当本进程自己已持有一个存活的 `activeProcess` 且健康检查通过时才直接复用；绝不盲目信任端口上由外部未知进程（如另一个安装包）监听的 `/api/server-info`。若首选端口被外部进程占用，立即回退内核分配空闲端口（`LCODE_SERVER_PORT=0`），宁可新建独立 origin 也绝不跨应用串连数据。

### 2.3 应用内热更新资产隔离
- **原缺陷**：`Updater.parseLatestRelease` 匹配任意以 `-arm64.apk` 结尾的 Release 资产。若共存版匹配到原版签名的 `LemonCode-android-x.y.z-arm64.apk`，不仅包名不一致无法升级自身，同包名自建签名的场景更会触发系统签名冲突失败。
- **修复**：`Updater` 按 `BuildConfig.UPDATE_ASSET_SUFFIX` 精确匹配专属后缀（共存版为 `-plus-arm64.apk` 与 `-plus-arm64.apk.sha256`；当后缀不含 `-plus` 时也显式排除 `-plus-arm64.apk` 以免互相误捡）。CI 工作流同步产出带专属标识的 APK 名称。

## 3. 原生层优化设计清单

1. **子进程生命周期与快速失败（`AndroidRuntime`）**
   - **启动期快速失败**：`waitForPort` 轮询期间用 `process.isAlive()` 检测子进程状态；若 Node 进程已提前崩溃退出，再做一次最终日志读取后立即抛出含退出码与日志尾部的 `RuntimeStartException`，消除原先必等满 **120 秒**超时的假死体验。
   - **重置与重启清理**：新增 `AndroidRuntime.stopServer()`（先优雅 `destroy()` 等待短暂退出，超时再 `destroyForcibly()`），在 `MainActivity.confirmReset()` 清除数据目录前先停止旧 Node 服务端，修复原先“边跑服务端边删底层 SQLite 数据库与解包目录”导致的句柄残留和端口占用。
   - **日志轮询增量读取**：`waitForPort` 只读取 `runtime.log` 尾部字节并使用预编译静态 `Pattern`，避免每 200ms 全文件分配与重复编译正则。
   - **Tar 解包缓冲与断链安全（`TarExtractor` + `AndroidRuntime`）**：`AndroidRuntime.extractPayload` 为 `assets.open(PAYLOAD)` 正确包裹 `BufferedInputStream`（注意 `runtime-payload.tar` 是未压缩 `.tar` 或 `.tar.gz` 自适应探测——先核查实际打包格式，严禁误加不匹配的解压流！）；`TarExtractor` 创建符号链接前改用 `Os.lstat` 检测断链目录项，修复 `target.exists()` 对断链返回 `false` 导致 `symlink` 抛 `EEXIST` 的隐患。
   - **清理死代码**：移除未使用的 `canonicalFilesDir`、`out(...)` 冗余包装，以及 `MainActivity` 中与 `SystemBarHelper` 重复的 `UI_BACKGROUND_*` / `appThemeIsLight` 死字段。

2. **网络与诊断 I/O 优化（`Diagnostics` / `Updater` / `DeviceSyncClient`）**
   - **修复 `Diagnostics.postBytes` 双重读取响应流 Bug**：原代码写为 `readAll(connection.getInputStream()) == null ? "OK" : new String(readAll(connection.getInputStream()), ...)`，第一次 `readAll` 已将流消费到 EOF，第二次恒返回空字符串 `""`！修复为单次读取并优先返回状态码与响应摘要。
   - **二进制流式 Multipart 上传**：`Diagnostics.multipart` 原先在内存中把整段日志拼接成巨型 `String` 再整体 `.getBytes()`，改为直接按 UTF-8 字节流构造并支持对大体积日志控制上限，同时增强脱敏规则（覆盖 URL query 中的 `token=`/`key=`/`code=`/`state=` 以及 Anthropic `sk-ant-`、OpenAI `sk-proj-` 等新型密钥前缀），并在后台回调 UI 前检查 `activity.isFinishing() || activity.isDestroyed()` 防止窗口泄漏异常。
   - **`Updater` 并发防重与进度节流**：引入 `AtomicBoolean checking / downloading` 防止连点或前后台切换并发拉起多个检查/下载线程；下载进度 UI 更新按百分比变化（每 ≥1% 或 ≥200ms）节流 `runOnUiThread`，消除每 128KB 切一次主线程的消息风暴；HTTP 非 200 时支持解析 GitHub API 限流提示。
   - **`DeviceSyncClient` JSON 安全序列化**：`importSnapshot` 原先用字符串拼接 `"{\"snapshot\":" + snapshotJson + "}"`，先用 `org.json.JSONTokener` / 轻量合法性校验确保 `snapshotJson` 为合法 JSON 对象后再发送，避免畸形快照构造出损坏请求体。

3. **前台保活与扫码页健壮性（`MirrorKeepAliveService` / `ScanActivity` / `XiaomiFocus`）**
   - **`MirrorKeepAliveService` 防泄漏与超时保护**：`WakeLock` 改用包名隔离 tag 并加设单次合理超时上限（如 4 小时并在活跃心跳时按需续期）防止极端异常永久锁死 CPU；若 `MainActivity` 已销毁且 `target` 连续丢失，自动收敛空转。
   - **`ScanActivity` 生命周期与大图解码优化**：相机重绑与生命周期状态对齐（`onStop`/`onDestroy` 复位 `cameraBound` 以便从后台/设置页授完权限返回时可靠恢复预览）；相册选图使用 `Bitmap.Config.RGB_565` 降低一半解码内存占用。
   - **`XiaomiFocus` 反射缓存**：将 `SystemProperties.get` 的 `Method` 反射查找缓存为静态常量，避免每次通知更新重复反射查找类与方法。

## 4. 验收判据

1. `apps/android` 本地 `./gradlew testDebugUnitTest lintDebug assembleDebug` 全绿通过。
2. 新增共存与优化单元测试（端口隔离、子进程退出快失败、Updater 专属后缀过滤、OAuth 多 scheme、Diagnostics 脱敏与单次流读取、TarExtractor 断链覆盖）。
3. `node scripts/android/verify-apk.mjs` 校验产出的共存版 APK 通过，且 APK 包名为 `vin.lemon.android.plus`。
4. `node --test scripts/android/*.test.mjs` 通过。
5. 所有改动提交至独立分支 `feat/android-coexist-optimized` 并经 GitHub Data API 推送至 `johnson020202/LemonCode-Android`。
