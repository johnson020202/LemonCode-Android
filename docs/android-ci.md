# Android 独立运行 + 远程控制版：构建、验证与边界

## 现状

APK 内自带 Node 运行时、上游本地服务端、Agent 与共享 Web 客户端，默认在手机内独立执行、不需要电脑。已在 Android 15 arm64 模拟器上验证：应用解包运行时、启动本机服务端、WebView 加载真实界面（`127.0.0.1:<port>`）。除独立运行外，还支持扫电脑上的二维码进入**远程控制**（加入电脑远控房间操作电脑工作区）或**会话同步**（把电脑上的一条会话快照一次性拉到本机）。

**这仍不是“所有功能完全同步”的完成品。** 桌面 Electron 能力（内嵌浏览器、Computer Use）、文件下载等尚未实现，列表见下。

## 组件所有者

- `apps/android`：唯一 Activity、运行时解包、符号链接、启动与停止、WebView 容器与 SAF 上传。
- `scripts/android/`：可复现构建（Web → 服务端 bundle → Agent → Termux 运行时 → APK 资源）。
- 运行时二进制来自 Termux 官方 apt 源，按 apt 索引逐包校验 SHA-256；不下载任意脚本执行。
- 会话/任务事实由本机上游服务端与其 Agent 进程持有，不存在第二套状态。

## 本地构建

```sh
pnpm install --frozen-lockfile --ignore-scripts
export NODE_OPTIONS=--max-old-space-size=6144
export ANDROID_HOME=<sdk> JAVA_HOME=<jdk21>
node scripts/android/build-standalone.mjs
(cd apps/android && ./gradlew testDebugUnitTest lintDebug assembleDebug)
node scripts/android/verify-apk.mjs
```

`build-standalone.mjs` 幂等：Web 产物、服务端 bundle、Agent CLI、Termux 包、精简运行时、jniLibs 与 assets 都会重建。生成目录（`.android-tools/`、`jniLibs/`、`assets/runtime/`、`assets/www/`）不入库。

## 自动构建

- `.github/workflows/android-build.yml`：push main / PR / 手动触发；跑脚本测试、`pnpm typecheck`、`pnpm lint`、架构检查、移动端契约测试、独立运行时构建、Gradle 单测/lint/打包，并校验 APK 内容后上传 artifact。
- `.github/workflows/android-upstream-sync.yml`：每 30 分钟检查上游 `lemon-casino/LemonCode` main，用普通 merge 保留 Android 适配，冲突即失败且不推送；成功后显式调用构建流程。
- 轮询无法保证“每次上游 push 立即构建”；严格实时需上游仓库接入 workflow 或 webhook。
- 当前仓库：`johnson020202/LemonCode-Android`（私有）。
- `.github/workflows/android-release.yml`：打 `android-v*` tag 或手动运行会产出**固定密钥签名**的 release APK，并按需创建 GitHub Release。密钥保存在仓库 Secrets（`ANDROID_KEYSTORE_BASE64` 等），缺少任一项即失败，绝不回退 debug 证书。

## 已验证（2026-10-25）

- 上游基线 `40851a3`（与上游 v3.21.0 同一提交），客户端 `3.17.5`；上游 main 无新增提交，远控协议零差异。
- `pnpm typecheck` 通过；`pnpm lint` 通过（0 warnings / 0 errors）。
- Node 24.21.0 + JDK 21 构建正式包；`testDebugUnitTest`、`lintDebug`、`assembleRelease` 通过。JUnit 16 项，0 failures / 0 errors；Android lint 0 errors（12 个既有 warning）。
- 正式包 `versionName=3.17.5`、`versionCode=31705`；签名证书与 `android-v0.1.9` 一致（证书 SHA-256 前缀 `2d8cfdf9`）。
- APK 内 128 个原生库与运行时链接一致，payload SHA-256 匹配，无 source map。
- 同步二维码解析使用 Android 8 起可用的 UTF-8 `URLDecoder.decode(String, String)`，不依赖 API 33 的 `Charset` 重载。
- 发布时必须同时给 `build-standalone.mjs` 和 Gradle 传入 `ANDROID_VERSION_NAME` / `ANDROID_VERSION_CODE`，并核对 APK 实际 `aapt dump badging` 与签名；不能仅凭附件文件名判断包内版本。

## 扫码协同实测（2026-10-25，Android 15 模拟器 + 隔离桌面实例）

- **远程控制（MIRROR）端到端通过**：桌面 `startRemotePairing` 生成真实配对房间（生产 Worker `code.lemon.vin`）→ Android WebView 加载配对链接显示“等待桌面确认” → 桌面 `decideRemotePairing(accept)` 后状态为 `bridged`、`connections=1`（设备名 Android · Chrome）→ 手机端显示电脑工作区 `lcode-remote-e2e-workspace` → 手机终端 `pwd` 输出 Mac 路径 `/tmp/lcode-remote-e2e.Xov7xO/data/.lcode/workspace/default`，提示符为 Mac 主机名；手机终端写入 `phone-marker.txt` 后在 Mac 文件系统读到内容，证明命令在电脑执行而非手机本机。
- **会话同步（COPY）端到端通过**：真实 `DeviceSyncServer` 提供快照 → Android 扫码结果分流 → `CopyClient` 拉取 → `DeviceSyncClient` 导入本机服务端 → 打开 `?task=<新sessionId>` 页面，仪器断言同步文本在手机 WebView 中可见；同一 token 二次拉取返回 401，一次性生效。仪器测试：`ScanPairingInstrumentedTest#copyScanResultImportsSnapshot`（需 `scan_url` 与 `expected_text` 参数）。
- **协议回归**：`pnpm test:remote-control` 76 项通过；device-sync 服务端/导入/SQLite 7 项通过；Android JVM 单测 16 项通过。
- **真机热更新**：用户实测确认 App 内检查更新 → 下载 → 安装成功，版本显示正确。
- **相机识别未通过（环境受限）**：模拟器以 `-camera-back imagefile:<二维码.png>` 提供静态图像，CameraX 正常启动但 ML Kit 未识别出二维码，45 秒内未回传结果；尚不能判定是真机问题还是模拟器图像限制，**真机摄像头扫码仍需实测**。
- **相册截图识别已通过（3.17.5）**：`ScanHomeAndGalleryInstrumentedTest#galleryScreenshotIsRecognized` 在模拟器上通过系统图片选择器 URI 读取真实二维码 PNG，ML Kit 解码成功并回传 MainActivity，页面进入配对 URL。不依赖相机权限。
- **首页选择停留已通过（3.17.5）**：`homeWaitsForUserChoice` 断言运行时准备完成后 home panel 仍可见、WebView 未创建，点击“打开工作区”才进入本机页面。
- **欢迎页入口与网址校验已通过（3.17.5）**：`welcomeRemoteEntriesAndUrlValidation` 验证欢迎页扫码按钮打开原生 ScanActivity，网址输入对话框拒绝不完整 URL 并显示错误，合法链接进入配对流程。仪器测试与二维码图片只在 `androidTest` 测试 APK，release APK 不含这些入口。
- **安全边界**：欢迎页原生消息仅允许当前 App 回环 origin 的 `scan`/`url` 两个命令，主框架与来源 origin 双重校验；不引入 `addJavascriptInterface`，配对密钥不回传 React。

## 3.17.6 三项修复实测（2026-10-27，Android 15 模拟器）

- **系统通知已通过**：`POST_NOTIFICATIONS` 权限进包并在首次启动弹出系统授权框；授予后从真实页面用 `notify` 桥命令发送，`dumpsys notification` 确认两条记录：普通通知落 `task-updates`（importance=3），授权类落 `task-actions`（importance=4，高优先级）。应用退到后台（HOME）后再次发送仍能送达。只使用公开 Android 通知渠道能力，未使用厂商私有接口。
- **文件选择器已通过**：从本机页面触发 `<input type=file>`（带用户手势）后系统 SAF `com.android.documentsui.PickActivity` 正常成为前台 Activity；镜像 SPA 的 history 路径按同 host 放行，选择结果返回时二次校验来源。
- **原页面重连已通过**：远控回归 13/13，含「心跳断连后通过原设备凭据恢复、不重新确认」「未接管的 bridge.open 超时不能触发整页重连」「撤销后清理持久凭据」。
- **构建与静态检查**：JUnit（含新增 `NotificationPayloadTest`）通过、`lintDebug` 通过、`pnpm typecheck` 与 `oxlint` 通过（0 warnings / 0 errors）。
- **包体**：`versionName=3.17.6`、`versionCode=31706`、`sdkVersion=26`；签名证书 SHA-256 前缀 `2d8cfdf9`，与 `android-v0.1.9` 起的历史包一致。APK 内已确认含 `ic_notification`、两条通知渠道文案与打包后的 `notify` 桥接代码。

## 3.17.9 按钮统一 + OAuth 回调重复投递修复（2026-10-30，Android 15 模拟器）

- **原生首页重做（iOS 风格）**：纯黑底 + 分组卡片（`Card.LemonCode`），主操作白底黑字 14dp 连续圆角、次操作深色填充 + 细描边、危险操作红字；状态区从裸文本改为卡片。替换掉原先 Material 默认的灰色块按钮。
- **Web 端按钮统一**：`components/ui/button.tsx` 圆角由 `rounded-md` 统一到 10px，并在 `pointer: coarse`（触摸设备）下强制 `min-h-11`，保证 44pt 级点击区域。
- **OAuth 回调重复投递（真实缺陷，已修）**：同一条 `lcode://oauth/callback` 会经 `onNewIntent`、页面内导航拦截、`onPageFinished` 三条路径各投一次，实测单次深链被投递 **3 次**。第一次成功后 OAuth 服务清空 `pendingState`，后两次在换 token 期间抛「state 不匹配」，`setOAuthError` 把已成功的登录覆盖成「授权失败，请重试」——与用户截图现象一致。现在按「回调 URL + 页面代际」去重：同一页面内同一条只投一次，页面重载（`onPageStarted` 自增代际）后允许重投。
- **回调落在远程页时先切回本机页**：授权期间若停在上游套餐页等非本机页面，该页面没有 `__lcodeDeliverOAuthCallback` 钩子，直接注入等于丢弃；现在检测到当前页不是本机 origin 时先 `launchRuntime()` 回到本机页再投递。
- **已通过**：单次深链投递次数从 3 降为 1（logcat `LemonCodeOAuth` 实测）；原生首页新样式截图确认；JUnit、`lintDebug`、`typecheck`、`oxlint` 通过。
- **包体**：`versionName=3.17.9`、`versionCode=31709`；签名证书 SHA-256 前缀 `2d8cfdf9`。
- **未覆盖**：真实 Z.AI 账号的完整授权登录。模拟器内 `zcode.z.ai` 无法解析（`ping: unknown host`），页面 `fetch` 返回 `Failed to fetch`，因此端到端授权只能在用户真机验证；本次修复的是回调投递与去重这条链路。

## 3.17.8 OAuth 授权回调修复实测（2026-10-30，Android 15 模拟器）

- **根因**：Z.AI / BigModel 授权完成后，网站会跳回 `lcode://oauth/callback`。安卓清单里从未注册这个 scheme（注册数为 0），系统找不到接收方；同时 Web 端 `onOAuthCallback` 是空实现（上游同样如此）。所以浏览器里授权成功了，结果却没有任何接收方，界面只能等到超时并显示「授权失败」。
- **修复**：`AndroidManifest.xml` 注册 `lcode://oauth/callback` 的 VIEW/BROWSABLE intent-filter；`MainActivity` 在 `onNewIntent`（singleTask 复用实例）与冷启动 `onCreate` 两处接收，经 `OAuthCallback.accept` 校验形状后通过 `evaluateJavascript` 投递给页面；页面未就绪时暂存，等 `onPageFinished` 补发。Web 端 `onOAuthCallback` 改为真实订阅表，并暴露 `window.__lcodeDeliverOAuthCallback`；回调早于 React 挂载时排队，注册监听器后立即补发。页面内直接跳 `lcode://` 也会在 `handleNavigation` 被拦下并投递，不再按非法导航丢弃。
- **安全边界**：深链只接受 `lcode://oauth/callback` 且必须带查询参数，拒绝 userinfo、其它 scheme/host/path 与超长地址；真正的 state 校验仍由页面内 OAuth 服务完成。
- **已通过**：模拟器上 `dumpsys package` 确认深链已注册（`Scheme: lcode`、`Authority: oauth`、`Path: /callback`）；在页面内装探针后触发 `lcode://oauth/callback?code=probe2&state=state2`，页面真实收到完整回调（含 `code` 与 `state`），说明从系统 Intent 到页面分发的整条链路已打通。
- **构建与静态检查**：JUnit（含新增 `OAuthCallbackTest`）通过、`lintDebug` 通过、`pnpm typecheck` 与 `oxlint` 通过。
- **包体**：`versionName=3.17.8`、`versionCode=31708`；签名证书 SHA-256 前缀 `2d8cfdf9`，与历史发布包一致。
- **未覆盖**：真实 Z.AI / BigModel 账号的完整授权登录（需要用户本人凭据与网络），本次只验证了回调投递链路本身。

## 3.17.7 浏览器授权跳转修复实测（2026-10-30，Android 15 模拟器）

- **根因**：`platform.openExternal` 在 Web 模式下用 `window.open`，而原生 `onCreateWindow` 只认「用户手势 + 链接命中」，供应商授权、MCP 授权、文档链接这类程序化弹窗全部落到「新窗口未打开」。
- **修复**：新增 `open <https-url>` 桥命令，`openExternal`/反馈/社区在安卓壳内改由原生处理；`onCreateWindow` 改为用一次性 WebView 取得目标地址后走统一导航策略，不再依赖手势命中信息。桥命令仍要求主框架 + 回环 origin 双重校验，目标地址只接受无 userinfo 的纯 HTTPS。
- **已通过**：在 BigModel Coding Plan 详情页点「Get API key」，页面真实发出 `open https://bigmodel.cn/coding-plan/personal/overview`，原生弹出「在系统浏览器打开外部 HTTPS 链接？」确认框，点「打开浏览器」后 Chrome 成为前台并加载目标地址。
- **已通过（弹窗兜底）**：页面脚本直接 `window.open(url, "_blank")` 同样触发确认框并进入浏览器，不再出现「新窗口未打开」。
- **已通过（回归）**：设置页 16 个分区逐个打开，内容均正确；通知桥命令仍可用（应用在后台也能送达）；文件选择器、原页面重连未受影响。
- **构建与静态检查**：JUnit 30 项（含新增 `LocalBridgeOpenTest`）通过、`lintDebug` 通过、`pnpm typecheck` 与 `oxlint` 通过（0 warnings / 0 errors）。
- **包体**：`versionName=3.17.7`、`versionCode=31707`；签名证书 SHA-256 前缀 `2d8cfdf9`，与历史发布包一致。

## 未验证 / 未实现

- 真实模型对话（需要用户自己的模型凭据与网络条件）。
- **本机终端不可用（既有取舍，非本版引入）**：`build-runtime.mjs` 把 `node-pty` 保持 external（Android 无法编译该原生模块），本机工作区打开终端会提示 `node-pty is unavailable in this runtime`。远控（MIRROR）模式下的终端在电脑侧执行，不受影响。
- **真机摄像头扫码与真机端到端复测**：相机识别、MIRROR/COPY 在真实手机上的完整链路；模拟器已验证配对与同步协议、页面与电脑侧执行。
- **真机通知与授权跳转**：模拟器已验证权限、渠道、前后台送达与浏览器跳转；厂商 ROM（澎湃 OS 等）的后台限制、默认浏览器接管与通知折叠行为未在真机确认。
- 手机镜像的断网重连、后台恢复、多设备并发等边界场景。
- 文件下载与保存、内嵌浏览器、Computer Use、后台常驻、插件市场 Git 安装。
- 真机热更新已由用户确认成功；不同签名历史包的覆盖升级、低版本 Android（< 8.0）与 x86 设备未验证。
- 上游全部业务功能的逐项设备验收；不能据构建成功宣称“全功能同步”。
