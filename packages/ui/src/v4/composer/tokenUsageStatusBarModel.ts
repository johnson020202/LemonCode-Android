import type { ConversationSnapshot } from "@lcode/shared/lcode-protocol-v4";
import { readSessionTokenTotal, type ChildSessionUsage } from "./sessionTokenStats.js";

/**
 * Token 用量状态栏的派生规则。
 *
 * <p>单独成文件是为了让这些口径可以被单元测试直接覆盖——它们决定界面上显示的是
 * 「本轮的速度」还是「上一轮的旧数字」，一旦放宽就会把过期读数当成事实展示。
 */

export interface TokenUsageStatusBarPreferences {
  enabled: boolean;
  speed: boolean;
  context: boolean;
  session: boolean;
  cache: boolean;
  request: boolean;
  subagents: boolean;
  animations: boolean;
}

/** 默认全开：状态栏是常驻信息，没改过偏好时不应要求先配置才出现。 */
export const DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES: TokenUsageStatusBarPreferences = {
  enabled: true,
  speed: true,
  context: true,
  session: true,
  cache: true,
  request: true,
  subagents: true,
  animations: true,
};

export interface TokenUsageTotals {
  own: number | null;
  children: number;
  total: number | null;
  partial: boolean;
}

/** 会话合计 = 主会话已结算 + 直属子代理已结算；任一侧未知即标记为部分，不把缺失当 0。 */
export function resolveTokenUsageTotals(
  cumulative: ConversationSnapshot["usage"]["cumulative"] | undefined,
  childUsage: ChildSessionUsage | undefined,
  contextUsedTokens: number,
): TokenUsageTotals {
  const own = readSessionTokenTotal(cumulative ?? null, contextUsedTokens);
  const children = (childUsage?.inputTokens ?? 0) + (childUsage?.outputTokens ?? 0);
  return {
    own,
    children,
    total: own === null && children === 0 ? null : (own ?? 0) + children,
    partial: own === null || (childUsage?.unknownCount ?? 0) > 0,
  };
}

/**
 * 「最近请求」只认本轮最后一行所属的那次请求，且必须有正向输出与耗时；
 * 换轮之后上一轮的数字不能继续冒充本轮。
 */
export function resolveTokenUsageLastRequest(
  snapshot: ConversationSnapshot | null | undefined,
): NonNullable<ConversationSnapshot["usage"]["modelOutput"]>["lastRequest"] | null {
  const recorded = snapshot?.usage.modelOutput ?? null;
  const request = recorded?.lastRequest ?? null;
  if (!request || recorded?.turnId !== snapshot?.rows.window.at(-1)?.turnId) return null;
  if (
    !Number.isFinite(request.outputTokens) ||
    request.outputTokens <= 0 ||
    !Number.isFinite(request.durationMs) ||
    request.durationMs <= 0
  ) {
    return null;
  }
  return request;
}

/** 缓存只在真正读/写过时出现：恒为 0 会让人以为缓存可用但从未命中。 */
export function shouldShowTokenUsageCache(
  ownSessionTotal: number | null,
  cacheReadTokens: number | null | undefined,
  cacheWriteTokens: number | null | undefined,
): boolean {
  return (
    ownSessionTotal !== null &&
    Number.isFinite(cacheReadTokens) &&
    (cacheReadTokens ?? 0) >= 0 &&
    ((cacheReadTokens ?? 0) > 0 || (cacheWriteTokens ?? 0) > 0)
  );
}
