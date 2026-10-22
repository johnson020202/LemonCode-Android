// 把精简后的 Android 运行时组装进 APK：
// - 可执行文件与共享库放入 jniLibs/arm64-v8a/lib*.so：Android 只允许从 nativeLibraryDir
//   执行随包二进制，且安装器只会解压 lib*.so；
// - 其余数据文件打入 assets/runtime-payload.tar（安装后解压到应用私有目录）；
// - 生成 manifest，供原生层建立"名称 → 原生库"的符号链接，保持 ELF SONAME 可解析。
// 不在此脚本内下载任何内容：输入必须是已校验的 .android-tools/android-runtime。
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { promisify } from "node:util";

const run = promisify(execFile);
const repoRoot = resolve(import.meta.dirname, "../..");

function encodedName(path) {
  // 扁平化到 nativeLibraryDir，文件名必须保持 lib*.so，且只含安全字符。
  return `lib${path.replace(/[^A-Za-z0-9]+/gu, "_")}_bin.so`;
}

export async function packageRuntimeForApk({ root = repoRoot } = {}) {
  const source = resolve(root, ".android-tools/android-runtime");
  const appDir = resolve(root, "apps/android/app/src/main");
  const jniDir = resolve(appDir, "jniLibs/arm64-v8a");
  const assetsDir = resolve(appDir, "assets/runtime");
  await rm(jniDir, { recursive: true, force: true });
  await rm(assetsDir, { recursive: true, force: true });
  await mkdir(jniDir, { recursive: true });
  await mkdir(assetsDir, { recursive: true });

  const usr = join(source, "usr");
  const entries = await readdir(join(usr, "bin"));
  const libEntries = await readdir(join(usr, "lib"));

  // 判定可执行/库文件：bin/ 下全部，lib/ 下 .so 及符号链接目标。
  const nativeFiles = [];
  const binaryMap = [];
  for (const name of entries) {
    const full = join(usr, "bin", name);
    const info = await stat(full);
    if (!info.isFile()) continue;
    const so = encodedName(`usr/bin/${name}`);
    await cp(full, join(jniDir, so), { dereference: true });
    nativeFiles.push(so);
    binaryMap.push({ path: `usr/bin/${name}`, soname: so, executable: true });
  }
  for (const name of libEntries) {
    const full = join(usr, "lib", name);
    const info = await stat(full);
    if (!info.isFile()) continue;
    const so = encodedName(`usr/lib/${name}`);
    await cp(full, join(jniDir, so), { dereference: true });
    nativeFiles.push(so);
    binaryMap.push({ path: `usr/lib/${name}`, soname: so, executable: false });
  }

  // 数据文件（libexec / provider 配置等）打进 tar，由原生层解压。
  const dataRoot = resolve(root, ".android-tools/runtime-data");
  await rm(dataRoot, { recursive: true, force: true });
  await mkdir(dataRoot, { recursive: true });
  for (const name of await readdir(usr)) {
    if (name === "bin" || name === "lib") continue;
    await cp(join(usr, name), join(dataRoot, name), { recursive: true, dereference: true });
  }
  const runtimeBundle = resolve(root, ".android-tools/runtime-bundle");
  await cp(runtimeBundle, join(dataRoot, "server"), { recursive: true });
  await cp(
    resolve(root, "apps/lcode-cli/packages/cli/dist/lcode.cjs"),
    join(dataRoot, "server/lcode.cjs"),
  );
  await cp(resolve(root, "apps/android/app/src/main/assets/www"), join(dataRoot, "server/www"), {
    recursive: true,
  });
  await run("tar", ["-czf", join(assetsDir, "runtime-payload.tar"), "-C", dataRoot, "."]);

  const payload = await readFile(join(assetsDir, "runtime-payload.tar"));
  const manifest = {
    schemaVersion: 1,
    abi: "arm64-v8a",
    nodePath: binaryMap.find((entry) => entry.path === "usr/bin/node")?.soname,
    // 原生层据此建立符号链接，使 LD_LIBRARY_PATH 与 SONAME 契约成立。
    links: binaryMap,
    payloadBytes: payload.length,
    payloadSha256: createHash("sha256").update(payload).digest("hex"),
    nativeLibs: nativeFiles.length,
  };
  await writeFile(
    join(assetsDir, "runtime-manifest.json"),
    JSON.stringify(manifest, null, 2) + "\n",
  );
  return manifest;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  packageRuntimeForApk()
    .then((manifest) =>
      console.info(
        `Android runtime packaged: ${manifest.nativeLibs} libs, payload ${manifest.payloadBytes} bytes`,
      ),
    )
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
