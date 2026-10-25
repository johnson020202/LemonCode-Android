import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import type { ConversationSnapshot } from "@lcode/shared/lcode-protocol-v4";
import { LCodeIntlProvider } from "@/i18n/IntlProvider.js";
import {
  DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES,
  TokenUsageStatusBar,
} from "./TokenUsageStatusBar.js";

const cumulative = {
  inputTokens: 1_286_000,
  outputTokens: 40_000,
  cacheReadTokens: 5_926_000,
  cacheWriteTokens: 12_000,
};

function snapshot({
  turnId = "turn-1",
  lastRequest = { requestId: "r1", outputTokens: 900, durationMs: 32_900, completedAt: 10 },
}: {
  turnId?: string;
  lastRequest?: { requestId: string; outputTokens: number; durationMs: number; completedAt: number } | null;
} = {}): ConversationSnapshot {
  return {
    usage: {
      cumulative,
      contextWindow: { usedTokens: 1_380, maxTokens: 10_000 },
      modelOutput: { turnId, activeRequestId: "r1", lastRequest },
    },
    rows: { window: [{ turnId: "turn-1" }] },
  } as unknown as ConversationSnapshot;
}

function render(ui: React.ReactElement) {
  return renderToStaticMarkup(<LCodeIntlProvider initialLocale="zh-CN">{ui}</LCodeIntlProvider>);
}

test("有会话数据时状态栏渲染速度、会话、缓存、最近请求与子代理", () => {
  const html = render(
    <TokenUsageStatusBar
      snapshot={snapshot()}
      outputSpeed={{ liveRate: 244, average: null, pending: false }}
      childUsage={{ inputTokens: 500_000, outputTokens: 10_000, unknownCount: 0 }}
      childCount={2}
      childOutputSpeed={{ liveRate: null, average: null, pending: false }}
      preferences={DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES}
    />,
  );
  assert.match(html, /data-testid="token-usage-statusbar"/);
  assert.match(html, /data-testid="token-usage-speed"/);
  assert.match(html, /data-testid="token-usage-session"/);
  assert.match(html, /data-testid="token-usage-cache"/);
  assert.match(html, /data-testid="token-usage-request"/);
  assert.match(html, /data-testid="token-usage-subagents"/);
  // 速度用实时读数而不是均速：实时读数存在时 compact 模式只显示数值 + /s。
  assert.match(html, /244/);
});

test("没有数据时不渲染任何占位", () => {
  const html = render(<TokenUsageStatusBar snapshot={null} />);
  assert.equal(html, "");
});

test("关闭总量偏好后不再显示该项，但其它项不受影响", () => {
  const html = render(
    <TokenUsageStatusBar
      snapshot={snapshot()}
      preferences={{ ...DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES, session: false }}
    />,
  );
  assert.doesNotMatch(html, /data-testid="token-usage-session"/);
});

test("整条关闭偏好时不渲染", () => {
  const html = render(
    <TokenUsageStatusBar
      snapshot={snapshot()}
      preferences={{ ...DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES, enabled: false }}
    />,
  );
  assert.equal(html, "");
});

test("上下文项只有在调用方声明可用时才占位", () => {
  const withContext = render(
    <TokenUsageStatusBar snapshot={snapshot()} contextControl={<span>ctx</span>} contextAvailable />,
  );
  assert.match(withContext, /data-usage-tone="context"[\s\S]*ctx/);

  const withoutContext = render(
    <TokenUsageStatusBar
      snapshot={snapshot()}
      contextControl={<span>ctx</span>}
      contextAvailable={false}
    />,
  );
  assert.doesNotMatch(withoutContext, /data-usage-tone="context"/);
});
