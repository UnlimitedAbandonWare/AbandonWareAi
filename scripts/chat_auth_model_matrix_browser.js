'use strict';
// chat_auth_model_matrix_browser — /chat post-fix browser matrix for Codex.
// Runs (policy-chosen auth model) x (varied prompt) x (symptom gesture) cells,
// then reports PASS/FAIL/NOT_OBSERVED per cell so only failing cells get
// re-patched. Reuses existing modules — no re-implemented seams:
//   browser_model_select.js      selectModel / readObservedModel
//   chat_practice_browser.js     loadPromptRows (prompt file compat)
//   chat_rag_golden_browser.js   SendBudget / guardGenerationRoute / judge /
//                                collectStream / safeMetadata
//   chat_ui_browser_fault_fixture.js  createFaultFixture (needsModelCall=false)
//   test_model_policy.py         resolve / check / record / budget
//
//   node scripts/chat_auth_model_matrix_browser.js            # --dry-run plan
//     [--live] [--fixture] [--tier smoke|regression|full]
//     [--models a,b | --purpose p] [--prompts f.json] [--gestures G01,G03]
//     [--only-failed prev.json] [--max-gen N<=25] [--base url] [--public]
//     [--headful] [--catalog f.json] [--usage-log f] [--run id]
//
// Default is --dry-run: prints the cell plan and writes matrix-<ts>.{json,md}
// with 0 generations. Answer text is never stored; screenshots only.
// 401/403/429: that model's cells stop, next policy rank tried once, never a
// same-model retry. Without --public the base must be loopback; --public caps
// sends at 3 and prefixes [devin-test] (not used in the build directive).
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawnSync } = require('node:child_process');
const { selectModel, readObservedModel, bareModelId } = require('./browser_model_select.js');
const { loadPromptRows } = require('./chat_practice_browser.js');
const { SendBudget, guardGenerationRoute, judge, collectStream } = require('./chat_rag_golden_browser.js');
const { createFaultFixture } = require('./chat_ui_browser_fault_fixture.js');

const ROOT = path.resolve(__dirname, '..');
const POLICY_CLI = path.join(ROOT, 'scripts', 'test_model_policy.py');
const POLICY_YAML = path.join(ROOT, 'configs', 'agent-test-model-policy.yaml');
const DEFAULT_GESTURES = path.join(ROOT, 'configs', 'browser-gesture-catalog.yaml');
const DEFAULT_PROMPTS = path.join(ROOT, 'configs', 'chat-test-prompts-varied.json');
const LEDGER = path.join(ROOT, 'data', 'agent-handoff', 'test-model-policy');
const LOCAL_DEFAULT = 'http://127.0.0.1:18180';
const POLICY_MAX_GEN = 25;
const PUBLIC_MAX_SENDS = 3;
const PUBLIC_PREFIX = '[devin-test] ';
const AUTH_QUOTA = new Set([401, 403, 429]);
// 디버그 기간 채팅 대기 상한 = chat.run.max-duration-seconds + 20s 여유.
const CHAT_RUN_MAX_MS = (() => { const s = Number(process.env.CHAT_RUN_MAX_DURATION_SECONDS); return Number.isFinite(s) && s > 0 ? s * 1000 : 600000; })();
const ANSWER_WAIT_MS = CHAT_RUN_MAX_MS + 20000;
const TIERS = {
  smoke: { models: 2, prompts: 4, purpose: 'smoke' },
  regression: { models: 3, prompts: 5, purpose: 'regression' },
  full: { models: 3, prompts: 8, purpose: 'regression' },
};
const INTERNAL_LEAK = /backend_timeout|stream_failed|message_failed|Traceback|^\s*at\s+[\w.$]+\([^)]*:\d+\)|\[Agent-[A-Za-z0-9_-]+\]/m;
const PREPARING = /Assistant is preparing|답변을 준비/;

// ---------- strict-subset YAML reader (same subset test_model_policy.py uses)
function yamlScalar(v) {
  if (v.startsWith('[') && v.endsWith(']')) {
    const inner = v.slice(1, -1).trim();
    return inner ? inner.split(',').map(x => yamlScalar(x.trim())) : [];
  }
  if (v.length >= 2 && v[0] === v[v.length - 1] && (v[0] === '"' || v[0] === "'")) return v.slice(1, -1);
  if (v === 'null' || v === '~' || v === 'None') return null;
  if (v.toLowerCase() === 'true') return true;
  if (v.toLowerCase() === 'false') return false;
  const n = Number(v);
  return Number.isNaN(n) ? v : n;
}
function loadStrictYaml(file) {
  const items = [];
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.includes('#') ? raw.slice(0, raw.indexOf('#')) : raw;
    if (!line.trim()) continue;
    items.push([line.length - line.trimStart().length, line.trim()]);
  }
  let pos = 0;
  function parse(indent) {
    let out = null;
    while (pos < items.length) {
      const [ind, content] = items[pos];
      if (ind < indent) break;
      if (ind > indent) throw Error('yaml_indent:' + content.slice(0, 40));
      if (content.startsWith('- ')) {
        if (out === null) out = [];
        out.push(yamlScalar(content.slice(2).trim())); pos++; continue;
      }
      if (out === null) out = {};
      const i = content.indexOf(':'), key = content.slice(0, i).trim();
      const val = content.slice(i + 1).trim();
      pos++;
      out[key] = val === '' ? (pos < items.length && items[pos][0] > ind ? parse(items[pos][0]) : null)
                            : yamlScalar(val);
    }
    return out;
  }
  return items.length ? parse(items[0][0]) : {};
}

// ---------- args -------------------------------------------------------------
function parseArgs(argv) {
  const a = { tier: 'smoke', models: null, purpose: null, gestures: null, prompts: null,
              catalog: null, usageLog: null, dryRun: true, live: false, fixture: false,
              maxGen: POLICY_MAX_GEN, base: LOCAL_DEFAULT, public: false, headful: false,
              onlyFailed: null, run: null };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k === '--tier') a.tier = argv[++i];
    else if (k === '--models') a.models = argv[++i].split(',').map(s => s.trim()).filter(Boolean);
    else if (k === '--purpose') a.purpose = argv[++i];
    else if (k === '--gestures') a.gestures = argv[++i].split(',').map(s => s.trim()).filter(Boolean);
    else if (k === '--prompts') a.prompts = argv[++i];
    else if (k === '--catalog') a.catalog = argv[++i];
    else if (k === '--usage-log') a.usageLog = argv[++i];
    else if (k === '--dry-run') a.dryRun = true;
    else if (k === '--live') { a.live = true; a.dryRun = false; }
    else if (k === '--fixture') a.fixture = true;
    else if (k === '--max-gen') a.maxGen = Number(argv[++i]);
    else if (k === '--base') a.base = argv[++i];
    else if (k === '--public') a.public = true;
    else if (k === '--headful') a.headful = true;
    else if (k === '--only-failed') a.onlyFailed = argv[++i];
    else if (k === '--run') a.run = argv[++i];
    else throw Error('unknown_arg:' + k);
  }
  if (!TIERS[a.tier]) throw Error('invalid_tier');
  if (!Number.isInteger(a.maxGen) || a.maxGen < 1 || a.maxGen > POLICY_MAX_GEN)
    throw Error('invalid_max_gen_exceeds_policy_25');
  if (a.models && a.models.length === 0) throw Error('empty_models');
  if (a.onlyFailed && !fs.existsSync(a.onlyFailed)) throw Error('only_failed_not_found');
  const url = new URL(a.base);
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if (!loopback && !a.public) throw Error('PUBLIC_REQUIRES_FLAG');
  if (!['http:', 'https:'].includes(url.protocol)) throw Error('bad_scheme');
  if (url.username || url.password || (url.pathname !== '/' && url.pathname !== ''))
    throw Error('base_must_be_origin');
  if (a.public) a.maxGen = Math.min(a.maxGen, PUBLIC_MAX_SENDS);
  a.base = url.origin;
  return a;
}

// ---------- policy + catalog --------------------------------------------------
function policyCli(args) {
  const out = spawnSync('python', ['-B', POLICY_CLI].concat(args),
    { encoding: 'utf8', cwd: ROOT });
  const lines = (out.stdout || '').trim().split('\n').filter(Boolean);
  let parsed = {};
  try { parsed = lines.length ? JSON.parse(lines[lines.length - 1]) : {}; }
  catch (e) { parsed = { verdict: 'PARSE_ERROR', error: String(e).slice(0, 120) }; }
  return { parsed, status: out.status };
}
function policyArgs(a, extra) {
  const args = extra.slice();
  if (a.catalog) args.push('--catalog', a.catalog);
  if (a.usageLog) args.push('--usage-log', a.usageLog);
  return args;
}
function policyNeverSelect() {
  try { return new Set(loadStrictYaml(POLICY_YAML).neverSelect || []); }
  catch (e) { return new Set(['chatgpt-oauth:codex-auto-review']); }
}
async function fetchCatalog(a) {
  if (a.catalog) return JSON.parse(fs.readFileSync(a.catalog, 'utf8'));
  const resp = await fetch(a.base + '/api/chat/models');
  if (!resp.ok) throw Error('MODEL_CATALOG_HTTP_' + resp.status);
  return resp.json();
}

// ---------- corpus ------------------------------------------------------------
const KOREAN_PARAGRAPH =
  '이 프로젝트는 로컬 우선 RAG 오케스트레이션 데모로 전사 입력부터 검색, 근거 수집, ' +
  '모델 라우팅, 렌즈 표시까지 한 파이프라인으로 검증한다. 에이전트는 정책이 정한 ' +
  '모델만 사용하고 실패 문구와 내부 코드가 대화 말풍선에 새지 않는지 확인한다. ';
function materializeGenerator(gen) {
  if (!gen || gen.kind !== 'korean_paragraph_repeat') throw Error('unknown_generator');
  const chars = Number(gen.chars);
  if (!Number.isInteger(chars) || chars < 1 || chars > 20000) throw Error('bad_generator_chars');
  let text = '';
  while (text.length < chars) text += KOREAN_PARAGRAPH;
  return text.slice(0, chars) + (gen.suffix ? '\n' + gen.suffix : '');
}
function loadCorpus(a) {
  const file = a.prompts || DEFAULT_PROMPTS;
  if (a.prompts && !a.prompts.endsWith('varied.json')) {
    return loadPromptRows(file).map((r, i) => ({ id: 'P' + i, category: 'external',
      text: r.text, purposeHint: 'practice_chat', gestureIds: ['G01'] }));
  }
  const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
  const rows = Array.isArray(doc) ? doc : doc.prompts;
  if (!Array.isArray(rows)) throw Error('corpus_must_be_array');
  return rows.map(r => ({ id: r.id, category: r.category,
    text: r.generator ? materializeGenerator(r.generator) : String(r.text || ''),
    turns: r.turns || null, purposeHint: r.purposeHint || 'practice_chat',
    gestureIds: r.gestureIds || ['G01'] }));
}
function pickPrompts(rows, count) {
  const byCat = new Map();
  for (const r of rows) {
    if (!byCat.has(r.category)) byCat.set(r.category, []);
    byCat.get(r.category).push(r);
  }
  const cats = [...byCat.keys()], picked = [];
  for (let round = 0; picked.length < count && round < 50; round++)
    for (const c of cats) {
      if (picked.length >= count) break;
      const row = byCat.get(c)[round];
      if (row && !picked.includes(row)) picked.push(row);
    }
  return picked;
}

// ---------- plan --------------------------------------------------------------
function resolveModels(a, catalogRows) {
  const never = policyNeverSelect();
  if (a.models) {
    for (const m of a.models)
      if (never.has(m)) throw Error('never_select_model:' + m);
    return { models: a.models.slice(), purpose: a.purpose || 'explicit', via: 'explicit' };
  }
  const purpose = a.purpose || TIERS[a.tier].purpose;
  const res = policyCli(policyArgs(a, ['resolve', '--purpose', purpose, '--base', a.base]));
  if (res.parsed.verdict !== 'RESOLVED') return { models: [], purpose, via: 'policy', verdict: res.parsed.verdict };
  const ordered = [res.parsed.selected].concat(res.parsed.alternates || [])
    .filter(m => m && !never.has(m));
  return { models: ordered.slice(0, TIERS[a.tier].models), purpose, via: 'policy', candidates: ordered };
}
function buildPlan(a, opts) {
  const rows = loadCorpus(a);
  const gc = loadStrictYaml(opts.gestureFile || DEFAULT_GESTURES);
  const gestures = gc.gestures || {};
  for (const g of (a.gestures || []))
    if (!gestures[g]) throw Error('unknown_gesture:' + g);
  const selectedGestures = a.gestures || Object.keys(gestures);
  const fixtureGestures = selectedGestures.filter(g => gestures[g] && gestures[g].needsModelCall === false);
  const liveGestures = selectedGestures.filter(g => gestures[g] && gestures[g].needsModelCall !== false);

  let wantedCells = null;
  if (a.onlyFailed) {
    const prev = JSON.parse(fs.readFileSync(a.onlyFailed, 'utf8'));
    wantedCells = new Set((prev.cells || [])
      .filter(c => c.judge === 'FAIL' || c.judge === 'NOT_OBSERVED' || c.verdict === 'FAIL')
      .map(c => (c.kind || 'live') + '|' + (c.model && c.model.requested || c.gestureId || '') + '|' + (c.promptId || '')));
  }

  const promptCount = Math.max(1, Math.min(TIERS[a.tier].prompts,
    Math.floor(a.maxGen / Math.max(1, (a.models || []).length || TIERS[a.tier].models))));
  const prompts = pickPrompts(rows, promptCount);
  const cells = [], fixtureCells = [];
  let genPlanned = 0;
  for (const m of opts.models)
    for (const r of prompts) {
      const cellGen = r.turns ? r.turns.length : 1;
      if (genPlanned + cellGen > a.maxGen) continue;   // never plan over the cap
      const gids = r.gestureIds.filter(g => selectedGestures.includes(g));
      const key = 'live|' + m + '|' + r.id;
      if (wantedCells && !wantedCells.has(key)) continue;
      genPlanned += cellGen;
      cells.push({ kind: 'live', model: { requested: m }, promptId: r.id,
        purpose: r.purposeHint, gestureIds: gids.length ? gids : liveGestures.slice(0, 1),
        turns: cellGen, promptChars: r.text.length, judge: 'PLANNED' });
    }
  for (const g of fixtureGestures) {
    const key = 'fixture|' + g + '|';
    if (wantedCells && !wantedCells.has(key)) continue;
    fixtureCells.push({ kind: 'fixture', gestureId: g, name: gestures[g].name,
      scenario: gestures[g].fixtureScenario, judge: 'PLANNED' });
  }
  return { rows, gestures, prompts, models: opts.models, cells, fixtureCells, genPlanned };
}

// ---------- DOM snapshot + assert judge (pure, unit-testable) -----------------
function rectsOverlap(r, s) {
  return r && s && r.x < s.x + s.w && s.x < r.x + r.w && r.y < s.y + s.h && s.y < r.y + r.h;
}
// snapshot = {assistantBubbles:[{state,text,rect}], userBubbles:[{rect}],
//   noticeBoxes:[{rect}], inputBar:{rect,overflows}, statusBarText, lateTokensAfterStop}
function judgeSnapshot(snapshot, asserts) {
  const b = snapshot.assistantBubbles || [], u = snapshot.userBubbles || [];
  const counts = {
    pendingAssistant: b.filter(x => x.state === 'pending').length,
    preparingText: b.filter(x => PREPARING.test(x.text || '')).length,
    internalLeak: b.filter(x => INTERNAL_LEAK.test(x.text || '')).length,
    orderViolation: u.filter((ur, i) => b[i] && b[i].rect && ur.rect && b[i].rect.y < ur.rect.y).length,
    overlapPairs: 0,
    lateTokensAfterStop: snapshot.lateTokensAfterStop || 0,
    duplicateAnswer: Math.max(0, b.length - u.length),
    layoutBreak: (snapshot.inputBar && snapshot.inputBar.overflows ? 1 : 0),
  };
  const elems = b.map(x => x.rect).concat(u.map(x => x.rect), (snapshot.noticeBoxes || []).map(x => x.rect))
    .filter(Boolean);
  for (let i = 0; i < elems.length; i++)
    for (let j = i + 1; j < elems.length; j++)
      if (rectsOverlap(elems[i], elems[j])) counts.overlapPairs++;
  if (snapshot.inputBar && snapshot.inputBar.rect)
    for (const e of elems) if (rectsOverlap(e, snapshot.inputBar.rect)) counts.layoutBreak++;
  const reasons = [];
  for (const [k, max] of Object.entries(asserts || {}))
    if ((counts[k] || 0) > max) reasons.push(k + ':' + counts[k] + '>' + max);
  return { verdict: reasons.length ? 'FAIL' : 'PASS', reasons, counts };
}
const SNAPSHOT_JS = `(() => {
  const R = el => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; };
  const bubbles = s => [...document.querySelectorAll(s)].map(el => ({
    state: el.getAttribute('data-state') || '', text: (el.innerText || '').slice(0, 400), rect: R(el) }));
  const bar = document.querySelector('#messageInput') || document.querySelector('textarea');
  const wrap = document.querySelector('#messages') || document.body;
  return {
    assistantBubbles: bubbles('.message.assistant, [data-message-role="assistant"]'),
    userBubbles: bubbles('.message.user, [data-message-role="user"]'),
    noticeBoxes: bubbles('.notice, .alert, .toast').filter(x => x.rect.w > 0 && x.rect.h > 0),
    inputBar: bar ? { rect: R(bar), overflows: bar.scrollWidth > bar.clientWidth + 4 || wrap.scrollWidth > wrap.clientWidth + 4 } : null,
    statusBarText: (document.querySelector('#modelStatus,#traceStatus') || {}).innerText || ''
  };})()`;
async function collectChatSnapshot(page) {
  return page.evaluate(SNAPSHOT_JS);
}

// ---------- runners -----------------------------------------------------------
async function runFixtureCell(page, cell, gestures) {
  const g = gestures[cell.gestureId];
  await page.locator('#messageInput').fill('matrix fixture probe');
  await page.locator('#sendBtn').click();
  let lateTokens = 0;
  if (cell.gestureId === 'G04') {
    await page.waitForTimeout(300);
    const stop = page.locator('#stopBtn');
    if (await stop.count()) await stop.click().catch(() => {});
    const before = await collectChatSnapshot(page);
    await page.waitForTimeout(1600);
    const after = await collectChatSnapshot(page);
    const txt = x => (x.assistantBubbles || []).map(b => b.text).join('').length;
    lateTokens = txt(after) > txt(before) ? 1 : 0;
  } else {
    await page.waitForTimeout(1500);
  }
  const snap = await collectChatSnapshot(page);
  snap.lateTokensAfterStop = lateTokens;
  const judged = judgeSnapshot(snap, g.asserts);
  return { ...cell, httpStatus: 200, reasonCode: 'fixture:' + cell.scenario,
    requestId: null, judge: judged.verdict, reasons: judged.reasons, counts: judged.counts };
}
async function runLiveCell(page, context, a, cell, corpus, gestures, budget, out) {
  const row = corpus.find(r => r.id === cell.promptId);
  const text = (a.public ? PUBLIC_PREFIX : '') + (row ? row.text : cell.promptId);
  const turns = row && row.turns ? row.turns : [text];
  let httpStatus = null, reasonCode = null, requestId = null, observed = null;
  const captured = [];
  const onResponse = resp => {
    const p = new URL(resp.url()).pathname;
    if (!/^\/api\/chat\/(?:stream|sync)$/.test(p)) return;
    httpStatus = resp.status();
    const h = resp.headers();
    requestId = h['x-request-id'] || h['x-trace-id'] || requestId;
    observed = h['x-model-used'] || observed;
    captured.push(collectStream(resp).then(s => {
      reasonCode = s.metadata.reasonCode || reasonCode;
      observed = s.metadata.observedModel || s.metadata.claimedModel || observed;
    }));
  };
  page.on('response', onResponse);
  try {
    const sel = await selectModel(page, cell.model.requested);
    cell.selectApplied = sel.applied;
    const gids = cell.gestureIds || [];
    await page.locator('#messageInput').fill(turns[0]);
    const sendPromise = page.waitForResponse(
      r => /^\/api\/chat\/(?:stream|sync)$/.test(new URL(r.url()).pathname),
      { timeout: ANSWER_WAIT_MS }).catch(() => null);
    await page.locator('#sendBtn').click();
    if (gids.includes('G07')) {
      await page.waitForTimeout(300);
      await page.locator('#messageInput').fill(turns[0] + ' (2nd)');
      if (budget.used < budget.max) await page.locator('#sendBtn').click();
    }
    if (gids.includes('G04')) {
      await page.waitForTimeout(800);
      const stop = page.locator('#stopBtn');
      if (await stop.count()) await stop.click().catch(() => {});
    }
    if (gids.includes('G05')) {
      const alt = (cell.alternates || []).find(m => m !== cell.model.requested);
      if (alt) { await page.waitForTimeout(600); await selectModel(page, alt).catch(() => {}); }
    }
    if (gids.includes('G06')) {
      await page.waitForTimeout(600);
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1500);
    }
    const resp = await sendPromise;
    if (resp) httpStatus = resp.status();
    for (let t = 1; t < turns.length && budget.used < budget.max; t++) {
      await page.waitForTimeout(800);
      await page.locator('#messageInput').fill(turns[t]);
      await page.locator('#sendBtn').click();
      await page.waitForResponse(
        r => /^\/api\/chat\/(?:stream|sync)$/.test(new URL(r.url()).pathname),
        { timeout: ANSWER_WAIT_MS }).catch(() => null);
    }
    await page.waitForFunction(() => {
      const s = document.querySelector('#stopBtn');
      return !s || s.hidden || getComputedStyle(s).display === 'none';
    }, null, { timeout: ANSWER_WAIT_MS }).catch(() => {});
    await page.waitForTimeout(1200);
    await Promise.all(captured);
    observed = observed || await readObservedModel(page);
    const snap = await collectChatSnapshot(page);
    const mergedAsserts = {};
    for (const g of gids) Object.assign(mergedAsserts, (gestures[g] || {}).asserts || {});
    const judged = judgeSnapshot(snap, mergedAsserts);
    const bubbleText = (snap.assistantBubbles || []).map(b => b.text).join(' ');
    const golden = judge('C1', {}, { answer: bubbleText, firstBodyMs: 1000, modelStatus: 'x' });
    const reasons = judged.reasons.concat(
      golden.verdict === 'FAIL' ? golden.reasons.map(r => 'answer:' + r) : []);
    cell.model.observed = observed;
    cell.httpStatus = httpStatus; cell.reasonCode = reasonCode;
    cell.requestId = requestId; cell.snapshot = snap;
    if (httpStatus === null) { cell.judge = 'NOT_OBSERVED'; cell.reasons = ['no_response']; }
    else if (AUTH_QUOTA.has(httpStatus)) { cell.judge = 'BLOCKED_API'; cell.reasons = ['auth_or_quota:' + httpStatus]; }
    else {
      const chk = policyCli(policyArgs(a, ['check', '--selected', cell.model.requested,
        '--observed', String(observed || '')]));
      cell.modelCheck = chk.parsed.verdict;
      if (chk.parsed.verdict !== 'OK' && chk.parsed.verdict !== 'UNOBSERVED')
        reasons.push('model:' + chk.parsed.verdict);
      cell.judge = reasons.length ? 'FAIL' : 'PASS'; cell.reasons = reasons;
      cell.counts = judged.counts;
    }
  } catch (e) {
    cell.judge = 'FAIL'; cell.reasons = ['runner:' + String(e.message || e).split('\n')[0].slice(0, 140)];
  } finally {
    page.removeListener('response', onResponse);
  }
  const shot = path.join(out.shotDir, cell.model.requested.replace(/[^\w.-]/g, '_') + '-' + cell.promptId + '.png');
  await page.screenshot({ path: shot }).catch(() => {});
  cell.screenshot = path.relative(ROOT, shot);
  return cell;
}

// ---------- report ------------------------------------------------------------
function writeReports(a, run, planish, cells, out) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const summary = {
    cells: cells.filter(c => (c.kind || 'live') === 'live').length,
    fixtureCells: cells.filter(c => c.kind === 'fixture').length,
    pass: cells.filter(c => c.judge === 'PASS').length,
    fail: cells.filter(c => c.judge === 'FAIL').length,
    not_observed: cells.filter(c => c.judge === 'NOT_OBSERVED').length,
    blocked: cells.filter(c => c.judge === 'BLOCKED_API' || c.judge === 'BLOCKED').length,
  };
  const doc = { tool: 'chat_auth_model_matrix_browser', schema: 'awx.chat-auth-model-matrix.v1',
    run, mode: a.live ? 'live' : a.fixture ? 'fixture' : 'dry-run', tier: a.tier,
    base: a.base, public: a.public, at: stamp,
    gen: { max: a.maxGen, planned: planish.genPlanned || 0, used: out.genUsed },
    models: planish.models, prompts: (planish.prompts || []).map(r => ({ id: r.id, category: r.category, chars: r.text.length })),
    summary, cells };
  fs.mkdirSync(LEDGER, { recursive: true });
  const jp = path.join(LEDGER, 'matrix-' + stamp + '.json');
  fs.writeFileSync(jp, JSON.stringify(doc, (k, v) => k === 'snapshot' ? undefined : v, 2));
  const mp = path.join(LEDGER, 'matrix-' + stamp + '.md');
  const lines = ['# chat auth model matrix ' + stamp, '',
    'mode=' + doc.mode + ' tier=' + a.tier + ' base=' + a.base + ' run=' + run,
    'gen planned=' + doc.gen.planned + ' used=' + (out.genUsed || 0) + '/' + a.maxGen, '',
    '| kind | model.req | model.obs | prompt | gestures | http | reason | reqId | judge | reasons |',
    '|---|---|---|---|---|---|---|---|---|---|'];
  for (const c of cells)
    lines.push('| ' + c.kind + ' | ' + (c.model ? c.model.requested : '-') + ' | ' +
      (c.model && c.model.observed || '-') + ' | ' + (c.promptId || c.gestureId) + ' | ' +
      (c.gestureIds || [c.gestureId]).join(',') + ' | ' + (c.httpStatus || '-') + ' | ' +
      (c.reasonCode || '-') + ' | ' + (c.requestId || '-') + ' | ' + c.judge + ' | ' +
      (c.reasons || []).join(';') + ' |');
  lines.push('', '[matrix] cells=' + summary.cells + ' pass=' + summary.pass +
    ' fail=' + summary.fail + ' not_observed=' + summary.not_observed +
    ' fixture_cells=' + summary.fixtureCells +
    ' gen_used=' + (out.genUsed || 0) + '/' + a.maxGen);
  fs.writeFileSync(mp, lines.join('\n') + '\n');
  return { json: jp, md: mp, summary, lastLine: lines[lines.length - 1] };
}

// ---------- main --------------------------------------------------------------
async function main() {
  let a;
  try { a = parseArgs(process.argv.slice(2)); }
  catch (e) { console.log(JSON.stringify({ verdict: 'USAGE', reason: e.message })); return 2; }
  const run = a.run || ('matrix-' + Date.now());
  const out = { genUsed: 0, shotDir: path.join(LEDGER, 'matrix-shots-' + run) };
  let resolved;
  try { resolved = resolveModels(a); }
  catch (e) { console.log(JSON.stringify({ verdict: 'USAGE', reason: e.message })); return 2; }
  if (resolved.verdict && resolved.verdict !== 'RESOLVED' && !a.models) {
    const planish = buildPlan(a, { models: [] });
    const rep = writeReports(a, run, planish, [], out);
    console.log(JSON.stringify({ verdict: resolved.verdict, purpose: resolved.purpose, report: rep.json }));
    console.log(rep.lastLine);
    return 6;
  }
  const planish = buildPlan(a, { models: resolved.models });
  for (const c of planish.cells) c.alternates = resolved.candidates || [];

  if (a.dryRun && !a.fixture) {
    const rep = writeReports(a, run, planish, planish.cells.concat(planish.fixtureCells), out);
    console.log(JSON.stringify({ verdict: 'DRY_RUN', tier: a.tier, models: resolved.models,
      purpose: resolved.purpose, liveCells: planish.cells.length,
      fixtureCells: planish.fixtureCells.length, genPlanned: planish.genPlanned,
      report: rep.json, md: rep.md }));
    console.log(rep.lastLine);
    return 0;
  }

  let chromium;
  try { ({ chromium } = require('playwright')); }
  catch (e) {
    const rep = writeReports(a, run, planish,
      planish.cells.concat(planish.fixtureCells).map(c => ({ ...c, judge: 'NOT_OBSERVED', reasons: ['PLAYWRIGHT_MISSING'] })), out);
    console.log(JSON.stringify({ verdict: 'NOT_RUN', reason: 'PLAYWRIGHT_MISSING', report: rep.json }));
    console.log(rep.lastLine);
    return 5;
  }

  fs.mkdirSync(out.shotDir, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: !a.headful });
  const allCells = [];
  try {
    // pass 1: fixture gestures — 0 model generations.
    const wantFixture = a.fixture || a.live;
    if (wantFixture && planish.fixtureCells.length) {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
      const page = await ctx.newPage();
      for (const fc of planish.fixtureCells) {
        const fixture = createFaultFixture({
          targetPort: Number(new URL(a.base).port) || 18180, scenario: fc.scenario || 'error-late' });
        await new Promise(r => fixture.listen(0, '127.0.0.1', r));
        const port = fixture.address().port;
        try {
          await page.goto('http://127.0.0.1:' + port + '/chat-ui', { waitUntil: 'domcontentloaded' });
          await page.locator('#messageInput').waitFor({ timeout: 15000 });
          const done = await runFixtureCell(page, fc, planish.gestures);
          const shot = path.join(out.shotDir, 'fixture-' + fc.gestureId + '.png');
          await page.screenshot({ path: shot }).catch(() => {});
          done.screenshot = path.relative(ROOT, shot);
          allCells.push(done);
        } catch (e) {
          allCells.push({ ...fc, judge: 'FAIL', reasons: ['fixture:' + String(e.message || e).split('\n')[0].slice(0, 120)] });
        } finally {
          await new Promise(r => fixture.close(r));
        }
      }
      await ctx.close();
    }
    if (a.fixture) {   // fixture-only mode ends here
      const rep = writeReports(a, run, planish, allCells, out);
      console.log(JSON.stringify({ verdict: 'FIXTURE_DONE', report: rep.json, md: rep.md }));
      console.log(rep.lastLine);
      return rep.summary.fail ? 1 : 0;
    }

    // pass 2: live cells — model x prompt, generation budget at the wire.
    if (a.live) {
      const budget = new SendBudget(Math.min(a.maxGen, planish.genPlanned || a.maxGen));
      const blocked = [];
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
      await ctx.route('**/api/chat/**', r => guardGenerationRoute(r, budget, blocked));
      const page = await ctx.newPage();
      let pageReady = false, lastModel = null, blockedModels = new Set();
      for (const cell of planish.cells) {
        if (blockedModels.has(cell.model.requested)) {
          cell.judge = 'BLOCKED'; cell.reasons = ['model_blocked_auth_or_quota']; allCells.push(cell); continue;
        }
        if (budget.used >= budget.max) { cell.judge = 'BLOCKED'; cell.reasons = ['MAX_SENDS_REACHED']; allCells.push(cell); continue; }
        const cap = policyCli(policyArgs(a, ['budget', '--run', run]));
        if (cap.parsed.verdict === 'BUDGET_EXCEEDED') {
          cell.judge = 'BLOCKED'; cell.reasons = ['BUDGET_EXCEEDED']; allCells.push(cell); continue;
        }
        if (!pageReady) { await page.goto(a.base + '/chat'); pageReady = true; }
        else if (cell.model.requested !== lastModel) {
          await page.locator('#newChatBtn').click().catch(() => {});
          await page.waitForTimeout(400);
        }
        const before = budget.used;
        await runLiveCell(page, ctx, a, cell, planish.rows, planish.gestures, budget, out);
        out.genUsed += Math.max(0, budget.used - before);
        policyCli(policyArgs(a, ['record', '--agent', 'devin-matrix', '--purpose', cell.purpose || 'smoke',
          '--model', cell.model.requested, '--code', String(cell.httpStatus == null ? 'no_response' : cell.httpStatus),
          '--run', run]));
        if (cell.judge === 'BLOCKED_API') {
          blockedModels.add(cell.model.requested);
          const alt = (cell.alternates || []).find(m => !blockedModels.has(m) && m !== cell.model.requested);
          if (alt) {
            const retry = { ...cell, model: { requested: alt }, judge: 'PLANNED',
              reasons: ['rank_down_after_' + cell.httpStatus] };
            planish.cells.push(retry);   // next policy rank once, never same model
          }
        }
        lastModel = cell.model.requested;
        allCells.push(cell);
      }
      if (blocked.length) for (const c of allCells) if (c.judge === 'PLANNED') { c.judge = 'BLOCKED'; c.reasons = blocked; }
      await ctx.close();
    }
  } finally {
    await browser.close();
  }
  const rep = writeReports(a, run, planish, allCells, out);
  console.log(JSON.stringify({ verdict: 'DONE', report: rep.json, md: rep.md, summary: rep.summary }));
  console.log(rep.lastLine);
  return rep.summary.fail ? 1 : 0;
}

module.exports = { parseArgs, loadStrictYaml, loadCorpus, materializeGenerator, pickPrompts,
  resolveModels, buildPlan, judgeSnapshot, rectsOverlap, collectChatSnapshot,
  runFixtureCell, runLiveCell, writeReports, INTERNAL_LEAK, PREPARING, TIERS };
if (require.main === module) {
  main().then(code => { process.exitCode = code; })
    .catch(e => { console.error(e.name + ': ' + e.message); process.exitCode = 1; });
}
