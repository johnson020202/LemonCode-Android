# Android 独立运行版：构建、验证与边界

## 现状

APK 内自带 Node 运行时、上游本地服务端、Agent 与共享 Web 客户端，在手机内独立执行，不需要电脑。已在 Android 15 arm64 模拟器上验证：应用解包运行时、启动本机服务端、WebView 加载真实界面（`127.0.0.1:<port>`）。

**这仍不是“所有功能完全同步”的完成品。** 桌面 Electron 能力（内嵌浏览器、Computer Use）、文件下载、APK 自更新等尚未实现，列表见下。

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

## 已验证（2026-10-22）

- 上游基线 `40851a3`，客户端 `3.17.4`。
- `pnpm typecheck`、`pnpm lint`、`pnpm architecture:check --changed`、`scripts/android` 10 项测试全部通过。
- `./gradlew testDebugUnitTest lintDebug assembleDebug` 成功；JUnit 11 项通过；Android lint 0 错误。
- `verify-apk.mjs`：110 个原生库与 manifest 链接一一对应，payload 摘要匹配，无 source map。
- 模拟器实测：安装后自动解包，服务端监听 `127.0.0.1`，`/api/server-info` 返回本机工作区，WebView 加载真实 LCode 界面并进入引导页；截图与浏览器同页对比布局一致（之前的重叠是模拟器截屏伪影）。
- 随包 Agent CLI 在应用沙箱内 `--version` 与 `app-server --stdio` 均可启动。

## 未验证 / 未实现

- 真实模型对话（需要用户自己的模型凭据与网络条件）。
- 文件下载与保存、内嵌浏览器、Computer Use、后台常驻、插件市场 Git 安装、APK 自更新。
- 正式签名、覆盖升级、低版本 Android（< 8.0）与 x86 设备。
- 上游全部业务功能的逐项设备验收；不能据构建成功宣称“全功能同步”。
