# Android 独立运行版

## 目标与边界

交付一个**不依赖电脑**的 Android 应用：手机内自带 Node 运行时、上游 LCode 本地服务端、Agent 与共享 Web 客户端。用户已明确选择独立运行，不再以远程控制版作为最终形态。

当前不以“APK 构建成功”等同“所有功能同步”。桌面专属能力（Electron 内嵌浏览器、Computer Use）在 Android 上无等价实现，必须显式列为未实现，而不是用空实现假装可用。

## 架构与所有者

```text
Activity（唯一原生界面）
  └─ AndroidRuntime.prepare：解包 assets/runtime-payload.tar → files/runtime
       └─ 建立 usr/bin、usr/lib → nativeLibraryDir 的符号链接（Android 10+ 唯一可执行位置）
  └─ AndroidRuntime.startServer：node server/entry-http.mjs，监听 127.0.0.1:<内核分配端口>
       └─ 上游 createLocalServices：文件/Git/终端/会话/MCP/插件
       └─ Agent 子进程：LCODE_AGENT_SERVER_COMMAND = 随包 node + lcode.cjs app-server --stdio
  └─ WebView 加载 http://127.0.0.1:<port>/（共享 packages/web 构建产物）
```

- **状态所有者**：本机服务端进程及其数据库是唯一事实源；原生层不缓存会话、任务或业务状态。
- **唯一写入路径**：UI → 本机 HTTP/WSS → 上游服务；不存在桌面 Host 或第二套队列。
- **执行位置**：所有工具调用在本机 Linux 用户空间（Android 内核 + Termux 用户空间）执行。
- **网络**：仅回环地址允许明文；外部 HTTPS 链接交给系统浏览器；无原生 JS 桥。

## 运行时来源与完整性

- 运行时 ELF 取自 Termux 官方 apt 源的 aarch64 包，构建期按 apt 索引校验 SHA-256，锁定版本写入 `termux-lock.json`。
- ELF 以 `lib*.so` 命名放入 `jniLibs/arm64-v8a`，这是 Android 安装器唯一会解压且允许执行的随包位置；其余数据打入 `assets/runtime/runtime-payload.tar`。
- 启动时按 `runtime-manifest.json` 建立符号链接，使 ELF 的 SONAME 依赖可解析；manifest 摘要不匹配则整包重新解包。
- 运行时不可用时，原生层显示失败原因（含服务端日志尾部），不静默回退到远程页面。

## 应用私有目录的路径别名

Android 应用私有目录同时存在两个名字：`/data/user/0/<pkg>` 与 `/data/data/<pkg>`，前者是指向后者的符号链接。HyperOS 上应用自身无权访问 `/data/data/<pkg>`（stat 返回 EACCES），但 Node 的 `realpath` 仍会解析出该别名。同一策略还拒绝把 `link` 用作创建新文件的发布方式：设备实测 `link(临时文件, 尚不存在的目标)` 返回 EACCES，而 `rename` 到同一目标成功。

- **不变量**：Project Memory 只拒绝记忆根父目录及其内部的 symlink/junction。父目录之上的平台别名（设备实测只有 `/data/user/0` 是链接，其下每一级都是普通目录）必须放行；父目录自己被换成链接仍是逃逸路径，必须拒绝。判定只看这一级的 `lstat`，不拿整段 `realpath` 和原路径比较。
- **发布**：新建文件的原子发布在 POSIX 上用 `rename`，不用 `link`。`rename` 会覆盖并发中新建的目标，所以发布钩子之后先 `lstat` 确认目标不存在，存在即按 stale write 拒绝且不改写；确认与 `rename` 之间的窗口由调用方的 writer 锁串行化。
- **状态所有者**：路径合法性只由 `project-memory-paths` 判定；原生层不缓存、不改写记忆目录。
- **失败语义**：检查失败仍在 `context_initialization` 阶段抛出，轮次不进入模型；不能因为平台别名或 `link` 被拒把一次正常发送变成无回复的空白轮次。
- **边界**：inode 比较不能作为判定依据，平台别名那一级也不能跟随后再 stat：HyperOS 对解析后的 `/data/data/<pkg>` 返回 EACCES。

## 失败语义

- 解包/链接/启动失败：回到首页显示错误，不黑屏、不加载远端替代客户端。
- 服务端 120 秒内未就绪：报错并保留日志；用户可重试或清除数据后重建。
- WebView 渲染进程退出、TLS 错误、非本机导航：终止会话回首页。
- 系统回收进程：应用重新打开时重启运行时；不承诺后台常驻。

## 与上游同步

- 构建工作流每次从当前提交重新生成 Web、服务端、Agent 与运行时；不缓存跨提交的业务产物。
- 上游同步工作流以普通 Git merge 保留 Android 适配，冲突失败不推送；平台契约指纹变化阻断无人审核构建。
- 轮询存在延迟，不能声称“上游每次 push 立即构建”。

## 验收

1. 构建脚本可从零复现，逐包校验运行时 SHA-256。
2. Android 单测、lint 与 `verify-apk.mjs` 通过：manifest 链接与 APK 内原生库一一对应，payload 摘要匹配，无 source map。
3. 设备/模拟器验证：安装即启动、本机服务端返回 `/api/server-info`、WebView 加载真实界面、Agent 可启动。
4. 未实现项必须在文档中保留，不能用编译成功替代功能验收。
