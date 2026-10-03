// Actual LmsApplication only. Credentials are synthetic and arrive over stdin, never in artifacts.
// Optional --model-purpose <quality|regression|smoke|cross_provider|local_fallback>
// appends one policy-driven /chat check: resolve via scripts/test_model_policy.py,
// select in #modelSelect, send one [devin-test] synthetic message, compare the
// observed model, record usage. Without the flag every behavior is unchanged.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

// 디버그 기간 채팅 대기 상한 = chat.run.max-duration-seconds + 20s 여유.
const CHAT_RUN_MAX_MS = (() => { const s = Number(process.env.CHAT_RUN_MAX_DURATION_SECONDS); return Number.isFinite(s) && s > 0 ? s * 1000 : 600000; })();
const ANSWER_WAIT_MS = CHAT_RUN_MAX_MS + 20000;

function parseArgs(argv) {
  const args = { origin: null, modelPurpose: null, send: true, run: null };
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--model-purpose') args.modelPurpose = argv[++i] || null;
    else if (a === '--run') args.run = argv[++i] || null;
    else if (a === '--no-send') args.send = false;
    else if (!a.startsWith('--')) positional.push(a);
  }
  args.origin = positional[0];
  return args;
}

function policyCli(args) {
  const tool = path.join(__dirname, 'test_model_policy.py');
  const out = spawnSync('python', ['-B', tool].concat(args), { encoding: 'utf8' });
  const lines = (out.stdout || '').trim().split('\n').filter(Boolean);
  return { parsed: lines.length ? JSON.parse(lines[lines.length - 1]) : {}, status: out.status };
}

async function modelPolicyStage(page, origin, args) {
  const run = args.run || ('phase2-' + Date.now());
  const resolved = policyCli(['resolve', '--purpose', args.modelPurpose,
    '--base', origin.origin, '--run', run]).parsed;
  const info = { purpose: args.modelPurpose, run, resolve: resolved.verdict, selected: resolved.selected };
  if (resolved.verdict !== 'RESOLVED' || !args.send) {
    if (args.send === false) info.send = 'skipped(--no-send)';
    return info;
  }
  const cap = policyCli(['budget', '--run', run]).parsed;
  info.budget = cap;
  if (cap.verdict === 'BUDGET_EXCEEDED') { info.send = 'blocked'; return info; }
  const { selectModel, readObservedModel } = require('./browser_model_select.js');
  await page.goto(origin.origin + '/chat');
  info.select = await selectModel(page, resolved.selected);
  const message = '[devin-test] policy ' + args.modelPurpose + ' — reply with the single word OK';
  const responsePromise = page.waitForResponse(
    r => r.url().startsWith(origin.origin + '/api/chat/stream'), { timeout: ANSWER_WAIT_MS });
  await page.locator('#messageInput').fill(message);
  await page.locator('#sendBtn').click();
  const response = await responsePromise;
  await page.waitForTimeout(2000);
  const observed = response.headers()['x-model-used'] || await readObservedModel(page);
  const chk = policyCli(['check', '--selected', resolved.selected,
    '--observed', String(observed || '')]).parsed;
  policyCli(['record', '--agent', 'devin-phase2', '--purpose', args.modelPurpose,
    '--model', resolved.selected, '--code', String(response.status()), '--run', run]);
  info.http = response.status(); info.observedModel = observed; info.checkVerdict = chk.verdict;
  return info;
}

async function main() {
  const { chromium } = require('playwright');
  const fixture = JSON.parse(fs.readFileSync(0, 'utf8'));
  const args = parseArgs(process.argv.slice(2));
  const origin = new URL(args.origin);
  assert.equal(origin.protocol, 'http:');
  assert.equal(origin.hostname, '127.0.0.1');
  const output = path.join(__dirname, '../output/playwright/phase2-full-app');
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const checks = [];
  const network = [];
  let stage = 'login';
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.name));
    page.on('response', response => {
      const url = new URL(response.url());
      if (url.origin === origin.origin) network.push({ path: url.pathname, status: response.status() });
    });
    await page.route('**/*', route => {
      const url = new URL(route.request().url());
      if (url.origin === origin.origin) return route.continue();
      // Decorative CDN only; no application API response is replaced.
      if (url.href === 'https://cdn.tailwindcss.com/')
        return route.fulfill({ status: 200, contentType: 'application/javascript', body: '' });
      return route.abort();
    });
    assert.equal((await context.request.get(origin.origin + '/internal/agent/tools')).status(), 403);
    await page.goto(origin.origin + '/login');
    await page.locator('[name="username"]').fill(fixture.username);
    await page.locator('[name="password"]').fill(fixture.password);
    const [landing] = await Promise.all([
      page.waitForResponse(response => new URL(response.url()).pathname === '/index'),
      page.waitForURL('**/index'), page.locator('button[type="submit"]').click()
    ]);
    assert.equal(landing.status(), 200);
    checks.push('real form login and anonymous tool rejection');
    await context.setExtraHTTPHeaders({ 'X-Admin-Token': fixture.capability });
    stage = 'tools';
    const inventoryResponse = await context.request.get(origin.origin + '/internal/agent/tools');
    assert.equal(inventoryResponse.status(), 200);
    const config = (await inventoryResponse.json()).tools.find(tool => tool.id === 'config.inspect');
    assert.equal(config.registered, true);
    assert.equal(config.readOnly, true);
    const invoked = await context.request.post(origin.origin + '/internal/agent/tools/config.inspect:invoke', { data: {} });
    assert.equal(invoked.status(), 200);
    const result = await invoked.json();
    assert.equal(result.ok, true);
    assert.equal(result.toolId, config.id);
    assert.equal(result.policyDecision, 'ALLOW');
    assert.equal(result.resultValidation, 'PASSED');
    assert.ok(!(await invoked.text()).includes(fixture.capability));
    checks.push('production inventory capability and config.inspect invocation');
    stage = 'diagnostics';
    assert.equal((await page.goto(origin.origin + '/admin/debug-events')).status(), 200);
    await page.waitForFunction(() => document.querySelector('#triadicJson').textContent !== '-');
    const auxiliary = {};
    for (const [name, endpoint, selector] of [
      ['localLlm', '/api/diagnostics/local-llm/smoke-history?limit=12', '#localLlmSmokeJson'],
      ['triadic', '/api/diagnostics/debug/triadic-adjudication', '#triadicJson']
    ]) {
      const response = await context.request.get(origin.origin + endpoint);
      assert.equal(response.status(), 200);
      assert.ok(response.headers()['content-type'].includes('application/json'));
      const body = await response.json();
      const rendered = JSON.parse(await page.locator(selector).textContent());
      if (name === 'triadic') assert.equal(rendered.decision, body.decision);
      else assert.equal(rendered.available, body.available);
      auxiliary[name] = { status: response.status(), available: body.available ?? null,
        reason: body.reason ?? body.reasonCode ?? null };
    }
    const events = await context.request.get(origin.origin + '/api/diagnostics/debug/events');
    assert.equal(events.status(), 200);
    assert.ok(Array.isArray(await events.json()));
    checks.push('real diagnostic JSON and both auxiliary panels agree with server');
    for (let i = 0; i < 2; i++) {
      stage = 'sse-open-' + i;
      await page.locator('#live').check();
      await page.waitForFunction(() => document.querySelector('#status').textContent === 'LIVE: connected');
      await page.waitForFunction(() => JSON.parse(document.querySelector('#listJson').textContent).length > 0);
      const ids = JSON.parse(await page.locator('#listJson').textContent()).map(event => event.id);
      assert.equal(new Set(ids).size, ids.length);
      assert.ok(!(await page.locator('body').textContent()).includes(fixture.capability));
      if (i === 0) await page.screenshot({ path: path.join(output, 'connected.png'), fullPage: true });
      await page.locator('#live').uncheck();
      await page.waitForFunction(() => !document.querySelector('#status').textContent.startsWith('LIVE: connected'));
    }
    checks.push('real EventSource connect stop reconnect and deduplicated delivery');
    assert.equal(pageErrors.length, 0);
    const extra = {};
    if (args.modelPurpose) {
      stage = 'model-policy';
      extra.modelPolicy = await modelPolicyStage(page, origin, args);
      checks.push('model policy ' + args.modelPurpose + ': ' +
        (extra.modelPolicy.checkVerdict || extra.modelPolicy.resolve || extra.modelPolicy.send));
    }
    fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify({
      applicationRuntime: 'full LmsApplication local profile', checks, auxiliary,
      applicationApiMocks: 0, decorativeCdn: 'replaced locally', pageErrorCount: 0,
      externalProviderCalls: 'not_observed', network, ...extra
    }, null, 2));
    process.stdout.write(JSON.stringify({ fullApplicationBrowserChecks: checks.length, pageErrorCount: 0 }) + '\n');
  } catch (error) {
    fs.writeFileSync(path.join(output, 'failure.json'), JSON.stringify({ stage, checks, network, errorType: error.name }));
    throw error;
  } finally { await browser.close(); }
}
module.exports = { parseArgs };
if (require.main === module) {
  main().catch(error => { console.error(error.name + ': full application browser proof failed'); process.exitCode = 1; });
}
