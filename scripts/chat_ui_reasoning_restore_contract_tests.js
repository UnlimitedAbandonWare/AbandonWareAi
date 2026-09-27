// Execute the active UI functions with synthetic events; no network or provider calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '../main/resources/static/js/chat.js'), 'utf8');
function activeFunction(name) {
  const match = new RegExp(`^(?:async )?function ${name}\\(`, 'm').exec(source);
  assert.ok(match, `active function missing: ${name}`);
  const end = source.indexOf('\n}', match.index);
  assert.ok(end > match.index, `function boundary missing: ${name}`);
  return source.slice(match.index, end + 2);
}

function reasoningHarness() {
  const context = vm.createContext({
    isAssistantStreamStopped: bubble => bubble?.stopped === true,
    recordChatTransitionDebug() {}, clearAssistantPendingPlaceholder() {},
    appendTextWithBreaks(bubble, text) { bubble.textContent += text; },
    refreshEvidenceRailFromAnswerText() {}, state: {}, dom: {},
  });
  const declarations = source.split('\n').filter(line =>
    /^(?:const|let) (?:assistantReasoningPattern|suppressAssistantReasoning|assistantReasoningStates)\b/.test(line));
  vm.runInContext([
    ...declarations, activeFunction('stripAssistantReasoningBlocks'),
    activeFunction('filterAssistantReasoningChunk'), activeFunction('renderChatEvent'),
  ].join('\n'), context);
  const bubble = () => ({ textContent: '', dataset: {}, stopped: false });
  const token = (target, data) => context.renderChatEvent({ type: 'token', data }, target);
  return { context, bubble, token };
}

test('visible prefix survives an unfinished reasoning opening; hidden suffix never renders', () => {
  const h = reasoningHarness(), a = h.bubble();
  h.token(a, 'Visible <think>synthetic-hidden');
  assert.equal(a.textContent, 'Visible ');
  h.token(a, ' still-hidden</think> answer');
  assert.equal(a.textContent, 'Visible  answer');
});

for (const tag of ['think', 'reasoning']) {
  test(`every split of the ${tag} opening and closing tags stays hidden`, () => {
    const open = `<${tag}>`, close = `</${tag}>`;
    for (let left = 1; left < open.length; left++) {
      for (let right = 1; right < close.length; right++) {
        const h = reasoningHarness(), a = h.bubble();
        for (const chunk of ['prefix ', open.slice(0, left), open.slice(left), 'synthetic-hidden',
          close.slice(0, right), close.slice(right), ' suffix']) h.token(a, chunk);
        assert.equal(a.textContent, 'prefix  suffix', `opening split ${left}, closing split ${right}`);
      }
    }
  });
}

test('complete blocks preserve surrounding streamed whitespace', () => {
  const h = reasoningHarness(), a = h.bubble();
  for (const chunk of ['a ', '<think>hidden</think>', ' b ', '<reasoning>hidden</reasoning> c']) h.token(a, chunk);
  assert.equal(a.textContent, 'a  b  c');
});

test('mixed-case and nested reasoning stay hidden through the outer closing tag', () => {
  const h = reasoningHarness(), a = h.bubble();
  for (const chunk of ['a<THINK>one<reasoning>two</reasoning>', 'three</THINK>b']) h.token(a, chunk);
  assert.equal(a.textContent, 'ab');
});

test('unclosed reasoning in a stopped assistant cannot suppress a fresh assistant', () => {
  const h = reasoningHarness(), a = h.bubble();
  h.token(a, '<think>');
  a.stopped = true; // The real rendering boundary rejects late events for this assistant.
  h.token(a, '</think>late');
  const b = h.bubble();
  h.token(b, 'Next visible answer');
  assert.equal(a.textContent, '');
  assert.equal(b.textContent, 'Next visible answer');
});

test('interleaved assistant identities keep independent suppression and pending suffixes', () => {
  const h = reasoningHarness(), a = h.bubble(), b = h.bubble();
  h.token(a, '<thi');
  h.token(b, 'other answer');
  h.token(a, 'nk>hidden</think>first answer');
  assert.equal(a.textContent, 'first answer');
  assert.equal(b.textContent, 'other answer');
});

test('ordinary angle text and nonmatching tag prefixes pass unchanged', () => {
  const h = reasoningHarness(), a = h.bubble();
  for (const chunk of ['2 < 3; <th', 'imble> text <b>bold</b>']) h.token(a, chunk);
  assert.equal(a.textContent, '2 < 3; <thimble> text <b>bold</b>');
});

test('Unicode text before a reasoning tag does not shift tag boundaries', () => {
  const h = reasoningHarness(), a = h.bubble();
  h.token(a, '\u0130 한글 <think>hidden</think> visible');
  assert.equal(a.textContent, '\u0130 한글  visible');
});

test('a clean EOF flushes an ordinary incomplete marker suffix', () => {
  const h = reasoningHarness(), a = h.bubble();
  h.token(a, 'literal <thi');
  a.textContent += h.context.filterAssistantReasoningChunk('', a, true);
  assert.equal(a.textContent, 'literal <thi');
});

test('same-assistant reconnect retains the hidden block until its close', () => {
  const h = reasoningHarness(), a = h.bubble();
  h.token(a, 'prefix<think>');
  // A reconnect supplies the same bubble to the active renderer.
  h.token(a, 'hidden-after-reconnect</think>suffix');
  assert.equal(a.textContent, 'prefixsuffix');
});

test('complete-answer sanitizer is independent of an unfinished streamed block', () => {
  const h = reasoningHarness(), a = h.bubble();
  h.token(a, '<think>');
  assert.equal(h.context.stripAssistantReasoningBlocks('<think>hidden</think> final answer'), 'final answer');
  assert.equal(h.context.stripAssistantReasoningBlocks('visible <reasoning>unfinished-hidden'), 'visible');
  assert.equal(h.context.stripAssistantReasoningBlocks('literal <thi'), 'literal <thi');
});

function hydrationHarness() {
  let calls = 0;
  const messages = [];
  const context = vm.createContext({
    state: { currentSessionId: 42 }, dom: { chatMessages: { children: messages } },
    sessionIdFromPayload: p => p.sessionId, restoreCurrentSessionId: () => 42,
    normalizeSessionIdValue: n => n, beginChatTransitionDebugTurn() {},
    apiCall: async () => { calls++; return context.response(); },
    response: async () => ({ json: async () => ({ found: true, messages: [{ role: 'assistant', content: 'restored' }] }) }),
    validateSessionDetail: (sid, detail) => detail.valid === false ? null : detail,
    rememberCurrentSessionId() {}, clearSelectionEntropyTrace() {},
    appendMessage: (role, content) => { const node = { role, content }; messages.push(node); return node; },
    applyRestoredTerminalStoppedState() {}, applyRestoredSessionSettings() {}, restoreSessionModeBadge() {},
    resumeStoredRunIfNeeded() {}, focusRestoredComposerIfDocumentOwned() {},
    clearSessionModeDiagnostics() {}, clearActiveRunIdentity() {}, setStatusRailValue() {},
    forgetCurrentSessionId() { context.state.currentSessionId = null; },
    clearOrphanedSessionlessTranscript() { messages.length = 0; },
    isHttp403: error => error?.status === 403,
  });
  vm.runInContext('let restoredSessionHydrated = false; let restoredSessionHydrationGeneration = 0;\n' +
    activeFunction('hydrateRestoredSessionTranscript'), context);
  return { context, messages, calls: () => calls, hydrate: () => context.hydrateRestoredSessionTranscript(),
    run: text => vm.runInContext(text, context) };
}

for (const failure of ['503', 'invalid-json', 'invalid-detail']) {
  test(`restoration retries after ${failure} and hydrates only once`, async () => {
    const h = hydrationHarness(), success = h.context.response;
    h.context.response = async () => {
      if (failure === '503') throw Object.assign(new Error('synthetic transient'), { status: 503 });
      return { json: async () => {
        if (failure === 'invalid-json') throw new SyntaxError('synthetic malformed fixture');
        return { valid: false };
      } };
    };
    assert.equal(await h.hydrate(), false);
    h.context.response = success;
    assert.equal(await h.hydrate(), true);
    assert.equal(await h.hydrate(), false);
    assert.equal(h.calls(), 2);
    assert.equal(h.messages.length, 1);
  });
}

test('concurrent restoration requests share the in-flight exclusion', async () => {
  const h = hydrationHarness(), success = h.context.response;
  let resolve;
  h.context.response = () => new Promise(done => { resolve = done; });
  const first = h.hydrate();
  assert.equal(await h.hydrate(), false);
  assert.equal(h.calls(), 1);
  resolve(await success());
  assert.equal(await first, true);
  assert.equal(h.messages.length, 1);
});

test('a failed old generation cannot unlock a newer successful session', async () => {
  const h = hydrationHarness();
  let reject;
  h.context.response = () => new Promise((_, fail) => { reject = fail; });
  const old = h.hydrate();
  h.run('restoredSessionHydrationGeneration++; restoredSessionHydrated = true; state.currentSessionId = 43;');
  reject(Object.assign(new Error('synthetic stale failure'), { status: 503 }));
  assert.equal(await old, false);
  assert.equal(h.run('restoredSessionHydrated'), true);
  assert.equal(h.context.state.currentSessionId, 43);
});

test('stale transcript responses do not append and may revalidate the same session', async () => {
  const h = hydrationHarness(), success = h.context.response;
  let resolve;
  h.context.response = () => new Promise(done => { resolve = done; });
  const old = h.hydrate();
  h.messages.push({ role: 'user', content: 'new live message' });
  resolve(await success());
  assert.equal(await old, false);
  h.context.response = success;
  assert.equal(await h.hydrate(), false); // Revalidate without duplicating the visible transcript.
  assert.equal(h.calls(), 2);
  assert.equal(h.messages.length, 1);
  assert.equal(h.run('restoredSessionHydrated'), true);
});

for (const status of [403, 404]) {
  test(`restoration ${status} still forgets the inaccessible session`, async () => {
    const h = hydrationHarness();
    h.context.response = async () => { throw Object.assign(new Error('synthetic inaccessible'), { status }); };
    assert.equal(await h.hydrate(), false);
    assert.equal(h.context.state.currentSessionId, null);
    assert.equal(h.messages.length, 0);
  });
}
