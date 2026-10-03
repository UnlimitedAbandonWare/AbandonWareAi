#!/usr/bin/env node
// Trace-dock browser probe. Reuses an already installed Playwright.
// Expected interval and call caps come from arguments. Before the three
// test ids exist, exit 3 and do not launch a browser or install anything.
const fs = require('node:fs');
const path = require('node:path');

const TESTIDS = ['trace-dock-toggle', 'trace-dock-current', 'trace-dock-history'];

function arg(name) {
  const index = process.argv.indexOf(name);
  if (index >= 0 && process.argv[index + 1] && !process.argv[index + 1].startsWith('--')) {
    return process.argv[index + 1];
  }
  return '';
}

function help() {
  console.log(`usage: node scripts/trace_dock_browser_probe.js [--root .]
  Before dock test ids exist: exit 3 (pre-implementation). No browser.
  After they exist, also pass --base-url http://127.0.0.1:<port> --interval-ms N
  --tolerance-ms N --max-calls N --observe-ms N --hidden-wait-ms N --hidden-max-calls N
  --screenshot <png> --out <json>
  Counts /events/page, refuses extra /api/chat/stream|sync and EventSource.
  Does not install Playwright. A missing module is exit 5.
  Synthetic visibilitychange is not a real OS tab switch.`);
}

function walk(dir, acc) {
  if (!fs.existsSync(dir)) return;
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'build') continue;
    const full = path.join(dir, name);
    const stat = fs.statSync(full);
    if (stat.isDirectory()) walk(full, acc);
    else if (/\.(js|html|css)$/i.test(name)) acc.push(full);
  }
}

function presentTestIds(root) {
  const files = [];
  walk(path.join(root, 'main', 'resources'), files);
  const blob = files.map(file => fs.readFileSync(file, 'utf8')).join('\n');
  return TESTIDS.filter(id => blob.includes(id));
}

function loopback(baseUrl) {
  let url;
  try { url = new URL(baseUrl); } catch { return false; }
  return url.protocol === 'http:' && (url.hostname === '127.0.0.1' || url.hostname === 'localhost');
}

async function main() {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    help();
    return 0;
  }
  const root = path.resolve(arg('--root') || '.');
  const present = presentTestIds(root);
  if (present.length === 0) {
    console.log(JSON.stringify({
      verdict: 'PRE_IMPL', exitCode: 3, reason: 'SELECTORS_ABSENT_PRE_PATCH',
      present, playwright: 'not-required'
    }));
    return 3;
  }
  const required = ['--base-url', '--interval-ms', '--tolerance-ms', '--max-calls',
    '--observe-ms', '--hidden-wait-ms', '--hidden-max-calls', '--screenshot', '--out'];
  const missing = required.filter(name => !arg(name));
  if (missing.length) {
    console.log(JSON.stringify({ verdict: 'USAGE', exitCode: 2, missing }));
    return 2;
  }
  const baseUrl = arg('--base-url');
  if (!loopback(baseUrl)) {
    console.log(JSON.stringify({ verdict: 'USAGE', exitCode: 2, reason: 'base-url-must-be-loopback' }));
    return 2;
  }
  let chromium;
  try {
    ({ chromium } = require('playwright'));
  } catch (error) {
    console.log(JSON.stringify({
      verdict: 'NOT_AVAILABLE', exitCode: 5, reason: 'playwright-missing',
      nodePath: process.env.NODE_PATH || ''
    }));
    return 5;
  }
  const intervalMs = Number(arg('--interval-ms'));
  const toleranceMs = Number(arg('--tolerance-ms'));
  const maxCalls = Number(arg('--max-calls'));
  const observeMs = Number(arg('--observe-ms'));
  const hiddenWaitMs = Number(arg('--hidden-wait-ms'));
  const hiddenMaxCalls = Number(arg('--hidden-max-calls'));
  const requests = [];
  const browser = await chromium.launch({ channel: 'msedge', headless: !process.argv.includes('--headed') });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    page.on('request', request => {
      const url = new URL(request.url());
      requests.push({
        path: url.pathname,
        search: url.search,
        resourceType: request.resourceType(),
        atMs: Date.now()
      });
    });
    await page.goto(new URL('/chat-ui', baseUrl).toString(), { waitUntil: 'domcontentloaded', timeout: 20000 });
    const found = {};
    for (const id of TESTIDS) {
      found[id] = await page.getByTestId(id).count();
    }
    const toggle = page.getByTestId('trace-dock-toggle');
    if (await toggle.count()) {
      await toggle.click();
    }
    const expanded = await toggle.count()
      ? await toggle.first().getAttribute('aria-expanded')
      : null;
    const started = Date.now();
    await page.waitForTimeout(observeMs);
    const visibleCalls = requests.filter(row => row.path.includes('/events/page') && row.atMs >= started);
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    const hiddenMark = Date.now();
    await page.waitForTimeout(hiddenWaitMs);
    const hiddenCalls = requests.filter(row => row.path.includes('/events/page') && row.atMs >= hiddenMark);
    const gaps = [];
    for (let i = 1; i < visibleCalls.length; i += 1) {
      gaps.push(visibleCalls[i].atMs - visibleCalls[i - 1].atMs);
    }
    const streamCalls = requests.filter(row => /\/api\/chat\/(stream|sync)/.test(row.path)).length;
    const eventSource = requests.filter(row => row.resourceType === 'eventsource'
      || row.path.includes('/events/stream')).length;
    const limits = visibleCalls.map(row => {
      const params = new URLSearchParams(row.search);
      return params.get('limit');
    });
    fs.mkdirSync(path.dirname(path.resolve(arg('--screenshot'))), { recursive: true });
    await page.screenshot({ path: path.resolve(arg('--screenshot')) });
    const problems = [];
    if (TESTIDS.some(id => !found[id])) problems.push('testid-missing');
    if (expanded == null) problems.push('aria-expanded-missing');
    if (streamCalls !== 0) problems.push('generation-call');
    if (eventSource !== 0) problems.push('event-source');
    if (visibleCalls.length > maxCalls) problems.push('max-calls');
    if (hiddenCalls.length > hiddenMaxCalls) problems.push('hidden-calls');
    if (gaps.some(gap => Math.abs(gap - intervalMs) > toleranceMs)) problems.push('interval');
    const report = {
      verdict: problems.length ? 'FAIL' : 'PASS',
      exitCode: problems.length ? 2 : 0,
      found, ariaExpanded: expanded, gaps, limits,
      visiblePageCalls: visibleCalls.length,
      hiddenPageCalls: hiddenCalls.length,
      streamCalls, eventSource, problems,
      screenshot: path.resolve(arg('--screenshot'))
    };
    fs.mkdirSync(path.dirname(path.resolve(arg('--out'))), { recursive: true });
    fs.writeFileSync(path.resolve(arg('--out')), JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ verdict: report.verdict, exitCode: report.exitCode, problems }));
    return report.exitCode;
  } finally {
    await browser.close();
  }
}

main().then(code => { process.exitCode = code; }).catch(error => {
  console.log(JSON.stringify({ verdict: 'ERROR', exitCode: 1, name: error && error.name }));
  process.exitCode = 1;
});
