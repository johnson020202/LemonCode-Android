import assert from "node:assert/strict";
import test from "node:test";
import type { SessionSummary } from "@lcode/shared/lcode-protocol-v4";
import {
  collectRunningTaskNotificationPayloads,
  RUNNING_NOTIFICATION_THROTTLE_MS,
} from "./taskNotificationOrchestrator.js";

type FormatMessage = Parameters<typeof collectRunningTaskNotificationPayloads>[0]["formatMessage"];

/** 只实现本测试用到的两个 key；其余返回 key 本身，便于断言。 */
const formatMessage = ((descriptor: { id: string }, values?: Record<string, unknown>) => {
  if (descriptor.id === "notification.running") return "任务运行中";
  if (descriptor.id === "notification.runningBody") return "正在执行…";
  if (descriptor.id === "notification.taskWithTitle") return `任务：${String(values?.title ?? "")}`;
  return descriptor.id;
}) as FormatMessage;

function session(overrides: Partial<SessionSummary> & { sessionId: string }): SessionSummary {
  return {
    workspaceId: "ws",
    title: "修复登录",
    phase: "running",
    sessionEnded: false,
    hasBackgroundWork: false,
    lastActivityAt: 1_700_000_000_000,
    createdAt: 1_700_000_000_000,
    ...overrides,
  } as SessionSummary;
}

const emptyMaps = () => ({
  startedAtBySessionId: new Map<string, number>(),
  lastEmittedAtBySessionId: new Map<string, number>(),
});

test("进入 running 产出进行中通知，带 startedAt", () => {
  const maps = emptyMaps();
  const payloads = collectRunningTaskNotificationPayloads({
    previousBySessionId: new Map([["s1", session({ sessionId: "s1", phase: "draft" })]]),
    sessions: [session({ sessionId: "s1" })],
    formatMessage,
    now: 1_700_000_100_000,
    ...maps,
  });
  assert.equal(payloads.length, 1);
  assert.equal(payloads[0]?.taskId, "s1");
  assert.equal(payloads[0]?.status, "running");
  assert.equal(payloads[0]?.startedAt, 1_700_000_100_000);
  assert.equal(payloads[0]?.title, "任务运行中");
});

test("持续 running 且预览未变时不重复通知", () => {
  const maps = emptyMaps();
  const previous = new Map([["s1", session({ sessionId: "s1", lastAssistantPreview: "正在读文件" })]]);
  const payloads = collectRunningTaskNotificationPayloads({
    previousBySessionId: previous,
    sessions: [session({ sessionId: "s1", lastAssistantPreview: "正在读文件" })],
    formatMessage,
    now: 1_700_000_100_000,
    ...maps,
  });
  assert.equal(payloads.length, 0);
});

test("预览变化受 10 秒节流约束", () => {
  const startedAtBySessionId = new Map([["s1", 1_700_000_000_000]]);
  const previous = new Map([["s1", session({ sessionId: "s1", lastAssistantPreview: "旧" })]]);
  const current = session({ sessionId: "s1", lastAssistantPreview: "新" });

  const throttled = collectRunningTaskNotificationPayloads({
    previousBySessionId: previous,
    sessions: [current],
    formatMessage,
    now: 1_700_000_005_000,
    startedAtBySessionId,
    lastEmittedAtBySessionId: new Map([["s1", 1_700_000_000_000]]),
  });
  assert.equal(throttled.length, 0);

  const allowed = collectRunningTaskNotificationPayloads({
    previousBySessionId: previous,
    sessions: [current],
    formatMessage,
    now: 1_700_000_000_000 + RUNNING_NOTIFICATION_THROTTLE_MS,
    startedAtBySessionId,
    lastEmittedAtBySessionId: new Map([["s1", 1_700_000_000_000]]),
  });
  assert.equal(allowed.length, 1);
  assert.equal(allowed[0]?.startedAt, 1_700_000_000_000);
});

test("force 模式忽略节流，为所有运行中会话补发", () => {
  const payloads = collectRunningTaskNotificationPayloads({
    previousBySessionId: new Map([["s1", session({ sessionId: "s1" })]]),
    sessions: [session({ sessionId: "s1" }), session({ sessionId: "s2" })],
    formatMessage,
    now: 1_700_000_005_000,
    startedAtBySessionId: new Map([
      ["s1", 1_700_000_000_000],
      ["s2", 1_700_000_001_000],
    ]),
    lastEmittedAtBySessionId: new Map([
      ["s1", 1_700_000_004_000],
      ["s2", 1_700_000_004_000],
    ]),
    force: true,
  });
  assert.deepEqual(
    payloads.map((payload) => payload.taskId),
    ["s1", "s2"],
  );
});

test("终态会话不产进行中通知", () => {
  const payloads = collectRunningTaskNotificationPayloads({
    previousBySessionId: new Map([["s1", session({ sessionId: "s1" })]]),
    sessions: [session({ sessionId: "s1", phase: "completedSuccess" })],
    formatMessage,
    now: 1_700_000_100_000,
    ...emptyMaps(),
  });
  assert.equal(payloads.length, 0);
});
