// Local-only browser regression. Uses an installed Playwright via NODE_PATH; no downloads.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

async function main() {
  const html = fs.readFileSync(path.join(__dirname, '../main/resources/templates/debug-events.html'), 'utf8');
  const output = path.join(__dirname, '../output/playwright/debug-events-cleanup',
    process.argv.includes('--probe-repair') ? 'probe-repair' : '.');
  fs.mkdirSync(output, { recursive: true });
  let mode = 'healthy';
  const streams = new Set();
  const network = [];
  const consoleMessages = [];
  const pageErrors = [];
  const checks = [];
  let delayedSnapshot;
  let snapshotArrived;
  const server = http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://127.0.0.1').pathname;
    if (pathname === '/admin/debug-events') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(html);
    }
    if (pathname === '/favicon.ico') { res.writeHead(204); return res.end(); }
    if (pathname === '/api/diagnostics/debug/events/stream') {
      if (mode === 'closed') { res.writeHead(204); return res.end(); }
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      res.write('retry: 100\nevent: hello\ndata: {}\n\n');
      streams.add(res);
      res.on('close', () => streams.delete(res));
      return;
    }
    const fixtures = {
      '/api/diagnostics/debug/events': [],
      '/api/diagnostics/debug/fingerprints': [],
      '/api/diagnostics/local-llm/smoke-history': { reportFound: false, reason: 'fixture_no_report' },
      '/api/diagnostics/debug/triadic-adjudication': { decision: 'HOLD', confidence: 'LOW', reasonCode: 'fixture' }
    };
    if (!Object.hasOwn(fixtures, pathname)) { res.writeHead(404); return res.end(); }
    if (pathname.endsWith('/events') && mode === 'http-error') {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      return res.end('synthetic-private-error-body');
    }
    if (pathname.endsWith('/events') && mode === 'delayed-snapshot') {
      delayedSnapshot = res;
      snapshotArrived();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    if (pathname.endsWith('/events') && mode === 'invalid-json') {
      return res.end('privateX');
    }
    res.end(JSON.stringify(fixtures[pathname]));
  });
  let browser;
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const origin = 'http://127.0.0.1:' + server.address().port;
    browser = await chromium.launch({ channel: 'msedge', headless: !process.argv.includes('--headed') });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    await page.route('**/*', route => {
      if (new URL(route.request().url()).origin === origin) return route.continue();
      // Keep the real template intact while replacing its decorative CDN script locally.
      if (route.request().url() === 'https://cdn.tailwindcss.com/') {
        return route.fulfill({ status: 200, contentType: 'application/javascript', body: '' });
      }
      return route.abort();
    });
    page.on('response', response => {
      const url = new URL(response.url());
      if (url.origin === origin) network.push({ path: url.pathname, status: response.status() });
    });
    page.on('console', message => consoleMessages.push(message.text()));
    page.on('pageerror', error => pageErrors.push(error.name));
    const status = page.locator('#status');
    async function check(name, expected) {
      const actual = await status.textContent();
      checks.push({ name, pass: expected.test(actual) && !/synthetic-private|privateX|SSELEAK/.test(actual) });
    }
    await page.goto(origin + '/admin/debug-events');
    await status.filter({ hasText: '업데이트 완료' }).waitFor();
    for (const [fixtureMode, expected] of [['http-error', /http_500/], ['invalid-json', /invalid_json/]]) {
      mode = fixtureMode;
      await Promise.all([
        page.waitForResponse(response => new URL(response.url()).pathname === '/api/diagnostics/debug/events'),
        page.locator('#btnRefresh').click()
      ]);
      await status.filter({ hasText: '실패:' }).waitFor();
      await check(fixtureMode, expected);
    }
    mode = 'healthy';
    await page.locator('#live').check();
    await status.filter({ hasText: /^LIVE: connected$/ }).waitFor();
    mode = 'closed';
    for (const stream of streams) stream.end();
    await page.waitForFunction(() => es && es.readyState === EventSource.CLOSED);
    await check('closed-stream-status', /^LIVE: disconnected$/);
    await page.locator('#btnRefresh').click();
    await page.waitForFunction(() => !document.getElementById('status').textContent.includes('refresh...'));
    await check('refresh-does-not-invent-connection', /^LIVE: disconnected$/);
    await page.screenshot({ path: path.join(output, 'disconnected-after-refresh.png'), fullPage: true });
    await page.locator('#live').uncheck();
    await status.filter({ hasText: '업데이트 완료' }).waitFor();
    mode = 'healthy';
    await page.locator('#live').check();
    await status.filter({ hasText: /^LIVE: connected$/ }).waitFor();
    await page.screenshot({ path: path.join(output, 'reconnected.png'), fullPage: true });
    checks.push({ name: 'reconnect', pass: true });
    mode = 'delayed-snapshot';
    const held = new Promise(resolve => { snapshotArrived = resolve; });
    await page.locator('#live').uncheck();
    await held;
    mode = 'healthy';
    await page.locator('#live').check();
    await status.filter({ hasText: /^LIVE: connected$/ }).waitFor();
    for (const stream of streams) stream.write('event: debug-event\ndata: {"id":"probe-current-live","level":"INFO","message":"current live event"}\n\n');
    await page.locator('#listJson').filter({ hasText: 'probe-current-live' }).waitFor({ state: 'attached' });
    const lateResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/diagnostics/debug/events');
    delayedSnapshot.writeHead(200, { 'Content-Type': 'application/json' });
    delayedSnapshot.end('[{"id":"probe-stale-snapshot"}]');
    await (await lateResponse).finished();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const visibleEvents = JSON.parse(await page.locator('#listJson').textContent());
    checks.push({ name: 'late-http-snapshot-preserves-live-events', pass: visibleEvents.length === 1 && visibleEvents[0].id === 'probe-current-live' });
    await check('late-http-snapshot-preserves-live-status', /^LIVE: connected$/);
    for (const stream of streams) stream.write('event: debug-event\ndata: SSELEAK private stream payload\n\nevent: debug-event\ndata: {"id":"after-invalid","level":"INFO"}\n\n');
    await page.locator('#listJson').filter({ hasText: 'after-invalid' }).waitFor({ state: 'attached' });
    checks.push({ name: 'malformed-sse-does-not-expose-parser-excerpt', pass: !consoleMessages.some(x => x.includes('SSELEAK')) });
    await page.screenshot({ path: path.join(output, 'late-snapshot-live-preserved.png'), fullPage: true });
    mode = 'http-error';
    await page.goto(origin + '/admin/debug-events?live=1');
    await status.filter({ hasText: /^LIVE: connected$/ }).waitFor();
    await check('live-starts-despite-failed-initial-snapshot', /^LIVE: connected$/);
    checks.push({ name: 'no-private-console-excerpts', pass: !consoleMessages.some(x => /synthetic-private|privateX|SSELEAK/.test(x)) });
    checks.push({ name: 'no-uncaught-page-errors', pass: pageErrors.length === 0 });
    const report = { fixtureOnly: true, externalServicesCalled: false, decorativeCdnStubbed: true,
      checks, network, consolePrivateExcerptCount: consoleMessages.filter(x => /synthetic-private|privateX|SSELEAK/.test(x)).length,
      pageErrorCount: pageErrors.length };
    fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report, null, 2));
    assert.ok(checks.every(item => item.pass), 'browser regression checks failed; see count-only result.json');
  } finally {
    if (browser) await browser.close();
    for (const stream of streams) stream.end();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
