#!/usr/bin/env node
// tools/agents/doctor.mjs
// $0-cost agent-CLI health report: paths, versions, headless-flag support,
// MCP-config locations, env key set-ness (names only), login evidence.
// Makes NO generation calls. `agy mcp list`/`agy models` are local/metadata
// probes with a short timeout; on failure the field is reported, not guessed.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const ENV_WATCH = ['GEMINI_API_KEY', 'OPENAI_API_KEY'];

function whichSync(name) {
  if (process.platform !== 'win32') {
    const r = spawnSync('command', ['-v', name], { encoding: 'utf8', shell: true });
    return r.status === 0 ? (r.stdout || '').trim().split('\n')[0] : null;
  }
  const r = spawnSync('where', [name], { encoding: 'utf8' });
  return r.status === 0 ? (r.stdout || '').split(/\r?\n/)[0].trim() : null;
}

function probe(cmd, args, timeoutMs = 12000) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 });
  const out = `${r.stdout || ''}\n${r.stderr || ''}`;
  return { status: r.status, timedOut: !!(r.error && r.error.code === 'ETIMEDOUT'), out };
}

function firstLine(s) { return (s || '').trim().split(/\r?\n/)[0].trim(); }

const rows = [];
function row(name, present, p, version, note) {
  rows.push({ name, present, path: p || '-', version: version || '-', note: note || '' });
}

// --- node ---
{
  const n = whichSync('node');
  if (n) { const v = probe(n, ['--version']); row('node', true, n, firstLine(v.out), 'runtime for tools/agents'); }
  else row('node', false, null, null, 'REQUIRED by tools/agents');
}

// --- agy ---
let agyPath = null;
{
  const envExe = process.env.AGY_EXE;
  const local = process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'agy', 'bin', 'agy.exe') : null;
  const onPath = whichSync('agy');
  agyPath = (envExe && fs.existsSync(envExe)) ? envExe : (onPath || (local && fs.existsSync(local) ? local : null));
  const onPathNow = !!onPath;
  if (agyPath) {
    const v = probe(agyPath, ['--version']);
    row('agy', true, agyPath, firstLine(v.out) || 'unknown',
      onPathNow ? 'on PATH' : 'NOT on PATH -> use Start-Agy-CLI.bat (works without PATH)');
  } else {
    row('agy', false, null, null, 'not found (AGY_EXE / PATH / %LOCALAPPDATA%\\agy\\bin)');
  }
}

// --- grok ---
let grokHeadless = 'unknown';
{
  const envExe = process.env.GROK_EXE;
  const local = process.env.USERPROFILE ? path.join(process.env.USERPROFILE, '.grok', 'bin', 'grok.exe') : null;
  const onPath = whichSync('grok');
  const p = (envExe && fs.existsSync(envExe)) ? envExe : (onPath || (local && fs.existsSync(local) ? local : null));
  if (p) {
    const v = probe(p, ['--version']);
    const h = probe(p, ['--help']);
    grokHeadless = /(--prompt-file|--single|-p,)/.test(h.out) ? 'yes (--prompt-file / -p)' : 'not found in --help';
    row('grok', true, p, firstLine(v.out), `headless: ${grokHeadless}`);
  } else row('grok', false, null, null, 'not found');
}

// --- codex ---
let codexHeadless = 'unknown';
{
  const envExe = process.env.CODEX_EXE;
  const local = process.env.APPDATA ? path.join(process.env.APPDATA, 'npm', 'codex.cmd') : null;
  const onPath = whichSync('codex');
  const cmdPath = (envExe && fs.existsSync(envExe)) ? envExe
    : (onPath && onPath.endsWith('.cmd') ? onPath : (local && fs.existsSync(local) ? local : onPath));
  if (cmdPath) {
    const comspec = process.env.ComSpec || 'cmd.exe';
    const v = probe(comspec, ['/d', '/s', '/c', cmdPath, '--version']);
    const h = probe(comspec, ['/d', '/s', '/c', cmdPath, 'exec', '--help']);
    codexHeadless = /exec[\s\S]*?--sandbox|read-only/.test(h.out) ? 'yes (exec -s read-only)' : 'not found in --help';
    row('codex', true, cmdPath, firstLine(v.out), `headless: ${codexHeadless}`);
  } else row('codex', false, null, null, 'not found');
}

// --- agy sub-probes (metadata only; never generation) ---
let agyAuth = 'UNKNOWN';
let mcpList = 'not run';
let models = 'not run';
if (agyPath) {
  const m = probe(agyPath, ['mcp', 'list'], 15000);
  mcpList = m.timedOut ? 'timeout' : firstLine(m.out) || `exit ${m.status}`;
  const ml = probe(agyPath, ['models'], 20000);
  if (ml.timedOut) { models = 'timeout'; agyAuth = 'UNKNOWN (models probe timed out)'; }
  else if (ml.status === 0 && /gemini|claude|gpt/i.test(ml.out)) {
    models = `${ml.out.split(/\r?\n/).filter((l) => /\S/.test(l)).length} model rows`;
    agyAuth = 'session active (models list fetched)';
  } else if (/sign.?in|login|auth|401|unauthorized/i.test(ml.out)) {
    models = `exit ${ml.status}`; agyAuth = 'LOGIN_NEEDED';
  } else { models = `exit ${ml.status}`; agyAuth = 'UNKNOWN'; }
}
// credential file evidence (names only; keyring/cookie auth leaves no file)
const geminiDir = process.env.USERPROFILE ? path.join(process.env.USERPROFILE, '.gemini') : null;
const credEvidence = [];
if (geminiDir && fs.existsSync(geminiDir)) {
  for (const rel of ['oauth_creds.json', 'google_accounts.json', 'antigravity-cli', 'config', 'GEMINI.md']) {
    if (fs.existsSync(path.join(geminiDir, rel))) credEvidence.push(`~/.gemini/${rel}`);
  }
}
if (credEvidence.length === 0) credEvidence.push('(none)');

// --- config files ---
const cfgGlobal = geminiDir ? path.join(geminiDir, 'config', 'mcp_config.json') : null;
const cfgProject = path.join(ROOT, '.agents', 'mcp_config.json');
const cfgGlobalState = cfgGlobal && fs.existsSync(cfgGlobal)
  ? `exists (${fs.statSync(cfgGlobal).size}B)` : 'absent';
const cfgProjectState = fs.existsSync(cfgProject) ? 'exists' : 'absent';

// --- local filesystem / git / runtime probes (auto-repair capable) ---
// Required checks decide the ALL-GREEN verdict; everything above is
// informational (presence/versions/auth evidence) and never fails the run.
const REQUIRED = [];
function req(name, ok, note) { REQUIRED.push({ name, ok: !!ok, note: note || '' }); }

function fsProbe() {
  // full cycle under ROOT/var: mkdir -> write -> read -> unlink -> rmdir
  const base = path.join(ROOT, 'var');
  fs.mkdirSync(base, { recursive: true });
  const dir = fs.mkdtempSync(path.join(base, 'agent-doctor-'));
  const f = path.join(dir, 'probe.txt');
  fs.writeFileSync(f, 'probe');
  const back = fs.readFileSync(f, 'utf8');
  fs.unlinkSync(f);
  fs.rmdirSync(dir);
  return back === 'probe' ? null : 'readback-mismatch';
}

let fsErr = null;
try { fsErr = fsProbe(); } catch (e) { fsErr = `${e.code || e.name}: ${e.message}`; }
let repairNote = '';
if (fsErr && /EPERM|EACCES|denied/i.test(fsErr)) {
  // bounded auto-repair: ONE agent_perm_repair.ps1 pass on var/, retry once
  const ps = probe('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
    path.join(ROOT, 'scripts', 'agent_perm_repair.ps1'), '-Root', ROOT, '-Targets', 'var'], 90000);
  repairNote = `perm-repair exit ${ps.status}${ps.timedOut ? ' timeout' : ''}, retried`;
  try { fsErr = fsProbe(); } catch (e) { fsErr = `${e.code || e.name}: ${e.message}`; }
}
req('fs r/w/d (var)', !fsErr, (fsErr || 'create+write+read+delete ok') + (repairNote ? ` | ${repairNote}` : ''));

// git binary + repo access
{
  const git = whichSync('git')
    || (fs.existsSync('F:\\git\\cmd\\git.exe') ? 'F:\\git\\cmd\\git.exe' : null);
  if (git) {
    const v = probe(git, ['--version']);
    row('git', true, git, firstLine(v.out));
    const rp = probe(git, ['-C', ROOT, 'rev-parse', '--is-inside-work-tree']);
    req('git repo access', rp.status === 0 && /true/i.test(rp.out), firstLine(rp.out) || `exit ${rp.status}`);
  } else { row('git', false, null, null, 'not found'); req('git repo access', false, 'git not found'); }
}

// java (JAVA_HOME preferred, then PATH, then the pinned JDK17 path)
{
  const jh = process.env.JAVA_HOME;
  const java = (jh && fs.existsSync(path.join(jh, 'bin', 'java.exe')) ? path.join(jh, 'bin', 'java.exe') : null)
    || whichSync('java')
    || (fs.existsSync('C:\\jdk\\jdk-17.0.13\\bin\\java.exe') ? 'C:\\jdk\\jdk-17.0.13\\bin\\java.exe' : null);
  if (java) {
    const v = probe(java, ['-version']);
    row('java', true, java, firstLine(v.out));
    req('java runs', v.status === 0, `exit ${v.status}`);
  } else { row('java', false, null, null, 'not found'); req('java runs', false, 'java not found'); }
}

// python
{
  const py = whichSync('python');
  if (py) {
    const v = probe(py, ['--version']);
    row('python', true, py, firstLine(v.out));
    req('python runs', v.status === 0, `exit ${v.status}`);
  } else { row('python', false, null, null, 'not found'); req('python runs', false, 'python not found'); }
}

// CLI executability is required; auth state stays informational
for (const n of ['agy', 'grok', 'codex']) {
  const r = rows.find((x) => x.name === n);
  req(`${n} executable`, !!(r && r.present), r ? r.path : 'not found');
}

// --- render ---
const w = [14, 8, 52, 22];
const line = (a, b, c, d) => `${a.padEnd(w[0])}${b.padEnd(w[1])}${c.padEnd(w[2])}${d}`;
console.log('=== Doctor-Agents (no generation calls) ===');
console.log(line('tool', 'present', 'path', 'version'));
console.log('-'.repeat(w[0] + w[1] + w[2] + w[3]));
for (const r of rows) console.log(line(r.name, String(r.present), r.path, r.version) + (r.note ? `  | ${r.note}` : ''));
console.log('\nagy probes : mcp list = ' + mcpList + ' | models = ' + models);
console.log('agy auth   : ' + agyAuth);
console.log('cred files : ' + credEvidence.join(', '));
console.log('env keys   : ' + ENV_WATCH.map((k) => `${k}=${process.env[k] ? 'set' : 'unset'}`).join('  '));
console.log(`mcp config : project .agents/mcp_config.json ${cfgProjectState} | global ~/.gemini/config/mcp_config.json ${cfgGlobalState}`);
console.log('PATH note  : ' + (rows.find((r) => r.name === 'agy')?.note?.includes('NOT on PATH')
  ? 'agy not on PATH -> Start-Agy-CLI.bat resolves it without PATH'
  : 'agy resolves via PATH or Start-Agy-CLI.bat'));
console.log('\nrequired checks:');
for (const r of REQUIRED) console.log(`  ${r.ok ? 'ok  ' : 'FAIL'} ${r.name}${r.note ? '  | ' + r.note : ''}`);
const failed = REQUIRED.filter((r) => !r.ok);
console.log('\nVERDICT: ' + (failed.length === 0
  ? 'ALL-GREEN'
  : `ISSUES (${failed.map((r) => r.name).join(', ')})`));
process.exit(failed.length === 0 ? 0 : 1);
