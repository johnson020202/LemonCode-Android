import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import type { PairingDeepLinkRoute } from "./pairingDeepLink.js";
import type { PairingFailureKey } from "./pairingFrames.js";
import type { MobileDataServices } from "./pairingSockets.js";
import {
  buildDataSocketUrl,
  connectBridgedDataSocket,
  MobileBridgeError,
} from "./pairingSockets.js";
import { MobilePairingPage } from "./MobilePairingPage.js";
import {
  CREDENTIAL_DEATH_KEYS,
  DATA_SOCKET_RETRY_DEADLINE_MS,
  DATA_SOCKET_RETRY_DELAY_MS,
  DATA_SOCKET_RETRY_KEYS,
} from "./mirrorReconnect.js";
import {
  clearStoredPairingCredential,
  loadStoredPairingCredential,
  saveStoredPairingCredential,
} from "./pairingCredentialStore.js";

interface ConnectedMirror {
  services: MobileDataServices;
  generation: number;
  credential: string;
  roomId: string;
}

type MirrorConnectionState =
  | { kind: "pairing" }
  | ({ kind: "connected" | "reconnecting" | "failed" } & ConnectedMirror);

/**
 * 连接代际的唯一所有者。重连只替换 RPC services，不重载 document；
 * 当前工作区由 Root 的导航快照恢复，会话事实仍来自桌面 Host 的 replayable 快照。
 */
export function MirrorSessionGate({
  route,
  renderWorkspace,
}: {
  route: PairingDeepLinkRoute;
  renderWorkspace: (services: MobileDataServices, navigationKey: string) => ReactNode;
}) {
  const [state, setState] = useState<MirrorConnectionState>({ kind: "pairing" });
  const stateRef = useRef(state);
  stateRef.current = state;
  const generationRef = useRef(0);
  const credentialRef = useRef<string | null>(null);
  const roomIdRef = useRef<string | null>(route.roomId);
  const mountedRef = useRef(true);
  const reconnectRef = useRef<(roomId: string, credential: string) => void>(() => {});

  const markDead = useCallback((key: PairingFailureKey, credential: string) => {
    clearStoredPairingCredential(
      key === "revoked" ? undefined : roomIdRef.current ?? undefined,
      credential,
    );
    credentialRef.current = null;
    generationRef.current += 1;
    setState((current) =>
      current.kind === "pairing" ? current : { ...current, kind: "failed" },
    );
  }, []);

  const handleDisconnect = useCallback(
    (generation: number, key: PairingFailureKey, credential: string, roomId: string) => {
      if (!mountedRef.current || generationRef.current !== generation) return;
      if (CREDENTIAL_DEATH_KEYS.has(key)) {
        markDead(key, credential);
        return;
      }
      if (!DATA_SOCKET_RETRY_KEYS.has(key)) {
        // 房间停止/过期等终态不能自动重试；保留当前页面与设备授权，等待桌面重新开启。
        generationRef.current += 1;
        setState((current) =>
          current.kind === "pairing" ? current : { ...current, kind: "failed" },
        );
        return;
      }
      reconnectRef.current(roomId, credential);
    },
    [markDead],
  );

  reconnectRef.current = (roomId, credential) => {
    const generation = ++generationRef.current;
    setState((current) =>
      current.kind === "pairing" ? current : { ...current, kind: "reconnecting" },
    );
    void (async () => {
      const deadline = Date.now() + DATA_SOCKET_RETRY_DEADLINE_MS;
      for (;;) {
        try {
          const services = await connectBridgedDataSocket(
            buildDataSocketUrl(roomId, credential),
            (key) => handleDisconnect(generation, key, credential, roomId),
          );
          if (!mountedRef.current || generationRef.current !== generation) {
            services.dispose?.(new Error("Superseded mirror connection"));
            return;
          }
          const previous = stateRef.current;
          if (previous.kind !== "pairing") {
            previous.services.dispose?.(new Error("Mirror connection replaced"));
          }
          const stored = loadStoredPairingCredential();
          if (stored?.deviceCredential === credential && stored.roomId !== roomId) {
            saveStoredPairingCredential({ ...stored, roomId });
          }
          credentialRef.current = credential;
          roomIdRef.current = roomId;
          setState({ kind: "connected", services, generation, credential, roomId });
          return;
        } catch (error) {
          const key = error instanceof MobileBridgeError ? error.key : "network";
          if (!mountedRef.current || generationRef.current !== generation) return;
          if (CREDENTIAL_DEATH_KEYS.has(key)) {
            markDead(key, credential);
            return;
          }
          if (
            !DATA_SOCKET_RETRY_KEYS.has(key) ||
            deadline - Date.now() <= DATA_SOCKET_RETRY_DELAY_MS
          ) {
            break;
          }
          await new Promise((resolve) => setTimeout(resolve, DATA_SOCKET_RETRY_DELAY_MS));
        }
      }
      if (mountedRef.current && generationRef.current === generation) {
        setState((current) =>
          current.kind === "pairing" ? current : { ...current, kind: "failed" },
        );
      }
    })();
  };

  const handleInitialConnected = useCallback((services: MobileDataServices) => {
    const stored = loadStoredPairingCredential();
    if (!stored || !roomIdRef.current) {
      services.dispose?.(new Error("Mirror credential unavailable"));
      return;
    }
    const generation = ++generationRef.current;
    credentialRef.current = stored.deviceCredential;
    setState({
      kind: "connected",
      services,
      generation,
      credential: stored.deviceCredential,
      roomId: roomIdRef.current,
    });
    // 一次性 capability 已消费，后续冷启动只能凭同源设备凭据恢复。
    if (window.location.hash) {
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
    }
  }, []);

  const handleInitialDisconnected = useCallback((key: PairingFailureKey) => {
    const current = stateRef.current;
    if (current.kind !== "connected" || !credentialRef.current) return;
    handleDisconnect(current.generation, key, current.credential, current.roomId);
  }, [handleDisconnect]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      const current = stateRef.current;
      if (
        current.kind === "connected" &&
        !document.hidden &&
        !window.navigator.onLine
      ) {
        return;
      }
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(
    () => () => {
      mountedRef.current = false;
      generationRef.current += 1;
      const current = stateRef.current;
      if (current.kind !== "pairing") {
        current.services.dispose?.(new Error("Mirror page closed"));
      }
    },
    [],
  );

  const retry = useCallback(() => {
    const current = stateRef.current;
    if (current.kind !== "failed" || !credentialRef.current) return;
    reconnectRef.current(current.roomId, current.credential);
  }, []);

  if (state.kind === "pairing") {
    return (
      <MobilePairingPage
        route={route}
        onConnected={handleInitialConnected}
        onDisconnected={handleInitialDisconnected}
      />
    );
  }

  const navigationKey = `lcode:mobile-mirror-navigation:v1:${state.roomId}`;
  return (
    <div className="h-dvh w-screen">
      <div key={state.generation} className="h-full">
        {renderWorkspace(state.services, navigationKey)}
      </div>
      {state.kind !== "connected" ? (
        <div className="pointer-events-none fixed inset-x-3 bottom-4 z-50 flex justify-center" role="status">
          <div
            className="pointer-events-auto flex max-w-lg items-center gap-3 rounded-xl border border-card-border bg-card px-4 py-3 text-ui-xs text-foreground shadow-xl"
            data-testid="mobile-mirror-connection-status"
          >
            <span>
              {state.kind === "reconnecting"
                ? "连接已断开，正在原页面恢复…"
                : credentialRef.current
                  ? "连接尚未恢复，当前页面已保留。"
                  : "设备授权已失效，请重新扫码配对。"}
            </span>
            {state.kind === "failed" && credentialRef.current ? (
              <button
                type="button"
                className="shrink-0 rounded-md border border-border px-2 py-1"
                onClick={retry}
              >
                重试
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}
