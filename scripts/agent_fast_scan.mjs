// agent_fast_scan.mjs — Node.js v24 streaming scanner for large agent session logs.
// Memory-bounded readline stream over fs.createReadStream; summarizes P11/P7 and
// generic Exception/Error hits. Standalone: node scripts/agent_fast_scan.mjs --file <log>
import fs from "node:fs";
import readline from "node:readline";
import { parseArgs } from "node:util";

const PATTERNS = [
  { id: "p11-cannot-find-path", re: /Cannot find path/i },
  { id: "p11-command-failed", re: /Command failed/i },
  { id: "p7-goal-conflict", re: /goal-conflict/i },
  { id: "exception", re: /\w*Exception\b/ },
  { id: "error", re: /\w*Error\b/ },
];

const USAGE = `agent_fast_scan.mjs — streaming error/warning summary for large logs
  node scripts/agent_fast_scan.mjs --file <path> [--limit 10] [--json]
Options:
  --file, -f   log/JSONL file to scan (required, or first positional arg)
  --limit      max sample lines kept (default 10)
  --json       JSON output (auto-friendly for AWX_AGENT/CI callers)
  --help, -h   this help`;

function main() {
  let values, positionals;
  try {
    ({ values, positionals } = parseArgs({
      options: {
        file: { type: "string", short: "f" },
        limit: { type: "string", default: "10" },
        json: { type: "boolean", default: false },
        help: { type: "boolean", short: "h", default: false },
      },
      allowPositionals: true,
    }));
  } catch (err) {
    console.error(`arg-error: ${err.message}`);
    console.error(USAGE);
    process.exit(2);
  }

  if (values.help) {
    console.log(USAGE);
    process.exit(0);
  }

  const file = values.file || positionals[0];
  if (!file) {
    console.error("missing --file <path>");
    console.error(USAGE);
    process.exit(2);
  }
  const limit = Math.max(1, Number.parseInt(values.limit, 10) || 10);
  const asJson = values.json || Boolean(process.env.AWX_AGENT || process.env.CI);

  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    console.error(`file-not-found: ${file}`);
    process.exit(3);
  }

  return { file, limit, asJson };
}

async function scan({ file, limit }) {
  const counts = {};
  const topMatches = [];
  let totalLines = 0;
  let matchedLines = 0;
  const started = Date.now();

  const rl = readline.createInterface({
    input: fs.createReadStream(file, { encoding: "utf8" }),
    crlfDelay: Infinity,
  });

  for await (const line of rl) {
    totalLines += 1;
    const hits = PATTERNS.filter((p) => p.re.test(line));
    if (hits.length === 0) continue;
    matchedLines += 1;
    for (const h of hits) counts[h.id] = (counts[h.id] || 0) + 1;
    if (topMatches.length < limit) {
      topMatches.push({ line: totalLines, pattern: hits[0].id, text: line.trim().slice(0, 240) });
    }
  }

  return {
    engine: "node-stream",
    file,
    totalLines,
    matchedLines,
    elapsedMs: Date.now() - started,
    counts,
    topMatches,
  };
}

const opts = main();
if (opts) {
  try {
    const result = await scan(opts);
    if (opts.asJson) {
      console.log(JSON.stringify(result));
    } else {
      console.log(`scan-log [${result.engine}] ${result.file}`);
      console.log(`lines=${result.totalLines} matched=${result.matchedLines} elapsed=${result.elapsedMs}ms`);
      for (const [k, v] of Object.entries(result.counts)) console.log(`  ${k}: ${v}`);
      for (const s of result.topMatches) console.log(`  L${s.line} [${s.pattern}] ${s.text}`);
    }
  } catch (err) {
    console.error(`scan-failed: ${err.message}`);
    process.exit(3);
  }
}
