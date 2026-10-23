import { createServer, type Server } from "node:http";
import { networkInterfaces } from "node:os";
import { timingSafeEqual } from "node:crypto";

/** 一次成功响应后密钥即失效，避免局域网内重复拉取同一份正文。 */
const DEFAULT_TTL_MS = 5 * 60 * 1000;
const MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024;

export interface DeviceSyncSnapshot {
  title: string;
  createdAt: number;
  updatedAt: number;
  messages: unknown[];
}

export interface DeviceSyncGrant {
  sessionId: string;
  /** 只接受属于这个工作区的会话，隔离 key 与任务索引一致。 */
  workspaceKey: string;
  token: string;
  expiresAt: number;
}

export interface DeviceSyncServerOptions {
  readSnapshot(sessionId: string): Promise<DeviceSyncSnapshot>;
  /** 返回会话所属工作区的隔离 key，不匹配则拒绝。 */
  workspaceKeyOf(sessionId: string): Promise<string | null>;
  now?(): number;
  ttlMs?: number;
}

export interface DeviceSyncEndpoint {
  port: number;
  urls: string[];
}

/**
 * 只服务设备同步的一次性拉取。不复用 entry-http，因此不暴露会话列表、文件或 RPC。
 * 密钥只通过 Authorization 头比对，不进路径、不进日志。
 */
export class DeviceSyncServer {
  private readonly grants = new Map<string, DeviceSyncGrant>();
  private server: Server | null = null;
  private readonly now: () => number;
  private readonly ttlMs: number;

  constructor(private readonly options: DeviceSyncServerOptions) {
    this.now = options.now ?? Date.now;
    this.ttlMs = options.ttlMs ?? DEFAULT_TTL_MS;
  }

  /** 登记一次拉取。同一会话重复登记覆盖旧密钥，旧密钥立即失效。 */
  grant(sessionId: string, workspaceKey: string, token: string): DeviceSyncGrant {
    const grant = { sessionId, workspaceKey, token, expiresAt: this.now() + this.ttlMs };
    this.grants.set(sessionId, grant);
    return grant;
  }

  revoke(sessionId: string): void {
    this.grants.delete(sessionId);
  }

  async listen(): Promise<DeviceSyncEndpoint> {
    if (this.server) return this.endpoint();
    const server = createServer((request, response) => {
      void this.handle(request, response).catch(() => {
        if (!response.headersSent) response.writeHead(500).end();
      });
    });
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "0.0.0.0", () => {
        server.off("error", reject);
        resolve();
      });
    });
    this.server = server;
    return this.endpoint();
  }

  async close(): Promise<void> {
    const server = this.server;
    this.server = null;
    this.grants.clear();
    if (!server) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  private endpoint(): DeviceSyncEndpoint {
    const address = this.server?.address();
    const port = address && typeof address === "object" ? address.port : 0;
    return { port, urls: lanUrls(port) };
  }

  private async handle(
    request: import("node:http").IncomingMessage,
    response: import("node:http").ServerResponse,
  ): Promise<void> {
    const url = new URL(request.url ?? "/", "http://localhost");
    const match = /^\/sync\/sessions\/([^/]+)$/u.exec(url.pathname);
    if (request.method !== "GET" || !match) {
      response.writeHead(404).end();
      return;
    }
    const sessionId = decodeURIComponent(match[1] ?? "");
    const grant = this.grants.get(sessionId);
    if (!grant || grant.expiresAt <= this.now() || !bearerMatches(request.headers.authorization, grant.token)) {
      response.writeHead(401).end();
      return;
    }
    const workspaceKey = await this.options.workspaceKeyOf(sessionId);
    if (workspaceKey === null || workspaceKey !== grant.workspaceKey) {
      response.writeHead(404).end();
      return;
    }
    const snapshot = await this.options.readSnapshot(sessionId);
    const body = JSON.stringify(snapshot);
    if (Buffer.byteLength(body) > MAX_SNAPSHOT_BYTES) {
      response.writeHead(413).end();
      return;
    }
    // 成功后立即失效，重复拉取一律 401，不区分原因。
    this.grants.delete(sessionId);
    response.writeHead(200, { "content-type": "application/json" }).end(body);
  }
}

function bearerMatches(header: string | undefined, token: string): boolean {
  const provided = header?.startsWith("Bearer ") ? header.slice(7) : "";
  const left = Buffer.from(provided);
  const right = Buffer.from(token);
  return left.length === right.length && timingSafeEqual(left, right);
}

/** 只要非内部 IPv4，与 scripts/lcode-distribution/runner.mjs 的 networkUrls 同一规则。 */
export function lanUrls(port: number): string[] {
  const urls: string[] = [];
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.internal || entry.family !== "IPv4") continue;
      urls.push(`http://${entry.address}:${port}/sync`);
    }
  }
  return urls;
}
