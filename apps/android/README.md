# LemonCode Android Preview

这是上游 `40851a3` 的 **共享 Web 远控原生容器**，不是 Android 独立运行时，也未实现或验收“全功能同步”。一个原生 `Activity` 负责配对入口、信任确认、WebView 生命周期、返回键和 SAF 上传；业务代码仍来自 `packages/web` / `packages/ui`，桌面 Host / CLI 是任务状态的唯一所有者。

## 原生合同与边界

```text
用户粘贴 HTTPS 配对链接
  -> UrlPolicy 校验，展示实际 origin 并确认控制权限信任
  -> 原生仅记住 https://host/p/roomId（无 query / fragment）
  -> WebViewAssetLoader 在该域名 HTTPS 默认 443 返回 APK 内置 index / 静态资源
  -> 同源 API / WSS 访问真实服务 -> 桌面已有 Host -> CLI
```

- 配对格式来自 `packages/web/src/remote/pairingDeepLink.ts`：`https://example.invalid/p/abcdefgh#c=abcdefgh`，roomId 为 8–64 个 base64url 字符，capability 为 8–128 个。示例是无效域名和占位值，不是凭据。
- 只接受 HTTPS、ASCII DNS 名或规范 IPv4、无 userinfo、无非默认端口（显式 `:443` 归一化）。暂不接受 IPv6、Unicode 域名、路径百分号编码；IDN 需用户审慎核对 punycode 域名。拒绝控制字符、反斜线、路径逃逸和混淆 authority。
- 输入 query 和未知 fragment 键被丢弃；仅把有效的 `#c=` 传给共享页面。无 capability 的配对路径允许尝试 Web 侧设备凭据恢复；凭据失效后必须重新从桌面获取配对链接。
- 每次选择不同于最后一次已确认 origin 的域名必须再次确认；没有持久化“所有可信域名”名单。原生偏好只存一条无 secret 的配对地址。输入框不参与保存实例状态 / 自动填充；不保存 WebView history / state，不自动恢复原始 capability，不记录 URL 或 JS console 内容。系统备份与设备迁移均排除应用数据。Web credential store 的已授权设备凭据仍存于 WebView 同源存储，不等于原生保存一次性 capability。
- WebView 内主页面只允许当前 origin 的当前 room 配对路径。其他安全 HTTPS 导航通过用户确认交给 `ACTION_VIEW`，不会在容器内打开任意站点；带 query / fragment 的外链会明确提示其参数将交给浏览器。拒绝 HTTP、file、content、intent、javascript、data、blob 顶层导航；不注册 VIEW 深链入口。不自动信任重定向或子窗口。
- `/api`、`/api/…`、`/ws`、`/ws/…` 永不映射本地 index。仅当前配对路径回退 `index.html`，其他资源按 APK 中实际存在的安全相对路径精确匹配；已知静态命名空间缺资源返回 404，不下载替代业务入口。主页面只能 GET。没有宽泛 SPA 回退。
- 明文、mixed content、文件/内容 URL 访问、第三方 Cookie、TLS 错误忽略、摄像头/麦克风/定位权限均禁用。无 `addJavascriptInterface`、无注入替代业务前端；WebView debugging 仅 `BuildConfig.DEBUG`。SAF 仅把本次用户选择的 `content://` URI 回传该 WebView，没有任意文件系统权限或永久 URI 授权。
- 内置 HTML 的 CSP 仅允许同源脚本、现有内联主题引导和 blob worker，禁止 iframe/object；Service Worker 的网络加载被禁用，不引入离线代理或另一份远端代码加载器。依赖远端脚本、iframe、Service Worker 或 eval 的可选共享功能可能因此不可用，须按实际业务用例审查，不能为兼容直接放宽安全边界。
- 上传用 `ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE`，支持单选/多选（最多 32 项）；不支持目录、拍照和原生录音。Android 文件提供器与 WebView 在关闭 content URL 访问时的 `<input type=file>` 兼容性仍需设备验证。
- **下载 / 保存未实现**。DownloadListener 和被阻止的 blob/data 下载导航显示“不支持，未保存” Toast，原生页一直说明缺口；不调用 DownloadManager，不伪造路径或成功回调。上游 `saveWebFile.ts` 在 `anchor.click()` 后会返回 `success: true`，这是“已尝试发起”的 Web 语义，**不是 Android 落盘结果**；本容器无法修复此返回值，必须在共享前端集成评审中持续列为缺口。没有静默落盘承诺，勿把任何 Web 成功提示计入保存验收。
- 返回键优先走仍在当前配对路径内的历史；否则销毁 WebView 回原生首页，再返回退出 Activity。显式“返回首页”始终可用。旋转回原生首页，不保存 capability；后台仅暂停 WebView，恢复沿用现有 replayable 重连，不承诺后台常驻或系统杀进程后继续手机任务。
- 原生页使用 DESIGN 的紧凑文字层级、语义资源和 16dp 间距，系统明暗主题；共享页面保留其原有主题。键盘 `adjustResize`，API 30+ 显式系统栏/IME insets，旧系统使用窗口 resize 与系统 inset，控件至少 48dp 触控高度。

## 构建接入

- Java 17、AGP 8.9.2、Gradle 8.11.1、compile / target SDK 35、min SDK 26。
- 包名 `vin.lemon.android`；debug 显示名 `LemonCode Android Preview`（release 也保留 Preview 标识，不暗示全功能已验收）。AndroidX WebKit `1.12.1`，JUnit `4.13.2`；无 Kotlin / Compose。
- 已提供标准 Gradle wrapper（发行包 SHA-256 校验）、共享前端构建脚本及 CI。完整构建步骤与当前验证记录见 `../../docs/android-ci.md`。
- 共享前端脚本必须把**真实完整**产物（含 index.html、静态资源、LICENSE / NOTICE）放到 `app/src/main/assets/www/`；不提交假的 index。静态 URL 应使用 origin 根路径（Vite 默认 base `/`）。没有产物时原生启动页明确阻止连接；debug 工程可以编译但不声称业务可运行。
- 可选 `app/src/main/assets/android-build.json` 用于首页只读版本展示；字段约定 `upstreamSha`、`clientVersion`、`versionName`、`versionCode`。缺失则显示 APK 的 BuildConfig 版本与“元数据不可用”，不伪造 SHA。此文件由共享构建脚本生成，不含地址、凭据或 secret。原生读取有大小上限且在后台线程执行。
- `ANDROID_VERSION_CODE` 默认 `1`，必须为 1–2100000000 的十进制整数；`ANDROID_VERSION_NAME` 默认 `0.1.0-preview`，不接受空值或控制字符。
- Release 签名**只**读取 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。缺项或 keystore 不是可读文件时 release 任务失败，不 fallback 到 debug、不生成新密钥、不产出假正式签名。release 还要求真实 `www/index.html` 和 `android-build.json`。密钥文件有效性和签名正确性最终由 AGP 校验。
- 执行 `./gradlew testDebugUnitTest lintDebug assembleDebug`；纯 Java 测试不需要设备。设备验收与构建成功是不同的检查，必须分别记录。

## 尚未验收 / 未支持

仍需 Android 模拟器/真机验证真实配对、凭据重连、跨域确认、API/WSS 没有被拦截、缺包资源不回源、上传取消/多选/失焦、外链和弹窗、返回键、旋转、前后台、API 26/35 系统栏与软键盘及正式签名证书。最新已执行检查见 `../../docs/android-ci.md`；不要把纯 Java 测试视为设备验收。

独立 Node / PTY / SQLite / MCP / Computer Use 运行时、桌面浏览器能力、后台通知、远程工作区创建、Android 下载保存都不能标为实现。APK 编译成功、共享源码复用或入口可见均不构成全功能验收。
