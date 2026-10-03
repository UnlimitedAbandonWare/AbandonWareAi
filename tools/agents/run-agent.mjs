#!/usr/bin/env node
// tools/agents/run-agent.mjs
// Common headless runner for CLI agents (agy / grok / codex).
// - Plain Node, zero external deps.
// - Prompt always comes from a file (avoids CMD quoting issues).
// - Default mode is read-only "plan"; accept-edits only when explicitly passed.
// - --dangerously-skip-permissions / bypass flags are NEVER attached unless
//   AWX_AGY_YOLO=1 (a warning line is written to stderr.log when they are).
// - No secret values are ever logged: only env var NAMES (set/unset) are recorded.
// Usage:
//   node run-agent.mjs --cli agy --role review --prompt-file prompt.md
//      [--model <id>] [--effort low|medium|high|max] [--mode plan|accept-edits]
//      [--conversation <id>] [--continue]
//      [--timeout 5m] [--schema <json-or-path>] [--output-format text|json]
// Output dir: data/agent-handoff/agent-runs/<yyyymmdd-hhmmss>-<cli>-<role>/
//   (override with AWX_AGENT_RUNS_DIR)
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');

// env var names whose *set-ness* (never values) may be recorded
const ENV_WATCH = [
  'AGY_EXE', 'GROK_EXE', 'CODEX_EXE', 'AWX_AGY_YOLO', 'AWX_AGENT_RUNS_DIR',
  'GEMINI_API_KEY', 'OPENAI_API_KEY', 'AGY_CLI_INTERACTIVE_HEADLESS',
];

const CLI_DEFS = {
  agy: {
    envExe: 'AGY_EXE',
    fallbacks: [process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'agy', 'bin', 'agy.exe')],
    timeoutFlag: (t) => ['--print-timeout', t],
    buildArgs({ prompt, promptFile, mode, model, effort, schema, outputFormat, yolo, conversation, resume }) {
      const a = ['-p', prompt, '--output-format', outputFormat || 'json', '--mode', mode === 'accept-edits' ? 'accept-edits' : 'plan'];
      if (model) a.push('--model', model);
      if (effort) a.push('--effort', effort);
      if (schema) a.push('--json-schema', schema.inline || schema.file);
      if (conversation) a.push('--conversation', conversation);
      if (resume) a.push('--continue');
      if (yolo) a.push('--dangerously-skip-permissions');
      return a;
    },
  },
  grok: {
    envExe: 'GROK_EXE',
    fallbacks: [process.env.USERPROFILE && path.join(process.env.USERPROFILE, '.grok', 'bin', 'grok.exe')],
    timeoutFlag: () => [],
    buildArgs({ promptFile, mode, model, effort, schema, outputFormat, yolo }) {
      const a = ['--prompt-file', promptFile, '--output-format', outputFormat || 'json',
        '--permission-mode', mode === 'accept-edits' ? 'acceptEdits' : 'plan', '--cwd', ROOT];
      if (model) a.push('--model', model);
      if (effort) a.push('--effort', effort);
      if (schema) a.push('--json-schema', schema.inline || schema.file);
      if (yolo) a.push('--permission-mode', 'bypassPermissions');
      return a;
    },
  },
  codex: {
    envExe: 'CODEX_EXE',
    fallbacks: [process.env.APPDATA && path.join(process.env.APPDATA, 'npm', 'codex.cmd')],
    timeoutFlag: () => [],
    viaStdin: true,
    buildArgs({ mode, model, schema, yolo }) {
      const a = ['exec', '-s', mode === 'accept-edits' ? 'workspace-write' : 'read-only', '-C', ROOT, '--json'];
      if (model) a.push('-m', model);
      if (schema) a.push('--output-schema', schema.file); // codex only accepts a schema FILE
      if (yolo) a.push('--dangerously-bypass-approvals-and-sandbox');
      return a;
    },
  },
};

function fail(msg, code = 2) {
  console.error(`[run-agent] ${msg}`);
  process.exit(code);
}

function whichSync(name) {
  const cmd = process.platform === 'win32' ? 'where' : 'command';
  const args = process.platform === 'win32' ? [name] : ['-v', name];
  const r = spawnSync(cmd, args, { encoding: 'utf8', shell: process.platform !== 'win32' });
  if (r.status === 0 && r.stdout) return r.stdout.split(/\r?\n/)[0].trim();
  return null;
}

function resolveCli(cli) {
  const def = CLI_DEFS[cli];
  if (!def) return null;
  const envPath = process.env[def.envExe];
  if (envPath && fs.existsSync(envPath)) return envPath;
  const onPath = whichSync(cli);
  if (onPath && fs.existsSync(onPath)) return onPath;
  for (const fb of def.fallbacks) if (fb && fs.existsSync(fb)) return fb;
  return envPath || null; // defined-but-missing env path wins for a clearer error
}

// Turns a resolved executable into a spawnable [cmd, prefixArgs].
// .mjs/.js/.cjs run under node (test stubs); .cmd/.bat need comspec.
function spawnTarget(exePath) {
  const ext = path.extname(exePath).toLowerCase();
  if (['.mjs', '.js', '.cjs'].includes(ext)) return { cmd: process.execPath, pre: [exePath] };
  if (['.cmd', '.bat'].includes(ext)) {
    const comspec = process.env.ComSpec || 'cmd.exe';
    return { cmd: comspec, pre: ['/d', '/s', '/c', exePath] };
  }
  return { cmd: exePath, pre: [] };
}

function parseTimeoutMs(text) {
  const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)?$/.exec(String(text || '5m').trim());
  if (!m) fail(`bad --timeout "${text}" (use 30s|5m|1h)`);
  const n = parseFloat(m[1]);
  const unit = m[2] || 's';
  return Math.round(n * { ms: 1, s: 1000, m: 60000, h: 3600000 }[unit]);
}

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

function main() {
  const { values: v } = parseArgs({
    options: {
      cli: { type: 'string' }, role: { type: 'string' }, 'prompt-file': { type: 'string' },
      model: { type: 'string' }, effort: { type: 'string' }, mode: { type: 'string', default: 'plan' },
      timeout: { type: 'string', default: '5m' }, schema: { type: 'string' },
      conversation: { type: 'string' }, continue: { type: 'boolean' },
      'output-format': { type: 'string' }, help: { type: 'boolean' },
    },
    strict: true,
  });
  if (v.help || !v.cli || !v.role || !v['prompt-file']) {
    console.log('usage: node run-agent.mjs --cli agy|grok|codex --role <name> --prompt-file <md> [--model m] [--effort l|m|h|max] [--mode plan|accept-edits] [--conversation <id>] [--continue] [--timeout 5m] [--schema <json|file>] [--output-format text|json]');
    process.exit(v.help ? 0 : 2);
  }
  const cli = v.cli.toLowerCase();
  if (cli === 'gemini') fail('gemini CLI removed 2026-09-30 — use "agy" (Start-Agy-CLI.bat)', 3);
  const def = CLI_DEFS[cli];
  if (!def) fail(`unknown --cli "${cli}" (known: ${Object.keys(CLI_DEFS).join(', ')})`, 3);
  const mode = v.mode === 'accept-edits' ? 'accept-edits' : 'plan';
  if (v.mode !== 'plan' && v.mode !== 'accept-edits') fail(`bad --mode "${v.mode}"`);
  const EFFORTS = ['low', 'medium', 'high', 'max'];
  if (v.effort && !EFFORTS.includes(v.effort)) fail(`bad --effort "${v.effort}" (${EFFORTS.join('|')})`);
  // agy is the amplifier lane (user decision 2026-09-30): default 'high' so
  // hypothesis bursts/directive design never run shallow; explicit --effort wins.
  const effort = v.effort || (cli === 'agy' ? 'high' : null);
  const promptFile = path.resolve(v['prompt-file']);
  if (!fs.existsSync(promptFile)) fail(`prompt file not found: ${promptFile}`);
  const prompt = fs.readFileSync(promptFile, 'utf8');
  const exe = resolveCli(cli);
  if (!exe) fail(`${cli} executable not found (env ${def.envExe}, PATH, or known install path)`, 3);

  const yolo = process.env.AWX_AGY_YOLO === '1';
  const timeoutMs = parseTimeoutMs(v.timeout);
  const schema = v.schema
    ? (fs.existsSync(v.schema) ? { file: path.resolve(v.schema) } : { inline: v.schema })
    : null;
  // codex only accepts a schema file path; materialize inline schemas
  const schemaForCli = schema && cli === 'codex' && schema.inline
    ? { file: null, _inline: schema.inline } // file path filled after runDir exists
    : schema;

  const runsBase = process.env.AWX_AGENT_RUNS_DIR
    ? path.resolve(process.env.AWX_AGENT_RUNS_DIR)
    : path.join(ROOT, 'data', 'agent-handoff', 'agent-runs');
  const safeRole = v.role.replace(/[^A-Za-z0-9_.-]/g, '-');
  const runDir = path.join(runsBase, `${stamp()}-${cli}-${safeRole}`);
  fs.mkdirSync(runDir, { recursive: true });
  fs.writeFileSync(path.join(runDir, 'prompt.md'), prompt);
  if (schemaForCli && schemaForCli._inline) {
    schemaForCli.file = path.join(runDir, 'schema.json');
    fs.writeFileSync(schemaForCli.file, schemaForCli._inline);
    delete schemaForCli._inline;
  }

  const tgt = spawnTarget(exe);
  const versionProbe = spawnSync(tgt.cmd, [...tgt.pre, '--version'], { encoding: 'utf8', timeout: 8000, maxBuffer: 4 * 1024 * 1024 });
  const version = (versionProbe.stdout || '').trim().split(/\r?\n/)[0] || null;

  const childArgs = [
    ...tgt.pre,
    ...def.buildArgs({ prompt, promptFile, mode, model: v.model, effort, schema: schemaForCli, outputFormat: v['output-format'], yolo, conversation: v.conversation, resume: !!v['continue'] }),
    ...def.timeoutFlag(v.timeout),
  ];
  // hard-kill grace over the CLI's own timeout: proportional, bounded [3s, 15s]
  const wallMs = timeoutMs + Math.min(15000, Math.max(3000, timeoutMs));

  const stderrLines = [];
  if (yolo) stderrLines.push('[run-agent] WARNING: AWX_AGY_YOLO=1 -> auto-approve/bypass flag attached');
  if (mode === 'accept-edits') stderrLines.push('[run-agent] WARNING: --mode accept-edits -> agent may modify files');
  if (v.effort && cli === 'codex') stderrLines.push('[run-agent] note: --effort ignored for codex (no such flag)');
  if ((v.conversation || v['continue']) && cli !== 'agy') stderrLines.push(`[run-agent] note: --conversation/--continue ignored for ${cli} (agy-only)`);
  if (prompt.length > 28000) stderrLines.push(`[run-agent] WARNING: prompt is ${prompt.length} chars (>28k); argv limit risk`);

  const startedAt = new Date();
  const r = spawnSync(tgt.cmd, childArgs, {
    cwd: ROOT, encoding: 'utf8', timeout: wallMs, maxBuffer: 64 * 1024 * 1024,
    input: def.viaStdin ? prompt : undefined,
    env: process.env,
  });
  const finishedAt = new Date();

  const stdout = r.stdout || '';
  const stderr = (stderrLines.length ? stderrLines.join('\n') + '\n' : '') + (r.stderr || '');
  const timedOut = (r.error && r.error.code === 'ETIMEDOUT')
    || (r.status === null && r.signal != null);
  const spawnErr = r.error && !timedOut ? String(r.error.code || r.error) : null;
  const exitCode = timedOut ? 124 : (r.status ?? (spawnErr ? 2 : 0));

  const trimmed = stdout.trimStart();
  const outName = trimmed.startsWith('{') || trimmed.startsWith('[') ? 'stdout.json' : 'stdout.txt';
  fs.writeFileSync(path.join(runDir, outName), stdout);
  fs.writeFileSync(path.join(runDir, 'stderr.log'), stderr);

  const meta = {
    cli, version, resolvedExe: exe, args: childArgs.slice(tgt.pre.length),
    mode, model: v.model || null, effort: effort || null,
    conversation: v.conversation || null, resume: !!v['continue'],
    timeout: v.timeout, wallTimeoutMs: wallMs, yolo,
    exit: exitCode, exitKind: timedOut ? 'timeout' : spawnErr ? 'spawn-error' : 'exit',
    signal: r.signal || null, durationMs: finishedAt - startedAt,
    startedAt: startedAt.toISOString(), finishedAt: finishedAt.toISOString(),
    cwd: ROOT, runDir, promptFile, stdoutFile: outName,
    envSet: ENV_WATCH.filter((k) => !!process.env[k]),
  };
  fs.writeFileSync(path.join(runDir, 'meta.json'), JSON.stringify(meta, null, 2));
  console.log(`[run-agent] cli=${cli} exit=${exitCode} kind=${meta.exitKind} dur=${meta.durationMs}ms`);
  console.log(`[run-agent] runDir=${runDir}`);
  process.exit(exitCode);
}

main();
