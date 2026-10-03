'use strict';
// chat_practice_browser — /chat practice/test runs with policy-chosen models.
// Part of configs/agent-test-model-policy.yaml tooling
// (DEMO1-DEVIN-CHAT-PRACTICE-MODEL-POLICY-20261002).
//
// Flow per question: `test_model_policy.py plan` (auto classify -> resolve) ->
// new conversation when the selected model changes -> selectModel via
// browser_model_select.js -> send -> observed-model `check` -> `record`.
// Report: data/agent-handoff/test-model-policy/practice-<ts>.md (no answer text).
//
//   node scripts/chat_practice_browser.js --prompts q.json|q.txt
//     [--base http://127.0.0.1:18180] [--driver browser|sync] [--max-calls 10]
//     [--run <ledgerId>] [--public] [--headful] [--prompt "text"]
//
// Public URL only with --public (adds [devin-test] prefix, caps sends at 3).
// 401/403/429 never retry the same model — one re-resolve to the next rank.
// Driver `browser` needs playwright; when absent the run reports NOT_RUN.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const POLICY = path.join(ROOT, 'scripts', 'test_model_policy.py');
const LEDGER = path.join(ROOT, 'data', 'agent-handoff', 'test-model-policy');
const LOCAL_DEFAULT = 'http://127.0.0.1:18180';
const PUBLIC_MAX_SENDS = 3;
const PUBLIC_PREFIX = '[devin-test] ';
const AUTH_QUOTA = new Set([401, 403, 429]);
// 디버그 기간 채팅 대기 상한 = chat.run.max-duration-seconds + 20s 여유.
const CHAT_RUN_MAX_MS = (() => { const s = Number(process.env.CHAT_RUN_MAX_DURATION_SECONDS); return Number.isFinite(s) && s > 0 ? s * 1000 : 600000; })();
const ANSWER_WAIT_MS = CHAT_RUN_MAX_MS + 20000;

function parseArgs(argv) {
  const a = { prompts: null, prompt: null, base: LOCAL_DEFAULT, public: false,
              maxCalls: 10, run: null, driver: 'browser', headful: false };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k === '--prompts') a.prompts = argv[++i];
    else if (k === '--prompt') a.prompt = argv[++i];
    else if (k === '--base') a.base = argv[++i];
    else if (k === '--public') a.public = true;
    else if (k === '--max-calls') a.maxCalls = Number(argv[++i]);
    else if (k === '--run') a.run = argv[++i];
    else if (k === '--driver') a.driver = argv[++i];
    else if (k === '--headful') a.headful = true;
    else throw Error('unknown_arg:' + k);
  }
  if (!a.prompts && !a.prompt) throw Error('prompts_required');
  const url = new URL(a.base);
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if (!loopback && !a.public) throw Error('PUBLIC_REQUIRES_FLAG');
  if (!['http:', 'https:'].includes(url.protocol)) throw Error('bad_scheme');
  if (url.username || url.password || (url.pathname !== '/' && url.pathname !== ''))
    throw Error('base_must_be_origin');
  if (!Number.isInteger(a.maxCalls) || a.maxCalls < 1 || a.maxCalls > 50)
    throw Error('invalid_max_calls');
  if (a.public) a.maxCalls = Math.min(a.maxCalls, PUBLIC_MAX_SENDS);
  if (!['browser', 'sync'].includes(a.driver)) throw Error('invalid_driver');
  a.base = url.origin;
  return a;
}

function loadPromptRows(file) {
  const raw = fs.readFileSync(file, 'utf8');
  if (file.toLowerCase().endsWith('.json')) {
    const data = JSON.parse(raw);
    if (!Array.isArray(data)) throw Error('prompts_json_must_be_array');
    return data.map(r => typeof r === 'string'
      ? { text: r, attachment: false }
      : { text: String(r.text || r.prompt || r.question || ''),
          attachment: !!(r.attachment || r.hasAttachment) });
  }
  return raw.split(/\r?\n/).filter(l => l.trim())
    .map(l => ({ text: l.trim(), attachment: false }));
}

function policyCli(args) {
  const out = spawnSync('python', ['-B', POLICY].concat(args),
    { encoding: 'utf8', cwd: ROOT });
  const lines = (out.stdout || '').trim().split('\n').filter(Boolean);
  let parsed = {};
  try { parsed = lines.length ? JSON.parse(lines[lines.length - 1]) : {}; }
  catch (e) { parsed = { verdict: 'PARSE_ERROR', error: String(e).slice(0, 120) }; }
  return { parsed, status: out.status };
}

function planItems(args) {
  let file = args.prompts;
  if (args.prompt) {
    fs.mkdirSync(LEDGER, { recursive: true });
    file = path.join(LEDGER, '.practice-prompt-' + Date.now() + '.json');
    fs.writeFileSync(file, JSON.stringify([{ text: args.prompt, attachment: false }]));
  }
  return policyCli(['plan', '--prompts-file', file, '--base', args.base]).parsed;
}

function budgetOk(run) {
  return policyCli(['budget', '--run', run]).parsed.verdict !== 'BUDGET_EXCEEDED';
}

function recordRow(run, purpose, model, code) {
  policyCli(['record', '--agent', 'devin-practice', '--purpose', purpose,
    '--model', model, '--code', String(code), '--run', run]);
}

async function sendSync(base, model, message) {
  const t0 = Date.now();
  try {
    const resp = await fetch(base + '/api/chat/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ message, question: message, model,
        strictModelSelection: true, useRag: false, useWebSearch: false,
        searchMode: 'OFF', maxTokens: 64 }),
    });
    let observed = resp.headers.get('x-model-used');
    try {
      const data = await resp.json();
      observed = data.modelUsed || data.observedModel || data.actualModel || observed;
    } catch (e) { /* non-json body */ }
    return { http: resp.status, observed, ms: Date.now() - t0 };
  } catch (e) {
    return { http: 'ERR:' + e.name, observed: null, ms: Date.now() - t0 };
  }
}

async function sendBrowser(page, base, model, message) {
  const { selectModel, readObservedModel } = require('./browser_model_select.js');
  const select = await selectModel(page, model);
  const t0 = Date.now();
  const responsePromise = page.waitForResponse(
    r => r.url().startsWith(base + '/api/chat/stream') ||
         r.url().startsWith(base + '/api/chat/sync'),
    { timeout: ANSWER_WAIT_MS });
  await page.locator('#messageInput').fill(message);
  await page.locator('#sendBtn').click();
  const response = await responsePromise;
  await page.waitForTimeout(2000);
  const observed = response.headers()['x-model-used'] || await readObservedModel(page);
  return { http: response.status(), observed, ms: Date.now() - t0, select };
}

async function runOnce(args) {
  const run = args.run || ('practice-' + Date.now());
  const rows = args.prompts ? loadPromptRows(args.prompts)
                            : [{ text: args.prompt, attachment: false }];
  const planned = planItems(args);
  const out = { tool: 'chat_practice_browser', base: args.base, run,
    driver: args.driver, public: args.public, maxCalls: args.maxCalls,
    plan: planned.verdict, mode: planned.mode, calls: 0, items: [] };
  if (planned.verdict !== 'PLANNED') { out.verdict = planned.verdict; return out; }

  let calls = 0, browser = null, page = null, pageReady = false;
  if (args.driver === 'browser') {
    let chromium;
    try { ({ chromium } = require('playwright')); }
    catch (e) { out.verdict = 'NOT_RUN'; out.reason = 'PLAYWRIGHT_MISSING'; return out; }
    browser = await chromium.launch({ channel: 'msedge', headless: !args.headful });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    page = await context.newPage();
    await page.route('**/*', route => {   // hard send budget at the wire
      const req = route.request();
      if (req.method() === 'POST' &&
          /\/api\/chat\/(?:stream|sync)$/.test(new URL(req.url()).pathname) &&
          calls >= args.maxCalls) return route.abort('blockedbyclient');
      return route.continue();
    });
  }

  try {
    for (const item of planned.items) {
      const row = { index: item.index, prompt: item.promptSummary,
        purpose: item.purpose, autoReason: item.autoReason,
        selected: item.selected, newConversation: item.newConversation };
      out.items.push(row);
      if (item.verdict !== 'RESOLVED' || !item.selected) { row.result = item.verdict; continue; }
      const text = rows[item.index] ? rows[item.index].text : item.promptSummary;
      const message = (args.public ? PUBLIC_PREFIX : '') + text;

      let attempts = 0, done = false;
      while (attempts < 2 && !done) {
        attempts++;
        if (calls >= args.maxCalls || !budgetOk(run)) { row.result = 'BUDGET_EXCEEDED'; break; }
        let sent;
        if (args.driver === 'browser') {
          if (!pageReady) { await page.goto(args.base + '/chat'); pageReady = true; }
          else if (row.newConversation) { await page.locator('#newChatBtn').click(); await page.waitForTimeout(500); }
          try { sent = await sendBrowser(page, args.base, item.selected, message); }
          catch (e) { sent = { http: 'ERR:' + e.name, observed: null, ms: 0 }; }
        } else {
          sent = await sendSync(args.base, item.selected, message);
        }
        calls++;
        recordRow(run, item.purpose, item.selected, sent.http);
        row.http = sent.http; row.ms = sent.ms; row.observed = sent.observed;
        if (AUTH_QUOTA.has(sent.http)) {
          const again = policyCli(['resolve', '--purpose', item.purpose,
            '--base', args.base]).parsed;
          row.authOrQuota = sent.http;
          if (again.verdict === 'RESOLVED' && again.selected !== item.selected
              && attempts < 2) {
            item.selected = again.selected;
            row.selected = again.selected; row.newConversation = true;
            continue;   // one re-resolve to the next rank, never same model
          }
          row.result = 'BLOCKED_API';
          break;
        }
        row.result = policyCli(['check', '--selected', item.selected,
          '--observed', String(sent.observed || '')]).parsed.verdict;
        done = true;
      }
      if (!row.result) row.result = 'NO_SEND';
    }
  } finally {
    if (browser) await browser.close();
  }

  out.calls = calls;
  out.verdict = out.items.every(i => i.result === 'OK') ? 'ALL_OK' : 'MIXED';
  fs.mkdirSync(LEDGER, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const reportPath = path.join(LEDGER, 'practice-' + stamp + '.md');
  const lines = ['# practice ' + stamp, '',
    'base=' + args.base + ' run=' + run + ' driver=' + args.driver +
    ' mode=' + planned.mode + ' calls=' + calls + '/' + args.maxCalls, '',
    '| # | prompt(40) | purpose | auto | selected | observed | http | ms | newConv | result |',
    '|---|---|---|---|---|---|---|---|---|---|'];
  for (const i of out.items)
    lines.push('| ' + i.index + ' | ' + String(i.prompt).replace(/\|/g, '/') +
      ' | ' + i.purpose + ' | ' + (i.autoReason || '') + ' | ' + (i.selected || '-') +
      ' | ' + (i.observed || '-') + ' | ' + (i.http || '-') + ' | ' + (i.ms || '-') +
      ' | ' + i.newConversation + ' | ' + i.result + ' |');
  fs.writeFileSync(reportPath, lines.join('\n') + '\n');
  out.report = reportPath;
  return out;
}

module.exports = { parseArgs, loadPromptRows };
if (require.main === module) {
  runOnce(parseArgs(process.argv.slice(2)))
    .then(out => {
      process.stdout.write(JSON.stringify(out) + '\n');
      if (out.verdict === 'NOT_RUN') process.exitCode = 5;
      else if (out.verdict !== 'ALL_OK' && out.verdict !== 'MIXED') process.exitCode = 6;
    })
    .catch(e => { console.error(e.name + ': ' + e.message); process.exitCode = 1; });
}
