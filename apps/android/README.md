# LemonCode Android：独立运行 + 远程控制

APK 内置 Node 运行时、上游本地服务端、Agent CLI 与共享 Web 客户端，默认全部在手机内执行、不需要电脑在线。同时支持扫电脑上的二维码进入两种协同模式：

- **远程控制（MIRROR）**：扫电脑上「远程控制」二维码（`https://<host>/p/<roomId>#c=<capability>`），加入电脑的远控房间，在手机上直接操作电脑工作区；配对与数据通道走 Cloudflare Workers 隧道，凭据只保存在本机。
- **会话同步（COPY）**：扫电脑上「设备同步」二维码（`http://<局域网IP>:<port>/sync/sessions/<id>#k=<token>`），把电脑上的单条会话快照一次性拉取到本机继续；走局域网直连，token 一次性有效。

## 运行结构

```text
APK（arm64-v8a）
├── jniLibs/arm64-v8a/lib*.so       Node、bash、Git、rg 等 ELF（Android 只允许从这里执行随包二进制）
└── assets/
    ├── runtime/runtime-payload.tar 应用数据：server bundle、Agent CLI、Web 产物、内置 Provider 配置
    ├── runtime/runtime-manifest.json 链接表与 payload SHA-256
    └── www/                        共享前端（用于打包，运行时以 server/www 提供）

启动：解包 → 在 usr/bin、usr/lib 建立指向 nativeLibraryDir 的符号链接
     → 启动 node server/entry-http.mjs（监听 127.0.0.1:<内核分配端口>）
     → WebView 打开 http://127.0.0.1:<port>/
```

- 本机服务端是上游 `packages/server` 的真实 HTTP/RPC 服务，`createLocalServices` 装配；Agent 由 `LCODE_AGENT_SERVER_COMMAND` 指向随包 `lcode.cjs`，会话、终端、文件、Git 都在手机内执行。
- 只有一个 `Activity`，无原生 JS 桥；只允许回环地址明文 HTTP（网络安全配置仅对 127.0.0.1/localhost 放行），外部链接交给系统浏览器。
- 远程控制走 Cloudflare Workers 隧道（默认 `https://code.lemon.vin`），配对 capability 只出现在二维码 fragment、不进服务器路径；会话同步只在局域网发生，token 一次性有效。两类二维码都由 `ScanPayload` 白名单分流，其它内容一律拒绝。

## 构建

需要 Node 24.21.0、pnpm 10.34.6、JDK 21、Android SDK 35。Windows/macOS 开发机均可；运行时来自 Termux 官方 apt 源的 aarch64 包，逐个校验 SHA-256。

```sh
pnpm install --frozen-lockfile --ignore-scripts
export NODE_OPTIONS=--max-old-space-size=6144
node scripts/android/build-standalone.mjs      # Web + 服务端 + Agent + 运行时 + 打包数据
cd apps/android && ./gradlew testDebugUnitTest lintDebug assembleDebug
node ../../scripts/android/verify-apk.mjs      # 校验清单/链接/无 source map
```

产物：`apps/android/app/build/outputs/apk/debug/app-debug.apk`（约 145 MB）。`scripts/android/` 下各步骤也可单独运行。

## 已知边界

| 项目                             | 状态                                                                   |
| -------------------------------- | ---------------------------------------------------------------------- |
| 本机 Agent 会话、终端、文件、Git | 已接通并在 Android 15 模拟器上启动成功；真实模型对话需用户配置模型凭据 |
| WebView 界面                     | 已加载真实共享客户端（已截图确认）                                     |
| 扫码远程控制电脑（MIRROR）       | 协议与上游 v3.17.4 对齐；真机链路（Worker 隧道往返）待真机回归         |
| 扫码同步会话到本机（COPY）       | 已实现；真机局域网拉取待回归                                           |
| 文件下载/保存                    | 未实现                                                                 |
| 应用内嵌浏览器 / Computer Use    | 依赖 Electron/桌面能力，未移植                                         |
| 后台常驻                         | 仅进程存活期间运行；系统回收后需重开 App                               |
| 插件市场 Git 安装                | 依赖 git 与网络，未单独验收                                            |
| 自动更新（APK 内）               | 已实现（GitHub Releases + SHA-256 校验），需真机回归                   |
| 正式签名                         | 已实现（CI 固定密钥签名 release），需真机回归覆盖升级                  |

## 验证记录

见 `docs/android-ci.md`。APK 构建成功不等于全功能验收；本文件不宣称已完成所有功能同步。
