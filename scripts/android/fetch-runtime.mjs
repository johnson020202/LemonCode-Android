// 为 Android 独立运行时下载可复现的 aarch64 组件并解包。
// 只从 Termux 官方 apt 源取包，逐个校验 apt 索引中的 SHA-256；任何一项不匹配即失败。
// 产物在 .android-tools/termux-usr（Git 忽略），由 build-runtime-package.mjs 精简后再进 APK。
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { promisify } from "node:util";
import { gunzipSync } from "node:zlib";

const run = promisify(execFile);
const APT_ROOT = "https://packages.termux.dev/apt/termux-main";
const repoRoot = resolve(import.meta.dirname, "../..");

// 运行时必需的包：Node 及其依赖、shell/Git/搜索工具、proot 依赖。
export const PACKAGES = [
  "nodejs-lts",
  "libc++",
  "openssl",
  "c-ares",
  "libicu",
  "libsqlite",
  "zlib",
  "bash",
  "dash",
  "coreutils",
  "git",
  "libcurl",
  "libexpat",
  "pcre2",
  "libgmp",
  "less",
  "readline",
  "libiconv",
  "libandroid-support",
  "libandroid-selinux",
  "libbsd",
  "libmd",
  "bzip2",
  "xz-utils",
  "liblzma",
  "lzlib",
  "tar",
  "gzip",
  "sed",
  "grep",
  "termux-tools",
  "ripgrep",
  "diffutils",
];

/** 解析 apt 索引，返回 package → {version, filename, sha256}。 */
export function parsePackagesIndex(text) {
  const result = new Map();
  let current = {};
  const flush = () => {
    if (current.Package && current.Filename && current.SHA256) {
      result.set(current.Package, {
        version: current.Version ?? "unknown",
        filename: current.Filename,
        sha256: current.SHA256,
      });
    }
    current = {};
  };
  for (const line of text.split("\n")) {
    if (!line.trim()) {
      flush();
      continue;
    }
    const index = line.indexOf(":");
    if (index < 0) continue;
    current[line.slice(0, index)] = line.slice(index + 1).trim();
  }
  flush();
  return result;
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

/** 极简 ar + tar 解包（macOS 的 ar 会对长文件名报错，这里自行解析）。 */
export function extractDeb(data, destination) {
  if (data.subarray(0, 8).toString("latin1") !== "!<arch>\n") {
    throw new Error("not a deb archive");
  }
  let position = 8;
  while (position + 60 <= data.length) {
    const header = data.subarray(position, position + 60);
    position += 60;
    const name = header.subarray(0, 16).toString("latin1").trim().replace(/\/$/u, "");
    const size = Number.parseInt(header.subarray(48, 58).toString("latin1").trim(), 10);
    if (!Number.isFinite(size) || size < 0) throw new Error("malformed ar entry");
    const body = data.subarray(position, position + size);
    position += size + (size % 2);
    if (!name.startsWith("data.tar")) continue;
    return decompressAndUntar(name, body, destination);
  }
  throw new Error("deb has no data.tar entry");
}

async function decompressAndUntar(name, body, destination) {
  const tarPath = join(destination, "..", `payload-${Date.now()}.tar`);
  const archive = name.endsWith(".gz") ? gunzipSync(body) : body;
  await writeFile(tarPath, archive);
  try {
    // xz/未压缩的 data.tar 交给系统 tar；Linux CI 与 macOS 都自带 bsdtar/GNU tar。
    await run("tar", ["-xf", tarPath, "-C", destination]);
  } finally {
    await rm(tarPath, { force: true });
  }
}

export async function fetchRuntimePackages({ root = repoRoot } = {}) {
  const cache = resolve(root, ".android-tools/termux-packages");
  const usr = resolve(root, ".android-tools/termux-usr");
  const work = resolve(root, ".android-tools/termux-extract");
  await mkdir(cache, { recursive: true });
  await mkdir(usr, { recursive: true });
  const response = await fetch(`${APT_ROOT}/dists/stable/main/binary-aarch64/Packages.gz`);
  if (!response.ok) throw new Error(`package index unavailable: ${response.status}`);
  const index = parsePackagesIndex(
    gunzipSync(Buffer.from(await response.arrayBuffer())).toString("utf8"),
  );
  const installed = [];
  for (const name of PACKAGES) {
    const entry = index.get(name);
    if (!entry) throw new Error(`Termux package not found: ${name}`);
    const file = resolve(cache, entry.filename.split("/").pop());
    let data;
    try {
      data = await readFile(file);
    } catch {
      const download = await fetch(`${APT_ROOT}/${entry.filename}`);
      if (!download.ok) throw new Error(`download failed for ${name}: ${download.status}`);
      data = Buffer.from(await download.arrayBuffer());
      await writeFile(file, data);
    }
    const actual = sha256(data);
    if (actual !== entry.sha256) {
      throw new Error(`checksum mismatch for ${name}: ${actual} != ${entry.sha256}`);
    }
    await rm(work, { recursive: true, force: true });
    await mkdir(work, { recursive: true });
    await extractDeb(data, work);
    const payloadUsr = join(work, "data/data/com.termux/files/usr");
    // Termux 的 deb 以 data/data/com.termux/files/usr 为前缀，合并成单一 usr 树。
    await run("cp", ["-R", `${payloadUsr}/.`, usr]);
    installed.push({ name, version: entry.version, sha256: entry.sha256 });
  }
  await rm(work, { recursive: true, force: true });
  await writeFile(
    resolve(root, ".android-tools/termux-lock.json"),
    JSON.stringify({ aptRoot: APT_ROOT, abi: "aarch64", packages: installed }, null, 2) + "\n",
  );
  return installed;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  fetchRuntimePackages()
    .then((installed) => console.info(`Fetched and verified ${installed.length} runtime packages`))
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
