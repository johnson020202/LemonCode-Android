// Android 独立运行入口：不连接桌面，直接在手机内启动上游本地服务端
// （文件、Git、会话、Agent、MCP、插件均由本进程与其子进程执行）。
// 与其他入口的差异仅在于内置 Provider 配置来自随包 JSON 文件，而不是构建期常量，
// 因为该 JSON 含反引号与转义序列，内联进 ESM bundle 会破坏模板字符串。
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { migrateHomeBrandDataRootSync } from "../../packages/shared/src/node.js";
import {
  createLocalServices,
  getAppConfigDir,
  getDataBaseDir,
  materializeLCodeBuiltinProviderConfig,
} from "../../packages/services/src/node.js";
import { createHttpServer } from "../../packages/server/src/http.js";

async function main(): Promise<void> {
  const runtimeRoot = resolve(process.env.LCODE_ANDROID_RUNTIME_ROOT?.trim() || process.cwd());
  const staticRoot = process.env.LCODE_WEB_STATIC_ROOT?.trim() || undefined;
  const port = Number(process.env.LCODE_SERVER_PORT || 0);
  // 0 表示由内核分配空闲端口；Android 端从标准日志行的 http:// 地址回读实际端口。
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error("LCODE_SERVER_PORT must be a valid TCP port");
  }
  const content = await readFile(resolve(runtimeRoot, "lcode-builtin.json"), "utf8");
  migrateHomeBrandDataRootSync(getDataBaseDir());
  const lcodeBuiltinProviderConfigFilePath = await materializeLCodeBuiltinProviderConfig({
    environmentConfigRoot: getAppConfigDir(),
    content,
  });
  const services = createLocalServices({
    lcodeBuiltinProviderConfigFilePath,
    providerProvisioningTargetEnabled: false,
  });
  // 仅回环监听：手机本机 WebView 访问，不接受外部设备直连。
  createHttpServer(services, port, {
    host: "127.0.0.1",
    ...(staticRoot ? { staticRoot, spaFallback: true } : {}),
  });
  console.info("[android-runtime] runtime ready");
}

void main().catch((error: unknown) => {
  console.error("[android-runtime] startup failed", error);
  process.exitCode = 1;
});
