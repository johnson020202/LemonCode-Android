import type { TaskNotificationPayload } from "@lcode/shared";
import type {
  ConversationSnapshot,
  PendingInteraction,
  SessionPhase,
  SessionSummary,
  UserInputRequestPayload,
} from "@lcode/shared/lcode-protocol-v4";
import type { IntlInstance } from "@/i18n/index.js";

type FormatMessage = IntlInstance["formatMessage"];

function terminalStatusForPhase(
  phase: SessionPhase,
): Extract<TaskNotificationPayload["status"], "completed" | "failed"> | null {
  if (phase === "completedSuccess" || phase === "completedInterrupted") {
    return "completed";
  }
  if (phase === "error") {
    return "failed";
  }
  return null;
}

function taskTitleBody(
  title: string,
  fallbackMessageId: string,
  formatMessage: FormatMessage,
): string {
  const trimmedTitle = title.trim();
  if (trimmedTitle) {
    return formatMessage({ id: "notification.taskWithTitle" }, { title: trimmedTitle });
  }
  return formatMessage({ id: fallbackMessageId });
}

function terminalPayloadForSession(
  session: SessionSummary,
  status: Extract<TaskNotificationPayload["status"], "completed" | "failed">,
  formatMessage: FormatMessage,
): TaskNotificationPayload {
  if (status === "completed") {
    return {
      taskId: session.sessionId,
      status,
      title: formatMessage({ id: "notification.completed" }),
      body: taskTitleBody(session.title, "notification.completedBody", formatMessage),
    };
  }

  return {
    taskId: session.sessionId,
    status,
    title: formatMessage({ id: "notification.error" }),
    body: taskTitleBody(session.title, "notification.error", formatMessage),
  };
}

export function collectTerminalTaskNotificationPayloads(params: {
  previousBySessionId: ReadonlyMap<string, SessionSummary>;
  sessions: readonly SessionSummary[];
  formatMessage: FormatMessage;
}): TaskNotificationPayload[] {
  const payloads: TaskNotificationPayload[] = [];
  for (const session of params.sessions) {
    const status = terminalStatusForPhase(session.phase);
    if (!status) continue;

    const previous = params.previousBySessionId.get(session.sessionId);
    if (!previous) continue;

    const previousStatus = terminalStatusForPhase(previous.phase);
    if (previousStatus === status) continue;

    payloads.push(terminalPayloadForSession(session, status, params.formatMessage));
  }
  return payloads;
}

/** 同一会话进行中通知的最小更新间隔：流式预览变化很频繁，10s 一次足够。 */
export const RUNNING_NOTIFICATION_THROTTLE_MS = 10_000;

function runningPayloadForSession(params: {
  session: SessionSummary;
  startedAt: number;
  formatMessage: FormatMessage;
}): TaskNotificationPayload {
  const preview = params.session.lastAssistantPreview?.trim();
  return {
    taskId: params.session.sessionId,
    status: "running",
    title: params.formatMessage({ id: "notification.running" }),
    body:
      preview ||
      taskTitleBody(params.session.title, "notification.runningBody", params.formatMessage),
    startedAt: params.startedAt,
  };
}

/**
 * 进行中通知投影（Android Live Updates）。
 *
 * 只在两种时机产出：会话从非 running 进入 running，或运行中预览变化且距上次更新
 * 超过节流窗口。终态由 {@link collectTerminalTaskNotificationPayloads} 负责，
 * 复用同一 taskId 派生的 notificationId 替换进行中通知。
 */
export function collectRunningTaskNotificationPayloads(params: {
  previousBySessionId: ReadonlyMap<string, SessionSummary>;
  sessions: readonly SessionSummary[];
  formatMessage: FormatMessage;
  now: number;
  startedAtBySessionId: ReadonlyMap<string, number>;
  lastEmittedAtBySessionId: ReadonlyMap<string, number>;
  /** 后台补发：忽略节流与预览是否变化，为所有运行中会话各产出一次。 */
  force?: boolean;
}): TaskNotificationPayload[] {
  const payloads: TaskNotificationPayload[] = [];
  for (const session of params.sessions) {
    if (session.phase !== "running") continue;

    const previous = params.previousBySessionId.get(session.sessionId);
    const becameRunning = !previous || previous.phase !== "running";
    const previewChanged =
      previous !== undefined && previous.lastAssistantPreview !== session.lastAssistantPreview;
    const lastEmittedAt = params.lastEmittedAtBySessionId.get(session.sessionId) ?? 0;

    if (!params.force) {
      if (!becameRunning) {
        if (!previewChanged) continue;
        if (params.now - lastEmittedAt < RUNNING_NOTIFICATION_THROTTLE_MS) continue;
      }
    }

    payloads.push(
      runningPayloadForSession({
        session,
        startedAt: params.startedAtBySessionId.get(session.sessionId) ?? params.now,
        formatMessage: params.formatMessage,
      }),
    );
  }
  return payloads;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isPlanApprovalInteraction(payload: UserInputRequestPayload): boolean {
  if (payload.toolName === "ExitPlanMode") {
    return true;
  }
  if (!isPlainRecord(payload.schema)) {
    return false;
  }
  return (
    payload.schema.interaction === "plan_approval" || payload.schema.toolName === "ExitPlanMode"
  );
}

function pendingInteractionPayload(params: {
  snapshot: ConversationSnapshot;
  interaction: PendingInteraction;
  formatMessage: FormatMessage;
}): TaskNotificationPayload | null {
  const { snapshot, interaction, formatMessage } = params;
  const requestId = interaction.interactionId;
  const taskId = snapshot.sessionId;
  const taskTitle = snapshot.meta.title;

  // workspace Hook review 的唯一入口是 Settings；不得把它伪装成
  // permission/elicitation 系统通知。
  if (interaction.payload.kind === "workspaceHookReview") {
    return null;
  }

  if (interaction.payload.kind === "permission") {
    return {
      taskId,
      status: "permission_request",
      requestId,
      title: formatMessage({ id: "notification.permissionRequired" }),
      body:
        interaction.payload.summary.trim() ||
        taskTitleBody(taskTitle, "notification.permissionRequired", formatMessage),
    };
  }

  if (isPlanApprovalInteraction(interaction.payload)) {
    return {
      taskId,
      status: "elicitation_request",
      requestId,
      title: formatMessage({ id: "notification.planApprovalRequired" }),
      body: formatMessage({ id: "notification.planApprovalBody" }),
    };
  }

  return {
    taskId,
    status: "elicitation_request",
    requestId,
    title: formatMessage({ id: "notification.inputRequired" }),
    body:
      interaction.payload.prompt.trim() ||
      taskTitleBody(taskTitle, "notification.inputRequired", formatMessage),
  };
}

export function collectPendingInteractionNotificationPayloads(params: {
  snapshot: ConversationSnapshot;
  seenRequestIds: ReadonlySet<string>;
  formatMessage: FormatMessage;
}): TaskNotificationPayload[] {
  return params.snapshot.pendingInteractions
    .filter((interaction) => !params.seenRequestIds.has(interaction.interactionId))
    .flatMap((interaction) => {
      const payload = pendingInteractionPayload({
        snapshot: params.snapshot,
        interaction,
        formatMessage: params.formatMessage,
      });
      return payload ? [payload] : [];
    });
}
