'use strict';
// codex_browser_agent -- bounded /chat browser-test orchestrator for Codex.
// Runs the suites in configs/codex-browser-agent-suites.json in order:
//   smoke -> golden -> fault -> fullapp -> gesture -> trace.
// Every generation-bearing suite resolves its model through
// scripts/test_model_policy.py (api-first until the 2026-12-30 cutoff) and the
// child tools record each send to usage.jsonl under --run <this run id>.
// Loopback only; there is no --public path. A suite that needs more
// generations than the remaining policy budget is NOT_RUN; if every API
// candidate proves dead the run goes BLOCKED_API and later generation suites
// are NOT_RUN (never a quiet local substitute). Evidence:
//   <ledger>/evidence/browser-agent-<ts>.{json,md} (no answer text).
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const POLICY_TOOL = path.join(ROOT, 'scripts', 'test_model_policy.py');
const USAGE_LOG = path.join(ROOT, 'data', 'agent-handoff', 'test-model-policy', 'usage.jsonl');
const DEFAULT_SUITES = path.join(ROOT, 'configs', 'codex-browser-agent-suites.json');
const DEFAULT_LEDGER = path.join(ROOT, 'data', 'agent-handoff', 'codex-browser-agent');
const API_ROUTE = /^(?:chatgpt-oauth:|llmrouter\.)/;
const API_DEAD_SIGNAL = /oauth|stream_required|timeout|blocked_api|local_before_cutoff|unobserved|backend|no_response|provider_not_configured|401|403|429|503/i;

function parseArgs(argv) {
  const a = { base: 'http://127.0.0.1:18180', dryRun: false, only: null,
    maxCalls: null, ledger: DEFAULT_LEDGER, run: null, suites: DEFAULT_SUITES, headful: false };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k === '--base') a.base = argv[++i];
    else if (k === '--dry-run') a.dryRun = true;
    else if (k === '--only') a.only = argv[++i];
    else if (k === '--max-calls') a.maxCalls = Number(argv[++i]);
    else if (k === '--ledger') a.ledger = path.resolve(ROOT, argv[++i]);
    else if (k === '--run') a.run = argv[++i];
    else if (k === '--suites') a.suites = path.resolve(ROOT, argv[++i]);
    else if (k === '--headful') a.headful = true;
    else throw Error('unknown_arg:' + k);
  }
  const url = new URL(a.base);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
    throw Error('loopback_required');
  a.base = url.origin;
  if (a.maxCalls != null && (!Number.isInteger(a.maxCalls) || a.maxCalls < 0 || a.maxCalls > 25))
    throw Error('invalid_max_calls');
  if (a.run != null && !/^[A-Za-z0-9_.:-]{1,80}$/.test(a.run)) throw Error('invalid_run');
  return a;
}

function defaultDeps() {
  return {
    spawn: (file, args, extra) => spawnSync(file, args, Object.assign(
      { encoding: 'utf8', cwd: ROOT, timeout: 600000, windowsHide: true }, extra || {})),
    getCatalog: async base => {
      const resp = await fetch(base + '/api/chat/models', { signal: AbortSignal.timeout(10000) });
      if (!resp.ok) throw Error('MODEL_CATALOG_HTTP_' + resp.status);
      return resp.json();
    },
    policy: args => {
      const out = spawnSync('python', ['-B', POLICY_TOOL].concat(args),
        { encoding: 'utf8', cwd: ROOT, timeout: 20000 });
      const line = (out.stdout || '').trim().split('\n').filter(Boolean).pop();
      if (!line) return { verdict: 'POLICY_TOOL_EMPTY' };
      try { return JSON.parse(line); } catch { return { verdict: 'POLICY_TOOL_PARSE' }; }
    },
    usageRows: runId => {
      if (!fs.existsSync(USAGE_LOG)) return [];
      return fs.readFileSync(USAGE_LOG, 'utf8').split('\n').filter(Boolean)
        .map(l => { try { return JSON.parse(l); } catch { return null; } })
        .filter(r => r && r.run === runId && r.kind === 'generation');
    },
    exists: p => fs.existsSync(path.resolve(ROOT, p)),
    now: () => Date.now(),
    faultDriver: (suite, opts) => faultSuiteDriver(suite, opts),
    env: process.env,
  };
}

// ---------- fault suite (0 generations, synthetic stream fixture) ----------
async function faultSuiteDriver(suite, opts) {
  const { createFaultFixture } = require('./chat_ui_browser_fault_fixture.js');
  let chromium;
  try { ({ chromium } = require('playwright')); }
  catch (e) { return { verdict: 'NOT_RUN', reasons: ['PLAYWRIGHT_MISSING'], cells: [] }; }
  const targetPort = Number(new URL(opts.base).port) || 18180;
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const cells = [];
  try {
    for (const scenario of suite.scenarios || ['error-late']) {
      const fixture = createFaultFixture({ targetPort, scenario, lateDelayMs: 75 });
      await new Promise(r => fixture.listen(0, '127.0.0.1', r));
      const port = fixture.address().port;
      try {
        const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
        const page = await ctx.newPage();
        const pageErrors = [];
        page.on('pageerror', e => pageErrors.push(String(e).split('\n')[0].slice(0, 120)));
        const t0 = Date.now();
        await page.goto('http://127.0.0.1:' + port + '/chat-ui', { waitUntil: 'domcontentloaded' });
        await page.locator('#messageInput').waitFor({ timeout: 15000 });
        await page.locator('#messageInput').fill('browser-agent fault probe');
        await page.locator('#sendBtn').click();
        let lateGrowth = 0;
        if (scenario === 'cancel-late') {
          await page.waitForTimeout(400);
          const stop = page.locator('#stopBtn');
          if (await stop.count()) await stop.click().catch(() => {});
          const len = () => page.evaluate(
            `(()=>[...document.querySelectorAll('[data-message-role="assistant"],.message.assistant')]
              .map(e=>e.innerText||'').join('').length)()`);
          const before = await len();
          await page.waitForTimeout(1600);
          lateGrowth = Math.max(0, (await len()) - before);
        } else {
          await page.waitForTimeout(1800);
        }
        const snap = await page.evaluate(`(() => {
          const t = s => [...document.querySelectorAll(s)].map(e => (e.innerText || '').slice(0, 160));
          return { assistants: t('[data-message-role="assistant"],.message.assistant'),
            notices: t('.toast.error,.alert-danger,.notice'),
            status: (document.querySelector('#modelStatus,#traceStatus') || {}).innerText || '' };
        })()`);
        const shot = path.join(opts.shotDir, 'fault-' + scenario + '.png');
        await page.screenshot({ path: shot }).catch(() => {});
        const surfaced = snap.notices.length > 0 || snap.assistants.length > 0
          || /error|오류|실패|cancel|취소|지연/i.test(snap.status);
        const reasons = pageErrors.slice();
        if (!surfaced) reasons.push('no_error_surface');
        if (lateGrowth > 0) reasons.push('late_tokens_after_stop:' + lateGrowth);
        cells.push({ scenario, verdict: reasons.length ? 'FAIL' : 'PASS', reasons,
          ms: Date.now() - t0, screenshot: shot });
        await ctx.close();
      } catch (e) {
        cells.push({ scenario, verdict: 'FAIL',
          reasons: ['driver:' + String(e.message || e).split('\n')[0].slice(0, 120)] });
      } finally {
        await new Promise(r => fixture.close(r));
      }
    }
  } finally {
    await browser.close();
  }
  return { verdict: cells.every(c => c.verdict === 'PASS') ? 'PASS' : 'FAIL',
    cells, generations: 0 };
}

// ---------- suite runners ----------------------------------------------------
function lastJson(stdout) {
  const line = (stdout || '').trim().split('\n').filter(Boolean).pop();
  try { return line ? JSON.parse(line) : null; } catch { return null; }
}

function spawnTool(deps, toolRel, args, extra, suite, ctx) {
  const opts = Object.assign({ timeout: (suite && suite.timeoutMs) || 600000 }, extra || {});
  const out = deps.spawn('node', [path.join(ROOT, toolRel)].concat(args), opts);
  const timedOut = !!(out.error && (out.error.code === 'ETIMEDOUT' || out.signal === 'SIGTERM'));
  if (timedOut && ctx) ctx.lastSpawnTimeoutMs = opts.timeout;
  return { status: out.status == null ? 1 : out.status, timedOut,
    stdout: out.stdout || '', stderr: String(out.stderr || '').slice(0, 400), json: lastJson(out.stdout) };
}

async function runSuite(suite, ctx) {
  const { deps, opts, run, shotDir, ledgerDir } = ctx;
  ctx.lastSpawnTimeoutMs = 0;
  const toolRel = suite.tool;
  if (!deps.exists(toolRel)) {
    return { id: suite.id, verdict: 'NOT_RUN', reasons: ['tool_missing:' + toolRel], generations: 0 };
  }
  const res = await dispatchSuite(suite, ctx);
  if (ctx.lastSpawnTimeoutMs && res.verdict === 'FAIL')
    res.reasons = (res.reasons || []).concat(['suite_timeout:' + ctx.lastSpawnTimeoutMs]);
  return res;
}

async function dispatchSuite(suite, ctx) {
  const { deps, opts, run, shotDir, ledgerDir } = ctx;
  const toolRel = suite.tool;
  switch (suite.id) {
    case 'smoke': {
      const promptFile = path.join(ledgerDir, 'smoke-prompts.json');
      fs.writeFileSync(promptFile, JSON.stringify(suite.prompts || []));
      const sends = Math.min(suite.estGenerations, ctx.remaining());
      const r = spawnTool(deps, toolRel, ['--prompts', promptFile, '--driver', 'browser',
        '--base', opts.base, '--run', run, '--max-calls', String(sends)]
        .concat(opts.headful ? ['--headful'] : []), null, suite, ctx);
      const j = r.json || {};
      const items = (j.items || []).map(i => ({ selected: i.selected, observed: i.observed,
        http: i.http, ms: i.ms, result: i.result, purpose: i.purpose }));
      const verdict = j.verdict === 'ALL_OK' ? 'PASS'
        : j.verdict === 'NOT_RUN' ? 'NOT_RUN' : 'FAIL';
      return { id: suite.id, verdict, generations: j.calls || 0, items,
        reasons: j.verdict && verdict === 'FAIL' ? [j.verdict] : (j.reason ? [j.reason] : []),
        report: j.report, apiDead: items.length > 0 && items.every(i =>
          i.result && i.result !== 'OK' && API_DEAD_SIGNAL.test(String(i.result) + ' ' + String(i.http))) };
    }
    case 'golden': {
      const sends = Math.min(10, ctx.remaining());
      const r = spawnTool(deps, toolRel, ['--base', opts.base, '--purpose', 'quality',
        '--run', run, '--max-sends', String(sends), '--repeat', '1'], null, suite, ctx);
      const j = r.json || {};
      const rows = (j.results || []).map(x => ({ id: x.id, verdict: x.verdict,
        requestedModel: x.requestedModel, observedModel: x.observedModel,
        reasons: x.reasons || [] }));
      const fails = rows.filter(x => x.verdict === 'FAIL').length;
      const blocked = rows.filter(x => x.verdict === 'BLOCKED').length;
      const verdict = !j.results ? 'FAIL' : fails ? 'FAIL' : blocked ? 'BLOCKED' : 'PASS';
      const sendsDone = j.sends || 0;
      return { id: suite.id, verdict, generations: sendsDone, rows,
        reasons: rows.flatMap(x => (x.reasons || []).map(rr => x.id + ':' + rr)).slice(0, 12),
        output: j.output,
        apiDead: sendsDone > 0 && rows.length > 0 && rows.every(x => x.verdict !== 'PASS')
          && rows.some(x => (x.reasons || []).some(rr => API_DEAD_SIGNAL.test(rr))) };
    }
    case 'fault': {
      const out = await deps.faultDriver(suite, { base: opts.base, shotDir });
      return { id: suite.id, verdict: out.verdict, generations: 0,
        cells: out.cells || [], reasons: out.reasons || [] };
    }
    case 'fullapp': {
      const user = process.env.DEMO1_TEST_USER, pass = process.env.DEMO1_TEST_PASS,
        cap = process.env.DEMO1_ADMIN_TOKEN;
      if (!user || !pass || !cap)
        return { id: suite.id, verdict: 'NOT_RUN', generations: 0,
          reasons: ['credentials_not_provisioned:need DEMO1_TEST_USER/DEMO1_TEST_PASS/DEMO1_ADMIN_TOKEN'] };
      const fixture = JSON.stringify({ username: user, password: pass, capability: cap });
      const r = spawnTool(deps, toolRel, [opts.base, '--model-purpose', 'regression', '--run', run],
        { input: fixture }, suite, ctx);
      const j = r.json || {};
      const verdict = r.status === 0 ? 'PASS' : 'FAIL';
      return { id: suite.id, verdict, generations: 0,
        reasons: r.status === 0 ? [] : ['phase2_exit_' + r.status],
        checks: j.fullApplicationBrowserChecks, modelPolicy: j.modelPolicy || null };
    }
    case 'gesture': {
      const testRel = suite.tests || 'scripts/chat_auth_model_matrix_browser_tests.js';
      if (!deps.exists(testRel))
        return { id: suite.id, verdict: 'NOT_RUN', reasons: ['matrix_tests_missing'], generations: 0 };
      const gate = deps.spawn('node', [path.join(ROOT, testRel)], { encoding: 'utf8', cwd: ROOT });
      if (gate.status !== 0)
        return { id: suite.id, verdict: 'NOT_RUN',
          reasons: ['devin matrix not ready: tests_exit_' + gate.status], generations: 0 };
      const sends = Math.min(suite.estGenerations, ctx.remaining());
      const r = spawnTool(deps, toolRel, ['--live', '--tier', 'smoke', '--max-gen', String(sends),
        '--purpose', 'smoke', '--run', run, '--base', opts.base], null, suite, ctx);
      const j = r.json || {};
      const verdict = j.verdict === 'DONE' ? (j.summary && j.summary.fail ? 'FAIL' : 'PASS')
        : j.verdict === 'NOT_RUN' ? 'NOT_RUN' : 'FAIL';
      return { id: suite.id, verdict, generations: 0,
        summary: j.summary, report: j.report,
        reasons: j.reason ? [j.reason] : [],
        apiDead: j.summary && j.summary.blocked > 0 && j.summary.pass === 0 };
    }
    case 'trace': {
      const outJson = path.join(ledgerDir, 'trace-probe-' + run + '.json');
      const shot = path.join(shotDir, 'trace-dock.png');
      const r = spawnTool(deps, toolRel, (suite.args || [])
        .concat(['--base-url', opts.base, '--screenshot', shot, '--out', outJson]), null, suite, ctx);
      if (r.status === 3) return { id: suite.id, verdict: 'NOT_RUN',
        reasons: ['trace_dock_testids_absent'], generations: 0 };
      if (r.status === 5) return { id: suite.id, verdict: 'NOT_RUN',
        reasons: ['PLAYWRIGHT_MISSING'], generations: 0 };
      return { id: suite.id, verdict: r.status === 0 ? 'PASS' : 'FAIL', generations: 0,
        reasons: r.status === 0 ? [] : ['probe_exit_' + r.status], out: outJson };
    }
    default:
      return { id: suite.id, verdict: 'NOT_RUN', reasons: ['unknown_suite'], generations: 0 };
  }
}

// ---------- plan / orchestrate ------------------------------------------------
async function runAgent(opts, deps) {
  deps = deps || defaultDeps();
  const cfg = JSON.parse(fs.readFileSync(opts.suites, 'utf8'));
  const run = opts.run || ('cba-' + Date.now().toString(36));
  const maxGens = Math.min(cfg.maxApiGenerationsPerRun || 25, opts.maxCalls == null ? 25 : opts.maxCalls);
  const ledgerDir = opts.ledger;
  const evDir = path.join(ledgerDir, 'evidence');
  const shotDir = path.join(evDir, 'shots-' + run);
  fs.mkdirSync(shotDir, { recursive: true });

  const result = { tool: 'codex_browser_agent', schema: 'awx.codex-browser-agent.v1',
    run, base: opts.base, dryRun: !!opts.dryRun, at: new Date().toISOString(),
    maxGenerations: maxGens, preflight: {}, suites: [] };

  // Preflight: catalog + policy resolves + budget (0 generations).
  let catalog = [];
  try { catalog = await deps.getCatalog(opts.base); }
  catch (e) { result.preflight.catalog = 'ERR:' + String(e.message || e).slice(0, 80); }
  const selectable = Array.isArray(catalog) ? catalog.filter(m => m.selectable !== false) : [];
  const apiSelectable = selectable.filter(m => API_ROUTE.test(m.id || ''));
  result.preflight.apiSelectable = apiSelectable.map(m => m.id);
  result.preflight.selectableCount = selectable.length;
  const purposes = [...new Set(cfg.suites.filter(s => s.purpose && s.purpose !== 'none')
    .map(s => s.purpose === 'auto' ? 'smoke' : s.purpose))];
  result.preflight.resolved = {};
  for (const p of purposes) {
    const r = deps.policy(['resolve', '--purpose', p, '--base', opts.base, '--run', run]);
    result.preflight.resolved[p] = { verdict: r.verdict, selected: r.selected,
      alternates: (r.alternates || []).slice(0, 4) };
  }
  const cap = deps.policy(['budget', '--run', run]);
  const alreadyUsed = cap.used || 0;
  let remaining = Math.max(0, Math.min(cap.remaining == null ? maxGens : cap.remaining, maxGens - alreadyUsed));
  result.preflight.budget = { limit: maxGens, usedBefore: alreadyUsed, remaining };

  const ctx = { deps, opts, run, shotDir, ledgerDir, remaining: () => remaining };
  const onlyList = opts.only ? String(opts.only).split(',').map(s => s.trim()).filter(Boolean) : null;
  const wanted = onlyList ? cfg.suites.filter(s => onlyList.includes(s.id)) : cfg.suites;
  if (onlyList && !wanted.length) {
    result.verdict = 'USAGE'; result.reason = 'unknown_suite:' + opts.only;
    return finish(result, evDir);
  }
  const apiBlockedAtStart = apiSelectable.length === 0;

  for (const suite of wanted) {
    const row = { id: suite.id, title: suite.title, tool: suite.tool,
      purpose: suite.purpose, estGenerations: suite.estGenerations };
    if (apiBlockedAtStart && suite.estGenerations > 0) {
      Object.assign(row, { verdict: 'NOT_RUN', reasons: ['api_blocked:no_selectable_api_model'] });
      result.suites.push(row); continue;
    }
    if (suite.estGenerations > remaining) {
      Object.assign(row, { verdict: 'NOT_RUN',
        reasons: ['budget_insufficient:need_' + suite.estGenerations + '_left_' + remaining] });
      result.suites.push(row); continue;
    }
    if (ctx.apiDead && suite.estGenerations > 0) {
      Object.assign(row, { verdict: 'NOT_RUN', reasons: ['api_blocked:all_candidates_failed'] });
      result.suites.push(row); continue;
    }
    if (opts.dryRun) { row.verdict = 'PLANNED'; result.suites.push(row); continue; }
    const t0 = deps.now();
    const rowsBefore = deps.usageRows(run).length;
    let out;
    try { out = await runSuite(suite, ctx); }
    catch (e) { out = { verdict: 'FAIL', reasons: ['runner:' + String(e.message || e).split('\n')[0].slice(0, 120)] }; }
    out.ms = deps.now() - t0;
    // Generation count is the usage-ledger delta under this run id (authoritative;
    // child tools record every send with --run <run>).
    out.generations = Math.max(0, deps.usageRows(run).length - rowsBefore);
    Object.assign(row, out);
    if (out.generations) remaining -= out.generations;
    if (out.apiDead) ctx.apiDead = true;
    result.suites.push(row);
  }

  const used = deps.usageRows(run);
  result.generations = { run, used: used.length, limit: maxGens,
    perModel: used.reduce((m, r) => { m[r.model] = (m[r.model] || 0) + 1; return m; }, {}),
    codes: used.reduce((m, r) => { m[String(r.code)] = (m[String(r.code)] || 0) + 1; return m; }, {}) };
  result.publicSends = 0;
  const fails = result.suites.filter(s => s.verdict === 'FAIL').length;
  const notRun = result.suites.filter(s => s.verdict === 'NOT_RUN').length;
  result.verdict = apiBlockedAtStart && result.suites.some(s => s.estGenerations > 0) ? 'BLOCKED_API'
    : fails ? 'FAIL' : notRun ? 'PARTIAL' : 'PASS';
  return finish(result, evDir);
}

function finish(result, evDir) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const jsonPath = path.join(evDir, 'browser-agent-' + stamp + '.json');
  const mdPath = path.join(evDir, 'browser-agent-' + stamp + '.md');
  result.evidence = { json: jsonPath, md: mdPath };
  fs.mkdirSync(evDir, { recursive: true });
  fs.writeFileSync(jsonPath, JSON.stringify(result, null, 2));
  const lines = ['# browser-agent ' + stamp, '',
    'run=' + result.run + ' base=' + result.base + ' verdict=' + result.verdict +
    (result.generations ? ' generations=' + result.generations.used + '/' + result.generations.limit : '') +
    ' publicSends=' + result.publicSends, '',
    '| suite | verdict | est_gen | gen | purpose | reasons |', '|---|---|---|---|---|---|'];
  for (const s of result.suites)
    lines.push('| ' + s.id + ' | ' + s.verdict + ' | ' + s.estGenerations + ' | ' +
      (s.generations == null ? 0 : s.generations) + ' | ' + s.purpose + ' | ' +
      ((s.reasons || []).join(';') || '-') + ' |');
  fs.writeFileSync(mdPath, lines.join('\n') + '\n');
  return result;
}

async function main() {
  let opts;
  try { opts = parseArgs(process.argv.slice(2)); }
  catch (e) { console.log(JSON.stringify({ verdict: 'USAGE', reason: e.message })); return 2; }
  try {
    const result = await runAgent(opts);
    console.log(JSON.stringify({ verdict: result.verdict, run: result.run,
      generations: result.generations, evidence: result.evidence,
      suites: result.suites.map(s => ({ id: s.id, verdict: s.verdict, gen: s.generations || 0,
        reasons: s.reasons || [] })) }));
    if (result.verdict === 'BLOCKED_API') return 3;
    if (result.verdict === 'FAIL') return 1;
    return 0;
  } catch (e) {
    console.log(JSON.stringify({ verdict: 'ERROR', reason: String(e.message || e).split('\n')[0].slice(0, 160) }));
    return 1;
  }
}

module.exports = { parseArgs, runAgent, defaultDeps, API_ROUTE };
if (require.main === module) {
  main().then(code => { process.exitCode = code; })
    .catch(e => { console.error(e.name + ': ' + e.message); process.exitCode = 1; });
}
