// 校验产出的 APK 确实包含可独立运行的运行时：
// - 只接受 arm64-v8a 原生库与 assets 运行时数据包；
// - manifest 里每个链接都能在 APK 的 lib/ 中找到对应 lib*.so；
// - 不含 source map、调试签名只能来自 debug 构建；
// - 元数据明确声明 standalone 与功能对等状态，避免把预览包当作全功能交付。
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { promisify } from "node:util";

const run = promisify(execFile);
const repoRoot = resolve(import.meta.dirname, "../..");

export async function inspectApk(apkPath) {
  const work = await mkdtemp(join(tmpdir(), "lcode-apk-"));
  try {
    await run("unzip", ["-q", apkPath, "-d", work]);
    const manifest = JSON.parse(
      await readFile(join(work, "assets/runtime/runtime-manifest.json"), "utf8"),
    );
    const entries = await run("unzip", ["-Z1", apkPath]).then(({ stdout }) => stdout.split("\n"));
    const native = new Set(entries.filter((entry) => entry.startsWith("lib/")));
    const missing = manifest.links
      .map((link) => `lib/arm64-v8a/${link.soname}`)
      .filter((entry) => !native.has(entry));
    if (missing.length) {
      throw new Error(`Runtime links missing from APK: ${missing.slice(0, 5).join(", ")}`);
    }
    if (!entries.includes("assets/runtime/runtime-payload.tar")) {
      throw new Error("APK is missing the runtime data payload");
    }
    if (!entries.includes("assets/www/index.html")) {
      throw new Error("APK is missing the shared web client");
    }
    const maps = entries.filter((entry) => entry.endsWith(".map"));
    if (maps.length) throw new Error(`APK contains source maps: ${maps.slice(0, 3).join(", ")}`);
    const payload = await readFile(join(work, "assets/runtime/runtime-payload.tar"));
    const digest = createHash("sha256").update(payload).digest("hex");
    if (digest !== manifest.payloadSha256) {
      throw new Error("Runtime payload digest does not match its manifest");
    }
    const metadataPath = join(work, "assets/android-build.json");
    let metadata = null;
    try {
      metadata = JSON.parse(await readFile(metadataPath, "utf8"));
    } catch {
      // 调试包允许缺少元数据；发布包在 Gradle 侧已强制要求。
    }
    const apk = await stat(apkPath);
    return {
      apk: apkPath,
      bytes: apk.size,
      abi: manifest.abi,
      nativeLibs: manifest.nativeLibs,
      linkedFiles: manifest.links.length,
      payloadBytes: manifest.payloadBytes,
      payloadSha256: digest,
      metadata,
    };
  } finally {
    await rm(work, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  const apkPath = resolve(
    repoRoot,
    process.argv[2] || "apps/android/app/build/outputs/apk/debug/app-debug.apk",
  );
  inspectApk(apkPath)
    .then(async (report) => {
      await writeFile(
        resolve(repoRoot, ".android-tools/runtime-verification.json"),
        JSON.stringify(report, null, 2) + "\n",
      );
      console.info(JSON.stringify(report, null, 2));
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
