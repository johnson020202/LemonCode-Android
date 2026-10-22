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

test("build workflow keeps verification gates and does not publish releases", async () => {
  const workflow = await readFile(resolve(root, ".github/workflows/android-build.yml"), "utf8");
  for (const command of [
    "pnpm typecheck",
    "pnpm lint",
    "testDebugUnitTest lintDebug assembleDebug",
    "check-parity.mjs",
    "build-standalone.mjs",
    "verify-apk.mjs",
  ]) {
    assert.ok(workflow.includes(command), `Missing gate: ${command}`);
  }
  assert.doesNotMatch(workflow, /continue-on-error|pull_request_target/u);
});

test("release workflow signs with repository secrets and never falls back to a debug key", async () => {
  const workflow = await readFile(resolve(root, ".github/workflows/android-release.yml"), "utf8");
  assert.ok(workflow.includes("secrets.ANDROID_KEYSTORE_BASE64"));
  assert.ok(workflow.includes("assembleRelease"));
  assert.match(workflow, /CN=Android Debug/u); // 断言显式拒绝 debug 证书
  assert.ok(workflow.includes("gh release create"));
  assert.doesNotMatch(workflow, /keytool -genkeypair/u);
});

test("standalone WebView keeps security boundaries", async () => {
  const activity = await readFile(
    resolve(root, "apps/android/app/src/main/java/vin/lemon/android/MainActivity.java"),
    "utf8",
  );
  // 只访问本机运行时 origin；无原生 JS 桥、无 TLS 忽略、不授予网页媒体权限。
  assert.match(activity, /UrlPolicy\.localNavigation\(origin, url\)/u);
  assert.ok(activity.includes("handler.cancel();"));
  assert.ok(activity.includes("MIXED_CONTENT_NEVER_ALLOW"));
  assert.ok(activity.includes("request.deny();"));
  assert.doesNotMatch(activity, /\.addJavascriptInterface\(/u);
  assert.doesNotMatch(activity, /onReceivedSslError[\s\S]{0,200}proceed\(\)/u);
});

test("runtime is launched in-app and never falls back to a remote host", async () => {
  const runtime = await readFile(
    resolve(root, "apps/android/app/src/main/java/vin/lemon/android/AndroidRuntime.java"),
    "utf8",
  );
  assert.ok(runtime.includes("127.0.0.1"));
  assert.ok(runtime.includes("LCODE_AGENT_SERVER_COMMAND"));
  assert.ok(runtime.includes("nativeLibraryDir"));
  // 独立运行版本不应再出现桌面配对深链。
  assert.doesNotMatch(runtime, /\/p\/room/iu);
});
