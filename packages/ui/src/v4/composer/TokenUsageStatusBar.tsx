/* oxlint-disable max-lines -- 六项指标的触发按钮、明细浮层与派生规则共用同一套色调与口径；拆文件会让每处各复制一份取整与越界判断。 */
import { useRef, useState, type CSSProperties, type PointerEvent, type ReactNode } from "react";
import { ArrowDownUp, Clock3, Database, Layers, Loader2 } from "lucide-react";
import type { ConversationSnapshot } from "@lcode/shared/lcode-protocol-v4";
import { Button } from "@/components/ui/button.js";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card.js";
import { useLCodeIntl } from "@/i18n/IntlProvider.js";
import { formatCompactTokenNumber } from "@/lib/tokenNumberFormat.js";
import type { ChildSessionUsage } from "./sessionTokenStats.js";
import {
  DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES,
  resolveTokenUsageLastRequest,
  resolveTokenUsageTotals,
  shouldShowTokenUsageCache,
  type TokenUsageStatusBarPreferences,
} from "./tokenUsageStatusBarModel.js";

export type { TokenUsageStatusBarPreferences };
export {
  DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES,
  resolveTokenUsageLastRequest,
  resolveTokenUsageTotals,
  shouldShowTokenUsageCache,
};
import { combineOutputSpeeds, type SessionOutputSpeed } from "./sessionOutputSpeed.js";
import { SessionOutputSpeedValue } from "./SessionOutputSpeedValue.js";

/**
 * Composer 底部的 Token 用量状态栏：速度、上下文、会话累计、缓存、最近请求与子代理
 * 常驻一行，每项各自可展开明细。
 *
 * <p>数据全部取自会话快照本身（由 CLI 在设备内产生），独立运行的安卓客户端与远程 Web
 * 客户端看到同一份事实，不需要为某个平台另开数据通道。
 */

type TokenUsageTone = "speed" | "context" | "session" | "cache" | "request" | "subagents";

const IDLE_SPEED: SessionOutputSpeed = { liveRate: null, average: null, pending: false };

function DetailRow({
  label,
  tone,
  children,
}: {
  label: string;
  tone: TokenUsageTone;
  children: ReactNode;
}) {
  return (
    <div
      className="token-usage-detail-row token-usage-tone flex min-w-0 flex-wrap items-center justify-between gap-x-4 gap-y-1"
      data-usage-tone={tone}
    >
      <span className="token-usage-detail-label inline-flex items-center gap-1.5">
        <i className="token-usage-detail-dot size-1.5 shrink-0 rounded-full" aria-hidden="true" />
        {label}
      </span>
      <span className="font-mono tabular-nums text-foreground">{children}</span>
    </div>
  );
}

/** 速度项图标：有实时读数时用波形，静止时显示等待圈。 */
function SpeedIcon({ live }: { live: boolean }) {
  if (!live) return <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />;
  return (
    <span className="token-usage-activity inline-flex h-3.5 items-center gap-0.5">
      {[0, 1, 2, 3].map((index) => (
        <i
          key={index}
          className="token-usage-wave w-0.5 rounded-sm bg-current"
          style={{ "--wave-index": index } as CSSProperties}
        />
      ))}
    </span>
  );
}

function UsageMetric({
  id,
  label,
  icon,
  value,
  children,
}: {
  id: TokenUsageTone;
  label: string;
  icon: ReactNode;
  value: ReactNode;
  children: ReactNode;
}) {
  // 鼠标悬停临时展开、点击固定展开；触摸设备没有 hover，点按直接固定，
  // 否则手机上这项明细永远打不开。
  const [openMode, setOpenMode] = useState<"hover" | "pinned" | null>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clearTimer = () => {
    if (closeTimer.current !== null) {
      clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
  };
  const handlePointerEnter = (event: PointerEvent) => {
    if (event.pointerType !== "mouse") return;
    clearTimer();
    if (openMode === null) setOpenMode("hover");
  };
  const handlePointerLeave = (event: PointerEvent) => {
    if (event.pointerType !== "mouse" || openMode !== "hover") return;
    clearTimer();
    closeTimer.current = setTimeout(() => {
      closeTimer.current = null;
      setOpenMode(null);
    }, 120);
  };

  return (
    <HoverCard
      open={openMode !== null}
      onOpenChange={(open) => {
        clearTimer();
        setOpenMode(open ? "pinned" : null);
      }}
    >
      <HoverCardTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="token-usage-metric token-usage-tone min-h-5 max-w-full gap-1 rounded-md px-1 text-ui-xs text-foreground-subtle"
          aria-label={label}
          data-testid={`token-usage-${id}`}
          data-usage-tone={id}
          onMouseDown={(event) => event.preventDefault()}
          onPointerEnter={handlePointerEnter}
          onPointerLeave={handlePointerLeave}
          onClick={(event) => {
            clearTimer();
            // 悬停中点击转为固定，避免浮层随后被 pointerleave 收起。
            if (openMode === "hover") {
              event.preventDefault();
              setOpenMode("pinned");
            }
          }}
        >
          <span className="token-usage-metric-icon inline-flex shrink-0" aria-hidden="true">
            {icon}
          </span>
          <span>{label}</span>
          <span className="min-w-0 font-mono tabular-nums text-foreground">{value}</span>
        </Button>
      </HoverCardTrigger>
      <HoverCardContent
        side="top"
        align="start"
        collisionPadding={8}
        className="token-usage-detail token-usage-tone gap-3 text-ui-sm [overflow-wrap:anywhere]"
        data-testid={`token-usage-detail-${id}`}
        data-usage-tone={id}
        onPointerEnter={handlePointerEnter}
        onPointerLeave={handlePointerLeave}
      >
        <div className="token-usage-detail-heading flex items-center gap-2 border-b border-border pb-2 text-ui-sm font-medium text-foreground">
          <span
            className="token-usage-heading-icon inline-flex shrink-0 rounded-md p-1"
            aria-hidden="true"
          >
            {icon}
          </span>
          {label}
        </div>
        {children}
      </HoverCardContent>
    </HoverCard>
  );
}

export function TokenUsageStatusBar({
  snapshot,
  outputSpeed = IDLE_SPEED,
  childUsage,
  childCount = 0,
  childCurrentOutputTokens,
  childOutputSpeed = IDLE_SPEED,
  preferences = DEFAULT_TOKEN_USAGE_STATUS_BAR_PREFERENCES,
  contextControl,
  contextAvailable,
}: {
  snapshot: ConversationSnapshot | null | undefined;
  outputSpeed?: SessionOutputSpeed;
  childUsage?: ChildSessionUsage;
  childCount?: number;
  childCurrentOutputTokens?: number | null;
  childOutputSpeed?: SessionOutputSpeed;
  preferences?: TokenUsageStatusBarPreferences;
  /** 上下文占比入口；由调用方传入，未提供时不渲染该项。 */
  contextControl?: ReactNode;
  /**
   * 上下文入口是否真有可展示的数据。contextControl 是元素、恒为真值，
   * 不能只看它是否存在——否则没有上下文档时会留下一个空壳占位。
   */
  contextAvailable?: boolean;
}) {
  const { intl, locale } = useLCodeIntl();
  const usage = snapshot?.usage;
  const message = (key: string) => intl.formatMessage({ id: `chat.tokenUsageStatusBar.${key}` });
  const exact = (value: number) => new Intl.NumberFormat(locale).format(value);
  const compact = (value: number) => formatCompactTokenNumber(locale, value);

  const totals = resolveTokenUsageTotals(
    usage?.cumulative,
    childUsage,
    usage?.contextWindow?.usedTokens ?? 0,
  );
  const combinedSpeed = combineOutputSpeeds([outputSpeed, childOutputSpeed]);
  const lastRequest = resolveTokenUsageLastRequest(snapshot);
  const hasSpeed =
    combinedSpeed.liveRate !== null || combinedSpeed.average !== null || combinedSpeed.pending;
  const cacheRead = usage?.cumulative.cacheReadTokens;
  const cacheWrite = usage?.cumulative.cacheWriteTokens;
  const hasCache = shouldShowTokenUsageCache(totals.own, cacheRead, cacheWrite);
  const showSession = Boolean(usage) && preferences.session;
  const showContext =
    preferences.context && Boolean(contextControl) && (contextAvailable ?? true);

  if (
    !preferences.enabled ||
    !(
      showSession ||
      showContext ||
      (preferences.speed && hasSpeed) ||
      (preferences.cache && hasCache) ||
      (preferences.subagents && childCount > 0) ||
      (preferences.request && lastRequest)
    )
  ) {
    return null;
  }

  const speedDetail = (speed: SessionOutputSpeed) => (
    <div className="token-usage-speed-detail token-usage-tone" data-usage-tone="speed">
      <SessionOutputSpeedValue speed={speed} intl={intl} locale={locale} />
    </div>
  );

  const mainGroup = (
    <div
      className="token-usage-detail-group token-usage-tone space-y-2 rounded-lg p-2"
      data-usage-tone="session"
    >
      <div className="text-ui-xs font-medium text-foreground-subtle">
        {intl.formatMessage({ id: "chat.sessionUsage.mainGroup" })}
      </div>
      <DetailRow label={intl.formatMessage({ id: "chat.sessionUsage.mainInput" })} tone="context">
        {totals.own === null ? "—" : exact(usage?.cumulative.inputTokens ?? 0)}
      </DetailRow>
      <DetailRow label={intl.formatMessage({ id: "chat.sessionUsage.mainOutput" })} tone="speed">
        {totals.own === null ? "—" : exact(usage?.cumulative.outputTokens ?? 0)}
      </DetailRow>
      {speedDetail(outputSpeed)}
    </div>
  );

  const childGroup = (
    <div
      className="token-usage-detail-group token-usage-tone space-y-2 rounded-lg p-2"
      data-usage-tone="subagents"
    >
      <div className="text-ui-xs font-medium text-foreground-subtle">
        {intl.formatMessage({ id: "chat.sessionUsage.childGroup" }, { count: childCount })}
      </div>
      <DetailRow label={intl.formatMessage({ id: "chat.sessionUsage.subagents" })} tone="subagents">
        {childUsage?.unknownCount ? "≥" : ""}
        {exact(totals.children)}
      </DetailRow>
      {childUsage?.unknownCount ? (
        <p className="text-foreground-subtle">{message("partial")}</p>
      ) : null}
      {childCurrentOutputTokens == null ? null : (
        <DetailRow
          label={intl.formatMessage({ id: "chat.sessionUsage.childCurrentOutput" })}
          tone="context"
        >
          {exact(childCurrentOutputTokens)}
        </DetailRow>
      )}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-foreground-subtle">
          {intl.formatMessage({ id: "chat.sessionUsage.childSpeed" })}
        </span>
        {speedDetail(childOutputSpeed)}
      </div>
    </div>
  );

  return (
    <div
      className="token-usage-statusbar flex min-w-0 flex-wrap items-center gap-x-1 gap-y-0.5 text-ui-xs text-foreground-subtle"
      role="group"
      aria-label={message("title")}
      data-testid="token-usage-statusbar"
      data-animated={preferences.animations}
      data-active={combinedSpeed.liveRate !== null}
      style={
        {
          "--token-usage-rate-scale": Math.min(1, 0.55 + (combinedSpeed.liveRate ?? 0) / 200),
        } as CSSProperties
      }
    >
      {preferences.speed && hasSpeed ? (
        <UsageMetric
          id="speed"
          label={message("speed")}
          icon={<SpeedIcon live={combinedSpeed.liveRate !== null} />}
          value={
            <SessionOutputSpeedValue speed={combinedSpeed} intl={intl} locale={locale} compact />
          }
        >
          {mainGroup}
          {childCount > 0 ? <div className="border-t border-border pt-3">{childGroup}</div> : null}
        </UsageMetric>
      ) : null}
      {showContext ? (
        <div
          className="token-usage-context token-usage-tone inline-flex min-w-0 items-center"
          data-usage-tone="context"
        >
          {contextControl}
        </div>
      ) : null}
      {showSession ? (
        <UsageMetric
          id="session"
          label={message("session")}
          icon={<Layers className="size-3.5" />}
          value={
            totals.total === null
              ? "—"
              : totals.total === 0
                ? message("pending")
                : `${totals.partial ? "≥" : ""}${compact(totals.total)}`
          }
        >
          <DetailRow
            label={intl.formatMessage({
              id: totals.partial ? "chat.sessionUsage.partialTitle" : "chat.sessionUsage.title",
            })}
            tone="session"
          >
            {totals.total === null ? "—" : `${totals.partial ? "≥" : ""}${exact(totals.total)}`}
          </DetailRow>
          {mainGroup}
          {childCount > 0 ? <div className="border-t border-border pt-3">{childGroup}</div> : null}
          <p className="text-ui-xs text-foreground-subtle">{message("accounting")}</p>
        </UsageMetric>
      ) : null}
      {preferences.cache && hasCache ? (
        <UsageMetric
          id="cache"
          label={message("cache")}
          icon={<Database className="size-3.5" />}
          value={compact(cacheRead ?? 0)}
        >
          <DetailRow label={message("cacheRead")} tone="cache">
            {exact(cacheRead ?? 0)}
          </DetailRow>
          <DetailRow label={message("cacheWrite")} tone="session">
            {Number.isFinite(cacheWrite) && (cacheWrite ?? 0) >= 0 ? exact(cacheWrite ?? 0) : "—"}
          </DetailRow>
          <p className="text-ui-xs text-foreground-subtle">{message("accounting")}</p>
        </UsageMetric>
      ) : null}
      {preferences.request && lastRequest ? (
        <UsageMetric
          id="request"
          label={message("request")}
          icon={<Clock3 className="size-3.5" />}
          value={`${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(
            lastRequest.durationMs / 1_000,
          )}s`}
        >
          <DetailRow label={message("requestOutput")} tone="speed">
            {exact(lastRequest.outputTokens)}
          </DetailRow>
          <DetailRow label={message("requestDuration")} tone="request">
            {exact(lastRequest.durationMs)} ms
          </DetailRow>
          <p className="text-ui-xs text-foreground-subtle">{message("requestScope")}</p>
        </UsageMetric>
      ) : null}
      {preferences.subagents && childCount > 0 ? (
        <UsageMetric
          id="subagents"
          label={message("subagents")}
          icon={<ArrowDownUp className="size-3.5" />}
          value={
            <span className="inline-flex items-center gap-1.5">
              {childOutputSpeed.liveRate !== null || childOutputSpeed.pending ? (
                <span
                  className="token-usage-live-dot size-1.5 rounded-full bg-brand"
                  aria-hidden="true"
                />
              ) : null}
              {childCount}
              <ArrowDownUp className="size-3" aria-hidden="true" />
              {childUsage?.unknownCount ? "≥" : ""}
              {compact(totals.children)}
            </span>
          }
        >
          {childGroup}
        </UsageMetric>
      ) : null}
    </div>
  );
}
