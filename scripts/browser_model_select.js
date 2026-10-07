// browser_model_select — CommonJS helpers for picking a catalog model in /chat
// per configs/agent-test-model-policy.yaml. Playwright-only; no install here.
//
// The server-rendered #modelSelect lists bare modelIds (e.g. "gpt-5.6-luna"),
// while the /api/chat/models catalog uses route ids ("chatgpt-oauth:gpt-5.6-
// luna", "llmrouter.api3"). selectModel tries the exact id, then the bare
// modelId, then mirrors the session-restore path in chat.js by prepending an
// option with the catalog id (the backend resolves the "chatgpt-oauth:" route).
'use strict';

const SELECT_SELECTORS = [
  '[data-testid="chat-model-select"]',
  '#chat-model-select',
  '#modelSelect',
];
const STATUS_SELECTORS = [
  '[data-testid="chat-model-status"]',
  '#chat-model-status',
  '#modelStatus',
];
const OBSERVED_SELECTORS = [
  '[data-debug-cockpit-cell="model"] small',
  '[data-debug-heartbeat-field="model"] small',
];

async function firstMatch(page, selectors) {
  for (const sel of selectors) {
    const loc = page.locator(sel).first();
    if (await loc.count()) return loc;
  }
  return null;
}

function bareModelId(catalogId) {
  return String(catalogId).startsWith('chatgpt-oauth:')
    ? String(catalogId).split(':').slice(1).join(':')
    : String(catalogId);
}

/**
 * Select catalog `id` in the chat model select; confirm via the status pill.
 * Returns { selectedValue, statusText, applied }.
 */
async function selectModel(page, catalogId, { timeout = 15000 } = {}) {
  const select = await firstMatch(page, SELECT_SELECTORS);
  if (!select) throw new Error('model select not found');
  if (!(await select.isVisible())) {
    const settings = page.locator('[data-testid="chat-response-settings"] > summary');
    if (await settings.count()) await settings.click();
  }
  await select.waitFor({ state: 'visible', timeout });
  const values = await select.locator('option').evaluateAll(o => o.map(x => x.value));
  const bare = bareModelId(catalogId);
  let selectedValue = null;
  if (values.includes(catalogId)) selectedValue = catalogId;
  else if (values.includes(bare)) selectedValue = bare;
  if (selectedValue !== null) {
    await select.selectOption(selectedValue);
  } else {
    await select.evaluate((el, v) => {
      if (![...el.options].some(o => o.value === v)) el.prepend(new Option(v, v));
      el.value = v;
      el.dispatchEvent(new Event('change', { bubbles: true }));
    }, catalogId);
    selectedValue = catalogId;
  }
  const status = await firstMatch(page, STATUS_SELECTORS);
  let statusText = '';
  const deadline = Date.now() + timeout;
  while (status && Date.now() < deadline) {
    statusText = (await status.innerText().catch(() => '')).trim();
    if (statusText.includes(selectedValue) || statusText.includes(bare)) break;
    await page.waitForTimeout(250);
  }
  const applied = statusText.includes(selectedValue) || statusText.includes(bare);
  return { selectedValue, statusText, applied };
}

/**
 * Best-effort read of the model that actually served the last answer:
 * trace/status surfaces in the chat-ui debug rails. Returns a string or null.
 */
async function readObservedModel(page) {
  for (const sel of STATUS_SELECTORS.concat(OBSERVED_SELECTORS)) {
    const loc = page.locator(sel).first();
    if (!(await loc.count())) continue;
    const text = (await loc.innerText().catch(() => '')).trim();
    const m = text.match(/(chatgpt-oauth:[\w.\-/]+|llmrouter\.[\w.\-/]+|[\w][\w.\-/:]*[\w])/);
    if (m && !/^(loading|unknown|-)$/i.test(m[0])) return m[0];
  }
  return null;
}

module.exports = { selectModel, readObservedModel, bareModelId };
