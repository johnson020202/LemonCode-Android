# Android 任务实时动态（Live Updates）

## 产品规则

- 目标：任务运行期间在通知栏/状态栏胶囊持续展示进度，用户不必反复打开 App 查看。
- 使用 **Android 16（API 36）标准 Live Updates**（promoted ongoing notification），不接小米私有扩展：
  - 小米「超级岛」走通知 extras 的 `miui.focus.param`，需要小米开发者平台注册、场景预审、方案审核、白名单调试与灰度，第三方无法自主接入。
  - HyperOS 4 的「实时动态」开关对应 Android 标准 promoted notification；「超级岛」是系统侧的展示形态，标准 API 是否上岛由系统决定。
- 只有 Android 壳支持；桌面/Web 不展示进行中通知（桌面有自己的窗口聚焦判定）。
- 通知是显示层，不是传输层：不替代远控连接、心跳或重连，也不改变会话事实来源。

## 状态与所有权

- 会话事实唯一来源仍是 sessions-index（`SessionSummary.phase`）。通知只是对 `running` 的投影。
- Renderer 只在以下时机投影「进行中」通知：
  1. 会话从非 `running` 进入 `running`；
  2. App 进入后台（`visibilitychange` → hidden）时，为所有正在运行的会话补发；
  3. 运行中 `lastAssistantPreview` 变化时更新，同一会话最多每 10 秒一次。
- 会话进入终态时，终态通知复用同一 `taskId` 派生的 notificationId，天然替换进行中通知。
- App 启动时清除上一次进程遗留的进行中通知（进程被杀后通知会残留）。

## Android 侧实现约束

- 通知必须满足 promoted 条件：常驻、有 contentTitle、使用 `BigTextStyle`、无自定义 RemoteView、非分组摘要、未 colorized、渠道 importance 不为 `MIN`。
- 渠道新增 `task-progress`（IMPORTANCE_LOW），静默、`setOnlyAlertOnce(true)`。
- `POST_PROMOTED_NOTIFICATIONS` 为非运行时权限，直接声明在清单。
- `setRequestPromotedOngoing(true)` 与状态栏计时器通过 API 36 方法实现；当前工程 compileSdk 35，使用反射调用并在低版本静默降级为普通常驻通知。
- 计时器：`setUsesChronometer(true)` + `setWhen(startedAt)`，状态栏胶囊显示已运行时长。
- 用户未开启「实时动态」时（`canPostPromotedNotifications() == false`），仍发布普通常驻通知，不报错。
- 首页提供「通知与实时动态设置」入口：API 36+ 优先打开 promoted 通知设置页，失败回退到应用通知设置页。

## 验收场景

1. 任务开始运行且 App 处于后台 → 通知栏出现常驻「任务运行中」，状态栏胶囊显示计时。
2. 任务输出更新 → 通知正文随之更新，频率不超过每 10 秒一次。
3. 任务完成/失败 → 同一条通知被终态通知替换，不再常驻。
4. App 从后台切回前台再切走 → 进行中通知仍在，不重复堆叠。
5. 进程被杀后重新打开 App → 上一次的进行中通知被清除，不残留。
6. Android 15 及以下 → 通知仍为普通常驻通知，功能可用但无胶囊。
7. 桌面/Web → 不产生进行中通知。

## 小米超级岛的实测结论（2026-11-03）

在 HyperOS 4.0 / Android 17（`persist.sys.feature.island=1`、`notification_focus_protocol=3`）真机核实：

- 「小米超级岛」开关由**小米焦点通知白名单**决定，不是应用能自行开启的。`settings get secure focus_notifs` 为空时，调用 `content://miui.statusbar.notification.public` 的 `canShowFocus` 直接抛
  `SecurityException: Package <pkg> is not owned by uid 2000`。
- 官方文档（pId=2131）与社区实现（dsh-Go、workisland-hyperos4）一致：国行需企业认证、上架小米商店、绑定签名并通过审核；国际版由用户开关控制。侧载包拿不到。
- **但岛本身能进**：HyperOS 把标准 promoted ongoing 通知画进岛，岛上取 `smallIcon + setShortCriticalText`（社区在 HyperOS 4.0 / Android 17 真机实测）。因此标准路径必须同时满足：
  1. 字面量 extra `android.requestPromotedOngoing = true`（该常量到 SDK 36.1 才公开，compileSdk 35 只能写字面量）；
  2. `setShortCriticalText(...)` 提供岛上短词，否则岛内为空；
  3. 通知在**前台也要投递**——实时动态是常驻状态条而非打扰，`document.hasFocus()` 的抑制不能作用于 `running` / `dismiss`。
- `miui.focus.param` 模板只在 `canShowFocus` 放行时附加（`XiaomiFocus.apply`），未放行时是无副作用的 no-op，不影响标准路径。
