// 为 Android 内嵌运行时打包上游本地服务端：把 entry-http 及其依赖打成单文件 ESM，
// 由 APK 内置的 Node 直接执行，手机不需要 node_modules。
// 与桌面构建的差异：
// - 内置 Provider 配置作为独立 JSON 资源随包发布，由 androidRuntimeEntry 在启动前写入，
//   不走 __LCODE_BUILTIN_PROVIDER_CONFIG_JSON__ 模板字符串（配置内含反引号与转义，直接内联会破坏 bundle）；
// - node-pty 等只能在桌面编译的原生模块保持 external，手机侧缺少 pty 时按既有语义报告不可用，
//   而不是让整个服务端启动失败。
import { build } from "esbuild";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const repoRoot = resolve(import.meta.dirname, "../..");
const outputDir = resolve(repoRoot, ".android-tools/runtime-bundle");

export async function buildServerBundle({ root = repoRoot, outDir = outputDir } = {}) {
  const builtinProviderConfig = (
    await readFile(resolve(root, "config/provider/lcode-builtin.json"), "utf8")
  ).trim();
  const { version } = JSON.parse(await readFile(resolve(root, "package.json"), "utf8"));
  await mkdir(outDir, { recursive: true });
  const outFile = resolve(outDir, "entry-http.mjs");
  const result = await build({
    entryPoints: [resolve(root, "scripts/android/runtime-entry.ts")],
    bundle: true,
    platform: "node",
    format: "esm",
    target: "node22",
    outfile: outFile,
    // 原生 ABI 模块无法跨平台打包；缺失时由各自服务在调用时降级。
    external: ["node-pty", "cpu-features", "koffi"],
    define: {
      __LCODE_VERSION__: JSON.stringify(version),
      __LCODE_ENV__: JSON.stringify("production"),
    },
    banner: {
      js: "import{createRequire as __lcodeCreateRequire}from'node:module';const require=__lcodeCreateRequire(import.meta.url);",
    },
    logLevel: "warning",
    metafile: true,
  });
  // Android 入口读取该文件后仍走上游 materializeLCodeBuiltinProviderConfig 的完整校验。
  await writeFile(resolve(outDir, "lcode-builtin.json"), builtinProviderConfig + "\n");
  const bytes = Object.values(result.metafile.outputs)[0]?.bytes ?? 0;
  await writeFile(
    resolve(outDir, "server-bundle.json"),
    JSON.stringify({ entry: "entry-http.mjs", bytes, nodeTarget: "node22" }, null, 2) + "\n",
  );
  return { outFile, bytes };
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  buildServerBundle()
    .then(({ bytes }) => console.info(`Android server bundle ready: ${bytes} bytes`))
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
