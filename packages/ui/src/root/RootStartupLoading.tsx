import { useEffect, useState, type ReactNode } from "react";
import { cn } from "@/components/lib/utils.js";
import appLogoUrl from "@/assets/app-logo.svg";

interface RootStartupLoadingProps {
  label: string;
  children?: ReactNode;
  busy?: boolean;
}

export function RootStartupLoading({ label, children, busy = true }: RootStartupLoadingProps) {
  return (
    <div
      // Web 端全局 html/body/#root 为 Electron 透明背景让路，React 接管后会替换 HTML 启动壳。
      // 这里必须由阻塞态自身承接主题背景，否则远控链接会在 Root 恢复期间继续露出浏览器白底。
      className="flex h-full min-h-dvh flex-col items-center justify-center gap-6 bg-background text-foreground"
      role="status"
      aria-busy={busy}
      aria-label={label}
      data-testid="root-startup-loading"
    >
      <LCodeStartupLogoBadge />
      <StartupStageHint />
      {children}
    </div>
  );
}

/**
 * 启动等待的进度反馈。
 *
 * <p>远程控制走 Cloudflare 中转，恢复会话的 RTT 被放大，纯 logo 动画很容易被
 * 当成「卡死了」。这里按停留时长逐级给出更具体的阶段说明，让等待有预期；
 * 只在超过阈值后出现，避免正常启动时闪一下反而更乱。
 */
function StartupStageHint() {
  const [elapsedMs, setElapsedMs] = useState(0);
  useEffect(() => {
    const startedAt = Date.now();
    const timer = window.setInterval(() => setElapsedMs(Date.now() - startedAt), 500);
    return () => window.clearInterval(timer);
  }, []);

  if (elapsedMs < 1200) return null;
  const slow = elapsedMs > 6000;
  return (
    <div className="flex flex-col items-center gap-2" data-testid="root-startup-stage-hint">
      <div className="h-1 w-28 overflow-hidden rounded-full bg-foreground/10">
        <div className="h-full w-1/3 animate-pulse rounded-full bg-foreground/30" />
      </div>
      <p className="text-xs text-foreground-subtle">
        {slow ? "网络较慢，仍在恢复工作区…" : "正在恢复工作区…"}
      </p>
    </div>
  );
}

/** 初始化与引导共用品牌图标，保持底色、描边、圆角和标志比例一致。 */
export function LCodeStartupLogoBadge({ animated = true }: { animated?: boolean }) {
  return (
    <div className="relative flex size-24 items-center justify-center rounded-3xl bg-[linear-gradient(180deg,#000000_0%,#151718_100%)] text-[#ffffff] shadow-xl/20 before:pointer-events-none before:absolute before:inset-0 before:rounded-[inherit] before:border before:border-[rgba(255,255,255,0.1)] before:content-['']">
      <LCodeStartupLogo className="h-auto w-14" animated={animated} />
    </div>
  );
}

function LCodeStartupLogo({
  className,
  animated = true,
}: {
  className?: string;
  animated?: boolean;
}) {
  return (
    <img
      src={appLogoUrl}
      alt=""
      draggable={false}
      className={cn("shrink-0", className, animated && "animate-pulse")}
    />
  );
}
