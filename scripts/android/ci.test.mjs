import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../..");

test("upstream sync builds the merged SHA without relying on token-triggered push", async () => {
  const workflow = await readFile(
    resolve(root, ".github/workflows/android-upstream-sync.yml"),
    "utf8",
  );
  assert.match(workflow, /uses: \.\/\.github\/workflows\/android-build\.yml/u);
  assert.match(workflow, /source_ref: \$\{\{ needs\.sync\.outputs\.source_ref \}\}/u);
  assert.match(workflow, /git merge --abort/u);
  assert.doesNotMatch(workflow, /git push[^\n]*(?:--force| -f\b)/u);
});

test("preview workflow keeps verification gates and does not publish releases", async () => {
  const workflow = await readFile(resolve(root, ".github/workflows/android-build.yml"), "utf8");
  for (const command of [
    "pnpm typecheck",
    "pnpm lint",
    "testDebugUnitTest lintDebug assembleDebug",
    "check-parity.mjs",
  ]) {
    assert.ok(workflow.includes(command), `Missing gate: ${command}`);
  }
  assert.doesNotMatch(workflow, /continue-on-error|gh release|pull_request_target/u);
});

test("encoded API requests bypass only local-asset path validation", async () => {
  const activity = await readFile(
    resolve(root, "apps/android/app/src/main/java/vin/lemon/android/MainActivity.java"),
    "utf8",
  );
  const origin = activity.indexOf("if (!selectedHost) return null;");
  const api = activity.indexOf(
    "if (UrlPolicy.isServerPath(request.getUrl().getPath())) return null;",
  );
  const assets = activity.indexOf("if (!UrlPolicy.isSelectedOrigin(selected, url))");
  assert.ok(origin > 0 && origin < api && api < assets);
  assert.ok(activity.includes("handler.cancel();"));
  assert.ok(activity.includes("MIXED_CONTENT_NEVER_ALLOW"));
  assert.doesNotMatch(activity, /\.addJavascriptInterface\(/u);
});
