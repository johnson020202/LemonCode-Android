import {
  createMessageId,
  createPartId,
  createSessionId,
  type DeviceSyncCommitBundle,
  type MessageId,
  type MessageInfo,
  type MessagePart,
  type MessageWithParts,
  type SessionId,
  type TraceId,
  type WorkspaceId,
} from "@lcode/contracts";
import type { LCodeSessionImportDeviceSyncParams } from "@lcode/shared";
import { projectIdFromDirectory } from "../app/paths.js";

type RawRecord = Record<string, unknown>;

/** 设备同步的标题前缀；正文标题保持电脑上的原文。 */
const DEVICE_SYNC_TITLE_PREFIX = "来自电脑 · ";

/**
 * 把电脑快照改写成手机工作区里的新会话。
 * 消息、part 与内部引用全部重新分配 ID；assistant 的 path 是执行路径，
 * 必须指向手机工作区，而正文里的绝对路径是历史记录，保持原样。
 */
export function buildDeviceSyncBundle(params: {
  snapshot: LCodeSessionImportDeviceSyncParams["snapshot"];
  workspacePath: string;
  workspaceIdentity?: string;
  version: string;
  traceId: TraceId;
}): DeviceSyncCommitBundle {
  const sessionId = createSessionId();
  const messages = params.snapshot.messages as unknown as MessageWithParts[];
  const messageIds = new Map<string, MessageId>();
  messages.forEach((message, index) => {
    messageIds.set(String(message.info.id), createMessageId(`${sessionId}_${index}`));
  });
  let lastUserId: MessageId | undefined;
  const rewritten = messages.map((message) => {
    const info = { ...(message.info as unknown as RawRecord) };
    const newId = messageIds.get(String(message.info.id)) as MessageId;
    info.id = newId;
    info.sessionID = sessionId;
    info.anchor = rewriteAnchor(info.anchor, messageIds);
    if (info.role === "assistant") {
      const parent = messageIds.get(String(info.parentID)) ?? lastUserId ?? newId;
      info.parentID = parent;
      info.path = { cwd: params.workspacePath, root: params.workspacePath };
    } else {
      lastUserId = newId;
    }
    const parts = message.parts.map((part, partIndex) => {
      const next = { ...(part as unknown as RawRecord) };
      next.id = createPartId(`${newId}_${partIndex}`);
      next.messageID = newId;
      next.sessionID = sessionId;
      rewritePartReferences(next, messageIds, newId, sessionId);
      return next as unknown as MessagePart;
    });
    return { info: info as unknown as MessageInfo, parts };
  });

  return {
    session: {
      id: sessionId,
      projectID: projectIdFromDirectory(params.workspacePath),
      workspaceID: params.workspaceIdentity as WorkspaceId | undefined,
      traceID: params.traceId,
      slug: `device-sync-${sessionId}`,
      directory: params.workspacePath,
      path: params.workspacePath,
      title: `${DEVICE_SYNC_TITLE_PREFIX}${params.snapshot.title}`,
      titleSource: "custom",
      version: params.version,
      time: {
        created: params.snapshot.createdAt,
        updated: params.snapshot.updatedAt,
      },
    },
    messages: rewritten,
  };
}

function rewriteAnchor(
  anchor: unknown,
  messageIds: Map<string, MessageId>,
): RawRecord | undefined {
  if (!anchor || typeof anchor !== "object") return undefined;
  const next = { ...(anchor as RawRecord) };
  if (Array.isArray(next.orderedMessageIds)) {
    next.orderedMessageIds = next.orderedMessageIds
      .map((id) => messageIds.get(String(id)))
      .filter((id): id is MessageId => id !== undefined);
  }
  if (next.boundaryMessageId !== undefined) {
    const mapped = messageIds.get(String(next.boundaryMessageId));
    if (mapped) next.boundaryMessageId = mapped;
    else delete next.boundaryMessageId;
  }
  return next;
}

/** 部分 part 用消息 ID 做锚点；找不到对应消息时删掉可选引用，避免留下悬空 ID。 */
function rewritePartReferences(
  part: RawRecord,
  messageIds: Map<string, MessageId>,
  fallbackId: MessageId,
  sessionId: SessionId,
): void {
  for (const key of ["anchorMessageId", "originAnchorMessageId", "summaryMessageId", "tail_start_id"]) {
    if (part[key] === undefined) continue;
    const mapped = messageIds.get(String(part[key]));
    if (mapped) part[key] = mapped;
    else delete part[key];
  }
  if (part.targetMessageId !== undefined) {
    part.targetMessageId = messageIds.get(String(part.targetMessageId)) ?? fallbackId;
  }
  if (part.parentSessionId !== undefined) {
    part.parentSessionId = sessionId;
  }
}
