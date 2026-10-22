import { lstat, mkdir, realpath } from "node:fs/promises";
import { dirname, isAbsolute, join, normalize, parse, relative, resolve, sep } from "node:path";
import { ProjectMemoryChangeSchema } from "@lcode/contracts";
import { getNodeErrorCode } from "./file-system-common.js";
import { memoryError } from "./project-memory-io.js";

interface DirectoryIdentity {
  dev: number;
  ino: number;
}
export interface MemoryRoot {
  rootDir: string;
  stateDir: string;
  lockPath: string;
  pins: Map<string, DirectoryIdentity>;
}

export function contained(directory: string, path: string): boolean {
  const value = relative(directory, path);
  return value === "" || (!isAbsolute(value) && value !== ".." && !value.startsWith(`..${sep}`));
}

export function samePath(left: string, right: string): boolean {
  return relative(left, right) === "";
}

export function localAbsolutePath(path: string): string {
  // UNC、设备路径及导航段不允许在 realpath 之前触发网络 I/O 或被 normalize 隐去。
  if (
    !isAbsolute(path) ||
    /^[\\/]{2}/u.test(path) ||
    [...path].some((char) => char.charCodeAt(0) < 32) ||
    path.split(/[\\/]/u).some((part) => part === ".." || part === ".")
  ) {
    throw memoryError(
      "invalid_path",
      path,
      "Project Memory requires an absolute local path without navigation segments",
    );
  }
  return normalize(path);
}

async function checkDirectory(
  path: string,
  pins: MemoryRoot["pins"],
  create: boolean,
  trustedPrefix?: string,
): Promise<void> {
  let info;
  try {
    info = await lstat(path);
  } catch (error) {
    if (getNodeErrorCode(error) !== "ENOENT" || !create || pins.has(path)) throw error;
    // 不使用 recursive mkdir：每一层都先验证，避免穿过 junction 创建根外目录。
    try {
      await mkdir(path, { mode: 0o700 });
    } catch (mkdirError) {
      if (getNodeErrorCode(mkdirError) !== "EEXIST") throw mkdirError;
    }
    info = await lstat(path);
  }
  // 记忆根父目录之上的平台别名（/data/user/0）不拒绝：设备实测那一级是符号链接，
  // 其下每一级都是普通目录。不能跟随它再 stat：HyperOS 对解析后的 /data/data/<pkg>
  // 返回 EACCES，跟随一次就会让登记失败。这一级也不做 inode 锚点。
  // 父目录及以内的链接仍是逃逸路径，照旧拒绝。
  const platformAlias =
    info.isSymbolicLink() &&
    trustedPrefix !== undefined &&
    path !== trustedPrefix &&
    !contained(trustedPrefix, path);
  if (platformAlias) return;
  if (info.isSymbolicLink() || !info.isDirectory()) {
    throw memoryError(
      "invalid_path",
      path,
      "Project Memory directory must not be a symlink or junction",
    );
  }
  const expected = pins.get(path);
  if (expected && (expected.dev !== info.dev || expected.ino !== info.ino)) {
    throw memoryError("invalid_path", path, "Registered Project Memory directory identity changed");
  }
  pins.set(path, { dev: info.dev, ino: info.ino });
}

async function directoryChain(
  path: string,
  pins: MemoryRoot["pins"],
  create: boolean,
  trustedPrefix?: string,
): Promise<void> {
  const root = parse(path).root;
  let current = root;
  for (const component of relative(root, path).split(sep).filter(Boolean)) {
    current = join(current, component);
    await checkDirectory(current, pins, create, trustedPrefix);
  }
}

export async function prepareMemoryRoot(rawRoot: string): Promise<MemoryRoot> {
  const rootDir = localAbsolutePath(rawRoot);
  const stateDir = join(dirname(rootDir), "memory-state");
  if (samePath(rootDir, stateDir) || samePath(rootDir, parse(rootDir).root)) {
    throw memoryError(
      "invalid_path",
      rootDir,
      "Project Memory root must have a separate sibling control directory",
    );
  }
  const root = {
    rootDir,
    stateDir,
    lockPath: join(stateDir, "writer"),
    pins: new Map<string, DirectoryIdentity>(),
  };
  const trustedPrefix = dirname(rootDir);
  await directoryChain(rootDir, root.pins, true, trustedPrefix);
  await directoryChain(stateDir, root.pins, true, trustedPrefix);
  await assertMemoryRoot(root);
  return root;
}

export async function assertMemoryRoot(root: MemoryRoot): Promise<void> {
  const trustedPrefix = dirname(root.rootDir);
  for (const path of root.pins.keys())
    await checkDirectory(path, root.pins, false, trustedPrefix);
}

export async function initializeMemoryDirectories(root: MemoryRoot): Promise<void> {
  for (const directory of ["journal", "preimages", "reviews", "staging"]) {
    await assertMemoryRoot(root);
    await directoryChain(join(root.stateDir, directory), root.pins, true, dirname(root.rootDir));
  }
}

export function memoryRelativePath(root: MemoryRoot, path: string): string {
  localAbsolutePath(path);
  if (!contained(root.rootDir, path) || samePath(root.rootDir, path)) {
    throw memoryError("invalid_path", path, "Project Memory target escapes the registered root");
  }
  const fileName = relative(root.rootDir, path).split(sep).join("/");
  if (
    !ProjectMemoryChangeSchema.shape.fileName.safeParse(fileName).success ||
    fileName
      .split("/")
      .some(
        (part) =>
          /[. ]$/u.test(part) || /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/iu.test(part),
      )
  ) {
    throw memoryError(
      "invalid_path",
      path,
      "Project Memory accepts contained regular Markdown paths only",
    );
  }
  return fileName;
}

export async function checkMemoryTarget(
  root: MemoryRoot,
  fileName: string,
  createParents = false,
): Promise<string> {
  const path = resolve(root.rootDir, fileName);
  if (!ProjectMemoryChangeSchema.shape.fileName.safeParse(fileName).success) {
    throw memoryError("invalid_path", fileName, "Invalid Project Memory relative path");
  }
  memoryRelativePath(root, path);
  await assertMemoryRoot(root);
  // 目标的子目录只属于本次路径验证；删除旧记忆目录不应污染 root/state 的长期身份锚点。
  await directoryChain(dirname(path), new Map(root.pins), createParents, dirname(root.rootDir));
  try {
    const info = await lstat(path);
    if (info.isSymbolicLink() || !info.isFile()) {
      throw memoryError(
        "invalid_path",
        path,
        "Project Memory target must be a regular file, not a link",
      );
    }
  } catch (error) {
    if (getNodeErrorCode(error) !== "ENOENT") throw error;
  }
  return path;
}

export async function checkLockPath(root: MemoryRoot): Promise<void> {
  await assertMemoryRoot(root);
  const lock = `${root.lockPath}.lock`;
  try {
    const info = await lstat(lock);
    if (info.isSymbolicLink() || !info.isDirectory()) {
      throw memoryError("invalid_path", lock, "Project Memory lock must not be redirected");
    }
  } catch (error) {
    if (getNodeErrorCode(error) !== "ENOENT") throw error;
  }
}

/**
 * 把物理路径里、记忆根父目录之上的平台别名换回登记拼写。
 *
 * <p>登记路径是 {@code /data/user/0/.../memory}，realpath 得到 {@code /data/data/.../memory}。
 * 两者从记忆根的父目录开始逐级同名，只是更上层的祖先被换成了别名。父目录之内出现的链接
 * （记忆根自己、root-alias、内部 junction）名字对不上，不能剥，仍由 samePath 拒绝。
 * 不在这里跟随链接：HyperOS 上 stat {@code /data/data/<pkg>} 会 EACCES，只按路径分量换回登记拼写。
 */
function stripPlatformAlias(path: string, physical: string, roots: readonly MemoryRoot[]): string {
  for (const root of roots) {
    const owner = dirname(root.rootDir);
    if (!contained(owner, path) && path !== owner) continue;
    let lexical = path;
    let resolved = physical;
    while (lexical !== owner && dirname(lexical) !== lexical && dirname(resolved) !== resolved) {
      lexical = dirname(lexical);
      resolved = dirname(resolved);
    }
    if (lexical !== owner) continue;
    while (dirname(lexical) !== lexical && dirname(resolved) !== resolved) {
      const nextLexical = dirname(lexical);
      const nextResolved = dirname(resolved);
      if (relative(nextLexical, lexical) !== relative(nextResolved, resolved)) break;
      lexical = nextLexical;
      resolved = nextResolved;
    }
    if (dirname(lexical) === lexical || dirname(resolved) === resolved) continue;
    const above = dirname(resolved);
    if (above === resolved || contained(owner, dirname(lexical)) || dirname(lexical) === owner) continue;
    return join(lexical, relative(resolved, physical));
  }
  return physical;
}

async function existingRealPath(path: string): Promise<string> {
  let cursor = path;
  const suffix: string[] = [];
  for (;;) {
    try {
      return join(await realpath(cursor), ...suffix);
    } catch (error) {
      if (getNodeErrorCode(error) !== "ENOENT" && getNodeErrorCode(error) !== "ENOTDIR")
        throw error;
      const parent = dirname(cursor);
      if (parent === cursor) throw error;
      suffix.unshift(relative(parent, cursor));
      cursor = parent;
    }
  }
}


export class MemoryRootRegistry {
  readonly roots = new Map<string, MemoryRoot>();

  get(rawRoot: string): MemoryRoot {
    const path = localAbsolutePath(rawRoot);
    const root = [...this.roots.values()].find((entry) => samePath(entry.rootDir, path));
    if (!root)
      throw memoryError(
        "unsupported",
        path,
        "Project Memory root has not been explicitly registered",
      );
    return root;
  }

  async route(rawPath: string): Promise<MemoryRoot | undefined> {
    if (this.roots.size === 0) return undefined;
    // Windows 的设备命名空间能别名到已登记根；不能让它落回普通 writer。
    if (/^[\\/]{2}[?.][\\/]/u.test(rawPath))
      throw memoryError(
        "invalid_path",
        rawPath,
        "Managed filesystem mutations reject device namespace aliases",
      );
    // 登记根只可能是本地路径；根外普通 UNC 交回既有 FS/权限，避免拦截共享盘且不触网探测。
    if (/^[\\/]{2}[^\\/]+[\\/][^\\/]+/u.test(rawPath)) return undefined;
    const path = normalize(rawPath);
    const roots = [...this.roots.values()];
    // 先匹配未经折叠的前缀，禁止 root/../other.md 绕过注册边界。
    const lexical = rawPath.replaceAll("\\", "/");
    const normalizeCase = (value: string) =>
      process.platform === "win32" ? value.toLowerCase() : value;
    let matched: MemoryRoot | undefined;
    for (const root of roots) {
      const rootPrefix = normalizeCase(root.rootDir.replaceAll("\\", "/"));
      const statePrefix = normalizeCase(root.stateDir.replaceAll("\\", "/"));
      const candidate = normalizeCase(lexical);
      if (
        candidate === rootPrefix ||
        candidate.startsWith(`${rootPrefix}/`) ||
        candidate === statePrefix ||
        candidate.startsWith(`${statePrefix}/`)
      )
        localAbsolutePath(rawPath);
      if (contained(root.stateDir, path))
        throw memoryError(
          "invalid_path",
          path,
          "Project Memory control storage cannot be mutated through ordinary file operations",
        );
      if (contained(root.rootDir, path)) {
        localAbsolutePath(rawPath);
        matched = root;
      }
    }
    if (!isAbsolute(path)) return matched;
    if (matched) {
      await assertMemoryRoot(matched);
      const fileName = relative(matched.rootDir, path);
      if (/\.md$/iu.test(fileName)) memoryRelativePath(matched, path);
      else if (fileName.split(/[\\/]/u).some((part) => /[. ]$/u.test(part) || part.includes(":"))) {
        throw memoryError("invalid_path", path, "Project Memory path aliases are not writable");
      }
    }
    // 物理路径只用来发现“链接落进了已登记根”。比较前先剥掉记忆根之上的平台别名：
    // /data/user/0 与 /data/data 指向同一目录，不能把这条祖先链接当成写入逃逸。
    // 记忆根自身或其内部的链接不在前缀里，剥不掉，仍然拒绝。
    const physical = stripPlatformAlias(path, await existingRealPath(path), roots);
    if (roots.some((root) => contained(root.stateDir, physical))) {
      throw memoryError(
        "invalid_path",
        path,
        "Project Memory control storage aliases are not writable",
      );
    }
    if (
      !samePath(path, physical) &&
      (matched || roots.some((root) => contained(root.rootDir, physical)))
    ) {
      throw memoryError(
        "invalid_path",
        path,
        "Project Memory aliases and symlink paths are not writable",
      );
    }
    return matched;
  }
}
