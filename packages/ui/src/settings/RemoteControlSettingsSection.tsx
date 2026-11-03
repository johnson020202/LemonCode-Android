// 远程控制设置段（基础设置）：手机扫码配对 + 连接配置 + 安全与隐私。
// 配置/配对状态/设备列表的唯一所有者是 Desktop Main（cfworker-remote/PROTOCOL.md §6.3），
// Renderer 经 useRemoteControl（remoteControlBridge 能力面）读写，不触碰 window.lcode。
// 扫码配对在此处与侧栏 footer 弹框并存：两处投影同一份 Main 状态与同一个 pairingUrl，
// 二维码与「复制链接」两条连接路径在每一处都完整提供。
import { useCallback, useMemo } from "react";
import { toast } from "@/components/ui/toast.js";
import { usePlatform } from "@/hooks/usePlatform.js";
import { useRemoteControl } from "@/hooks/useRemoteControl.js";
import { useLCodeIntl } from "@/i18n/IntlProvider.js";
import { logger } from "@/logger.js";
import { startUserAction } from "@/lib/userActionTelemetry.js";
import { RemoteControlConnectionSettings } from "@/settings/RemoteControlConnectionSettings.js";
import { RemoteControlSecuritySettings } from "@/settings/RemoteControlSecuritySettings.js";
import { RemotePairingPanel } from "@/settings/RemotePairingPanel.js";
import {
  buildRemotePairingMirrorTarget,
  type RemotePairingStartResult,
} from "@/settings/remoteControlBridge.js";

/** 激活 workspace 上下文（tabStore 权威），用于组装手机镜像 target；由调用方传入。 */
export interface RemoteControlMirrorWorkspace {
  remoteSessionId?: string | null;
  workspacePath?: string | null;
  workspaceIdentity?: string | null;
}

export function RemoteControlSettingsSection({
  mirrorWorkspace,
}: {
  /** 缺省时无法组装镜像 target，「开启等待」禁用并提示（fail-closed 提前，手机不白扫）。 */
  mirrorWorkspace?: RemoteControlMirrorWorkspace;
} = {}) {
  const { intl } = useLCodeIntl();
  const platform = usePlatform();
  const {
    bridge,
    config,
    configLoading,
    configSaving,
    pairing,
    pairingUrl,
    startingPairing,
    stoppingPairing,
    devices,
    devicesLoading,
    deviceActionPendingId,
    testingConnection,
    refreshDevices,
    saveConfig,
    startPairing,
    stopPairing,
    decidePairing,
    revokeDevice,
    testConnection,
  } = useRemoteControl(platform);

  const mirrorTarget = useMemo(
    () => buildRemotePairingMirrorTarget(mirrorWorkspace ?? {}),
    [mirrorWorkspace],
  );
  const canStart = mirrorTarget !== null;

  const handleStartPairing = useCallback(async () => {
    const trace = startUserAction({
      featureId: "settings.remoteControl",
      action: "pairing_start",
      trigger: "button",
    });
    const result: RemotePairingStartResult | null = await startPairing(
      mirrorTarget ? { target: mirrorTarget } : undefined,
    );
    if (!result) {
      trace.fail({ failureStage: "capability_missing" });
      return;
    }
    if (result.success) {
      trace.complete({ resultSource: "platform_result" });
      return;
    }
    trace.fail({ failureStage: "pairing_start_failed" });
    toast(
      intl.formatMessage(
        { id: "settings.remoteControl.pairing.startFailed" },
        { error: result.error },
      ),
    );
  }, [intl, mirrorTarget, startPairing]);

  const handleStopPairing = useCallback(async () => {
    const trace = startUserAction({
      featureId: "settings.remoteControl",
      action: "pairing_stop",
      trigger: "button",
    });
    try {
      await stopPairing();
      trace.complete({ resultSource: "platform_result" });
    } catch (error) {
      trace.fail({ failureStage: "pairing_stop_failed" });
      logger.warn("[remote-control] 停止配对失败", {
        error: error instanceof Error ? error.message : String(error),
      });
      toast(intl.formatMessage({ id: "settings.remoteControl.pairing.stopFailed" }));
    }
  }, [intl, stopPairing]);

  const handleDecidePairing = useCallback(
    async (requestId: string, accept: boolean) => {
      const trace = startUserAction({
        featureId: "settings.remoteControl",
        action: "pairing_decide",
        trigger: "button",
      });
      try {
        await decidePairing(requestId, accept);
        // 裁决后的面板状态以 Main 推送为准（accepted/rejected → bridged/waiting）。
        trace.complete({ resultSource: "platform_result" });
      } catch (error) {
        trace.fail({ failureStage: "pairing_decide_failed" });
        logger.warn("[remote-control] 配对裁决提交失败", {
          error: error instanceof Error ? error.message : String(error),
        });
        toast(intl.formatMessage({ id: "settings.remoteControl.pairing.decideFailed" }));
      }
    },
    [decidePairing, intl],
  );

  if (!bridge) {
    // 分区只在桌面注册；走到这里说明当前桌面的 preload 版本还早于远程控制能力，
    // 按既有设置段的 desktopOnly 形态提示，而不是渲染一组点了没反应的控件。
    return (
      <div className="rounded-lg border border-border bg-surface px-4 py-3 text-ui-base text-foreground-subtle">
        {intl.formatMessage({ id: "settings.remoteControl.unavailable" })}
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <section className="space-y-3">
        <div className="text-ui-base font-medium text-foreground-subtle">
          {intl.formatMessage({ id: "settings.remoteControl.pairing.section" })}
        </div>
        {configLoading ? (
          <p className="text-ui-base text-foreground-subtle">
            {intl.formatMessage({ id: "common.loading" })}
          </p>
        ) : config?.enabled ? (
          <RemotePairingPanel
            canStart={canStart}
            noMirrorTargetHint={intl.formatMessage({
              id: "settings.remoteControl.pairing.noMirrorTarget",
            })}
            pairing={pairing}
            pairingUrl={pairingUrl}
            startingPairing={startingPairing}
            stoppingPairing={stoppingPairing}
            onStart={handleStartPairing}
            onStop={handleStopPairing}
            onDecide={handleDecidePairing}
          />
        ) : (
          // 主开关是功能总闸：关闭时 Main 不产生任何出站请求，这里只给去开启的出口。
          <p className="text-ui-base text-foreground-subtle">
            {intl.formatMessage({ id: "remoteControl.quick.disabledHint" })}
          </p>
        )}
      </section>

      <section className="space-y-3">
        <RemoteControlConnectionSettings
          config={config}
          configLoading={configLoading}
          configSaving={configSaving}
          saveConfig={saveConfig}
          testConnection={testConnection}
          testingConnection={testingConnection}
          testSupported={bridge.testRemoteControlConnection !== undefined}
        />
      </section>

      <section className="space-y-3">
        <div className="text-ui-base font-medium text-foreground-subtle">
          {intl.formatMessage({ id: "settings.remoteControl.security.section" })}
        </div>
        <RemoteControlSecuritySettings
          config={config}
          configLoading={configLoading}
          configSaving={configSaving}
          saveConfig={saveConfig}
          devices={devices}
          devicesLoading={devicesLoading}
          deviceActionPendingId={deviceActionPendingId}
          refreshDevices={refreshDevices}
          revokeDevice={revokeDevice}
        />
      </section>
    </div>
  );
}
