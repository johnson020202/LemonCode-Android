import { clearStoredPairingCredential } from "./pairingCredentialStore.js";
import type { PairingFailureKey } from "./pairingFrames.js";

/** 房间关闭不撤销设备；只有设备鉴权失败或明确吊销才退休本地凭据。 */
export const CREDENTIAL_DEATH_KEYS: ReadonlySet<PairingFailureKey> = new Set(["auth", "revoked"]);

/** 迟到的失败也按房间与凭据匹配，不会清除另一房间的新授权。 */
export function retirePairingCredentialOnFailure(
  key: PairingFailureKey,
  roomId: string,
  credential: string,
): boolean {
  if (!CREDENTIAL_DEATH_KEYS.has(key)) return false;
  clearStoredPairingCredential(key === "revoked" ? undefined : roomId, credential);
  return true;
}

export function shouldFallbackToPairingCapability(params: {
  key: PairingFailureKey;
  storedRoomId: string;
  routeRoomId: string;
  hasCapability: boolean;
}): boolean {
  // 网络重试耗尽后重放一次性 capability，会把可恢复断线变成链接失效。
  // 同房间已有授权也证明其旧 capability 已消费；仅新房间的鉴权失败可重新确认。
  return (
    params.key === "auth" && params.hasCapability && params.storedRoomId !== params.routeRoomId
  );
}

/**
 * 建连与接管后的断链共用这套有界重试策略：网络闪断、heartbeat 与 busy 可恢复；
 * auth、房间过期/失效与 stopped 等终态重试无意义，不消耗 Worker 失败计数。
 * 恢复只替换 RPC services，由 MirrorSessionGate 的 generation 重建 ChannelClient，
 * 不刷新文档，不另建会话或绕过既有 replayable 快照。
 */
export const DATA_SOCKET_RETRY_KEYS: ReadonlySet<PairingFailureKey> = new Set([
  "busy",
  "network",
  "heartbeat",
]);

export const DATA_SOCKET_RETRY_DELAY_MS = 3_000;
// 覆盖旧桥最长约 75s 的心跳回收，并留出 resumed 宽限的交叠余量。
export const DATA_SOCKET_RETRY_DEADLINE_MS = 90_000;

/**
 * 可取消重试等待。后台定时器被节流时，online/visible 可唤醒同一轮重试，
 * 不必新建第二个连接循环；旧 generation 的结果仍由调用方丢弃。
 */
export function createRetryDelay(): {
  wait: () => Promise<void>;
  wake: () => void;
  cancel: () => void;
} {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  let finish: (() => void) | undefined;
  let cancelled = false;
  return {
    wait: () =>
      new Promise<void>((resolve) => {
        if (cancelled) {
          resolve();
          return;
        }
        finish = () => {
          if (timeout !== undefined) clearTimeout(timeout);
          timeout = undefined;
          finish = undefined;
          resolve();
        };
        timeout = setTimeout(() => finish?.(), DATA_SOCKET_RETRY_DELAY_MS);
      }),
    wake: () => finish?.(),
    cancel: () => {
      cancelled = true;
      finish?.();
    },
  };
}
