'use strict';
// Offline contract tests for codex_browser_agent: all child tools and the
// policy/catalog seams are stubbed; no server, browser, or generation calls.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { parseArgs, runAgent } = require('./codex_browser_agent');

const SUITES = path.resolve(__dirname, '../configs/codex-browser-agent-suites.json');

function makeDeps(over = {}) {
  const spawned = [];
  const deps = {
    spawned,
    spawn: (file, args, extra) => {
      spawned.push({ file, args });
      const tool = String(args[0] || '');
      if (tool.includes('chat_practice_browser'))
        return { status: 0, stdout: JSON.stringify({ verdict: 'ALL_OK', calls: 2,
          items: [{ selected: 'chatgpt-oauth:gpt-5.6-luna', observed: 'chatgpt-oauth:gpt-5.6-luna', http: 200, result: 'OK' }] }) };
      if (tool.includes('chat_rag_golden_browser'))
        return { status: 0, stdout: JSON.stringify({ sends: 9,
          results: [{ id: 'C1', verdict: 'PASS', reasons: [] }] }) };
      if (tool.includes('phase2_full_app'))
        return { status: 0, stdout: JSON.stringify({ fullApplicationBrowserChecks: 12,
          modelPolicy: { http: 200, checkVerdict: 'OK' } }) };
      if (tool.includes('matrix_browser_tests'))
        return { status: 0, stdout: 'ok' };
      if (tool.includes('chat_auth_model_matrix_browser'))
        return { status: 0, stdout: JSON.stringify({ verdict: 'DONE',
          summary: { cells: 6, pass: 6, fail: 0, blocked: 0, not_observed: 0 } }) };
      if (tool.includes('trace_dock_browser_probe'))
        return { status: 0, stdout: 'probe-ok' };
      return { status: 0, stdout: '{}' };
    },
    getCatalog: async () => [
      { id: 'chatgpt-oauth:gpt-5.6-luna', selectable: true },
      { id: 'gemma4:26b', selectable: true }],
    policy: args => {
      if (args[0] === 'resolve')
        return { verdict: 'RESOLVED', selected: 'chatgpt-oauth:gpt-5.6-luna',
          alternates: ['chatgpt-oauth:gpt-5.6-terra'] };
      if (args[0] === 'budget') return { verdict: 'OK', used: 0, remaining: 25, limit: 25 };
      return { verdict: 'OK' };
    },
    usageRows: run => [],
    exists: p => true,
    now: () => Date.now(),
    faultDriver: async () => ({ verdict: 'PASS',
      cells: [{ scenario: 'error-late', verdict: 'PASS', reasons: [] }], generations: 0 }),
    env: {},
  };
  return Object.assign(deps, over);
}

function tempLedger() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'cba-test-'));
}

function opts(over = {}) {
  return Object.assign({ base: 'http://127.0.0.1:18180', dryRun: false, only: null,
    maxCalls: null, ledger: tempLedger(), run: 'test-run', suites: SUITES, headful: false }, over);
}

test('parseArgs enforces loopback, bounds and known flags', () => {
  assert.throws(() => parseArgs(['--base', 'https://example.com']), /loopback/);
  assert.throws(() => parseArgs(['--base', 'http://127.0.0.1:18180/x']), /loopback/);
  assert.throws(() => parseArgs(['--max-calls', '26']), /invalid_max_calls/);
  assert.throws(() => parseArgs(['--bogus']), /unknown_arg/);
  assert.equal(parseArgs(['--only', 'smoke']).only, 'smoke');
  assert.equal(parseArgs(['--base', 'http://localhost/']).base, 'http://localhost');
});

test('dry-run plans six bundles within the 25-generation cap and spawns nothing', async () => {
  const deps = makeDeps();
  const result = await runAgent(opts({ dryRun: true }), deps);
  assert.equal(result.suites.length, 6);
  assert.deepEqual(result.suites.map(s => s.id), ['smoke', 'golden', 'fault', 'fullapp', 'gesture', 'trace']);
  const total = result.suites.reduce((n, s) => n + s.estGenerations, 0);
  assert(total <= 25, 'planned generations ' + total + ' must stay <= 25');
  assert(result.suites.every(s => s.verdict === 'PLANNED'));
  assert.equal(deps.spawned.length, 0);
  assert.equal(result.generations.used, 0);
  assert.equal(result.publicSends, 0);
});

test('no selectable api route yields BLOCKED_API and marks generation suites NOT_RUN', async () => {
  const deps = makeDeps({ getCatalog: async () => [{ id: 'gemma4:26b', selectable: true }] });
  const result = await runAgent(opts({ dryRun: true }), deps);
  assert.equal(result.verdict, 'BLOCKED_API');
  for (const s of result.suites)
    if (s.estGenerations > 0) {
      assert.equal(s.verdict, 'NOT_RUN');
      assert(s.reasons[0].startsWith('api_blocked'));
    }
});

test('budget smaller than a bundle marks it and later large bundles NOT_RUN only', async () => {
  const deps = makeDeps({ policy: args => {
    if (args[0] === 'resolve') return { verdict: 'RESOLVED', selected: 'chatgpt-oauth:gpt-5.6-luna', alternates: [] };
    if (args[0] === 'budget') return { verdict: 'OK', used: 22, remaining: 3, limit: 25 };
    return { verdict: 'OK' };
  } });
  const result = await runAgent(opts({ dryRun: true }), deps);
  const byId = Object.fromEntries(result.suites.map(s => [s.id, s]));
  assert.equal(byId.smoke.verdict, 'PLANNED');
  assert.equal(byId.golden.verdict, 'NOT_RUN');
  assert.match(byId.golden.reasons[0], /budget_insufficient/);
  assert.equal(byId.fullapp.verdict, 'NOT_RUN');
  assert.equal(byId.fault.verdict, 'PLANNED');   // 0-generation suites still run
  assert.equal(byId.trace.verdict, 'PLANNED');
});

test('gesture bundle is NOT_RUN when the devin matrix tool or its tests are absent', async () => {
  const noFile = makeDeps({ exists: p => !String(p).includes('chat_auth_model_matrix_browser') });
  const r1 = await runAgent(opts({ only: 'gesture' }), noFile);
  assert.equal(r1.suites[0].verdict, 'NOT_RUN');
  assert.match(r1.suites[0].reasons[0], /tool_missing|matrix_tests_missing/);
  const badTests = makeDeps({ spawn: (file, args) => {
    if (String(args[0]).includes('_tests')) return { status: 1, stdout: 'fail' };
    return { status: 0, stdout: '{}' };
  } });
  const r2 = await runAgent(opts({ only: 'gesture' }), badTests);
  assert.equal(r2.suites[0].verdict, 'NOT_RUN');
  assert.match(r2.suites[0].reasons[0], /matrix not ready/);
});

test('happy path passes suites through and reports ledger-counted generations', async () => {
  const genRows = [];
  process.env.DEMO1_TEST_USER = 't'; process.env.DEMO1_TEST_PASS = 't'; process.env.DEMO1_ADMIN_TOKEN = 't';
  try {
    const deps = makeDeps();
    const innerSpawn = deps.spawn;
    deps.spawn = (file, args, extra) => {
      const tool = String(args[0] || '');
      if (/chat_practice_browser|chat_rag_golden_browser|phase2_full_app|chat_auth_model_matrix_browser\.js$/.test(tool))
        genRows.push({ run: 'test-run', kind: 'generation', model: 'chatgpt-oauth:gpt-5.6-luna', code: '200' });
      return innerSpawn(file, args, extra);
    };
    deps.usageRows = () => genRows;
    const result = await runAgent(opts({}), deps);
    assert.equal(result.verdict, 'PASS');
    assert.equal(result.suites.length, 6);
    assert(result.suites.every(s => s.verdict === 'PASS'), JSON.stringify(result.suites.map(s => [s.id, s.verdict])));
    assert.equal(result.generations.used, 4);   // one recorded send per gen-suite (smoke, golden, fullapp, gesture)
    assert(fs.existsSync(result.evidence.json));
    assert(fs.existsSync(result.evidence.md));
  } finally {
    delete process.env.DEMO1_TEST_USER; delete process.env.DEMO1_TEST_PASS; delete process.env.DEMO1_ADMIN_TOKEN;
  }
});

test('all-dead smoke results propagate api_blocked to later generation suites', async () => {
  const deps = makeDeps({ spawn: (file, args) => {
    const tool = String(args[0] || '');
    if (tool.includes('chat_practice_browser'))
      return { status: 6, stdout: JSON.stringify({ verdict: 'MIXED', calls: 2, items: [
        { selected: 'chatgpt-oauth:gpt-5.6-luna', observed: null, http: 503, result: 'BLOCKED_API' },
        { selected: 'chatgpt-oauth:gpt-5.6-luna', observed: null, http: 503, result: 'BLOCKED_API' }] }) };
    if (tool.includes('trace_dock')) return { status: 0, stdout: 'ok' };
    return { status: 0, stdout: '{}' };
  } });
  const result = await runAgent(opts({}), deps);
  const byId = Object.fromEntries(result.suites.map(s => [s.id, s]));
  assert.equal(byId.smoke.verdict, 'FAIL');
  for (const id of ['golden', 'fullapp', 'gesture']) {
    assert.equal(byId[id].verdict, 'NOT_RUN');
    assert.match(byId[id].reasons[0], /api_blocked/);
  }
  assert.equal(byId.fault.verdict, 'PASS');
  assert.equal(byId.trace.verdict, 'PASS');
  assert.equal(result.verdict, 'FAIL');
});

test('--only runs a single bundle and generation totals come from the usage ledger', async () => {
  const genRows = [];
  const deps = makeDeps({
    spawn: () => {
      genRows.push({ run: 'test-run', kind: 'generation', model: 'chatgpt-oauth:gpt-5.6-luna', code: '200' });
      genRows.push({ run: 'test-run', kind: 'generation', model: 'chatgpt-oauth:gpt-5.6-luna', code: '200' });
      return { status: 0, stdout: JSON.stringify({ verdict: 'ALL_OK', calls: 2, items: [] }) };
    },
    usageRows: () => genRows });
  const result = await runAgent(opts({ only: 'smoke' }), deps);
  assert.equal(result.suites.length, 1);
  assert.equal(result.suites[0].id, 'smoke');
  assert.equal(result.suites[0].generations, 2);
  assert.equal(result.generations.used, 2);
  assert.equal(result.generations.perModel['chatgpt-oauth:gpt-5.6-luna'], 2);
});
