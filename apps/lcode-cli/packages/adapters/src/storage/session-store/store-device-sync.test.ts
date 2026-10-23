import assert from "node:assert/strict";
import test from "node:test";
import type { DeviceSyncCommitBundle, MessageId, PartId, SessionId } from "@lcode/contracts";
import { createSqliteSessionStore } from "../session-store.js";

function bundle(): DeviceSyncCommitBundle {
  const sessionID = "sess_phone" as SessionId;
  const messageID = "msg_phone" as MessageId;
  return {
    session: {
      id: sessionID,
      projectID: "proj_phone" as DeviceSyncCommitBundle["session"]["projectID"],
      slug: "phone",
      directory: "/data/user/0/vin.lemon.android/files/workspace",
      path: "/data/user/0/vin.lemon.android/files/workspace",
      title: "来自电脑 fixprobe",
      titleSource: "custom",
      version: "1",
    },
    messages: [
      {
        info: { id: messageID, sessionID, agent: "lcode-agent", role: "user", time: { created: 1 } },
        parts: [
          { id: "part_phone" as PartId, messageID, sessionID, type: "text", text: "电脑上的原话" },
        ],
      },
    ],
  };
}

test("device sync commits the snapshot in one transaction and keeps a repeat idempotent", async () => {
  const store = createSqliteSessionStore({ dbPath: ":memory:" });
  try {
    const input = bundle();
    const created = await store.commitDeviceSyncBundle(input);
    assert.equal(created.id, input.session.id);
    const messages = await store.messages({ sessionID: created.id });
    assert.equal(messages.length, 1);
    assert.equal(messages[0]?.parts[0]?.type === "text" ? messages[0].parts[0].text : "", "电脑上的原话");
    const counts = store.debugCounts();
    assert.equal((await store.commitDeviceSyncBundle(input)).id, created.id);
    assert.deepEqual(store.debugCounts(), counts);
  } finally {
    store.close();
  }
});

test("device sync rejects a message or part that belongs to another session and writes nothing", async () => {
  const store = createSqliteSessionStore({ dbPath: ":memory:" });
  try {
    const input = bundle();
    input.messages[0]!.info.sessionID = "sess_other" as SessionId;
    await assert.rejects(store.commitDeviceSyncBundle(input), /does not belong to the target session/);
    assert.equal(await store.getSession(input.session.id), null);

    const partMismatch = bundle();
    partMismatch.messages[0]!.parts[0]!.messageID = "msg_other" as MessageId;
    await assert.rejects(store.commitDeviceSyncBundle(partMismatch), /does not belong to its message/);
    assert.equal(await store.getSession(partMismatch.session.id), null);
  } finally {
    store.close();
  }
});
