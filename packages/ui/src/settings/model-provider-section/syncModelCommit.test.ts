import assert from "node:assert/strict";
import test from "node:test";
import { probeAndSyncModel } from "./syncModelOperations.js";

function fixture(configured = false) {
  const abort = new AbortController();
  const writes: string[] = [];
  let present = configured;
  const input: Parameters<typeof probeAndSyncModel>[0] = {
    id: "model-a",
    signal: abort.signal,
    isConfigured: () => present,
    probe: async () => ({ success: true }),
    add: async () => {
      writes.push("add");
      present = true;
    },
    setEnabled: async (enabled: boolean) => {
      writes.push(`enabled:${enabled}`);
    },
  };
  return { input, writes, abort };
}

test("new model is only added after a successful probe", async () => {
  const { input, writes } = fixture();
  const events: string[] = [];
  const result = await probeAndSyncModel({
    ...input,
    probe: async () => {
      events.push("probe");
      assert.deepEqual(writes, []);
      return { success: true };
    },
    add: async () => {
      events.push("add");
      await input.add();
    },
  });
  assert.equal(result.success, true);
  assert.deepEqual(events, ["probe", "add"]);
  assert.deepEqual(writes, ["add"]);
});

test("failed and thrown probes never add an unconfigured model", async () => {
  for (const throws of [false, true]) {
    const { input, writes } = fixture();
    const result = await probeAndSyncModel({
      ...input,
      probe: async () => {
        if (throws) throw new Error("HTTP 403");
        return { success: false, error: { message: "HTTP 403" } };
      },
    });
    assert.equal(result.success, false);
    assert.equal(result.message, "HTTP 403");
    assert.deepEqual(writes, []);
  }
});

test("an inconclusive probe never disables an existing model", async () => {
  // 60 秒探测 deadline 与 4 路并发排队会让排在后面的探测以“cancelled”结束；
  // 若据此禁用，已配置模型会成批从选择器消失，形成“选模型空白”。
  for (const message of [
    "Model request was cancelled.",
    "The operation was aborted due to timeout",
    "Aborted",
  ]) {
    const { input, writes } = fixture(true);
    const result = await probeAndSyncModel({
      ...input,
      probe: async () => ({ success: false, error: { message } }),
    });
    assert.equal(result.success, false);
    assert.equal(result.inconclusive, true);
    assert.deepEqual(writes, [], "inconclusive probes must not change enabled state");
  }
});

test("an inconclusive probe keeps remote models out of the list", async () => {
  const { input, writes } = fixture();
  const result = await probeAndSyncModel({
    ...input,
    probe: async () => ({ success: false, error: { message: "Model request was cancelled." } }),
  });
  assert.equal(result.inconclusive, true);
  assert.deepEqual(writes, []);
});

test("provider rejections still disable an existing model", async () => {
  for (const message of ["Provider authentication failed.", "HTTP 403", "Model unavailable."]) {
    const { input, writes } = fixture(true);
    const result = await probeAndSyncModel({
      ...input,
      probe: async () => ({ success: false, error: { message } }),
    });
    assert.equal(result.inconclusive, undefined);
    assert.deepEqual(writes, ["enabled:false"]);
  }
});

test("configured model is probed before changing its enabled state and never re-added", async () => {
  for (const success of [true, false]) {
    const { input, writes } = fixture(true);
    const result = await probeAndSyncModel({
      ...input,
      probe: async () => {
        assert.deepEqual(writes, []);
        return { success, error: { message: "unavailable" } };
      },
    });
    assert.equal(result.success, success);
    assert.deepEqual(writes, [`enabled:${success}`]);
  }
});

test("closing while probe is in flight prevents both additions and enable changes", async () => {
  for (const configured of [false, true]) {
    const { input, writes, abort } = fixture(configured);
    await probeAndSyncModel({
      ...input,
      probe: async () => {
        abort.abort();
        return { success: true };
      },
    });
    assert.deepEqual(writes, []);
  }
});

test("a configured model deleted during the probe is not silently restored", async () => {
  const { input, writes } = fixture(true);
  let configured = true;
  await probeAndSyncModel({
    ...input,
    isConfigured: () => configured,
    probe: async () => {
      configured = false;
      return { success: true };
    },
  });
  assert.deepEqual(writes, []);
});

test("an already canceled operation does not send a probe", async () => {
  const { input, abort, writes } = fixture();
  abort.abort();
  await probeAndSyncModel({
    ...input,
    probe: async () => {
      assert.fail("canceled operation must not dispatch");
    },
  });
  assert.deepEqual(writes, []);
});

test("save failures remain distinct from connectivity failures", async () => {
  const { input } = fixture();
  const result = await probeAndSyncModel({
    ...input,
    add: async () => {
      throw new Error("read-only configuration");
    },
  });
  assert.equal(result.success, false);
  assert.equal(result.stage, "save");
  assert.equal(result.message, "read-only configuration");
});

test("repeating a successful sync only enables the existing row", async () => {
  const { input, writes } = fixture();
  await probeAndSyncModel(input);
  await probeAndSyncModel(input);
  assert.deepEqual(writes, ["add", "enabled:true"]);
});
