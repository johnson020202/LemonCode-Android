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

- 上游基线 `40851a3`（与上游 v3.21.0 同一提交），客户端 `3.17.4`；上游 main 无新增提交，远控协议零差异。
- `pnpm typecheck` 通过；`pnpm lint` 通过（0 warnings / 0 errors）。
- Node 24.21.0 + JDK 21 构建正式包；`testDebugUnitTest`、`lintDebug`、`assembleRelease` 通过。JUnit 16 项，0 failures / 0 errors；Android lint 0 errors（12 个既有 warning）。
- 正式包 `versionName=3.17.4`、`versionCode=31704`；签名证书与 `android-v0.1.9` 一致（证书 SHA-256 前缀 `2d8cfdf9`）。
- APK 内 128 个原生库与运行时链接一致，payload SHA-256 匹配，无 source map。
- 同步二维码解析使用 Android 8 起可用的 UTF-8 `URLDecoder.decode(String, String)`，不依赖 API 33 的 `Charset` 重载。
- 发布时必须同时给 `build-standalone.mjs` 和 Gradle 传入 `ANDROID_VERSION_NAME` / `ANDROID_VERSION_CODE`，并核对 APK 实际 `aapt dump badging` 与签名；不能仅凭附件文件名判断包内版本。

## 扫码协同实测（2026-10-25，Android 15 模拟器 + 隔离桌面实例）

- **远程控制（MIRROR）端到端通过**：桌面 `startRemotePairing` 生成真实配对房间（生产 Worker `code.lemon.vin`）→ Android WebView 加载配对链接显示“等待桌面确认” → 桌面 `decideRemotePairing(accept)` 后状态为 `bridged`、`connections=1`（设备名 Android · Chrome）→ 手机端显示电脑工作区 `lcode-remote-e2e-workspace` → 手机终端 `pwd` 输出 Mac 路径 `/tmp/lcode-remote-e2e.Xov7xO/data/.lcode/workspace/default`，提示符为 Mac 主机名；手机终端写入 `phone-marker.txt` 后在 Mac 文件系统读到内容，证明命令在电脑执行而非手机本机。
- **会话同步（COPY）端到端通过**：真实 `DeviceSyncServer` 提供快照 → Android 扫码结果分流 → `CopyClient` 拉取 → `DeviceSyncClient` 导入本机服务端 → 打开 `?task=<新sessionId>` 页面，仪器断言同步文本在手机 WebView 中可见；同一 token 二次拉取返回 401，一次性生效。仪器测试：`ScanPairingInstrumentedTest#copyScanResultImportsSnapshot`（需 `scan_url` 与 `expected_text` 参数）。
- **协议回归**：`pnpm test:remote-control` 76 项通过；device-sync 服务端/导入/SQLite 7 项通过；Android JVM 单测 16 项通过。
- **真机热更新**：用户实测确认 App 内检查更新 → 下载 → 安装成功，版本显示正确。
- **相机识别未通过（环境受限）**：模拟器以 `-camera-back imagefile:<二维码.png>` 提供静态图像，CameraX 正常启动但 ML Kit 未识别出二维码，45 秒内未回传结果；尚不能判定是真机问题还是模拟器图像限制，**真机摄像头扫码仍需实测**。

## 未验证 / 未实现

- 真实模型对话（需要用户自己的模型凭据与网络条件）。
- **真机摄像头扫码与真机端到端复测**：相机识别、MIRROR/COPY 在真实手机上的完整链路；模拟器已验证配对与同步协议、页面与电脑侧执行。
- 手机镜像的断网重连、后台恢复、多设备并发等边界场景。
- 文件下载与保存、内嵌浏览器、Computer Use、后台常驻、插件市场 Git 安装。
- 真机热更新已由用户确认成功；不同签名历史包的覆盖升级、低版本 Android（< 8.0）与 x86 设备未验证。
- 上游全部业务功能的逐项设备验收；不能据构建成功宣称“全功能同步”。
