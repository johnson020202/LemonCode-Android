import assert from "node:assert/strict";
import test from "node:test";
import { DeviceSyncServer, type DeviceSyncSnapshot } from "./device-sync-server.js";

const snapshot: DeviceSyncSnapshot = {
  title: "fixprobe",
  createdAt: 1,
  updatedAt: 2,
  messages: [{ info: { id: "m1" }, parts: [] }],
};

function server(overrides: Partial<ConstructorParameters<typeof DeviceSyncServer>[0]> = {}) {
  return new DeviceSyncServer({
    readSnapshot: async () => snapshot,
    workspaceKeyOf: async () => "/workspace",
    now: () => 1_000,
    ttlMs: 1_000,
    ...overrides,
  });
}

async function get(port: number, path: string, token?: string): Promise<{ status: number; body: string }> {
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });
  return { status: response.status, body: await response.text() };
}

test("a grant serves exactly one snapshot and then rejects every repeat", async () => {
  const sync = server();
  const endpoint = await sync.listen();
  try {
    sync.grant("sess_1", "/workspace", "secret");
    const first = await get(endpoint.port, "/sync/sessions/sess_1", "secret");
    assert.equal(first.status, 200);
    assert.equal(JSON.parse(first.body).title, "fixprobe");
    assert.equal((await get(endpoint.port, "/sync/sessions/sess_1", "secret")).status, 401);
  } finally {
    await sync.close();
  }
});

test("wrong token, expired grant and foreign workspace all refuse without leaking which", async () => {
  let now = 1_000;
  const sync = server({ now: () => now });
  const endpoint = await sync.listen();
  try {
    sync.grant("sess_1", "/workspace", "secret");
    assert.equal((await get(endpoint.port, "/sync/sessions/sess_1", "nope")).status, 401);
    now = 3_000;
    assert.equal((await get(endpoint.port, "/sync/sessions/sess_1", "secret")).status, 401);
    now = 1_000;
    sync.grant("sess_2", "/other", "secret");
    assert.equal((await get(endpoint.port, "/sync/sessions/sess_2", "secret")).status, 404);
    assert.equal((await get(endpoint.port, "/sync/sessions")).status, 404);
  } finally {
    await sync.close();
  }
});

test("an oversized snapshot is refused and the grant stays unconsumed", async () => {
  const sync = server({
    readSnapshot: async () => ({ ...snapshot, messages: ["x".repeat(33 * 1024 * 1024)] }),
  });
  const endpoint = await sync.listen();
  try {
    sync.grant("sess_1", "/workspace", "secret");
    assert.equal((await get(endpoint.port, "/sync/sessions/sess_1", "secret")).status, 413);
    // 413 不是成功响应，密钥不失效；但正文仍然不能被取走。
    assert.equal((await get(endpoint.port, "/sync/sessions/sess_1", "secret")).status, 413);
  } finally {
    await sync.close();
  }
});

test("closing the server drops grants and stops listening", async () => {
  const sync = server();
  const endpoint = await sync.listen();
  sync.grant("sess_1", "/workspace", "secret");
  await sync.close();
  await assert.rejects(get(endpoint.port, "/sync/sessions/sess_1", "secret"));
});
