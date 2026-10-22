import { spawn } from "node:child_process";
import { cp, lstat, mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { checkParity } from "./check-parity.mjs";

export const repoRoot = resolve(import.meta.dirname, "../..");
const markerName = ".android-generated";

function command(commandName, args, cwd, env = process.env) {
  return new Promise((accept, reject) => {
    const child = spawn(commandName, args, { cwd, env, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", (code, signal) =>
      code === 0 ? accept() : reject(new Error(`${commandName} failed: ${code ?? signal}`)),
    );
  });
}

async function rejectLinks(path) {
  const info = await lstat(path);
  if (info.isSymbolicLink()) throw new Error(`Symlink not allowed in generated assets: ${path}`);
  if (info.isDirectory())
    for (const entry of await readdir(path)) await rejectLinks(join(path, entry));
}

export async function stageAssets(root, metadata) {
  const source = resolve(root, "packages/web/dist");
  const assets = resolve(root, "apps/android/app/src/main/assets");
  const target = resolve(assets, "www");
  if (!(await readFile(join(source, "index.html"), "utf8")).includes("<html"))
    throw new Error("Missing real Web build");
  await rejectLinks(source);
  await mkdir(assets, { recursive: true });
  await rejectLinks(assets);
  let existing = false;
  try {
    await lstat(target);
    existing = true;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (existing) {
    if (
      (await readFile(join(target, markerName), "utf8").catch(() => "")) !==
      "generated-by-android-build\n"
    ) {
      throw new Error("Refusing to replace unmarked Android asset directory");
    }
    await rm(target, { recursive: true });
  }
  await mkdir(target);
  await writeFile(join(target, markerName), "generated-by-android-build\n");
  await cp(source, target, { recursive: true, filter: (path) => !path.endsWith(".map") });
  const legal = join(target, "legal");
  await mkdir(legal, { recursive: true });
  for (const name of ["LICENSE", "NOTICE.md", "THIRD-PARTY-NOTICES.md"])
    await cp(join(root, name), join(legal, name));
  await writeFile(
    join(assets, "android-build.json"),
    JSON.stringify(
      {
        ...metadata,
        // 独立运行：本机 Node 运行时执行服务端与 Agent，不连接桌面 Host。
        mode: "standalone-android",
        standalone: true,
        fullFeatureParity: false,
      },
      null,
      2,
    ) + "\n",
  );
  return target;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.some((arg) => arg !== "--stage-only"))
    throw new Error("Usage: node scripts/android/build-web.mjs [--stage-only]");
  const baseline = await checkParity(repoRoot);
  const { version } = JSON.parse(await readFile(join(repoRoot, "package.json"), "utf8"));
  if (!argv.includes("--stage-only")) {
    await command("pnpm", ["--filter", "@lcode/web", "build"], repoRoot, {
      ...process.env,
      LCODE_ENV: "production",
      LCODE_COMMIT: baseline.currentSha,
    });
  }
  const target = await stageAssets(repoRoot, {
    upstreamSha: baseline.upstreamSha,
    sourceSha: baseline.currentSha,
    sourceDirty: baseline.sourceDirty,
    clientVersion: version,
    versionName: process.env.ANDROID_VERSION_NAME || `${version}-android`,
    versionCode: Number(process.env.ANDROID_VERSION_CODE || "1"),
    builtAt: new Date().toISOString(),
    stageOnly: argv.includes("--stage-only"),
  });
  console.info(`Android shared client staged: ${target}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
