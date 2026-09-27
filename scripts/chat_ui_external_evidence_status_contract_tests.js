const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const chatJs = fs.readFileSync(path.join(root, 'main', 'resources', 'static', 'js', 'chat.js'), 'utf8');

function extractFunction(name) {
  const start = chatJs.indexOf(`function ${name}(`);
  assert.ok(start >= 0, `helper ${name} must exist in chat.js`);
  const end = chatJs.indexOf('\n}\n', start);
  assert.ok(end >= 0, `helper ${name} must terminate at column 0`);
  return chatJs.slice(start, end + 3);
}

function extractBlock(startMarker, endMarker) {
  const start = chatJs.indexOf(startMarker);
  assert.ok(start >= 0, `block start '${startMarker}' must exist`);
  const end = chatJs.indexOf(endMarker, start);
  assert.ok(end >= 0, `block end '${endMarker}' must exist`);
  const endLine = chatJs.indexOf('\n', end);
  return chatJs.slice(start, endLine);
}

const helpersSource = [
  'statusOf', 'externalEvidenceDetail', 'isDesktopLocalReady',
  'isReadOnlySupabaseProbeAction', 'isOptionalSupabaseProofRow',
  'isOptionalBrowserProofRow', 'isOptionalComputerProofRow',
  'isStaleExternalEvidenceRow', 'isSupportingPatchDropEvidence',
].map(extractFunction).join('\n');

const rowBlock = extractBlock('const browserStaleOnlyWarn', 'const computerUiDetail');
const aggregateBlock = extractBlock('const externalProofStatus', 'const externalHeartbeatDetail');

function runBlock(block, resultExpr, scopeVars) {
  const ctx = vm.createContext(Object.assign({}, scopeVars));
  vm.runInContext(helpersSource, ctx, { filename: 'chat.js#helpers' });
  return vm.runInContext(`${block}\n(${resultExpr});`, ctx, { filename: 'chat.js#decision' });
}

function decideRows({ browserRow = {}, computerRow = {}, goalNextRow = {}, desktopOnlyReady = true,
                      browserStatus = 'WARN', computerStatus = 'WARN' }) {
  return runBlock(rowBlock,
    '{ browserStaleOnlyWarn, browserSupportingProof, browserUiStatus, browserUiDetail,'
    + ' computerStaleOnlyWarn, computerSupportingProof, computerUiStatus, computerUiDetail }',
    {
      goalNextRow, desktopOnlyReady,
      browserRow, browserStatus, browserDetail: 'browser-detail',
      computerRow, computerStatus, computerEvidenceDetail: 'computer-detail',
    });
}

function decideAggregate({ externalRow = {}, externalStatus = 'WARN', supabaseRow = {}, supabaseStatus = 'OK',
                           browserProofStatus = 'OK', browserSupportingProof = false,
                           computerStatus = 'OK', computerSupportingProof = false,
                           freshnessStatus = 'OK', patchDropRow = {}, patchDropStatus = 'OK',
                           desktopOnlyReady = true, liveStatus = 'OK', coreStatus = 'OK',
                           uiStatus = 'OK', modelStatus = 'OK', answerStatus = 'OK' }) {
  return runBlock(aggregateBlock,
    '{ externalProofStatus, externalOverviewBenign, externalProofSeriousWarn,'
    + ' healthRailProofStatus, healthRailExternalStatus, healthRailStatus,'
    + ' externalProofUiStatus, externalSummaryStatus, externalHeartbeatStatus }',
    {
      externalRow, externalStatus, supabaseRow, supabaseStatus,
      browserProofStatus, browserSupportingProof,
      computerStatus, computerSupportingProof,
      freshnessStatus, patchDropRow, patchDropStatus,
      desktopOnlyReady, liveStatus, coreStatus, uiStatus, modelStatus, answerStatus,
      externalDetail: 'external-detail',
    });
}

const desktopReadyGoal = { localReady: true, completionReady: true, localPatchJustified: true };

function computerRowWith(evidenceNeeded, extra = {}) {
  return Object.assign({
    service: 'computer-use', status: 'WARN', stale: true,
    evidenceScope: 'gui-supporting-only', evidenceNeeded,
    nextAction: 'run_computer_use_lightweight_smoke', reachable: true,
    appCount: 3, targetableWindowCount: 2,
  }, extra);
}

const seriousComputerWarnings = [
  'computer_use_secret_pattern_hits',
  'computer_use_unreachable',
  'computer_use_boundary_incomplete',
  'computer_use_privacy_boundary_incomplete',
  'computer_use_smoke_not_ok',
  'computer_use_smoke_unreadable',
  'computer_use_smoke_missing',
];

for (const needed of seriousComputerWarnings) {
  const row = computerRowWith(needed, needed === 'computer_use_unreachable' ? { reachable: false } : {});
  const rows = decideRows({ computerRow: row, goalNextRow: desktopReadyGoal });
  assert.equal(rows.computerUiStatus, 'WARN',
    `serious computer warning must stay WARN at row level: ${needed}`);
  const agg = decideAggregate({
    externalRow: { name: 'externalEvidence', status: 'DISABLED', reason: 'ready' },
    externalStatus: 'WARN', computerRow: row,
    computerStatus: 'WARN', computerSupportingProof: rows.computerSupportingProof,
  });
  assert.equal(agg.externalProofUiStatus, 'WARN',
    `serious computer warning must stay WARN at external-proof aggregate: ${needed}`);
  assert.equal(agg.healthRailStatus, 'WARN',
    `serious computer warning must keep the health rail at WARN: ${needed}`);
}

{
  const row = computerRowWith('computer_use_smoke_stale');
  const rows = decideRows({ computerRow: row, goalNextRow: desktopReadyGoal });
  assert.equal(rows.computerUiStatus, 'SUPPORTING',
    'stale-only computer warning demotes to SUPPORTING, not OK');
  const agg = decideAggregate({
    externalRow: { name: 'externalEvidence', status: 'DISABLED', reason: 'ready' },
    externalStatus: 'WARN', computerRow: row,
    computerStatus: 'WARN', computerSupportingProof: rows.computerSupportingProof,
  });
  assert.equal(agg.externalProofUiStatus, 'OK',
    'stale-only external warnings still soften the aggregate when desktop is ready');
  assert.equal(agg.healthRailStatus, 'OK',
    'stale-only warnings do not break the health rail');
}

{
  const staleSecretBrowser = {
    service: 'browser', status: 'WARN', stale: true, evidenceScope: 'iab',
    evidenceNeeded: 'browser_ui_smoke_stale', nextAction: 'run_browser_local_ui_smoke',
    secretHits: 2, reachable: true, targetAccepted: true, targetContentVisible: true,
  };
  const rows = decideRows({ browserRow: staleSecretBrowser, goalNextRow: desktopReadyGoal });
  assert.equal(rows.browserUiStatus, 'WARN',
    'stale browser row masking secretHits must stay WARN');
  const agg = decideAggregate({
    externalRow: { name: 'externalEvidence', status: 'DISABLED', reason: 'ready' },
    externalStatus: 'WARN',
    browserProofStatus: 'WARN', browserSupportingProof: rows.browserSupportingProof,
  });
  assert.equal(agg.externalProofUiStatus, 'WARN',
    'stale-masked browser secret warning must stay WARN at aggregate');
}

{
  const cleanStaleBrowser = {
    service: 'browser', status: 'WARN', stale: true, evidenceScope: 'iab',
    evidenceNeeded: 'browser_ui_smoke_stale', nextAction: 'run_browser_local_ui_smoke',
    secretHits: 0, reachable: true, targetAccepted: true, targetContentVisible: true,
  };
  const rows = decideRows({ browserRow: cleanStaleBrowser, goalNextRow: desktopReadyGoal });
  assert.equal(rows.browserUiStatus, 'SUPPORTING',
    'truly stale-only browser warning demotes to SUPPORTING');
}

{
  const agg = decideAggregate({
    externalRow: { name: 'externalEvidence', status: 'WARN', reason: 'evidence_needed' },
    externalStatus: 'WARN',
  });
  assert.equal(agg.externalHeartbeatStatus, 'WARN',
    'blocking external overview WARN is never softened');
  assert.equal(agg.externalSummaryStatus, 'WARN');
}

{
  const rows = decideRows({ computerRow: computerRowWith('computer_use_smoke_stale'),
    goalNextRow: desktopReadyGoal, desktopOnlyReady: false });
  assert.equal(rows.computerUiStatus, 'WARN',
    'without desktop-ready the stale computer warning stays WARN');
}

console.log('[PASS] chat_ui_external_evidence_status_contract_tests');
