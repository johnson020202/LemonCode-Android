import type { DeviceSyncCommitBundle, SessionInfo } from "@lcode/contracts";
import type { SqliteStoreAccess } from "./store-access.js";
import * as sessionRepository from "./repositories/sessions.js";
import * as messageRepository from "./repositories/messages.js";

export const deviceSyncMethods = {
  /**
   * 设备同步的唯一写入入口。调用方负责重写 ID、sessionID 与 assistant 执行路径；
   * 这里只校验归属一致，并在一个事务里提交会话行、全部 message 与 part。
   * 中途失败回滚，不留半条会话。重复提交同一会话 ID 直接返回已有行，不追加消息。
   */
  async commitDeviceSyncBundle(
    this: SqliteStoreAccess,
    bundle: DeviceSyncCommitBundle,
  ): Promise<SessionInfo> {
    this.throwBeforeWrite();
    const sessionId = String(bundle.session.id);
    for (const message of bundle.messages) {
      if (String(message.info.sessionID) !== sessionId) {
        throw new Error("Device sync message does not belong to the target session");
      }
      for (const part of message.parts) {
        if (String(part.sessionID) !== sessionId || String(part.messageID) !== String(message.info.id)) {
          throw new Error("Device sync part does not belong to its message");
        }
      }
    }
    this.db.exec("begin immediate");
    try {
      const existing = sessionRepository.getSession(this.db, bundle.session.id);
      if (existing) {
        this.db.exec("commit");
        return existing;
      }
      const persisted = sessionRepository.createSession(this.db, bundle.session);
      for (const message of bundle.messages) {
        await messageRepository.saveMessage(this.db, message.info);
        for (const part of message.parts) {
          await messageRepository.savePart(this.db, part);
        }
      }
      this.db.exec("commit");
      return persisted;
    } catch (error) {
      this.db.exec("rollback");
      throw error;
    }
  },
};
