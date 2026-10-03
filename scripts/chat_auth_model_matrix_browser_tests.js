'use strict';
// Focused tests for chat_auth_model_matrix_browser — plan/judge/schema level.
// No playwright, no live server: policy resolve runs on a fixture catalog via
// --catalog and an isolated --usage-log in the OS temp dir.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  parseArgs, loadStrictYaml, loadCorpus, materializeGenerator, pickPrompts,
  resolveModels, buildPlan, judgeSnapshot, rectsOverlap, TIERS,
} = require('./chat_auth_model_matrix_browser.js');

const ROOT = path.resolve(__dirname, '..');
const CATALOG_YAML = path.join(ROOT, 'configs', 'browser-gesture-catalog.yaml');
const CORPUS_JSON = path.join(ROOT, 'configs', 'chat-test-prompts-varied.json');

const FIXTURE_CATALOG = [
  'gpt-5.6-luna', 'gpt-5.6-terra', 'gpt-5.5', 'gpt-6-astra', 'gpt-5.6-sol',
].map(m => ({ id: 'chatgpt-oauth:' + m, modelId: m, provider: 'openai',
              status: 'configured', selectable: true }))
  .concat([
    { id: 'llmrouter.api3', modelId: 'api3', provider: 'groq', status: 'configured', selectable: true },
    { id: 'gemma4:26b', modelId: 'gemma4:26b', provider: 'Ollama', status: 'installed', selectable: true },
    { id: 'qwen3.5:9b', modelId: 'qwen3.5:9b', provider: 'Ollama', status: 'installed', selectable: true },
    { id: 'chatgpt-oauth:codex-auto-review', modelId: 'codex-auto-review', provider: 'openai', status: 'configured', selectable: true },
  ]);

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'matrix-test-'));
const catalogFile = path.join(tmp, 'catalog.json');
const usageLog = path.join(tmp, 'usage.jsonl');
fs.writeFileSync(catalogFile, JSON.stringify(FIXTURE_CATALOG));

function argsOf(extra) {
  return parseArgs(['--catalog', catalogFile, '--usage-log', usageLog].concat(extra || []));
}

// ---------- arg validation ----------------------------------------------------
test('parseArgs rejects max-gen above the policy cap and bad tiers/bases', () => {
  assert.throws(() => parseArgs(['--max-gen', '26']), /exceeds_policy/);
  assert.throws(() => parseArgs(['--max-gen', '0']), /invalid_max_gen/);
  assert.throws(() => parseArgs(['--tier', 'nightly']), /invalid_tier/);
  assert.throws(() => parseArgs(['--base', 'https://example.com']), /PUBLIC_REQUIRES_FLAG/);
  assert.equal(parseArgs(['--base', 'https://abandonwareai.kro.kr', '--public']).maxGen, 3);
  assert.equal(parseArgs([]).dryRun, true);
  assert.equal(parseArgs(['--live']).dryRun, false);
  assert.deepEqual(parseArgs(['--gestures', 'G01,G03']).gestures, ['G01', 'G03']);
  assert.throws(() => parseArgs(['--only-failed', 'nonexistent.json']), /only_failed_not_found/);
});

// ---------- plan generation ----------------------------------------------------
test('tier plans produce 8 / 15 / <=25 cells and stay under max-gen', () => {
  for (const [tier, expectCells] of [['smoke', 8], ['regression', 15]]) {
    const a = argsOf(['--tier', tier]);
    const res = resolveModels(a);
    const plan = buildPlan(a, { models: res.models });
    assert.equal(plan.models.length, TIERS[tier].models, tier + ' model count');
    assert.equal(plan.prompts.length, TIERS[tier].prompts, tier + ' prompt count');
    assert.equal(plan.cells.length, expectCells, tier + ' cells');
    assert(plan.genPlanned <= a.maxGen);
  }
  const a = argsOf(['--tier', 'full']);
  const res = resolveModels(a);
  const plan = buildPlan(a, { models: res.models });
  assert(plan.cells.length <= 25 && plan.genPlanned <= 25, 'full stays under 25');
  assert(plan.fixtureCells.length >= 4, 'G01-G04 fixture cells planned');
});

test('models come only from the policy and neverSelect is excluded', () => {
  assert.throws(() => argsOf(['--models', 'chatgpt-oauth:codex-auto-review']) &&
    resolveModels(argsOf(['--models', 'chatgpt-oauth:codex-auto-review'])),
    /never_select/);
  const a = argsOf(['--tier', 'regression']);
  const res = resolveModels(a);
  assert.equal(res.via, 'policy');
  assert(!res.models.includes('chatgpt-oauth:codex-auto-review'));
  assert(res.models.every(m => m.startsWith('chatgpt-oauth:')), 'api_first era: oauth routes only');
});

test('--only-failed replans only FAIL/NOT_OBSERVED cells from a prior report', () => {
  const prev = path.join(tmp, 'prev-matrix.json');
  fs.writeFileSync(prev, JSON.stringify({ cells: [
    { kind: 'live', model: { requested: 'chatgpt-oauth:gpt-5.6-luna' }, promptId: 'GK1', judge: 'FAIL' },
    { kind: 'live', model: { requested: 'chatgpt-oauth:gpt-5.6-luna' }, promptId: 'GS1', judge: 'PASS' },
    { kind: 'live', model: { requested: 'chatgpt-oauth:gpt-5.6-terra' }, promptId: 'GK1', judge: 'NOT_OBSERVED' },
    { kind: 'fixture', gestureId: 'G01', judge: 'FAIL' },
    { kind: 'fixture', gestureId: 'G02', judge: 'PASS' },
  ] }));
  const a = argsOf(['--models', 'chatgpt-oauth:gpt-5.6-luna,chatgpt-oauth:gpt-5.6-terra',
    '--only-failed', prev]);
  const res = resolveModels(a);
  const plan = buildPlan(a, { models: res.models });
  const keys = plan.cells.map(c => c.model.requested + '|' + c.promptId).sort();
  assert.deepEqual(keys, ['chatgpt-oauth:gpt-5.6-luna|GK1', 'chatgpt-oauth:gpt-5.6-terra|GK1']);
  assert.deepEqual(plan.fixtureCells.map(c => c.gestureId), ['G01']);
});

test('unknown gesture ids are rejected at plan time', () => {
  const a = argsOf(['--gestures', 'G99']);
  assert.throws(() => buildPlan(a, { models: ['m'] }), /unknown_gesture/);
});

// ---------- judge unit tests ----------------------------------------------------
const rect = (x, y, w, h) => ({ x, y, w, h });
test('judgeSnapshot fails on pending residue and leftover preparing text', () => {
  const snap = {
    assistantBubbles: [
      { state: 'pending', text: 'Assistant is preparing', rect: rect(10, 200, 500, 80) },
    ],
    userBubbles: [{ rect: rect(10, 100, 500, 60) }],
    noticeBoxes: [], inputBar: { rect: rect(0, 900, 1440, 60), overflows: false },
  };
  const r = judgeSnapshot(snap, { pendingAssistant: 0, preparingText: 0 });
  assert.equal(r.verdict, 'FAIL');
  assert(r.reasons.some(x => x.startsWith('pendingAssistant:')));
  assert(r.reasons.some(x => x.startsWith('preparingText:')));
});

test('judgeSnapshot fails on raw internal markers inside a bubble', () => {
  for (const leak of ['backend_timeout after 30000ms', 'Traceback (most recent call last)',
                      'stream_failed: eof', '[Agent-CFMV-Supabase] on-start-failed']) {
    const snap = {
      assistantBubbles: [{ state: 'done', text: 'error: ' + leak, rect: rect(10, 200, 500, 80) }],
      userBubbles: [{ rect: rect(10, 100, 500, 60) }], noticeBoxes: [],
      inputBar: { rect: rect(0, 900, 1440, 60), overflows: false },
    };
    const r = judgeSnapshot(snap, { internalLeak: 0 });
    assert.equal(r.verdict, 'FAIL', leak);
    assert(r.reasons.some(x => x.startsWith('internalLeak:')), leak);
  }
  const okSnap = {
    assistantBubbles: [{ state: 'done', text: '요청이 시간 초과로 실패했어요. 다시 시도해 주세요.', rect: rect(10, 200, 500, 80) }],
    userBubbles: [{ rect: rect(10, 100, 500, 60) }], noticeBoxes: [],
    inputBar: { rect: rect(0, 900, 1440, 60), overflows: false },
  };
  assert.equal(judgeSnapshot(okSnap, { internalLeak: 0 }).verdict, 'PASS');
});

test('judgeSnapshot fails on order violation and bounding-box overlap', () => {
  const snap = {
    assistantBubbles: [{ state: 'done', text: 'answer', rect: rect(10, 50, 500, 80) }],
    userBubbles: [{ rect: rect(10, 200, 500, 60) }],
    noticeBoxes: [], inputBar: { rect: rect(0, 900, 1440, 60), overflows: false },
  };
  const r = judgeSnapshot(snap, { orderViolation: 0 });
  assert.equal(r.verdict, 'FAIL');
  assert(r.reasons.some(x => x.startsWith('orderViolation:')));
  const overlap = {
    assistantBubbles: [
      { state: 'done', text: 'a', rect: rect(10, 100, 500, 120) },
      { state: 'done', text: 'b', rect: rect(10, 150, 500, 120) },
    ],
    userBubbles: [{ rect: rect(10, 40, 500, 50) }],
    noticeBoxes: [], inputBar: { rect: rect(0, 900, 1440, 60), overflows: false },
  };
  const r2 = judgeSnapshot(overlap, { overlapPairs: 0 });
  assert.equal(r2.verdict, 'FAIL');
  assert(r2.reasons.some(x => x.startsWith('overlapPairs:')));
  assert.equal(rectsOverlap(rect(0, 0, 10, 10), rect(5, 5, 10, 10)), true);
  assert.equal(rectsOverlap(rect(0, 0, 10, 10), rect(20, 20, 10, 10)), false);
});

test('judgeSnapshot passes a clean settled snapshot', () => {
  const snap = {
    assistantBubbles: [{ state: 'done', text: '네, 지구에서 가장 깊은 바다는 마리아나 해구입니다.', rect: rect(10, 200, 900, 80) }],
    userBubbles: [{ rect: rect(10, 100, 700, 60) }],
    noticeBoxes: [], inputBar: { rect: rect(0, 900, 1440, 60), overflows: false },
    lateTokensAfterStop: 0,
  };
  const r = judgeSnapshot(snap, { pendingAssistant: 0, preparingText: 0, internalLeak: 0,
    orderViolation: 0, overlapPairs: 0 });
  assert.equal(r.verdict, 'PASS');
  assert.deepEqual(r.reasons, []);
});

// ---------- catalog + corpus schema ---------------------------------------------
test('gesture catalog has G01-G08 with required fields and needsModelCall flags', () => {
  const doc = loadStrictYaml(CATALOG_YAML);
  const g = doc.gestures;
  const ids = Object.keys(g).sort();
  assert.deepEqual(ids, ['G01', 'G02', 'G03', 'G04', 'G05', 'G06', 'G07', 'G08']);
  for (const [id, row] of Object.entries(g)) {
    for (const f of ['name', 'symptom', 'needsModelCall', 'steps', 'asserts', 'evidence'])
      assert(row[f] !== undefined && row[f] !== null, id + ' missing ' + f);
    assert.equal(typeof row.needsModelCall, 'boolean', id + ' needsModelCall type');
    assert(Array.isArray(row.steps) && row.steps.length > 0, id + ' steps');
    assert.equal(typeof row.asserts, 'object', id + ' asserts map');
    for (const k of Object.keys(row.asserts))
      assert(doc.assertKeys[k], id + ' assert key ' + k + ' undocumented');
  }
  for (const id of ['G01', 'G02', 'G03', 'G04'])
    assert.equal(g[id].needsModelCall, false, id + ' must be fixture-runnable');
  for (const id of ['G05', 'G06', 'G07', 'G08'])
    assert.equal(g[id].needsModelCall, true, id + ' needs a live send');
});

test('prompt corpus has >=30 rows, 8 categories x >=4, and the video prompt', () => {
  const doc = JSON.parse(fs.readFileSync(CORPUS_JSON, 'utf8'));
  const rows = doc.prompts;
  assert(rows.length >= 30, 'total >= 30, got ' + rows.length);
  const cats = {};
  for (const r of rows) {
    cats[r.category] = (cats[r.category] || 0) + 1;
    assert(r.id && r.purposeHint && Array.isArray(r.gestureIds), 'row fields ' + r.id);
    assert(r.text || r.generator, 'text-or-generator ' + r.id);
    assert(r.gestureIds.every(g => /^G0[1-8]$/.test(g)), 'gestureIds ' + r.id);
    assert(['smoke', 'regression', 'quality', 'practice_reasoning', 'practice_chat'].includes(r.purposeHint), 'purposeHint ' + r.id);
  }
  for (const c of ['greeting_short', 'typo_korean', 'general_knowledge', 'evidence_rag',
                   'code_reasoning', 'long_input', 'multi_turn', 'mixed'])
    assert((cats[c] || 0) >= 4, c + ' >= 4, got ' + (cats[c] || 0));
  assert(rows.some(r => r.text === '하이젠 버그버그가 뭐야'), 'video typo prompt present');
});

test('corpus loader materializes generators and pickPrompts varies categories', () => {
  const gen = materializeGenerator({ kind: 'korean_paragraph_repeat', chars: 2000 });
  assert.equal(gen.length, 2000);
  assert.throws(() => materializeGenerator({ kind: 'other' }), /unknown_generator/);
  const rows = loadCorpus(argsOf([]));
  assert(rows.length >= 30);
  assert(rows.every(r => r.text.length > 0));
  const picked = pickPrompts(rows, 4);
  assert.equal(new Set(picked.map(r => r.category)).size, 4, 'one per category first');
  const long = rows.find(r => r.id === 'LI3');
  assert.equal(long.text.length, 8000);
});

// ---------- reuse contract -------------------------------------------------------
test('matrix module reuses existing browser/policy modules (no duplicate impls)', () => {
  const src = fs.readFileSync(path.join(ROOT, 'scripts', 'chat_auth_model_matrix_browser.js'), 'utf8');
  for (const mod of ["./browser_model_select.js", "./chat_practice_browser.js",
                     "./chat_rag_golden_browser.js", "./chat_ui_browser_fault_fixture.js"])
    assert(src.includes("require('" + mod + "')"), 'missing reuse of ' + mod);
  assert(src.includes('test_model_policy.py'), 'policy CLI reuse');
  assert(!/function selectModel\(/.test(src), 'must not reimplement selectModel');
  assert(!/function safeMetadata\(/.test(src), 'must not reimplement safeMetadata');
});
