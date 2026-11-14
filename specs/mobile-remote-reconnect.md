# 手机远控后台恢复与关闭页面后重连

## 产品规则

- 用户已选择记住已授权设备。首次配对仍需桌面确认，一次性 capability 仍 consume-once；成功后手机同源持久保存设备凭据，关闭标签页或浏览器后重新打开同一房间链接先用设备凭据连接。
- `pairingCredentialStore` 是手机凭据唯一读写入口；版本化 localStorage 键为 `lcode:remote-pairing:device:v1`，仅保存 roomId、deviceId、deviceCredential、grantedAt。旧 lcode/zcode sessionStorage 记录迁移后移除；持久存储不可用时降级到当前标签页的 sessionStorage，不伪称能够跨关闭恢复。凭据不得写入日志、示例、HTTP fragment 之外的页面地址或构建产物。
- 设备授权由桌面设备表与 Worker 哈希校验决定。网络闪断、心跳超时、busy、房间缺失/过期/作废与停止不等于设备撤销，不删除有效凭据；明确设备鉴权失败或吊销时清理对应记录及旧键，迟到失败不得清除新配对结果。
- 已授权设备重连的网络/房间错误不能退回已使用的 capability。只有设备鉴权失败、链接指向不同房间且提供 capability 时允许完整新配对；同房间已配对的旧链接不得重放。
- 未接管的建桥超时仍归当前配对页处理，不调用已接管镜像的重连监督者；连接接管后仍由原页面按连接代际恢复，不整页刷新，避免两个重试所有者互相打断。
- Main 是桌面连接状态唯一所有者。手机断连进入 `reconnecting`，不是配对失败；宽限内保留 attachment 并重发 Initialize，宽限外先停泵再 detach，之后已授权设备可建立新的 attachment。房间仍存活，镜像 target、owner/lease、workspaceIdentity 与 remoteSessionId 不变。
- Worker 已桥接房间的 room.ready.expiresAt=null 表示没有配对 TTL；Main 与共享运行时校验必须接受该既有语义，不能恢复已消费的二维码。`reconnecting` 不显示二维码，不自动新建房间。
- Desktop continuous 与手机 web-remote-replayable 继续使用已有 Host；此修复不另起 Agent，不放宽吊销、单房间桥槽或一次性链接校验。
- 本阶段只修复和验证；不推送桌面 tag、不推送 Worker 仓库或部署。后续发布前从修复后的源码重新构建手机资源，并分别发布桌面与 Worker。

## Android 壳后台保活（2026-11-03）

- 心跳的**唯一发送者**仍是页面内的 `connectBridgedDataSocket`（每 30s 一条 `{"type":"ping"}`，§3.4）；原生壳不新建第二条连接、不自己解析协议，只负责在后台替页面**补拍**。
- 原生壳**不得调用 `WebView.onPause()`**：它会暂停 WebView 的 JS 定时器，导致后台 30s 心跳停摆，Worker 约 75s 回收桥槽，表现就是「切后台不到一分钟掉线」。
- 镜像页退到后台时运行 `MirrorKeepAliveService`（`foregroundServiceType=connectedDevice`），按 30s 协议周期调用页面导出的 `window.__lcodeRemotePingTick`；该函数由 `pairingSockets.ts` 注册，指向当前活动桥接的同一个发送器，断开时与定时器一起摘除。前台页面自己调度，原生服务停止，不创建第二条 socket。
- WakeLock 只在后台心跳发送/探活窗口短持，最长 10s 后释放；Doze 下停止 tick，不持续对抗系统休眠。回前台停止原生服务并调用页面探活，后台断网只停止服务，不能清除恢复地址或设备授权。
- 首次 MIRROR 页面加载、扫码后页面进入前台及前台网络回调均不启动前台服务；2026-11-15 真机崩溃证实这些前台调用可能留下待执行的 `ForegroundServiceDidNotStartInTimeException`，后续重启即崩。补拍仅从已有 MIRROR WebView 退后台的边界启动，并且前台恢复与页面回收必须收掉它。
- 切回本机工作区、销毁页面或退出应用都停止服务并清空注入目标，不能常驻耗电。
- 回到前台或收到 `online` 时，已连接 socket 立即发探活；`reconnecting` 取消其被节流的等待并立即推进，`failed` 且持有有效设备凭据时直接恢复。唤醒只驱动同一连接所有者，不另建并行重试循环。

## 半开连接与即时恢复（2026-11-14）

- **入站活性是唯一判据**：每条数据 socket 自己记录最近一次任意入站消息时间；`ping` 的 `send()` 成功不代表对端可达。45s 无入站消息即按 `heartbeat` 淘汰半开 socket，唤醒/上线探活后 8s 无响应同样淘汰。`bridge.open`、pong 和应用帧都算活性证明，不要求只识别 pong。
- **连接所有者**：`connectBridgedDataSocket` 仅拥有本条 socket 的发送、探活和 watchdog；`MirrorSessionGate` 是接管后连接代际与重试的唯一所有者，`MobilePairingPage` 只拥有接管前流程。全局原生钩子只指向最新活动连接，旧连接的清理不得误删新连接。
- **恢复边界**：连接恢复只替换 RPC services，并通过既有 `generation` 重建 `Root`/`ChannelClient` 初始化握手；导航仍按 `navigationKey` 恢复，Host 仍提供 `web-remote-replayable` 快照。不重载 document，不新建第二套会话队列或远端 Agent。
- **代际与取消**：每次重连获得新 generation；旧连接的迟到 close、超时与重试结果不得改变新状态。唤醒事件可取消当前重试 sleep 并立即执行，但不能让过期尝试重新接管页面。
- **冷启动配对页**：可重试失败的“重试”在原页面重启状态机；网络恢复与可见性恢复自动重试可恢复错误。只有明确 `auth`/`revoked` 才经共享入口退休匹配的设备凭据，瞬时失败不得清除。

```mermaid
sequenceDiagram
  participant A as Android 网络/生命周期
  participant P as 当前 MIRROR 页面
  participant G as MirrorSessionGate
  participant W as Worker
  A->>P: online / visible / 原生唤醒探活
  alt 已有活动 socket
    P->>W: 立即 ping
    W-->>P: 任意入站帧
    P->>P: 清除探活截止，刷新 lastInboundAt
  else 半开或无入站响应
    P->>P: 关闭 socket，heartbeat
    P->>G: 当前 generation 断链
    G->>W: 原设备凭据原页面重连
  else 正在重试或已失败
    A->>G: 唤醒当前所有者
    G->>W: 立即推进重试或重新恢复
  end
```

## 所有者与事件顺序

存储仅拒绝写入时，当前标签页的版本化 sessionStorage 降级记录优先，不在读取时反向覆盖其它标签页的持久授权。清理时逐个载体比较房间和凭据；明确设备吊销则退休所有匹配凭据的副本，迟到失败不能清除其它房间的新恢复结果。

```mermaid
sequenceDiagram
  participant P as 手机页面
  participant S as 手机凭据入口
  participant W as Worker 房间
  participant M as Desktop Main
  participant H as 已有窗口 Host
  P->>W: 首次 capability 配对
  W->>M: 请求桌面确认
  M->>W: 允许设备
  W->>P: 一次性下发设备凭据
  P->>S: 保存到同源持久存储
  P->>W: 设备凭据接入 /ws
  W->>M: bridge.open
  M->>H: web-remote-replayable attachment
  Note over P,W: 后台挂起或关闭标签页
  W->>M: peer.disconnected
  M->>M: reconnecting，宽限内保留 attachment
  opt 宽限耗尽
    W->>M: bridge.detached
    M->>H: 先停泵再 detach
  end
  P->>S: 重新打开，读取原设备凭据
  P->>W: 设备凭据接入，不重放 capability
  W->>M: bridge.open
  M->>H: 宽限内重发 Initialize，宽限外新建 attachment
  H-->>P: 同一运行时 replayable 恢复
```

## 验收场景

1. 手机授权后关闭页面，在同一浏览器同源重新打开原链接，免二次确认恢复；旧 sessionStorage 可迁移，存储损坏/拒绝访问不崩溃。
2. 后台断线与超过宽限的重连均成功；Main 展示等待重连，不出现 BRIDGE_DETACHED 配对错误；不会停止房间或复用已 detach 的 port。
3. busy/网络重试耗尽与房间终态只显示对应原因，不请求 /ws/pair；设备凭据仍可用于后续有效房间。
4. 新房间允许已授权设备重连；凭据不被识别且具有新 capability 时仍可完成桌面确认。
5. 吊销后无法连接并清理持久及旧键；旧记录不能复活。旧连接失败不能清除新设备记录；停止房间不会擅自撤销设备授权。
6. room.ready.expiresAt=null 及 reconnecting 的状态快照通过严格校验，界面不显示已消费二维码；stale room/device 事件不能拆新桥。
7. 实际执行 Node 回归、桌面控制器回归、浏览器关闭/重新打开与失败分支 E2E、类型检查、Lint 和架构检查；真机后台杀进程与生产 Workers 部署未执行时明确报告。

## 本地验证（2026-10-08）

- 33 个 Web 纯逻辑回归、40 个 Desktop 控制器/隧道/界面回归、11 个浏览器测试均通过。
  浏览器测试运行真实配对组件与 WebSocket 客户端，仅模拟 Worker 网络边界，覆盖窄/宽屏
  关闭重开、心跳断线、busy 截止、撤销、停止后跨房间恢复和建桥超时。
- 根 `pnpm typecheck`、`pnpm lint`、changed 架构检查通过；Worker 类型检查及 27 个测试通过。
  初轮根类型检查未覆盖 Main，额外检查为 94 → 85 个既有错误。用户要求一并解决后，
  已将 Main 纳入根命令并清零独立全量检查；修复与真实运行时回归见 `desktop-main-typecheck.md`。
- `pnpm build:mobile-web` 与 Desktop `build:no-runtime-assets` 通过；本地 Worker public
  已更新为当前源码。两份任务外文件的 SHA-256 保持一致。
- 尚未在真机验证系统杀后台进程，也未部署 Worker 或发布新桌面安装包；本地构建产物不代表线上已更新。
