import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtemp, mkdir, readFile, writeFile, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { stageAssets } from "./build-web.mjs";
import { compareFingerprints, watchedPaths } from "./check-parity.mjs";

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "lemon-android-test-"));
  await mkdir(join(root, "packages/web/dist/assets"), { recursive: true });
  await writeFile(join(root, "packages/web/dist/index.html"), "<html>real test fixture</html>");
  await writeFile(join(root, "packages/web/dist/assets/app.js"), "export const shared = true;");
  await writeFile(join(root, "packages/web/dist/assets/app.js.map"), "private source");
  for (const name of ["LICENSE", "NOTICE.md", "THIRD-PARTY-NOTICES.md"])
    await writeFile(join(root, name), name);
  return root;
}

test("packages exact shared assets without maps and includes truthful metadata/legal", async () => {
  const root = await fixture();
  try {
    const target = await stageAssets(root, { upstreamSha: "a".repeat(40) });
    assert.match(await readFile(join(target, "assets/app.js"), "utf8"), /shared = true/u);
    await assert.rejects(readFile(join(target, "assets/app.js.map")), { code: "ENOENT" });
    assert.equal(await readFile(join(target, "legal/LICENSE"), "utf8"), "LICENSE");
    const metadata = JSON.parse(await readFile(join(target, "../android-build.json"), "utf8"));
    assert.equal(metadata.fullFeatureParity, false);
    assert.equal(metadata.mode, "remote-host-preview");
    await writeFile(join(target, "stale.js"), "old");
    await stageAssets(root, {});
    await assert.rejects(readFile(join(target, "stale.js")), { code: "ENOENT" });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("never replaces unmarked user assets", async () => {
  const root = await fixture();
  const target = join(root, "apps/android/app/src/main/assets/www");
  try {
    await mkdir(target, { recursive: true });
    await writeFile(join(target, "personal.txt"), "keep");
    await assert.rejects(stageAssets(root, {}), /unmarked/u);
    assert.equal(await readFile(join(target, "personal.txt"), "utf8"), "keep");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects symlinked build assets", async () => {
  const root = await fixture();
  try {
    await symlink(join(root, "LICENSE"), join(root, "packages/web/dist/leak"));
    await assert.rejects(stageAssets(root, {}), /Symlink/u);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("contract guard detects changes and missing paths", () => {
  const files = Object.fromEntries(watchedPaths.map((path) => [path, "same"]));
  assert.deepEqual(compareFingerprints(files, files), []);
  assert.deepEqual(compareFingerprints(files, { ...files, [watchedPaths[0]]: "changed" }), [
    watchedPaths[0],
  ]);
  assert.equal(compareFingerprints({}, files).length, watchedPaths.length);
});
