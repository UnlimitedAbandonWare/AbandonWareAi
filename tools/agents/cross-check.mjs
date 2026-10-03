#!/usr/bin/env node
// tools/agents/cross-check.mjs
// 3-way cross-verification: positive -> negative -> judge (all --mode plan).
// Evidence input: git status/diff scoped to <path> + untracked-file content
// (bounded) + optional recent test log. Judge is forced to a JSON schema;
// anything without evidence is marked NOT_RUN / evidence_needed.
// Usage:
//   node cross-check.mjs <scope-path> [--cli agy] [--model m] [--test-log f]
// Output: data/agent-handoff/agent-runs/<ts>-crosscheck-<cli>/verdict.json
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const RUNNER = path.join(HERE, 'run-agent.mjs');
const PROMPT_DIR = path.join(ROOT, '.agents', 'prompts');

const MAX_FILE_BYTES = 8192;      // per untracked file
const MAX_EVIDENCE_CHARS = 24000; // keep prompts under argv limits
const JUDGE_SCHEMA = JSON.stringify({
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['PASS', 'PARTIAL', 'FAIL'] },
    claims: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          text: { type: 'string' },
          evidence: { type: 'string' },
          status: { type: 'string', enum: ['verified', 'evidence_needed', 'NOT_RUN', 'contradicted'] },
        },
        required: ['text', 'status'],
      },
    },
    risks: { type: 'array', items: { type: 'string' } },
    next: { type: 'array', items: { type: 'string' } },
  },
  required: ['verdict', 'claims', 'risks', 'next'],
});

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

function git(args) {
  const r = spawnSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  return r.status === 0 ? (r.stdout || '') : `[git ${args.join(' ')} failed: ${r.status}]`;
}

function buildEvidence(scope, testLog) {
  const rel = scope ? scope.replace(/\\/g, '/') : '.';
  let ev = '';
  ev += `## git status --porcelain -- ${rel}\n\`\`\`\n${git(['status', '--porcelain', '--', rel])}\`\`\`\n`;
  ev += `\n## git diff --stat -- ${rel}\n\`\`\`\n${git(['diff', '--stat', '--', rel])}\`\`\`\n`;
  ev += `\n## git diff -- ${rel}\n\`\`\`diff\n${git(['diff', '--', rel])}\`\`\`\n`;
  const status = git(['status', '--porcelain', '--', rel]);
  const untracked = status.split(/\r?\n/)
    .map((l) => (/^\?\?\s+(.+)$/.exec(l) || [])[1])
    .filter(Boolean);
  for (const f of untracked) {
    const abs = path.join(ROOT, f);
    if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) continue;
    let body = fs.readFileSync(abs, 'utf8');
    if (body.length > MAX_FILE_BYTES) body = body.slice(0, MAX_FILE_BYTES) + `\n... [truncated ${body.length - MAX_FILE_BYTES} chars]`;
    ev += `\n## NEW FILE (untracked) ${f}\n\`\`\`\n${body}\n\`\`\`\n`;
    if (ev.length > MAX_EVIDENCE_CHARS) break;
  }
  if (testLog && fs.existsSync(testLog)) {
    let body = fs.readFileSync(testLog, 'utf8');
    if (body.length > MAX_FILE_BYTES) body = body.slice(-MAX_FILE_BYTES);
    ev += `\n## recent test log ${testLog}\n\`\`\`\n${body}\n\`\`\`\n`;
  }
  if (ev.length > MAX_EVIDENCE_CHARS) {
    ev = ev.slice(0, MAX_EVIDENCE_CHARS) + `\n... [evidence truncated at ${MAX_EVIDENCE_CHARS} chars]`;
  }
  return ev;
}

function loadRolePrompt(name) {
  const p = path.join(PROMPT_DIR, `${name}.md`);
  if (!fs.existsSync(p)) return null;
  return fs.readFileSync(p, 'utf8');
}

// Layered verdict extraction from a headless run's stdout payload.
function extractVerdict(stdoutFile) {
  if (!stdoutFile || !fs.existsSync(stdoutFile)) return { verdict: 'NOT_RUN', reason: 'no stdout file' };
  const raw = fs.readFileSync(stdoutFile, 'utf8');
  const tryJson = (s) => { try { return JSON.parse(s); } catch { return null; } };
  let obj = tryJson(raw);
  if (obj && typeof obj === 'object') {
    if (obj.verdict) return obj;
    for (const k of ['response', 'result', 'message', 'text', 'output', 'content']) {
      const inner = typeof obj[k] === 'string' ? tryJson(obj[k]) : null;
      if (inner && inner.verdict) return inner;
      if (typeof obj[k] === 'object' && obj[k] && obj[k].verdict) return obj[k];
    }
    // plan mode denied a tool call and the model returned empty text:
    // SUCCESS envelope with response:"" + denied_actions[] -> NOT_RUN
    const denied = Array.isArray(obj.denied_actions) ? obj.denied_actions : [];
    const responseText = [obj.response, obj.result, obj.message, obj.text]
      .find((x) => typeof x === 'string' && x.trim());
    if (denied.length && !responseText) {
      return {
        verdict: 'NOT_RUN', status: 'evidence_needed',
        reason: 'agent attempted denied tool actions under read-only mode and returned an empty response',
        denied_actions: denied, raw: stdoutFile,
      };
    }
  }
  const m = raw.match(/\{[^{}]*"verdict"[\s\S]*\}/);
  if (m) {
    const parsed = tryJson(m[0]);
    if (parsed && parsed.verdict) return parsed;
  }
  return { verdict: 'UNPARSEABLE', status: 'evidence_needed', raw: stdoutFile };
}

function runRole(cli, role, promptText, runBase, extra) {
  const pfile = path.join(runBase, `${role}.prompt.md`);
  fs.writeFileSync(pfile, promptText);
  const args = [RUNNER, '--cli', cli, '--role', role, '--prompt-file', pfile, ...extra];
  const r = spawnSync(process.execPath, args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  process.stdout.write(r.stdout || '');
  if (r.stderr) process.stderr.write(r.stderr);
  const m = /runDir=(.+)\r?$/m.exec(r.stdout || '');
  const runDir = m ? m[1].trim() : null;
  let stdoutFile = null;
  if (runDir) {
    for (const name of ['stdout.json', 'stdout.txt']) {
      const c = path.join(runDir, name);
      if (fs.existsSync(c)) stdoutFile = c;
    }
    try {
      fs.mkdirSync(path.join(runBase, role), { recursive: true });
      for (const f of fs.readdirSync(runDir)) {
        fs.copyFileSync(path.join(runDir, f), path.join(runBase, role, f));
      }
    } catch { /* copy failure is non-fatal; originals remain */ }
  }
  return { exit: r.status, runDir, stdoutFile };
}

function main() {
  const { values: v, positionals } = parseArgs({
    options: {
      cli: { type: 'string', default: 'agy' }, model: { type: 'string' },
      'test-log': { type: 'string' }, reextract: { type: 'string' }, help: { type: 'boolean' },
    },
    allowPositionals: true, strict: true,
  });
  // offline re-extraction: rewrite verdict.json of an existing crosscheck dir
  // without any live call (used when the extractor is improved post-run).
  if (v.reextract) {
    const dir = path.resolve(v.reextract);
    const stdoutFile = ['stdout.json', 'stdout.txt']
      .map((n) => path.join(dir, 'judge', n)).find(fs.existsSync);
    const priorFile = path.join(dir, 'verdict.json');
    const prior = fs.existsSync(priorFile)
      ? JSON.parse(fs.readFileSync(priorFile, 'utf8')) : {};
    const verdict = extractVerdict(stdoutFile);
    fs.writeFileSync(priorFile, JSON.stringify({ ...prior, verdict }, null, 2));
    console.log(`[cross-check] reextract VERDICT=${verdict.verdict} -> ${priorFile}`);
    process.exit(0);
  }
  const scope = positionals[0];
  if (v.help || !scope) {
    console.log('usage: node cross-check.mjs <scope-path> [--cli agy] [--model m] [--test-log file] [--reextract <dir>]');
    process.exit(v.help ? 0 : 2);
  }
  for (const name of ['positive', 'negative', 'judge']) {
    if (!loadRolePrompt(name)) {
      console.error(`[cross-check] missing prompt .agents/prompts/${name}.md`);
      process.exit(3);
    }
  }
  const cli = v.cli.toLowerCase();
  if (cli === 'gemini') {
    console.error('[cross-check] gemini CLI removed 2026-09-30 — use --cli agy');
    process.exit(3);
  }
  const runBase = path.join(ROOT, 'data', 'agent-handoff', 'agent-runs', `${stamp()}-crosscheck-${cli}`);
  fs.mkdirSync(runBase, { recursive: true });
  const evidence = buildEvidence(scope, v['test-log'] ? path.resolve(v['test-log']) : null);
  fs.writeFileSync(path.join(runBase, 'evidence.md'), evidence);

  const extra = v.model ? ['--model', v.model] : [];
  const posText = `${loadRolePrompt('positive')}\n\n# Evidence (scope: ${scope})\n${evidence}`;
  console.log('[cross-check] 1/3 positive ...');
  const pos = runRole(cli, 'positive', posText, runBase, extra);

  const posBody = pos.stdoutFile ? fs.readFileSync(pos.stdoutFile, 'utf8') : '(no output)';
  const negText = `${loadRolePrompt('negative')}\n\n# Evidence (scope: ${scope})\n${evidence}\n\n# Positive review output (verify independently, do not echo)\n${posBody}`;
  console.log('[cross-check] 2/3 negative ...');
  const neg = runRole(cli, 'negative', negText, runBase, extra);

  const negBody = neg.stdoutFile ? fs.readFileSync(neg.stdoutFile, 'utf8') : '(no output)';
  const judgeText = `${loadRolePrompt('judge')}\n\n# Evidence (scope: ${scope})\n${evidence}\n\n# Positive output\n${posBody}\n\n# Negative output\n${negBody}`;
  console.log('[cross-check] 3/3 judge ...');
  const judge = runRole(cli, 'judge', judgeText, runBase, [...extra, '--schema', JUDGE_SCHEMA]);

  const verdict = extractVerdict(judge.stdoutFile);
  fs.writeFileSync(path.join(runBase, 'verdict.json'), JSON.stringify({
    scope, cli, model: v.model || null, finishedAt: new Date().toISOString(),
    exits: { positive: pos.exit, negative: neg.exit, judge: judge.exit },
    runs: { positive: pos.runDir, negative: neg.runDir, judge: judge.runDir },
    verdict,
  }, null, 2));
  console.log(`[cross-check] VERDICT=${verdict.verdict || 'UNPARSEABLE'}`);
  console.log(`[cross-check] dir=${runBase}`);
  process.exit(0);
}

export { extractVerdict };
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
