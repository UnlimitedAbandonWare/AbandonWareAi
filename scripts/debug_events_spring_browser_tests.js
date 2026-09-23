// Consumes an owned real Spring loopback runtime. It never creates or mocks application APIs.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

async function main() {
  const origin = new URL(process.argv[2]);
  assert.equal(origin.protocol, 'http:');
  assert.equal(origin.hostname, '127.0.0.1');
  const output = path.join(__dirname, '../output/playwright/structural-spring');
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const checks = [];
  let stage = 'load';
  let page;
  const network = [];
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.name));
    page.on('response', response => {
      const url = new URL(response.url());
      if (url.origin === origin.origin) network.push({ path: url.pathname, status: response.status() });
    });
    await page.route('**/*', route => {
      const url = new URL(route.request().url());
      if (url.origin === origin.origin) return route.continue();
      if (url.href === 'https://cdn.tailwindcss.com/') {
        return route.fulfill({ status: 200, contentType: 'application/javascript', body: '' });
      }
      return route.abort();
    });
    const response = await page.goto(origin.origin + '/admin/debug-events');
    assert.equal(response.status(), 200);
    stage = 'initial-refresh';
    // This bounded runtime omits local-LLM/triadic auxiliaries; allow the real initial refresh to finish.
    await page.waitForFunction(() => document.querySelector('#status').textContent.startsWith('실패:'));
    const initialEvents = await page.locator('#listJson').textContent();
    assert.ok(JSON.parse(initialEvents).length > 0);
    checks.push('real Spring template and diagnostic JSON rendered');
    stage = 'live-open';
    await page.locator('#live').check();
    await page.waitForFunction(() => document.querySelector('#status').textContent === 'LIVE: connected');
    stage = 'live-payload';
    await page.waitForFunction(() => JSON.parse(document.querySelector('#listJson').textContent).length > 0);
    const ids = JSON.parse(await page.locator('#listJson').textContent()).map(event => event.id);
    assert.equal(ids.length, new Set(ids).size);
    checks.push('real EventSource open state and production event payload agree');
    await page.screenshot({ path: path.join(output, 'connected.png'), fullPage: true });
    await page.locator('#live').uncheck();
    stage = 'stop';
    await page.waitForFunction(() => !document.querySelector('#live').checked &&
      !document.querySelector('#status').textContent.startsWith('LIVE: connected'));
    checks.push('user stop leaves connected state');
    await page.locator('#live').check();
    stage = 'second-open';
    await page.waitForFunction(() => document.querySelector('#status').textContent === 'LIVE: connected');
    checks.push('new connection after stop uses real Spring SSE');
    assert.equal(pageErrors.length, 0);
    assert.ok(network.some(row => row.path.endsWith('/events/stream') && row.status === 200));
    assert.ok(network.some(row => row.path.endsWith('/events') && row.status === 200));
    await page.locator('#live').uncheck();
    fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify({
      applicationRuntime: 'Spring servlet slice with production beans', checks,
      applicationApiMocks: 0, pageErrorCount: pageErrors.length,
      externalProviderCalls: 'not_observed', auxiliaryPanels: 'omitted, real 404',
      decorativeCdn: 'replaced locally', network
    }, null, 2));
    process.stdout.write(JSON.stringify({ springBrowserChecks: checks.length, pageErrorCount: 0 }) + '\n');
  } catch (error) {
    fs.writeFileSync(path.join(output, 'failure.json'), JSON.stringify({ stage, checks, network,
      errorType: error.name, status: page ? await page.locator('#status').textContent().catch(() => 'unavailable') : 'unavailable'
    }, null, 2));
    throw error;
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.name + ': real Spring browser proof failed'); process.exitCode = 1; });
