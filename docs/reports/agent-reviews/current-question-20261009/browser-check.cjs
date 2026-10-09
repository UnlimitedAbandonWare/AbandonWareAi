'use strict';
// Bounded, loopback-only verification; no auth state, raw streams, or private history saved.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../../..');
const { selectModel } = require(path.join(root, 'scripts/browser_model_select.js'));
const { safeMetadata, collectStream, ownedTrace } = require(path.join(root, 'scripts/chat_rag_golden_browser.js'));
const hash = value => value ? crypto.createHash('sha256').update(value).digest('hex').slice(0, 12) : null;
const base = 'http://127.0.0.1:18180';
const out = path.join(__dirname, 'browser-v2');
const run = 'cq-browser-retry-20261009';
const policy = spawnSync('python', ['-B', path.join(root, 'scripts/test_model_policy.py'),
  'resolve', '--purpose', 'regression', '--base', base, '--run', run], { cwd: root, encoding: 'utf8', timeout: 20000 });
const selection = JSON.parse((policy.stdout || '').trim().split('\n').pop());
if (policy.status !== 0 || selection.verdict !== 'RESOLVED' || !selection.selected) throw Error('policy_unavailable');
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const evidence = { checkedAt: new Date().toISOString(), base, requestedModel: selection.selected,
    restoredAuthState: false, maxGenerationPosts: 2, generationPosts: 0, scenarios: [] };
  try {
    for (const [id, question] of [['greeting', '안녕?'], ['general', '광합성이 무엇인지 한 문장으로 설명해줘.']]) {
      const context = await browser.newContext();
      const row = { id, freshContext: true, questionHash: hash(question), requests: [], consoleErrorCount: 0, failedRequestCount: 0 };
      try {
        await context.addInitScript(({ parserSource }) => {
          const parse = new Function('return (' + parserSource + ')')();
          const original = window.fetch.bind(window);
          window.__currentQuestionStreamReads = [];
          window.fetch = async (...args) => {
            const response = await original(...args);
            if (new URL(response.url, location.href).pathname === '/api/chat/stream') {
              const capture = { source: 'page_fetch_clone', bodyReadStatus: 'pending' };
              window.__currentQuestionStreamReads.push(response.clone().text().then(body => {
                capture.bodyReadStatus = 'success';
                return { metadata: parse(body, capture), capture };
              }, () => {
                capture.bodyReadStatus = 'failed'; capture.reason = 'page_body_read_failed';
                return { metadata: {}, capture };
              }));
            }
            return response;
          };
        }, { parserSource: safeMetadata.toString().replace('Buffer.byteLength(body)', 'new TextEncoder().encode(body).length') });
        await context.route('**/api/chat/**', async route => {
          const r = route.request(), endpoint = new URL(r.url()).pathname;
          if (r.method() === 'POST' && /^\/api\/chat\/(stream|sync)$/.test(endpoint)) {
            if (evidence.generationPosts >= 2) { row.budgetBlocked = true; return route.abort('blockedbyclient'); }
            evidence.generationPosts++;
          }
          return route.continue();
        });
        const page = await context.newPage();
        page.on('console', message => { if (message.type() === 'error') row.consoleErrorCount++; });
        page.on('requestfailed', () => row.failedRequestCount++);
        const captures = [];
        page.on('response', response => {
          const endpoint = new URL(response.url()).pathname;
          if (!/^\/api\/chat\/(stream|sync)$/.test(endpoint) || response.request().method() !== 'POST') return;
          const headers = response.headers();
          row.requests.push({ endpoint, status: response.status(),
            requestHash: hash(headers['x-request-id'] || headers['x-trace-id'] || '') });
          captures.push(collectStream(response));
        });
        await page.goto(base + '/chat', { waitUntil: 'domcontentloaded' });
        await page.waitForFunction(() => document.querySelector('#modelSelect')?.options.length > 1, null, { timeout: 15000 });
        await page.locator('#newChatBtn').click();
        row.selection = await selectModel(page, selection.selected);
        await page.locator('#modelSelectionMode').selectOption('strict');
        await page.locator('#searchModeSelect').selectOption('OFF');
        await page.locator('#useRagToggle').setChecked(false);
        const before = await page.locator('[data-message-role="assistant"]').count();
        await page.locator('#messageInput').fill(question);
        const response = page.waitForResponse(r => /^\/api\/chat\/(stream|sync)$/.test(new URL(r.url()).pathname)
          && r.request().method() === 'POST', { timeout: 90000 });
        await page.locator('#sendBtn').click();
        await response;
        await page.waitForFunction(() => {
          const stop = document.querySelector('#stopBtn');
          return !stop || stop.hidden || getComputedStyle(stop).display === 'none';
        }, null, { timeout: 120000 });
        row.protocolCaptures = (await Promise.all(captures)).map(r => r.capture);
        const results = await page.evaluate(async () => Promise.all(window.__currentQuestionStreamReads));
        const meta = Object.assign({}, ...results.map(r => r.metadata));
        row.streamCapture = results.map(r => r.capture);
        const trace = await ownedTrace(context, base, meta);
        row.metadata = Object.fromEntries(Object.entries(meta).filter(([key]) => !['sessionId', 'traceTurnId'].includes(key)));
        row.correlationHash = hash(meta.sessionId && meta.traceTurnId ? meta.sessionId + ':' + meta.traceTurnId : '');
        row.trace = { ...trace, traceTurnId: undefined };
        const assistants = page.locator('[data-message-role="assistant"]');
        row.assistantCreated = await assistants.count() > before;
        const answer = row.assistantCreated ? await assistants.last().innerText() : '';
        row.answerChars = answer.length; row.answerHash = hash(answer);
        row.holdDisplayed = /응답 본문을 보류|질문은 보류|\bHOLD\b/i.test(answer);
        row.backendUnavailable = meta.reasonCode === 'backend_unavailable' || /backend_unavailable/.test(answer);
        row.renderedBodyAvailable = row.assistantCreated && !!answer.trim() && !row.holdDisplayed && !row.backendUnavailable;
        if (row.assistantCreated) await assistants.last().screenshot({ path: path.join(out, id + '.png') });
        row.verdict = row.requests.some(r => r.status === 200) && row.renderedBodyAvailable && trace.matched
          && results.every(r => r.capture.bodyReadStatus === 'success') ? 'PASS' : 'FAIL';
        spawnSync('python', ['-B', path.join(root, 'scripts/test_model_policy.py'), 'record', '--agent', 'codex-current-question',
          '--purpose', 'regression', '--model', selection.selected, '--code', row.verdict === 'PASS' ? '0' : '1', '--run', run],
          { cwd: root, encoding: 'utf8', timeout: 10000 });
      } catch (error) { row.verdict = 'NOT_PROVEN'; row.errorClass = error.name; }
      finally { await context.close(); evidence.scenarios.push(row); fs.writeFileSync(path.join(out, 'evidence.json'), JSON.stringify(evidence, null, 2)); }
    }
  } finally { await browser.close(); }
  console.log(JSON.stringify({ generationPosts: evidence.generationPosts, scenarios: evidence.scenarios.map(r => ({ id: r.id,
    verdict: r.verdict, requests: r.requests, reasonCode: r.metadata?.reasonCode || null, releaseStatus: r.metadata?.releaseStatus || null,
    holdDisplayed: r.holdDisplayed, backendUnavailable: r.backendUnavailable, correlationHash: r.correlationHash })) }));
})().catch(error => { console.log(JSON.stringify({ verdict: 'NOT_PROVEN', errorClass: error.name })); process.exitCode = 1; });
