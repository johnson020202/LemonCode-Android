import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const exec = promisify(execFile);
export const watchedPaths = [
  "packages/shared/src/platform.ts",
  "packages/web/src/main.tsx",
  "packages/web/src/remote/pairingDeepLink.ts",
  "packages/web/src/remote/pairingSockets.ts",
  "packages/web/src/saveWebFile.ts",
  "packages/client/src/remoteServiceAccess.ts",
];
const root = resolve(import.meta.dirname, "../..");
const baselinePath = "scripts/android/parity-baseline.json";

export async function fingerprints(repo) {
  return Object.fromEntries(
    await Promise.all(
      watchedPaths.map(async (path) => [
        path,
        createHash("sha256")
          .update(await readFile(join(repo, path)))
          .digest("hex"),
      ]),
    ),
  );
}

export function compareFingerprints(expected, actual) {
  return watchedPaths.filter((path) => !expected[path] || expected[path] !== actual[path]);
}

async function git(repo, args) {
  return (await exec("git", ["-C", repo, ...args])).stdout.trim();
}

export async function checkParity(repo = root) {
  const baseline = JSON.parse(await readFile(join(repo, baselinePath), "utf8"));
  if (baseline.schemaVersion !== 1 || baseline.fullFeatureParity !== false)
    throw new Error("Invalid Android capability baseline");
  const changed = compareFingerprints(baseline.files, await fingerprints(repo));
  if (changed.length)
    throw new Error(
      `Android platform contracts changed; human review required:\n${changed.join("\n")}`,
    );
  const currentSha = await git(repo, ["rev-parse", "HEAD"]);
  const upstreamSha = await git(repo, [
    "merge-base",
    "HEAD",
    "refs/remotes/android-upstream/main",
  ]).catch(() => baseline.reviewedUpstreamSha);
  if (!/^[a-f0-9]{40}$/u.test(upstreamSha)) throw new Error("Missing upstream commit identity");
  const sourceDirty =
    (await git(repo, ["status", "--porcelain", "--untracked-files=normal"])) !== "";
  return { currentSha, upstreamSha, sourceDirty };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.slice(2).join(" ") === "--record-reviewed-baseline") {
      await writeFile(
        join(root, baselinePath),
        JSON.stringify(
          {
            schemaVersion: 1,
            reviewedUpstreamSha: await git(root, ["rev-parse", "HEAD"]),
            fullFeatureParity: false,
            meaning: "Contract drift guard only; not proof of feature parity or device acceptance.",
            files: await fingerprints(root),
          },
          null,
          2,
        ) + "\n",
      );
      console.info(
        "Recorded explicitly reviewed Android contract baseline; feature parity remains false.",
      );
    } else {
      if (process.argv.length !== 2) throw new Error("Unknown parity-check argument");
      console.info(JSON.stringify(await checkParity(), null, 2));
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
