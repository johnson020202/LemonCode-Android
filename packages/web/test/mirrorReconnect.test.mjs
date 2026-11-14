import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import {
  CREDENTIAL_DEATH_KEYS,
  DATA_SOCKET_RETRY_DEADLINE_MS,
  DATA_SOCKET_RETRY_KEYS,
  createRetryDelay,
  retirePairingCredentialOnFailure,
  shouldFallbackToPairingCapability,
} from "../src/remote/mirrorReconnect.ts";

// 覆盖点(契约 §3.3 / specs/mobile-remote-control-cf-workers.md:87):
// 接管后仅网络类断链自动刷新重连;建连阶段仅对 busy/网络类失败有界重试;
// 自动刷新受防循环预算约束。
describe("mirrorReconnect policy", () => {
  it("房间死亡或停止不删除跨房间仍有效的设备授权", () => {
    for (const key of ["network", "busy", "stopped", "expired", "room-missing", "invalidated"]) {
      assert.equal(CREDENTIAL_DEATH_KEYS.has(key), false);
    }
    assert.equal(CREDENTIAL_DEATH_KEYS.has("auth"), true);
    assert.equal(CREDENTIAL_DEATH_KEYS.has("revoked"), true);
  });

  it("重连网络/房间失败及原房间鉴权失败不能重放 capability", () => {
    for (const key of [
      "network",
      "busy",
      "heartbeat",
      "bridge-timeout",
      "stopped",
      "expired",
      "invalidated",
      "revoked",
    ]) {
      assert.equal(
        shouldFallbackToPairingCapability({
          key,
          storedRoomId: "old-room",
          routeRoomId: "new-room",
          hasCapability: true,
        }),
        false,
      );
    }
    assert.equal(
      shouldFallbackToPairingCapability({
        key: "auth",
        storedRoomId: "same-room",
        routeRoomId: "same-room",
        hasCapability: true,
      }),
      false,
    );
  });

  it("新房间设备鉴权失败仅在具有新 capability 时重新确认", () => {
    assert.equal(
      shouldFallbackToPairingCapability({
        key: "auth",
        storedRoomId: "old-room",
        routeRoomId: "new-room",
        hasCapability: true,
      }),
      true,
    );
    assert.equal(
      shouldFallbackToPairingCapability({
        key: "auth",
        storedRoomId: "old-room",
        routeRoomId: "new-room",
        hasCapability: false,
      }),
      false,
    );
  });
  it("断链重试覆盖半开连接的 heartbeat，终态不重连", () => {
    for (const key of ["busy", "network", "heartbeat"]) {
      assert.equal(DATA_SOCKET_RETRY_KEYS.has(key), true);
    }
    for (const key of ["auth", "stopped", "room-missing", "expired", "invalidated"]) {
      assert.equal(DATA_SOCKET_RETRY_KEYS.has(key), false);
    }
    assert.ok(DATA_SOCKET_RETRY_DEADLINE_MS >= 75_000);
  });

  it("唤醒立即结束同一次重试等待，不创建第二个循环", async () => {
    const delay = createRetryDelay();
    const waiting = delay.wait();
    let resolved = false;
    void waiting.then(() => { resolved = true; });
    delay.wake();
    await waiting;
    assert.equal(resolved, true);
    delay.cancel();
  });

  it("取消后等待立即返回，不残留节流定时器", async () => {
    const delay = createRetryDelay();
    delay.cancel();
    await delay.wait();
  });

  it("瞬时网络故障与房间停止不退休设备凭据", () => {
    assert.equal(retirePairingCredentialOnFailure("network", "room", "credential"), false);
    assert.equal(retirePairingCredentialOnFailure("stopped", "room", "credential"), false);
  });
});
