# LemonCode Android

LemonCode 的**独立运行** Android 客户端。APK 内含 Node 运行时、上游本地服务端、Agent 与完整共享界面，不连接电脑、不需要桌面在线。

## 下载

**最新版本：** [Releases](https://github.com/johnson020202/LemonCode-Android/releases) → `LemonCode-android-0.1.0-arm64.apk`

- 仅 arm64（几乎所有现代安卓手机），Android 8.0 及以上。
- 用固定密钥签名，后续版本可直接覆盖升级；卸载会清除本机会话数据。

## 使用

1. 安装 APK，打开后会先在手机内解包运行时（首次约 10–30 秒）。
2. 自动启动本机服务端并进入 LCode 界面。
3. 在设置里配置你自己的模型 Provider 与 API Key 即可开始对话、跑终端和改代码。

所有执行都发生在手机本地；应用只监听 `127.0.0.1`，不开放局域网端口。

## 0.1 版本能力与缺口

已实现：本机运行时、Agent 会话、终端、文件读写、Git、MCP/插件配置、共享 UI、SAF 文件上传。

**未实现**（不伪装为可用）：
- 文件下载与保存
- 内嵌浏览器与 Computer Use（依赖 Electron，Android 无等价实现）
- 后台常驻（进程被系统回收后需重开）
- APK 内自动更新
- x86 设备、Android 8.0 以下

## 从源码构建

见 [apps/android/README.md](apps/android/README.md) 与 [docs/android-ci.md](docs/android-ci.md)。

```sh
pnpm install --frozen-lockfile --ignore-scripts
export NODE_OPTIONS=--max-old-space-size=6144
node scripts/android/build-standalone.mjs
(cd apps/android && ./gradlew assembleRelease)   # 需要签名环境变量
```

## 自动更新构建

- `.github/workflows/android-upstream-sync.yml`：每 30 分钟检查上游 [lemon-casino/LemonCode](https://github.com/lemon-casino/LemonCode) main，普通合并保留 Android 适配；冲突即失败、不推送。
- `.github/workflows/android-build.yml`：每次 push 校验并构建 debug 包。
- `.github/workflows/android-release.yml`：打 `android-v*` tag 或手动触发，用仓库 Secrets 中的固定密钥签名并创建 Release。

运行时二进制来自 Termux 官方 apt 源，构建期逐包校验 SHA-256。

## 许可

上游 LCode 为 Apache-2.0；本仓库的 Android 适配沿用同一许可。第三方组件见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
