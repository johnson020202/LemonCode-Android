import { useEffect, useMemo, useRef, useState } from "react";
import type { IPlatformService, TaskNotificationPayload } from "@lcode/shared";
import type { ConversationSnapshot, SessionSummary } from "@lcode/shared/lcode-protocol-v4";
import { useServices } from "@/hooks/useServices.js";
import type { IntlInstance } from "@/i18n/index.js";
import { logger } from "@/logger.js";
import {
  collectPendingInteractionNotificationPayloads,
  collectRunningTaskNotificationPayloads,
  collectTerminalTaskNotificationPayloads,
} from "@/lib/taskNotificationOrchestrator.js";
import {
  acquireSessionsIndex,
  releaseSessionsIndex,
  type SessionsIndexScope,
} from "@/v4/sessionsIndexRegistry.js";
import type { SessionsIndexStoreStatus } from "@/v4/sessionsIndexStore.js";

type FormatMessage = IntlInstance["formatMessage"];
type TaskNotificationPlatform = Pick<
  IPlatformService,
  "showTaskNotification" | "supportsLiveTaskNotifications"
>;

interface WorkspaceTerminalTaskNotificationsParams {
  workspacePath: string;
  workspaceIdentity?: string;
  endpointKey?: string | null;
  enabled: boolean;
  rpcReady: boolean;
  platform: TaskNotificationPlatform | null | undefined;
  formatMessage: FormatMessage;
}

interface PendingInteractionTaskNotificationsParams {
  snapshot: ConversationSnapshot | null;
  enabled: boolean;
  platform: TaskNotificationPlatform | null | undefined;
  formatMessage: FormatMessage;
}

interface SessionsIndexNotificationState {
  signature: string;
  sessions: readonly SessionSummary[];
  status: SessionsIndexStoreStatus;
}

function trimOptional(value: string | null | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function buildWorkspaceNotificationSignature(params: {
  workspacePath: string;
  workspaceIdentity?: string;
  endpointKey?: string;
}): string {
  return [
    params.endpointKey ?? "__base__",
    params.workspaceIdentity ?? "",
    params.workspacePath,
  ].join("\0");
}

function toSessionMap(sessions: readonly SessionSummary[]): Map<string, SessionSummary> {
  return new Map(sessions.map((session) => [session.sessionId, session]));
}

function showTaskNotification(
  platform: TaskNotificationPlatform,
  payload: TaskNotificationPayload,
): void {
  try {
    platform.showTaskNotification(payload);
  } catch (error) {
    logger.warn("[task-notification] 触发平台通知失败", {
      taskId: payload.taskId,
      status: payload.status,
      error,
    });
  }
}

/**
 * v4 任务终态通知编排。
 *
 * v4 重构删掉旧 renderer background monitor 后，platform 通知通道仍在，
 * 但 sessions-index 事实没有再被翻译成展示命令，导致任务完成/失败没有系统通知。
 * 这里只在 renderer 做“已观察边沿”的通知意图，事实仍以 sessions-index 为准；
 * 是否因窗口活跃而抑制通知继续交给 desktop/web platform 层判断。
 */
export function useWorkspaceTerminalTaskNotifications({
  workspacePath,
  workspaceIdentity: rawWorkspaceIdentity,
  endpointKey: rawEndpointKey,
  enabled,
  rpcReady,
  platform,
  formatMessage,
}: WorkspaceTerminalTaskNotificationsParams): void {
  const { lcodeAgentService } = useServices();
  const workspaceIdentity = trimOptional(rawWorkspaceIdentity);
  const endpointKey = trimOptional(rawEndpointKey);
  const workspaceKey = workspaceIdentity ?? workspacePath;
  const signature = useMemo(
    () =>
      buildWorkspaceNotificationSignature({
        workspacePath,
        ...(workspaceIdentity ? { workspaceIdentity } : {}),
        ...(endpointKey ? { endpointKey } : {}),
      }),
    [endpointKey, workspaceIdentity, workspacePath],
  );
  const [indexState, setIndexState] = useState<SessionsIndexNotificationState>({
    signature,
    sessions: [],
    status: "idle",
  });
  const previousBySessionIdRef = useRef<Map<string, SessionSummary> | null>(null);
  /** 进行中通知：首次观察到 running 的时间，供状态栏计时器使用。 */
  const runningStartedAtRef = useRef<Map<string, number>>(new Map());
  const runningLastEmittedAtRef = useRef<Map<string, number>>(new Map());
  /** 当前 snapshot 的最新会话列表；visibilitychange 回调不能依赖闭包里的旧 state。 */
  const sessionsRef = useRef<readonly SessionSummary[]>([]);
  sessionsRef.current = indexState.sessions;

  useEffect(() => {
    previousBySessionIdRef.current = null;
    runningStartedAtRef.current.clear();
    runningLastEmittedAtRef.current.clear();
    setIndexState({ signature, sessions: [], status: "idle" });
  }, [signature]);

  useEffect(() => {
    if (!enabled || !rpcReady || !platform) {
      // App 壳在 remote attachment 就绪前会先挂载，旧通知 hook
      // 只看用户开关就订阅 sessions-index，从而越过 conversation 的 readiness gate
      // 访问断连代理。这里共用 workspace rpcReady；本地 workspace 始终为 true。
      previousBySessionIdRef.current = null;
      setIndexState({ signature, sessions: [], status: "idle" });
      return;
    }

    const registryScope: SessionsIndexScope = {
      workspaceKey,
      workspacePath,
      ...(workspaceIdentity ? { workspaceIdentity } : {}),
      ...(endpointKey ? { endpointKey } : {}),
    };
    const store = acquireSessionsIndex(registryScope, lcodeAgentService);
    const syncState = () => {
      setIndexState({
        signature,
        sessions: store.getSessions(),
        status: store.getStatus(),
      });
    };
    const unsubscribe = store.subscribe(syncState);
    syncState();

    return () => {
      unsubscribe();
      releaseSessionsIndex(registryScope, store);
    };
  }, [
    enabled,
    endpointKey,
    platform,
    rpcReady,
    signature,
    workspaceIdentity,
    workspaceKey,
    workspacePath,
    lcodeAgentService,
  ]);

  useEffect(() => {
    if (
      !enabled ||
      !platform ||
      indexState.signature !== signature ||
      indexState.status !== "live"
    ) {
      return;
    }

    const previousBySessionId = previousBySessionIdRef.current;
    const nextBySessionId = toSessionMap(indexState.sessions);
    if (!previousBySessionId) {
      previousBySessionIdRef.current = nextBySessionId;
      return;
    }

    const payloads = collectTerminalTaskNotificationPayloads({
      previousBySessionId,
      sessions: indexState.sessions,
      formatMessage,
    });
    for (const payload of payloads) {
      showTaskNotification(platform, payload);
    }

    // 进行中通知（Android 16 Live Updates）：只有安卓壳支持，桌面/Web 不产生。
    if (platform.supportsLiveTaskNotifications) {
      const now = Date.now();
      const runningPayloads = collectRunningTaskNotificationPayloads({
        previousBySessionId,
        sessions: indexState.sessions,
        formatMessage,
        now,
        startedAtBySessionId: runningStartedAtRef.current,
        lastEmittedAtBySessionId: runningLastEmittedAtRef.current,
      });
      for (const payload of runningPayloads) {
        if (!runningStartedAtRef.current.has(payload.taskId)) {
          runningStartedAtRef.current.set(payload.taskId, payload.startedAt ?? now);
        }
        runningLastEmittedAtRef.current.set(payload.taskId, now);
        showTaskNotification(platform, payload);
      }
      // 会话离开 running（终态或消失）后回收计时与节流记录，避免 Map 无限增长。
      const runningIds = new Set(
        indexState.sessions
          .filter((session) => session.phase === "running")
          .map((session) => session.sessionId),
      );
      const terminalIds = new Set(payloads.map((payload) => payload.taskId));
      for (const id of runningStartedAtRef.current.keys()) {
        if (runningIds.has(id)) continue;
        runningStartedAtRef.current.delete(id);
        runningLastEmittedAtRef.current.delete(id);
        // 终态通知会复用同一 notificationId 替换进行中通知；只有会话直接消失
        // （没有终态边沿）时才需要显式清除，否则通知栏会一直挂着。
        if (!terminalIds.has(id)) {
          showTaskNotification(platform, {
            taskId: id,
            status: "dismiss",
            title: "",
            body: "",
          });
        }
      }
    }

    previousBySessionIdRef.current = nextBySessionId;
  }, [enabled, formatMessage, indexState, platform, signature]);

  /**
   * App 切到后台时为所有运行中会话补发进行中通知。
   *
   * 用户可能在前台启动任务后再切走；那一刻不会有 phase 变化，只靠上面的边沿投影
   * 会漏掉。这里在 visibilitychange → hidden 时强制补一次，重复投递由同一
   * notificationId 覆盖，不会堆叠。
   */
  useEffect(() => {
    if (!enabled || !platform?.supportsLiveTaskNotifications) return;
    const handleVisibilityChange = () => {
      if (document.visibilityState !== "hidden") return;
      const sessions = sessionsRef.current;
      if (sessions.length === 0) return;
      const now = Date.now();
      const payloads = collectRunningTaskNotificationPayloads({
        previousBySessionId: previousBySessionIdRef.current ?? new Map(),
        sessions,
        formatMessage,
        now,
        startedAtBySessionId: runningStartedAtRef.current,
        lastEmittedAtBySessionId: runningLastEmittedAtRef.current,
        force: true,
      });
      for (const payload of payloads) {
        if (!runningStartedAtRef.current.has(payload.taskId)) {
          runningStartedAtRef.current.set(payload.taskId, payload.startedAt ?? now);
        }
        runningLastEmittedAtRef.current.set(payload.taskId, now);
        showTaskNotification(platform, payload);
      }
    };
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => document.removeEventListener("visibilitychange", handleVisibilityChange);
  }, [enabled, formatMessage, platform]);
}

/**
 * 当前 conversation snapshot 的阻塞交互通知。
 *
 * 首个 snapshot 只作为基线，避免订阅历史/恢复 replayable snapshot 时重放旧权限弹窗通知；
 * 后续新增 interactionId 才发通知，重复 pending 不会刷屏。
 */
export function usePendingInteractionTaskNotifications({
  snapshot,
  enabled,
  platform,
  formatMessage,
}: PendingInteractionTaskNotificationsParams): void {
  const seenRef = useRef<{ sessionId: string; seenRequestIds: Set<string> } | null>(null);

  useEffect(() => {
    if (!enabled || !snapshot) {
      seenRef.current = null;
      return;
    }

    const currentRequestIds = snapshot.pendingInteractions.map(
      (interaction) => interaction.interactionId,
    );
    const currentSeen = seenRef.current;
    if (!currentSeen || currentSeen.sessionId !== snapshot.sessionId) {
      seenRef.current = {
        sessionId: snapshot.sessionId,
        seenRequestIds: new Set(currentRequestIds),
      };
      return;
    }

    if (platform) {
      const payloads = collectPendingInteractionNotificationPayloads({
        snapshot,
        seenRequestIds: currentSeen.seenRequestIds,
        formatMessage,
      });
      for (const payload of payloads) {
        showTaskNotification(platform, payload);
      }
    }

    for (const requestId of currentRequestIds) {
      currentSeen.seenRequestIds.add(requestId);
    }
  }, [enabled, formatMessage, platform, snapshot]);
}
