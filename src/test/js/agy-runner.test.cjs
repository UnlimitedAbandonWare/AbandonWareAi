const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const RUNNER = path.join(ROOT, 'tools', 'agents', 'run-agent.mjs');
const SECRET = 'TESTSECRET_DO_NOT_LEAK';

function makeStub(dir) {
  const stub = path.join(dir, 'stub-agy.mjs');
  fs.writeFileSync(stub, `#!/usr/bin/env node
const args = process.argv.slice(2);
if (args[0] === '--version') { console.log('stub-agy 0.0.1'); process.exit(0); }
const sleep = Number(process.env.STUB_SLEEP_MS || 0);
const finish = () => {
  console.log(JSON.stringify({ argv: args, cwd: process.cwd() }));
  process.exit(Number(process.env.STUB_EXIT || 0));
};
if (sleep > 0) setTimeout(finish, sleep); else finish();
`);
  return stub;
}

function run(t, extraEnv, args) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'agy-run-'));
  const stub = makeStub(tmp);
  const env = {
    ...process.env, ...extraEnv,
    AGY_EXE: stub,
    AWX_AGENT_RUNS_DIR: tmp,
    GEMINI_API_KEY: SECRET,
    PROMPT_ENV: 'yes',
  };
  fs.writeFileSync(path.join(tmp, 'p.md'), 'test prompt: reply with the argv you received');
  const r = spawnSync(process.execPath, [RUNNER, '--cli', 'agy', '--role', 'test', '--prompt-file', path.join(tmp, 'p.md'), ...args], {
    cwd: ROOT, env, encoding: 'utf8', timeout: 60000,
  });
  const m = /runDir=(.+)\r?$/m.exec(r.stdout || '');
  const runDir = m ? m[1].trim() : null;
  return { r, runDir, tmp };
}

function walk(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p)); else out.push(p);
  }
  return out;
}

function noSecretLeak(runDir) {
  for (const f of walk(runDir)) {
    const body = fs.readFileSync(f, 'utf8');
    assert.ok(!body.includes(SECRET), `secret leaked into ${f}`);
  }
}

test('runner assembles plan-mode args, writes 4 artifacts, no secrets', () => {
  const { r, runDir } = run(null, {}, []);
  assert.equal(r.status, 0, `stderr: ${r.stderr}`);
  assert.ok(runDir && fs.existsSync(runDir));
  for (const f of ['prompt.md', 'stdout.json', 'stderr.log', 'meta.json'])
    assert.ok(fs.existsSync(path.join(runDir, f)), `missing ${f}`);
  const meta = JSON.parse(fs.readFileSync(path.join(runDir, 'meta.json'), 'utf8'));
  const out = JSON.parse(fs.readFileSync(path.join(runDir, 'stdout.json'), 'utf8'));
  const joined = meta.args.join(' ');
  assert.ok(meta.args.includes('-p'), 'prompt passed via -p');
  assert.ok(joined.includes('--mode plan'), 'default mode is plan');
  assert.ok(joined.includes('--output-format json'), 'default output json');
  assert.ok(joined.includes('--print-timeout'), 'print-timeout passed to agy');
  assert.ok(!joined.includes('dangerously-skip-permissions'), 'YOLO flag must not be default');
  assert.equal(meta.exit, 0); assert.equal(meta.version, 'stub-agy 0.0.1');
  assert.equal(meta.cwd, ROOT); assert.equal(out.cwd, ROOT, 'cli spawned with project-root cwd');
  assert.ok(meta.envSet.includes('GEMINI_API_KEY'), 'env name recorded (set-ness only)');
  noSecretLeak(runDir);
});

test('runner propagates non-zero exit code', () => {
  const { r, runDir } = run(null, { STUB_EXIT: '3' }, []);
  assert.equal(r.status, 3);
  const meta = JSON.parse(fs.readFileSync(path.join(runDir, 'meta.json'), 'utf8'));
  assert.equal(meta.exit, 3); assert.equal(meta.exitKind, 'exit');
  noSecretLeak(runDir);
});

test('runner kills on wall-clock timeout (exit 124)', () => {
  const { r, runDir } = run(null, { STUB_SLEEP_MS: '8000' }, ['--timeout', '1s']);
  assert.equal(r.status, 124, `stdout: ${r.stdout}`);
  const meta = JSON.parse(fs.readFileSync(path.join(runDir, 'meta.json'), 'utf8'));
  assert.equal(meta.exitKind, 'timeout');
});

test('YOLO flag only when AWX_AGY_YOLO=1; accept-edits only when explicit', () => {
  const a = run(null, { AWX_AGY_YOLO: '1' }, []);
  const metaA = JSON.parse(fs.readFileSync(path.join(a.runDir, 'meta.json'), 'utf8'));
  assert.ok(metaA.args.includes('--dangerously-skip-permissions'));
  assert.ok(fs.readFileSync(path.join(a.runDir, 'stderr.log'), 'utf8').includes('AWX_AGY_YOLO'));
  const b = run(null, {}, ['--mode', 'accept-edits']);
  const metaB = JSON.parse(fs.readFileSync(path.join(b.runDir, 'meta.json'), 'utf8'));
  assert.ok(metaB.args.join(' ').includes('--mode accept-edits'));
  noSecretLeak(a.runDir); noSecretLeak(b.runDir);
});

test('cross-check extractVerdict: envelope, denied-actions, embedded, missing', async () => {
  const { pathToFileURL } = require('node:url');
  const { extractVerdict } = await import(pathToFileURL(path.join(ROOT, 'tools', 'agents', 'cross-check.mjs')).href);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-extract-'));
  const write = (name, body) => { const p = path.join(dir, name); fs.writeFileSync(p, body); return p; };
  const v1 = extractVerdict(write('a.json', JSON.stringify({
    response: JSON.stringify({ verdict: 'PASS', claims: [], risks: [], next: [] }),
  })));
  assert.equal(v1.verdict, 'PASS');
  const v2 = extractVerdict(write('b.json', JSON.stringify({
    status: 'SUCCESS', response: '', denied_actions: [{ action: 'command' }],
  })));
  assert.equal(v2.verdict, 'NOT_RUN');
  assert.ok(v2.reason.includes('denied'));
  const v3 = extractVerdict(write('c.txt', 'prose {"verdict":"FAIL","claims":[],"risks":[],"next":[]} tail'));
  assert.equal(v3.verdict, 'FAIL');
  assert.equal(extractVerdict(path.join(dir, 'missing.json')).verdict, 'NOT_RUN');
});
