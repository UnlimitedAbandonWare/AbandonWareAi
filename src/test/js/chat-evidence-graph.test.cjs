'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { create } = require('../../../main/resources/static/js/chat-evidence-graph.js');
const harness = fs.readFileSync('scripts/chat_ui_stream_contract_tests.js', 'utf8');
const fixtureEnd = harness.indexOf('const script = fs.readFileSync(');
assert(fixtureEnd > 0);
function setup() {
  const sandbox = { require, console, process, Buffer, TextEncoder, TextDecoder, URL,
    URLSearchParams, AbortController, setTimeout, clearTimeout, setInterval, clearInterval,
    __dirname: path.resolve('scripts') };
  vm.runInNewContext(harness.slice(0, fixtureEnd) +
    '\nglobalThis.fixture={document:context.document,fakeElement,nodeText,focus:()=>focusedElement};', sandbox);
  const f = sandbox.fixture;
  const graph = create({ document: f.document });
  let current = { sessionId: 711, runToken: 'synthetic-A', generation: 1 };
  const isCurrent = expected => Object.keys(current).every(k => current[k] === expected[k]);
  const answer = () => {
    const wrap = f.fakeElement('synthetic-message');
    const node = f.fakeElement('synthetic-answer');
    node.ownerDocument = f.document; wrap.appendChild(node);
    f.document.getElementById('chatWindow').appendChild(wrap);
    return node;
  };
  const packet = (extra = {}) => ({ identity: { ...current }, sessionId: current.sessionId,
    traceTurnId: 'synthetic-terminal', isCurrent, answerText: 'Answer [W1]',
    evidence: [{ marker: 'W1', title: 'Own source', source: 'https://example.test/document' }], ...extra });
  const panel = node => node.parentElement.querySelector('[data-answer-provenance]');
  return { f, graph, answer, packet, panel, isCurrent, current: () => current, switch: x => { current = x; } };
}
test('U0: own final produces a collapsed source-only preview and immutable answer snapshot', () => {
  const s = setup(), a = s.answer(), input = s.packet();
  assert.equal(s.graph.finalize(a, input), true);
  const view = s.graph.view(a);
  assert.equal(view.status, 'ready'); assert.equal(view.graphKind, 'source-only');
  assert.equal(view.sources[0].cited, true); assert.equal(view.sources[0].use, 'UNKNOWN');
  assert(Object.isFrozen(view) && Object.isFrozen(view.sources) && Object.isFrozen(view.sources[0]));
  input.evidence[0].title = 'Changed later';
  assert.equal(view.sources[0].title, 'Own source');
  assert.equal(s.panel(a).querySelector('[data-role="expand"]').getAttribute('aria-expanded'), 'false');
});
test('U1: numeric Long terminal traces render without exposing the raw trace identity', () => {
  for (const traceTurnId of [987654321, Number.MAX_SAFE_INTEGER]) {
    const s = setup(), a = s.answer();
    assert.equal(s.graph.finalize(a, s.packet({ traceTurnId })), true);
    assert.equal(s.graph.view(a).status, 'ready');
    assert(!JSON.stringify(s.graph.view(a)).includes(String(traceTurnId)));
    assert(!s.f.nodeText(s.panel(a)).includes(String(traceTurnId)));
  }
});

test('U1: invalid numeric or coerced terminal traces remain unavailable', () => {
  for (const traceTurnId of [0, -1, 0.5, Number.MAX_SAFE_INTEGER + 1, NaN, Infinity, true, {}]) {
    const s = setup(), a = s.answer();
    assert.equal(s.graph.finalize(a, s.packet({ traceTurnId })), false);
    assert.equal(s.graph.view(a).status, 'unavailable');
  }
});

test('U1: late A cannot attach to B; completed A survives a new run and final ACK cleanup', () => {
  const s = setup(), a = s.answer(), b = s.answer(), old = s.packet();
  s.graph.finalize(a, old); const frozen = s.graph.view(a);
  s.switch({ sessionId: 711, runToken: 'synthetic-B', generation: 2 });
  s.graph.begin(b, s.current(), s.isCurrent);
  assert.equal(s.graph.finalize(b, old), false);
  assert.equal(s.graph.view(b).status, 'pending');
  s.graph.finalize(b, s.packet({ answerText: 'B', evidence: [] }));
  s.switch({ sessionId: 711, runToken: null, generation: 3 });
  assert.strictEqual(s.graph.view(a), frozen);
  assert.equal(s.graph.view(b).sources.length, 0);
  assert.equal(s.graph.finalize(a, old), false);
});
test('U1: session switch rejects the pending old answer', () => {
  const s = setup(), a = s.answer(), old = s.packet();
  s.graph.begin(a, old.identity, s.isCurrent);
  s.switch({ sessionId: 712, runToken: 'synthetic-B', generation: 2 });
  assert.equal(s.graph.finalize(a, old), false);
  assert.equal(s.graph.view(a).status, 'unavailable');
});
test('U1: cancel rejects a late final', () => {
  const s = setup(), a = s.answer(), old = s.packet();
  s.graph.begin(a, old.identity, s.isCurrent); s.graph.invalidate(a, 'cancelled');
  assert.equal(s.graph.finalize(a, old), false);
  assert.equal(s.graph.view(a).status, 'unavailable');
});
test('U1: regeneration on the same DOM rejects old identity without poisoning the new run', () => {
  const s = setup(), a = s.answer(), old = s.packet();
  s.graph.begin(a, old.identity, s.isCurrent);
  s.switch({ sessionId: 711, runToken: 'synthetic-B', generation: 2 });
  s.graph.begin(a, s.current(), s.isCurrent);
  assert.equal(s.graph.finalize(a, old), false);
  assert.equal(s.graph.finalize(a, s.packet()), true);
});
test('U1: restored answer without its own evidence stays unavailable', () => {
  const s = setup(), a = s.answer();
  s.graph.restore(a);
  assert.equal(s.graph.view(a).status, 'unavailable');
  assert.equal(s.graph.view(a).sources.length, 0);
  assert.equal(s.graph.finalize(a, s.packet()), false);
});
test('U1: saved answer restores only its own promoted evidence without a live run token', () => {
  const s = setup(), a = s.answer(), b = s.answer();
  for (const [node, turnId, source] of [[a, 101, 'A'], [b, 102, 'B']]) {
    node.dataset.turnId = String(turnId); node.dataset.sessionId = '711';
    const restored = s.graph.restore(node, { sessionId: 711, turnId, answerText: 'Answer [W1]',
      evidence: [{ marker: 'W1', title: 'Own ' + source, source: 'https://example.test/' + source,
        lineStart: 4, lineEnd: 6 }] });
    assert.equal(restored.status, 'ready');
    assert.equal(restored.sources.length, 1); assert.equal(restored.sources[0].url, 'https://example.test/' + source);
    assert.equal(restored.sources[0].cited, true); assert.equal(restored.sources[0].use, 'UNKNOWN');
    assert.equal(restored.sources[0].locator, '줄 4–6');
    assert.equal(s.graph.finalize(node, s.packet()), false, 'late live events cannot overwrite a stored answer');
    assert(!JSON.stringify(restored).includes('runToken'));
  }
});
test('U1: saved evidence requires exact positive session and assistant message binding', () => {
  for (const extra of [{ sessionId: 712 }, { turnId: 102 }, { turnId: null }, { evidence: null }]) {
    const s = setup(), a = s.answer(); a.dataset.turnId = '101'; a.dataset.sessionId = '711';
    const value = s.graph.restore(a, { sessionId: 711, turnId: 101, answerText: 'Answer [W1]',
      evidence: [{ marker: 'W1', title: 'Own source', source: 'https://example.test/A' }], ...extra });
    assert.equal(value.status, 'unavailable'); assert.equal(value.sources.length, 0);
  }
});
test('U1: DOM removal and projection mismatch reject pending work', () => {
  for (const change of ['removed', 'version']) {
    const s = setup(), a = s.answer(), input = s.packet();
    s.graph.begin(a, input.identity, s.isCurrent);
    if (change === 'removed') a.remove();
    else input.projectionVersion = 999;
    assert.equal(s.graph.finalize(a, input), false);
    assert.equal(s.graph.view(a).status, 'unavailable');
  }
});
test('U1: deleting a completed assistant removes its sibling panel and leaves another answer intact', () => {
  const s = setup(), root = s.f.fakeElement('shared-thread'), a = s.answer(), b = s.answer();
  s.f.document.getElementById('chatWindow').appendChild(root);
  root.appendChild(a); root.appendChild(b);
  s.graph.finalize(a, s.packet());
  const panelA = root.querySelector('[data-answer-provenance]');
  s.switch({ sessionId: 711, runToken: 'synthetic-B', generation: 2 });
  s.graph.finalize(b, s.packet({ evidence: [{ title: 'Only B' }] }));
  a.remove(); s.graph.invalidate(a, 'removed');
  assert(!panelA.parentElement, 'removed assistant left an orphan evidence panel');
  assert.equal(root.querySelectorAll('[data-answer-provenance]').length, 1);
  assert(s.f.nodeText(root.querySelector('[data-answer-provenance]')).includes('Only B'));
});
test('U1: missing terminal identity, ready fallback or missing current guard is unavailable', () => {
  for (const extra of [{ traceTurnId: null }, { traceTurnId: 'ready' }, { isCurrent: null }, { sessionId: 712 }]) {
    const s = setup(), a = s.answer();
    assert.equal(s.graph.finalize(a, s.packet(extra)), false);
    assert.equal(s.graph.view(a).status, 'unavailable');
  }
});
test('U2: title/host matches and ragUsed do not establish citation or KG use', () => {
  const s = setup(), a = s.answer();
  s.graph.finalize(a, s.packet({ answerText: 'Own source at example.test', ragUsed: true, graphUsed: true }));
  assert.equal(s.graph.view(a).sources[0].cited, false);
  const text = s.f.nodeText(s.panel(a));
  assert(text.includes('검색 후보 · 인용 미확인'));
  assert(text.includes('실제 사용 미확인')); assert(!text.includes('GraphRAG'));
});
test('U2/U3: empty own evidence ignores global poison and does not invent entities or edges', () => {
  const s = setup(), a = s.answer();
  s.graph.latestSources = [{ title: 'POISON' }];
  s.graph.finalize(a, s.packet({ answerText: 'POISON [W9]', evidence: [] }));
  assert.equal(s.graph.view(a).sources.length, 0);
  assert(s.f.nodeText(s.panel(a)).includes('이 답변에 표시할 근거가 없습니다'));
  assert(!s.f.nodeText(s.panel(a)).includes('POISON'));
});
test('U0/U4: preview 5 nodes/4 links and expanded 12 nodes; all remaining sources have list access', () => {
  const s = setup(), a = s.answer();
  const evidence = Array.from({ length: 23 }, (_, i) => ({ marker: 'W' + (i + 1), title: 'Source ' + i }));
  s.graph.finalize(a, s.packet({ evidence, answerText: evidence.map(e => '[' + e.marker + ']').join(' ') }));
  const panel = s.panel(a), preview = panel.querySelector('[data-role="graph"]');
  assert.equal(preview.dataset.nodes, '5'); assert.equal(preview.dataset.links, '4');
  panel.querySelector('[data-role="expand"]').click();
  assert.equal(preview.dataset.nodes, '12'); assert(Number(preview.dataset.links) <= 16);
  assert.equal(panel.querySelector('[data-role="source-list"]').children.length, 16);
  panel.querySelector('[data-role="next-page"]').click();
  assert(s.f.nodeText(panel.querySelector('[data-role="source-list"]')).includes('Source 22'));
});
test('U3/U4/U6: unsafe links and raw IDs/snippets are excluded; attachment revision only is preserved', () => {
  const s = setup(), a = s.answer();
  s.graph.finalize(a, s.packet({ evidence: [{ marker: 'W1', title: '<script>literal</script>',
    source: 'javascript:alert(1)', sourceId: 'PRIVATE_SOURCE', runToken: 'PRIVATE_RUN', snippet: 'PRIVATE_SNIPPET',
    sourceRevision: 'invented', filePath: 'C:/private/secret.txt',
    attachment: { sourceId: 'PRIVATE_ATTACHMENT', revision: 7, filename: 'allowed.pdf', locator: 'page 2' } }] }));
  const item = s.graph.view(a).sources[0];
  assert.equal(item.url, ''); assert.equal(item.revision, '7');
  const text = JSON.stringify(s.graph.view(a)) + s.f.nodeText(s.panel(a));
  for (const bad of ['PRIVATE_', 'C:/private', 'invented', 'javascript:']) assert(!text.includes(bad));
  s.panel(a).querySelector('[data-role="source-node"]').click();
  assert(s.f.nodeText(s.panel(a)).includes('제공되지 않음'));
});
test('U4: source and relation selection have names/state; Escape closes and restores focus', () => {
  const s = setup(), a = s.answer(); s.graph.finalize(a, s.packet());
  const panel = s.panel(a), toggle = panel.querySelector('[data-role="expand"]');
  const node = panel.querySelector('[data-role="source-node"]'); node.click();
  const selected = panel.querySelector('[data-role="source-node"]');
  assert.equal(selected.getAttribute('aria-pressed'), 'true');
  assert.strictEqual(s.f.focus(), selected);
  assert(node.getAttribute('aria-label').includes('Own source'));
  assert(s.f.nodeText(panel.querySelector('[data-role="inspector"]')).includes('답변 인용'));
  panel.listeners.keydown({ key: 'Escape', preventDefault() {} });
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.strictEqual(s.f.focus(), toggle);
  panel.querySelector('[data-role="relation"]').click();
  assert(s.f.nodeText(panel.querySelector('[data-role="inspector"]')).includes('원문 관계: UNKNOWN'));
});
test('U4: credential and token URLs are never emitted', () => {
  const s = setup(), a = s.answer();
  const syntheticTokenUrl = new URL('https://example.test/');
  syntheticTokenUrl.searchParams.set('token', 'synthetic-fixture-value');
  s.graph.finalize(a, s.packet({ evidence: ['https://user:pass@example.test/', syntheticTokenUrl.href,
    'data:text/html,unsafe', '/relative'].map(source => ({ title: 'Safe label', source })) }));
  assert(s.graph.view(a).sources.every(x => x.url === ''));
});
test('U5: rendering error falls back to the same source list without throwing or touching answer body', () => {
  const s = setup(), a = s.answer(); a.textContent = 'Existing body';
  const original = s.f.document.createElement; let failed = false;
  s.f.document.createElement = tag => {
    if (tag === 'div' && !failed) { failed = true; throw new Error('synthetic renderer failure'); }
    return original(tag);
  };
  assert.doesNotThrow(() => s.graph.finalize(a, s.packet()));
  assert.equal(a.textContent, 'Existing body');
  assert(s.f.nodeText(s.panel(a)).includes('Own source'));
});
test('U5: fallback source selection exposes its own locator/revision and excerpt-unavailable detail', () => {
  const s = setup(), a = s.answer(), original = s.f.document.createElement; let failed = false;
  s.f.document.createElement = tag => {
    if (tag === 'div' && !failed) { failed = true; throw new Error('synthetic layout failure'); }
    return original(tag);
  };
  s.graph.finalize(a, s.packet({ evidence: [{ marker: 'W1', title: 'Own source',
    attachment: { filename: 'allowed.pdf', locator: 'page 8', revision: 9 } }] }));
  const panel = s.panel(a); panel.querySelector('[data-role="list-source"]').click();
  const detail = s.f.nodeText(panel.querySelector('[data-role="inspector"]'));
  assert(detail.includes('page 8') && detail.includes('9') && detail.includes('제공되지 않음'));
});
test('U5: expansion failure leaves the source list accessible without an uncaught error', () => {
  const s = setup(), a = s.answer(); s.graph.finalize(a, s.packet());
  const original = s.f.document.createElement; let failed = false;
  s.f.document.createElement = tag => {
    if (tag === 'div' && !failed) { failed = true; throw new Error('synthetic expansion failure'); }
    return original(tag);
  };
  const panel = s.panel(a);
  assert.doesNotThrow(() => panel.querySelector('[data-role="expand"]').click());
  assert.equal(panel.querySelector('[data-role="details"]').hidden, false);
  assert(s.f.nodeText(panel.querySelector('[data-role="source-list"]')).includes('Own source'));
});
test('U4/U5: failed source or relation redraw restores focus to a surviving control', () => {
  for (const role of ['source-node', 'relation']) {
    const s = setup(), a = s.answer(); s.graph.finalize(a, s.packet());
    const panel = s.panel(a), origin = panel.querySelector('[data-role="' + role + '"]');
    const toggle = panel.querySelector('[data-role="expand"]');
    origin.focus();
    const original = s.f.document.createElement; let failed = false;
    s.f.document.createElement = tag => {
      if (tag === 'div' && !failed) { failed = true; throw new Error('synthetic selection redraw failure'); }
      return original(tag);
    };
    assert.doesNotThrow(() => origin.click());
    assert.strictEqual(s.f.focus(), toggle, role + ' left focus on a removed control');
    assert(toggle.parentElement);
    assert(s.f.nodeText(panel.querySelector('[data-role="inspector"]')).includes('Own source'));
    assert(s.f.nodeText(panel.querySelector('[data-role="source-list"]')).includes('Own source'));
  }
});
test('U2: ambiguous duplicate citation markers remain unconfirmed', () => {
  const s = setup(), a = s.answer();
  s.graph.finalize(a, s.packet({ evidence: [{ marker: 'W1', title: 'One' }, { marker: 'W1', title: 'Two' }] }));
  assert(s.graph.view(a).sources.every(source => !source.cited && source.use === 'UNKNOWN'));
});
