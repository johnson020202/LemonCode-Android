// 生成 Android 独立运行时的精简包：只保留安装后实际需要执行的 Node、shell、Git、
// 搜索工具及其动态库，剔除 Termux 中与本地服务端无关的 qemu/lazygit 等大件。
// 产物结构 (runtime.json + 文件清单) 由 APK 打包脚本消费，不在此处写死版本。
import { createHash } from "node:crypto";
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { join, relative, resolve } from "node:path";

const repoRoot = resolve(import.meta.dirname, "../..");

// 命令白名单：上游终端/Agent/工具链需要的可执行入口。
const BINARIES = [
  "node",
  "bash",
  "sh",
  "dash",
  "git",
  "git-receive-pack",
  "git-upload-pack",
  "git-upload-archive",
  "rg",
  "bfs",
  "sed",
  "grep",
  "tar",
  "gzip",
  "bzip2",
  "xz",
  "zstd",
  "ls",
  "cat",
  "cp",
  "mv",
  "rm",
  "mkdir",
  "ln",
  "chmod",
  "env",
  "readlink",
  "realpath",
  "dirname",
  "basename",
  "head",
  "tail",
  "wc",
  "sort",
  "uniq",
  "cut",
  "tr",
  "find",
  "which",
  "test",
  "expr",
  "sleep",
  "date",
  "id",
  "uname",
  "printf",
  "tee",
  "touch",
  "stat",
  "du",
  "df",
  "ps",
  "kill",
  "mktemp",
  "diff",
];
// 动态库：整目录保留，缺失任一 .so 都会让 node/git 在启动时失败。
const LIB_DIRS = ["lib"];
// etc 携带 CA 证书与 OpenSSL 配置；缺少它 Node 无法进行任何 HTTPS 请求。
const EXTRA_DIRS = ["etc"];
// git-core 是 git 的别名入口（大量硬链接到同一个二进制），APK 里只需主 git 可执行文件，
// 保留整目录会让产物多出数百 MB 重复数据。
const LIBEXEC_FILES = ["libexec/rmt"];

async function listFiles(root, base = root) {
  const entries = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const full = join(root, entry.name);
    if (entry.isDirectory()) entries.push(...(await listFiles(full, base)));
    else if (entry.isFile()) entries.push(relative(base, full).split("\\").join("/"));
  }
  return entries;
}

export async function buildAndroidRuntime({ root = repoRoot } = {}) {
  const source = resolve(root, ".android-tools/termux-usr");
  const target = resolve(root, ".android-tools/android-runtime");
  const usr = join(target, "usr");
  await rm(target, { recursive: true, force: true });
  await mkdir(join(usr, "bin"), { recursive: true });
  let missing = [];
  for (const name of BINARIES) {
    try {
      await cp(join(source, "bin", name), join(usr, "bin", name), { dereference: true });
    } catch {
      missing.push(name);
    }
  }
  for (const dir of LIB_DIRS) {
    await cp(join(source, dir), join(usr, dir), { recursive: true, dereference: true }).catch(
      () => {},
    );
  }
  for (const dir of EXTRA_DIRS) {
    await cp(join(source, dir), join(usr, dir), { recursive: true, dereference: true }).catch(
      () => {},
    );
  }
  for (const file of LIBEXEC_FILES) {
    await cp(join(source, file), join(usr, file), { dereference: true }).catch(() => {});
  }
  // 运行时只允许执行随包文件；PATH 在启动时由原生层固定为该目录。
  const manifest = [];
  for (const file of await listFiles(usr)) {
    const info = await stat(join(usr, file));
    manifest.push({ path: file, bytes: info.size, executable: file.startsWith("bin/") });
  }
  const digest = createHash("sha256");
  for (const entry of manifest) digest.update(`${entry.path}\n`);
  const versionSource = await readFile(join(source, "bin/node")).catch(() => undefined);
  const runtime = {
    schemaVersion: 1,
    runtimePlatform: "android-arm64",
    nodeVersion: "24.18.0",
    binaries: BINARIES.filter((name) => !missing.includes(name)),
    missingBinaries: missing,
    files: manifest.length,
    bytes: manifest.reduce((sum, entry) => sum + entry.bytes, 0),
    manifestSha256: digest.digest("hex"),
    nodeBytes: versionSource?.length ?? 0,
  };
  await writeFile(join(target, "runtime.json"), JSON.stringify(runtime, null, 2) + "\n");
  return runtime;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  buildAndroidRuntime()
    .then((runtime) => console.info(JSON.stringify(runtime, null, 2)))
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
