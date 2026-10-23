import assert from "node:assert/strict";
import test from "node:test";
import type { TraceId } from "@lcode/contracts";
import type { LCodeSessionImportDeviceSyncParams } from "@lcode/shared";
import { buildDeviceSyncBundle } from "./device-sync-import.js";

const PHONE_WORKSPACE = "/data/user/0/vin.lemon.android/files/workspace";
const DESKTOP_WORKSPACE = "/Users/someone/projects/demo";

function snapshot(): LCodeSessionImportDeviceSyncParams["snapshot"] {
  return {
    title: "修复登录页",
    createdAt: 1_700_000_000_000,
    updatedAt: 1_700_000_100_000,
    messages: [
      {
        info: {
          id: "msg_desktop_user",
          sessionID: "sess_desktop",
          role: "user",
          time: { created: 1_700_000_000_000 },
          agent: "lcode-agent",
        },
        parts: [
          {
            id: "part_desktop_text",
            messageID: "msg_desktop_user",
            sessionID: "sess_desktop",
            type: "text",
            text: `看看 ${DESKTOP_WORKSPACE}/src/login.tsx`,
          },
        ],
      },
      {
        info: {
          id: "msg_desktop_assistant",
          sessionID: "sess_desktop",
          role: "assistant",
          time: { created: 1_700_000_050_000, completed: 1_700_000_060_000 },
          parentID: "msg_desktop_user",
          agent: "lcode-agent",
          mode: "build",
          path: { cwd: DESKTOP_WORKSPACE, root: DESKTOP_WORKSPACE },
          cost: 0,
          tokens: { input: 1, output: 1, reasoning: 0, cache: { read: 0, write: 0 } },
        },
        parts: [
          {
            id: "part_desktop_timeline",
            messageID: "msg_desktop_assistant",
            sessionID: "sess_desktop",
            type: "timeline",
            display: "compact",
            timelineType: "context_compaction",
            operationId: "op",
            trigger: "manual",
            anchorMessageId: "msg_desktop_user",
          },
        ],
      },
    ],
  };
}

test("device sync rewrites ids and workspace while keeping historical text paths", () => {
  const bundle = buildDeviceSyncBundle({
    snapshot: snapshot(),
    workspacePath: PHONE_WORKSPACE,
    workspaceIdentity: "phone-workspace",
    version: "1.0.0",
    traceId: "trace_device_sync" as TraceId,
  });

  const sessionId = String(bundle.session.id);
  assert.ok(sessionId.startsWith("sess_"));
  assert.notEqual(sessionId, "sess_desktop");
  assert.equal(bundle.session.directory, PHONE_WORKSPACE);
  assert.equal(bundle.session.path, PHONE_WORKSPACE);
  assert.equal(bundle.session.title, "来自电脑 · 修复登录页");
  assert.equal(bundle.session.time?.created, 1_700_000_000_000);
  assert.equal(bundle.session.time?.updated, 1_700_000_100_000);

  const [user, assistant] = bundle.messages;
  assert.ok(user && assistant);
  const userId = String(user.info.id);
  const assistantId = String(assistant.info.id);
  assert.notEqual(userId, "msg_desktop_user");
  assert.notEqual(assistantId, "msg_desktop_assistant");
  assert.equal(String(user.info.sessionID), sessionId);
  assert.equal(String(assistant.info.sessionID), sessionId);
  assert.equal(assistant.info.role === "assistant" ? String(assistant.info.parentID) : "", userId);
  assert.deepEqual(
    assistant.info.role === "assistant" ? assistant.info.path : null,
    { cwd: PHONE_WORKSPACE, root: PHONE_WORKSPACE },
  );

  const userPart = user.parts[0];
  assert.equal(userPart?.type === "text" ? userPart.text : "", `看看 ${DESKTOP_WORKSPACE}/src/login.tsx`);
  assert.notEqual(String(userPart?.id), "part_desktop_text");
  assert.equal(String(userPart?.messageID), userId);

  const timeline = assistant.parts[0];
  assert.equal(timeline?.type, "timeline");
  assert.equal(String((timeline as { anchorMessageId?: unknown }).anchorMessageId), userId);
  assert.equal(String(timeline?.id).startsWith("part_"), true);
});
