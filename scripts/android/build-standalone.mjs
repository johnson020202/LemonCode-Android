// 一条命令生成可独立运行的 Android APK 所需全部产物：
//   1) 共享 Web 生产构建 → assets/www
//   2) 本机服务端 bundle + 内置 Provider 配置
//   3) 上游 Agent CLI（lcode.cjs）
//   4) Termux aarch64 运行时（校验 SHA-256）→ 精简 → jniLibs + assets 数据包
// 所有步骤都是幂等的；产物目录由脚本自行重建，构建机无需预置大文件。
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { resolve } from "node:path";
import { buildServerBundle } from "./build-runtime.mjs";
import { fetchRuntimePackages } from "./fetch-runtime.mjs";
import { buildAndroidRuntime } from "./build-runtime-package.mjs";
import { packageRuntimeForApk } from "./package-runtime.mjs";

const run = promisify(execFile);
const repoRoot = resolve(import.meta.dirname, "../..");

async function step(name, action) {
  console.info(`[android] ${name}`);
  return action();
}

export async function buildStandaloneInputs({ root = repoRoot } = {}) {
  await step("shared web production build", async () => {
    const { main } = await import("./build-web.mjs");
    await main([]);
  });
  await step("local server bundle", () => buildServerBundle({ root }));
  await step("agent cli build", async () => {
    await run("pnpm", ["--dir", "apps/lcode-cli", "--filter", "!@lcode/cli", "build"], {
      cwd: root,
    });
    await run("node", ["scripts/build.mjs"], {
      cwd: resolve(root, "apps/lcode-cli/packages/cli"),
      env: { ...process.env, NODE_OPTIONS: "--max-old-space-size=6144" },
    });
  });
  await step("termux runtime packages", () => fetchRuntimePackages({ root }));
  await step("runtime payload", () => buildAndroidRuntime({ root }));
  return step("apk runtime packaging", () => packageRuntimeForApk({ root }));
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  buildStandaloneInputs()
    .then((manifest) => console.info(`[android] ready: ${JSON.stringify(manifest)}`))
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
