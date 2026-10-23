import { randomBytes } from "node:crypto";
import { DeviceSyncServer, type DeviceSyncSnapshot } from "./device-sync-server.js";

export interface DeviceSyncControl {
  start(params: {
    sessionId: string;
    workspaceKey: string;
    readSnapshot(sessionId: string): Promise<DeviceSyncSnapshot>;
    workspaceKeyOf(sessionId: string): Promise<string | null>;
  }): Promise<{ port: number; urls: string[]; token: string; expiresAt: number }>;
  stop(sessionId: string): Promise<void>;
  close(): Promise<void>;
}

/**
 * 进程内唯一的同步监听。读取由调用方注入，避免这里依赖协议 context。
 * 没有活跃 grant 时关闭端口，不让空监听留在局域网上。
 */
export function createDeviceSyncControl(): DeviceSyncControl {
  let server: DeviceSyncServer | null = null;
  // 按会话记账而不是计数：刷新二维码会为同一会话重复 start，
  // 计数会累加但 stop 只减一次，端口就永远关不掉。
  const granted = new Set<string>();
  let readSnapshot: (sessionId: string) => Promise<DeviceSyncSnapshot> = async () => {
    throw new Error("Device sync snapshot reader is not ready");
  };
  let workspaceKeyOf: (sessionId: string) => Promise<string | null> = async () => null;

  return {
    async start(params) {
      readSnapshot = params.readSnapshot;
      workspaceKeyOf = params.workspaceKeyOf;
      server ??= new DeviceSyncServer({
        readSnapshot: (sessionId) => readSnapshot(sessionId),
        workspaceKeyOf: (sessionId) => workspaceKeyOf(sessionId),
      });
      const endpoint = await server.listen();
      if (endpoint.urls.length === 0) {
        throw new Error("No LAN address available for device sync");
      }
      const token = randomBytes(32).toString("base64url");
      const grant = server.grant(params.sessionId, params.workspaceKey, token);
      granted.add(params.sessionId);
      return { port: endpoint.port, urls: endpoint.urls, token, expiresAt: grant.expiresAt };
    },
    async stop(sessionId) {
      if (!server) return;
      server.revoke(sessionId);
      granted.delete(sessionId);
      if (granted.size === 0) {
        const closing = server;
        server = null;
        await closing.close();
      }
    },
    async close() {
      granted.clear();
      const closing = server;
      server = null;
      await closing?.close();
    },
  };
}
