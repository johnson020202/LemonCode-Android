// 设备同步面板：在当前工作区选一条对话，生成一次性局域网二维码。
// 状态唯一来源是 session/syncStart 的返回；token 只在内存里拼进二维码 fragment，
// 不写日志、不展示明文、不落本地存储。关闭弹框或切换会话立即 stop，端口随之关闭。
import { useCallback, useEffect, useRef, useState } from "react";
import type { LCodeSessionInfo } from "@lcode/shared";
import { LoaderCircle, QrCode } from "lucide-react";
import { Button } from "@/components/ui/button.js";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover.js";
import { ControlHintTooltip } from "@/ControlHintTooltip.js";
import { useLCodeSessionService } from "@/hooks/useLCodeSessionService.js";
import { useLCodeIntl } from "@/i18n/IntlProvider.js";
import { RemotePairingQrCode } from "@/settings/RemotePairingQrCode.js";
import { formatDateTime } from "@/settings/automationFormat.js";
import { StatusDot } from "@/settings/StatusDot.js";

interface DeviceSyncPanelProps {
  workspacePath: string;
  workspaceIdentity?: string;
  remoteSessionId?: string;
}

interface ActiveDeviceSync {
  sessionId: string;
  /** 完整二维码内容，仅存在组件内存中。 */
  url: string;
  expiresAt: number;
}

type DeviceSyncError = "noLan" | "failed" | null;

const SESSION_LIST_LIMIT = 50;

export function DeviceSyncPanel({
  workspacePath,
  workspaceIdentity,
  remoteSessionId,
}: DeviceSyncPanelProps) {
  const { intl } = useLCodeIntl();
  const lcodeSessionService = useLCodeSessionService(
    workspacePath,
    remoteSessionId,
    workspaceIdentity,
  );
  const [open, setOpen] = useState(false);
  const [sessions, setSessions] = useState<LCodeSessionInfo[] | null>(null);
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [startingSessionId, setStartingSessionId] = useState<string | null>(null);
  const [active, setActive] = useState<ActiveDeviceSync | null>(null);
  const [error, setError] = useState<DeviceSyncError>(null);
  // 卸载清理要用最新值；state 闭包在 effect 里会过期。
  const activeRef = useRef<ActiveDeviceSync | null>(null);
  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  const stopSync = useCallback(
    async (sessionId: string) => {
      try {
        await lcodeSessionService.stopDeviceSync({
          workspacePath,
          workspaceIdentity,
          remoteSessionId,
          sessionId,
        });
      } catch {
        // Host 已重启或会话已结束时停止没有意义，关闭入口不能被它打断。
      }
    },
    [lcodeSessionService, remoteSessionId, workspaceIdentity, workspacePath],
  );

  useEffect(() => {
    return () => {
      const sessionId = activeRef.current?.sessionId;
      if (sessionId) {
        void lcodeSessionService
          .stopDeviceSync({ workspacePath, workspaceIdentity, remoteSessionId, sessionId })
          .catch(() => undefined);
      }
    };
  }, [lcodeSessionService, remoteSessionId, workspaceIdentity, workspacePath]);

  const loadSessions = useCallback(async () => {
    setLoadingSessions(true);
    try {
      const list = await lcodeSessionService.listSessions({
        workspacePath,
        workspaceIdentity,
        remoteSessionId,
        includeArchived: false,
        limit: SESSION_LIST_LIMIT,
      });
      setSessions(list);
      setError(null);
    } catch {
      setSessions([]);
      setError("failed");
    } finally {
      setLoadingSessions(false);
    }
  }, [lcodeSessionService, remoteSessionId, workspaceIdentity, workspacePath]);

  const startSync = useCallback(
    async (sessionId: string) => {
      if (startingSessionId) return;
      setStartingSessionId(sessionId);
      setError(null);
      try {
        const previous = activeRef.current;
        if (previous && previous.sessionId !== sessionId) {
          await stopSync(previous.sessionId);
        }
        const result = await lcodeSessionService.startDeviceSync({
          workspacePath,
          workspaceIdentity,
          remoteSessionId,
          sessionId,
        });
        if (result.urls.length === 0) {
          setActive(null);
          setError("noLan");
          return;
        }
        // 多网卡时取第一块非内部 IPv4；手机与电脑同网段才连得上，由用户自行核对。
        // 会话 ID 走路径、密钥只进 fragment，与服务端路由和日志边界一致。
        const base = result.urls[0];
        setActive({
          sessionId,
          url: `${base}/sessions/${encodeURIComponent(sessionId)}#k=${result.token}`,
          expiresAt: result.expiresAt,
        });
      } catch {
        setActive(null);
        setError("failed");
      } finally {
        setStartingSessionId(null);
      }
    },
    [
      lcodeSessionService,
      remoteSessionId,
      startingSessionId,
      stopSync,
      workspaceIdentity,
      workspacePath,
    ],
  );

  const handleOpenChange = useCallback(
    (next: boolean) => {
      setOpen(next);
      if (next) {
        setSessions(null);
        setError(null);
        void loadSessions();
        return;
      }
      const previous = activeRef.current;
      setActive(null);
      setError(null);
      if (previous) {
        void stopSync(previous.sessionId);
      }
    },
    [loadSessions, stopSync],
  );

  const handleStop = useCallback(() => {
    const previous = activeRef.current;
    setActive(null);
    setError(null);
    if (previous) {
      void stopSync(previous.sessionId);
    }
  }, [stopSync]);

  const selectedSession = active
    ? (sessions?.find((session) => session.sessionId === active.sessionId) ?? null)
    : null;
  const busy = startingSessionId !== null;

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
      <ControlHintTooltip title={intl.formatMessage({ id: "settings.deviceSync.title" })}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon-lg"
            data-testid="device-sync-trigger"
            aria-label={intl.formatMessage({ id: "settings.deviceSync.title" })}
          >
            <QrCode className="size-4" />
          </Button>
        </PopoverTrigger>
      </ControlHintTooltip>
      {/* footer 贴底边：向上弹出；宽度收进视口，保证 224px 二维码完整显示。 */}
      <PopoverContent side="top" align="start" className="w-[min(92vw,360px)] space-y-3 p-4">
        <div className="flex items-center gap-2">
          <StatusDot tone={error ? "red" : active ? "green" : "subtle"} spinning={loadingSessions} />
          <span className="text-ui-base font-medium text-foreground">
            {intl.formatMessage({ id: "settings.deviceSync.title" })}
          </span>
          {active ? (
            <span className="text-ui-xs text-foreground-subtle">
              {intl.formatMessage(
                { id: "settings.deviceSync.expiresAt" },
                { time: formatDateTime(active.expiresAt) },
              )}
            </span>
          ) : null}
        </div>

        {error === "noLan" ? (
          <p className="text-ui-base text-destructive">
            {intl.formatMessage({ id: "settings.deviceSync.noLan" })}
          </p>
        ) : null}
        {error === "failed" ? (
          <p className="text-ui-base text-destructive">
            {intl.formatMessage({ id: "settings.deviceSync.failed" })}
          </p>
        ) : null}

        {active ? (
          <div className="flex flex-col gap-3">
            <div className="self-center">
              <RemotePairingQrCode url={active.url} />
            </div>
            <p className="text-ui-base text-foreground-subtle">
              {intl.formatMessage({ id: "settings.deviceSync.hint" })}
            </p>
            {selectedSession ? (
              <p className="truncate text-ui-xs text-foreground-subtle">{selectedSession.title}</p>
            ) : null}
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                size="lg"
                variant="outline"
                disabled={busy}
                onClick={() => void startSync(active.sessionId)}
              >
                {startingSessionId === active.sessionId ? (
                  <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
                ) : null}
                {intl.formatMessage({ id: "common.refresh" })}
              </Button>
              <Button type="button" size="lg" variant="destructive" disabled={busy} onClick={handleStop}>
                {intl.formatMessage({ id: "settings.deviceSync.stop" })}
              </Button>
            </div>
          </div>
        ) : loadingSessions ? (
          <p className="flex items-center gap-2 text-ui-base text-foreground-subtle">
            <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
            {intl.formatMessage({ id: "common.loading" })}
          </p>
        ) : sessions && sessions.length === 0 ? (
          <p className="text-ui-base text-foreground-subtle">
            {intl.formatMessage({ id: "settings.deviceSync.empty" })}
          </p>
        ) : (
          <div className="flex flex-col gap-1.5">
            <p className="text-ui-base text-foreground-subtle">
              {intl.formatMessage({ id: "settings.deviceSync.pick" })}
            </p>
            <ul className="max-h-64 space-y-1 overflow-y-auto" data-testid="device-sync-sessions">
              {(sessions ?? []).map((session) => (
                <li key={session.sessionId}>
                  <Button
                    type="button"
                    variant="ghost"
                    className="h-auto w-full justify-start gap-2 px-2 py-1.5 text-left"
                    disabled={busy}
                    onClick={() => void startSync(session.sessionId)}
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-ui-base text-foreground">
                        {session.title || intl.formatMessage({ id: "taskList.untitled" })}
                      </span>
                      <span className="block text-ui-xs text-foreground-subtle">
                        {formatDateTime(session.updatedAt)}
                      </span>
                    </span>
                    {startingSessionId === session.sessionId ? (
                      <LoaderCircle className="size-4 shrink-0 animate-spin" aria-hidden="true" />
                    ) : null}
                  </Button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}
