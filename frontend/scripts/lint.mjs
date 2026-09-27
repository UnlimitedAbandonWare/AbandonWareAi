import { readdir, readFile } from "node:fs/promises";
import { dirname, extname, join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const checkable = [];

async function walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name === ".next") continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      await walk(full);
      continue;
    }
    if (extname(entry.name) === ".mjs" || entry.name.endsWith(".test.mjs")) {
      checkable.push(full);
    }
    if (entry.name.endsWith(".js")) {
      const source = await readFile(full, "utf8");
      if (!source.includes("<")) checkable.push(full);
    }
  }
}

await walk(join(root, "src"));
await walk(join(root, "scripts"));
await walk(join(root, "test"));

for (const file of checkable) {
  const result = spawnSync(process.execPath, ["--check", file], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

const packageJson = await readFile(join(root, "package.json"), "utf8");
if (/NEXT_PUBLIC_.*(?:KEY|TOKEN|SECRET)/i.test(packageJson)) {
  console.error("public secret-like env name found in package.json");
  process.exit(1);
}
