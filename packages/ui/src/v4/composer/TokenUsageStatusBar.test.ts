import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import type { ConversationSnapshot } from "@lcode/shared/lcode-protocol-v4";
import {
  DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES,
  resolveTokenUsageLastRequest,
  resolveTokenUsageTotals,
  shouldShowTokenUsageCache,
  type TokenUsageStatusBarPreferences,
} from "./tokenUsageStatusBarModel.js";

const source = await readFile(new URL("./TokenUsageStatusBar.tsx", import.meta.url), "utf8");

type Cumulative = ConversationSnapshot["usage"]["cumulative"];
type LastRequest = NonNullable<ConversationSnapshot["usage"]["modelOutput"]>["lastRequest"];

function cumulative(overrides: Partial<Cumulative> = {}): Cumulative {
  return {
    inputTokens: 100,
    outputTokens: 50,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    ...overrides,
  };
}

function request(overrides: Partial<NonNullable<LastRequest>> = {}): NonNullable<LastRequest> {
  return { requestId: "r1", outputTokens: 120, durationMs: 4_000, completedAt: 10, ...overrides };
}

/**
 * 只构造状态栏真正会读的字段，再断言成 ConversationSnapshot：
 * 派生规则若开始依赖新的快照字段，这份最小夹具会编译失败，提醒同时补口径说明。
 */
function snapshot({
  turnId = "turn-1",
  lastRequest = request(),
}: {
  turnId?: string;
  lastRequest?: LastRequest;
} = {}): ConversationSnapshot {
  return {
    usage: {
      cumulative: cumulative(),
      contextWindow: { usedTokens: 1_000, maxTokens: 10_000 },
      modelOutput: { turnId, activeRequestId: "r1", lastRequest },
    },
    rows: { window: [{ turnId: "turn-1" }] },
  } as unknown as ConversationSnapshot;
}

function preference(prefs: TokenUsageStatusBarPreferences, key: keyof TokenUsageStatusBarPreferences) {
  return prefs[key];
}

test("默认偏好全开，状态栏不需要先配置才会出现", () => {
  assert.equal(preference(DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES, "enabled"), true);
  for (const key of ["speed", "context", "session", "cache", "request", "subagents"] as const) {
    assert.equal(preference(DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES, key), true, key);
  }
});

test("会话合计把主会话与直属子代理相加，某一侧未知时标记为部分", () => {
  const totals = resolveTokenUsageTotals(cumulative(), undefined, 1_000);
  assert.equal(totals.own, 150);
  assert.equal(totals.children, 0);
  assert.equal(totals.total, 150);
  assert.equal(totals.partial, false);

  const withChild = resolveTokenUsageTotals(
    cumulative(),
    { inputTokens: 10, outputTokens: 5, unknownCount: 1 },
    1_000,
  );
  assert.equal(withChild.total, 165);
  assert.equal(withChild.partial, true, "子代理用量缺失时不能把合计当成完整值");
});

test("累计缺失且没有子代理用量时返回 null 而不是 0", () => {
  const totals = resolveTokenUsageTotals(undefined, undefined, 0);
  assert.equal(totals.own, null);
  assert.equal(totals.total, null, "0 不是消耗总量，不能渲染成真实读数");
});

test("最近请求只认本轮最后一次请求", () => {
  assert.equal(resolveTokenUsageLastRequest(snapshot())?.outputTokens, 120);
  assert.equal(
    resolveTokenUsageLastRequest(snapshot({ turnId: "turn-0" })),
    null,
    "换了轮次之后上一轮的数字不能继续冒充本轮",
  );
});

test("最近请求丢弃没有输出或没有耗时的记录", () => {
  assert.equal(resolveTokenUsageLastRequest(snapshot({ lastRequest: request({ outputTokens: 0 }) })), null);
  assert.equal(resolveTokenUsageLastRequest(snapshot({ lastRequest: request({ durationMs: 0 }) })), null);
  assert.equal(resolveTokenUsageLastRequest(snapshot({ lastRequest: null })), null);
});

test("缓存指标只在真实读写过之后出现，并且与会话指标同样受偏好控制", () => {
  assert.equal(shouldShowTokenUsageCache(150, 0, 0), false, "0 命中时不该出现缓存项");
  assert.equal(shouldShowTokenUsageCache(150, 900, 0), true);
  assert.equal(shouldShowTokenUsageCache(150, 0, 40), true, "只有写入也要显示");
  assert.equal(shouldShowTokenUsageCache(null, 900, 0), false, "会话用量未知时不给缓存读数");
  assert.match(source, /const hasCache = shouldShowTokenUsageCache\(/);
  assert.match(source, /preferences\.cache && hasCache/);
});

test("触摸设备没有 hover，点按必须能打开明细", () => {
  // 触摸不产生 pointerenter，只有 pointerdown/click；这里守住「点击不做 pointerType 过滤」的路径。
  assert.match(source, /onClick=\{\(event\) => \{[\s\S]*?setOpenMode\("pinned"\)/);
  assert.doesNotMatch(
    source,
    /onClick=\{\(event\) => \{\s*if \(event\.pointerType !== "mouse"\) return;/,
  );
});

test("状态栏自报可访问名称，并暴露稳定的 testid 供端到端断言", () => {
  assert.match(source, /role="group"/);
  assert.match(source, /aria-label=\{message\("title"\)\}/);
  assert.match(source, /data-testid="token-usage-statusbar"/);
});
