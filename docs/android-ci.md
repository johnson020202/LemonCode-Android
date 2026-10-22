# Android 开发与持续构建

## 当前交付是什么

这是 **LemonCode Android Preview 远控客户端**，复用上游 `packages/web` / `packages/ui` 的完整前端构建，连接现有桌面 Host。不是手机独立执行 Agent 的版本，也没有完成用户要求的“所有功能同步”验收。

工程：`apps/android`。技术：Java 17、AGP 8.9.2、Gradle 8.11.1，Android 8.0/API 26 起，目标 API 35。只声明 INTERNET 权限；上传通过系统文件选择器。

## 本地构建

需要 Node 24.21.0、pnpm 10.34.6、JDK 21、Android SDK platforms;android-35 / build-tools;35.0.0，设置 `JAVA_HOME` 与 `ANDROID_HOME`。

```sh
pnpm install --frozen-lockfile --ignore-scripts
export NODE_OPTIONS=--max-old-space-size=6144
export ANDROID_VERSION_NAME=3.17.4-android-preview
export ANDROID_VERSION_CODE=1
node --test scripts/android/*.test.mjs
node scripts/android/check-parity.mjs
pnpm typecheck
pnpm lint
pnpm architecture:check --changed
node scripts/android/build-web.mjs
(cd apps/android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug)
```

`--ignore-scripts` 是 Android 共享前端构建的限定安装方式，避免下载不使用的 Electron 和编译桌面原生模块；不是桌面开发的通用安装方式。共享前端实际生产构建必须继续成功。

APK 输出：`apps/android/app/build/outputs/apk/debug/app-debug.apk`。生成资源、SDK、Gradle缓存和密钥不提交。脚本只清理标记为自身生成的资源目录，遇到未标记目录或符号链接拒绝覆盖。

`build-web.mjs --stage-only` 只用于明确已有 Web 构建的本地检查，会在元数据标为 stageOnly；正式 CI 不使用该参数。实际使用步骤：安装 Preview APK，在电脑 LemonCode 开启远控，复制完整 HTTPS 配对链接到手机输入框，确认目标域名与电脑授权。电脑/执行宿主必须在线。

## 自动更新行为

- `.github/workflows/android-build.yml`：main、android/** 分支 push、PR、手动运行或被同步流程调用时执行检查、Web 构建、Android 单测/lint/编译，上传 debug APK 和元数据为 Actions artifact。
- `.github/workflows/android-upstream-sync.yml`：仅非原上游仓库运行，每小时第 17/47 分钟检查固定上游 main。自动合并到 fork 默认分支；冲突立即失败、终止合并且不推送，不做强制覆盖。成功后显式调用可复用构建流程，不依赖 GITHUB_TOKEN 推送触发另一个 push workflow。
- **轮询不等于每个上游 push 立即触发**：GitHub schedule 可能延迟，也可能把多个提交合为一次 HEAD 构建。严格“每次更新立即构建”需要上游维护者把构建 workflow 放入原仓库，或配置授权 webhook/dispatch；不能在 fork 中假装能监听他人仓库的 push。
- **当前没有推送/启用这些工作流，没有远程 Actions 运行证据。** 需要用户明确目标 GitHub 仓库并授权发布源代码，启用 Actions 和 workflow contents:write；受保护分支可能拒绝同步推送。
- 同步合并不是功能验收。平台接口/配对/服务代理指纹变化会阻断构建；必须审阅变更、更新 Android 适配与用例后，才可显式运行 `node scripts/android/check-parity.mjs --record-reviewed-baseline` 并提交基线。普通业务变化仍需业务回归，指纹相同不代表所有功能相同。

## 签名与分发

CI 默认仅产生 debug artifact，不创建 GitHub Release、Play 商店版本或假正式签名。不同机器/runner 的 debug 证书可能不同，后续 debug APK 未必能覆盖安装；不要把卸载重装作为正式升级机制（会清除本机配对数据）。

稳定更新必须由用户持有并备份长期 keystore，配置以下环境变量，再执行 `./gradlew assembleRelease`：

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

无签名材料 release 任务失败，不自动生成替代密钥。本阶段未做正式签名、Release 发布或自动下载安装。签名凭据应使用 CI Secrets，不写仓库/日志/交接文件。

## 功能对等状态

| 类别 | 当前状态 |
| --- | --- |
| 共享界面、会话/模型设置、项目浏览、Git、终端 | 复用上游共享前端与远端服务；仍需要手机端端到端验收 |
| Agent、MCP、插件、skills、hooks、工作流 | 在现有宿主执行，不是 Android 本机实现；不能据此保证全部入口在手机可用 |
| HTTPS 配对、域名确认、凭据恢复 | 原生接入与策略单测；真实双向配对/WSS 待设备验证 |
| 文件上传、返回键、键盘/系统栏 | 原生实现；真机行为未验收 |
| 文件下载/保存 | 未实现；Web 的发起成功不能视为手机落盘成功 |
| Electron 内嵌浏览器、Computer Use、Chrome 导入 | 未移植，不宣称可用 |
| 手机本机 Node/PTY/SQLite、任意 stdio 插件 | 未实现，需用户先确定是否要求完全独立运行 |
| 后台通知、OAuth/支付/分享深链、远程工作区创建 | 未完成对应 Android 适配与验收 |
| 上游更新检测、构建配置 | 已编写，尚未在 GitHub 部署；不能称为已自动运行 |

完整目标仍受运行方式、宿主连接、真实设备、远程发布授权和长期签名材料约束。不得把 Preview APK 交付描述为“所有功能同步已完成”。

## 本次实际验证（2026-10-22）

上游基线 `40851a32349ed4e233e5a5dd4578eb8e91d556a3`，客户端 `3.17.4`，本地分支 `android/shared-client`。改动未提交、未推送；构建元数据明确 `sourceDirty: true`。

- `pnpm install --frozen-lockfile --ignore-scripts` 成功；首次普通安装因不需要的 Electron 下载未完成被终止，随后用限定前端安装方式完成。
- Node 24.21.0 下 `pnpm typecheck`、`pnpm lint`、`pnpm architecture:check --changed` 通过。首次 typecheck 的默认 512 MB 堆耗尽，设置本进程 `NODE_OPTIONS=--max-old-space-size=6144` 后通过，未修改系统 Node 配置。
- `pnpm test:android`：7 个测试通过。现有配对凭据、帧和重连测试：26 个通过。
- `pnpm build:android:web`：完整共享 Web 生产构建成功。上游 Vite 有未来 configLoader 兼容性警告，不是构建失败。
- `./gradlew testDebugUnitTest lintDebug assembleDebug`：成功；JUnit 11 个测试，0 失败；Android lint 0 错误、7 个非阻断警告（新版本属性兼容提示、布局/资源/国际化提示）。首次 lint 发现 API 26 误用 API 27 导航条属性，已通过资源和 SDK 条件分支修复，不使用忽略基线。
- `:app:verifyReleaseInputs`：无签名材料时按设计失败，验证正式版不会回退临时签名。
- `apksigner verify --verbose`：APK v2 签名通过，证书为 Android Debug。包名 `vin.lemon.android`，versionCode 1，minSDK 26、targetSDK 35，仅 INTERNET 权限。
- 对 APK 中 3,809 个共享静态/许可文件逐个与暂存产物比较，全部相同；无 source map；metadata 为 `remote-host-preview`、`fullFeatureParity:false`、`stageOnly:false`。
- APK：19,681,492 字节；SHA-256：`afac8330423e71877d79dc38b6e97532fc9a3df2e1f969eb9a4a18af6a1f8234`。
- GitHub 工作流只做 YAML 解析和结构测试，未在 GitHub 运行。独立模型评审尝试受服务容量/配置错误阻塞，未取得独立评审结论。
- `adb devices -l` 无设备；没有 Android 真机/模拟器安装、真实配对、上传、后台恢复的验证证据。上游 Web GUI E2E 与完整业务回归未执行，正式签名及自动升级未验收。

