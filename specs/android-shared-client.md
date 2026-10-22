# Android 共享客户端与持续构建

## 目标与明确边界

用户要求 Android 版本跟随 lemon-casino/LemonCode 更新，核心要求是同步全部功能。当前上游只提供桌面运行时和移动 Web attachment。本阶段交付共享客户端 Android 工程和可重复构建链，不把 APK 编译成功、接口存在或 WebView 包装等同于全功能验收。

尚待用户决定：Android 是否必须独立运行。本阶段不引入第二份 Agent、会话数据库或任务队列，默认沿用已有远控 attachment；手机独立 Node/PTY/SQLite/MCP/Computer Use 运行时不在已实现范围。桌面浏览器、Computer Use、后台通知、远程工作区创建等缺口逐项公开，禁止标为已完成。

## 架构与状态所有者

- `apps/android` 拥有原生窗口、受限 HTTPS 配对导航、Android 文件选择与保存、返回键、前后台 WebView 生命周期。
- `packages/web` 和 `packages/ui` 仍是唯一业务客户端；APK 内置本次上游构建的完整 Web 产物，不从公网下载替代应用代码。
- WebView 通过 AndroidX WebViewAssetLoader 在用户明确选择的 HTTPS 配对域名下加载本地静态资源，沿用同源 WSS 配对协议；`/api/`、WebSocket 和外部服务仍访问真实服务。只允许当前应用页面导航，其他 HTTPS 链接交给系统浏览器。禁止任意 JavaScript 原生桥、file:// 权限、混合内容、TLS 错误忽略及明文 HTTP。
- 一次性配对 capability 仅存在页面 URL/内存，不写日志或原生偏好；记住设备凭据沿用现有 Web credential store。原生只保存去掉 fragment/query 的配对路径，关闭系统备份。
- 桌面窗口 Host 与 CLI 保留任务/会话/文件/终端/MCP/插件唯一事实源。

```text
Android 启动页 -> 用户输入 HTTPS 配对链接 -> APK 内置共享客户端
                                              |
                                        WSS 双方授权
                                              |
                           桌面 Main 转发 -> 已有窗口 Host -> CLI
                                               |
                                     replayable 恢复与事件序号
```

不修改 desktop-continuous / web-remote-replayable 语义，不新增任务写入路径。后台恢复沿用现有重连机制，不宣称系统杀进程后仍持续执行手机任务。

## 上游同步与构建所有者

- Android 适配独立于上游业务源码；`scripts/android/` 负责暂存静态产物、源码指纹/功能差异检查、版本元数据。
- Android 构建工作流构建当前提交；计划工作流检查上游 main，以 Git merge 保留 Android 适配，冲突必须失败并保留证据，不使用强制覆盖或自动解决语义冲突。versionCode 使用从 2024-01-01 起的构建秒数，不使用各 workflow 各自的 run_number；同秒构建或跨分支正式发布仍需独立发布序列。
- 同源 `/api/` / `/ws/` 的编码路径直接透传，不应用 APK 静态资源的禁百分号规则；静态资源仍严格校验路径并只从已打包文件读取。
- 对原仓库无写入权限时，fork 不会收到上游 push。计划轮询只保证发现最新 HEAD，不声称立即或逐提交执行。若要每次 push 立即构建，需上游仓库安装本工作流或授权 webhook/repository_dispatch。
- 根仓库中的工作流文件不等于已部署：只有用户明确批准推送、目标仓库与签名凭据后才能称为云端自动化启用。不得在本机创建定时任务代替 GitHub Actions。
- Release 必须使用 GitHub Secrets 中长期稳定的 keystore；缺少签名时失败或仅产出明确标注的 debug artifact，不能每次生成新 release 签名。
- 构建元数据记录上游 SHA、客户端版本、Android versionCode 和能力差异。类型/平台接口变化和缺口清单漂移应阻断无人审核发布，而非用空实现假装同步。

## 验收

1. 本地可构建 debug APK；APK 包含本次共享 Web 产物、许可证/NOTICE 和构建信息。
2. 原生链接校验、路径处理及构建/同步脚本有可执行测试；CI 实际调用这些测试。
3. 尝试并如实记录仓库要求的 typecheck、lint、architecture 检查及现有 Web/远控测试。已有失败与新失败区分。
4. 配对、正常断线/恢复、文件上传/保存、返回键、键盘适配需要 Android 设备或模拟器验证；无设备不能标记通过。
5. 功能矩阵区分源码复用、原生实现、远端依赖、未实现、未验证。只有所有用户认可范围内的用例通过才允许声称全功能对等。
