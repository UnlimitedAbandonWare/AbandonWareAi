'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('main/resources/static/js/chat-trace-ui.js', 'utf8');

class Element {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.parentElement = null;
    this.dataset = {};
    this.className = '';
    this.textContent = '';
    this.listeners = {};
    this.open = false;
    this.style = {};
  }
  get nodeType() { return 1; }
  get classList() {
    const names = this.className.split(/\s+/).filter(Boolean);
    names.contains = name => names.includes(name);
    return names;
  }
  get childNodes() { return this.children; }
  get cells() { return this.children.filter(child => ['TD', 'TH'].includes(child.tagName)); }
  get innerHTML() { return this.textContent + this.children.map(child => child.outerHTML).join(''); }
  get outerHTML() { return '<' + this.tagName + '>' + this.innerHTML + '</' + this.tagName + '>'; }
  closest(tag) {
    for (let node = this; node; node = node.parentElement) {
      if (node.tagName === tag.toUpperCase()) return node;
    }
    return null;
  }
  get isConnected() { return Boolean(this.connected || this.parentElement?.isConnected); }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  appendChild(child) {
    if (child.nodeType === 11) {
      [...child.children].forEach(node => this.appendChild(node));
      return child;
    }
    if (child.parentElement) child.remove();
    child.parentElement = this;
    this.children.push(child);
    return child;
  }
  insertBefore(child, reference) {
    if (child.parentElement) child.remove();
    const index = this.children.indexOf(reference);
    this.children.splice(index < 0 ? this.children.length : index, 0, child);
    child.parentElement = this;
    return child;
  }
  replaceChildren(...children) {
    this.children.forEach(child => { child.parentElement = null; });
    this.children = [];
    this.append(...children);
  }
  after(next) {
    const siblings = this.parentElement.children;
    siblings.splice(siblings.indexOf(this) + 1, 0, next);
    next.parentElement = this.parentElement;
  }
  setAttribute() {}
  addEventListener(name, callback) { this.listeners[name] = callback; }
  querySelectorAll(selector) {
    const all = [];
    const walk = node => {
      for (const child of node.children || []) {
        const match = /^(\w+)?(?:\.([\w-]+))?$/.exec(selector);
        if ((selector === '[data-role="trace"]' && child.dataset?.role === 'trace') ||
            (selector === '[class]' && child.className) ||
            (match && (!match[1] || child.tagName === match[1].toUpperCase()) &&
              (!match[2] || child.classList.includes(match[2])))) all.push(child);
        walk(child);
      }
    };
    walk(this);
    return all;
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  remove() {
    if (!this.parentElement) return;
    const siblings = this.parentElement.children;
    siblings.splice(siblings.indexOf(this), 1);
    this.parentElement = null;
  }
}

class Fragment extends Element {
  constructor() { super('fragment'); }
  get nodeType() { return 11; }
}

function harness(options = {}) {
  const root = new Element('div');
  root.connected = true;
  const admin = new Element('div');
  const toggle = { checked: options.enabled !== false };
  const sanitizerCalls = [];
  const fetchCalls = [];
  const documentListeners = {};
  const document = {
    createElement: tag => new Element(tag),
    querySelector: selector => selector === '[data-admin-diagnostics]' ? admin
      : selector === '[data-chat-trace-toggle]' ? toggle : null,
    querySelectorAll: selector => root.querySelectorAll(selector),
    addEventListener(name, listener) { documentListeners[name] = listener; }
  };
  const window = { location: { href: 'http://localhost/chat', origin: 'http://localhost' },
    DOMPurify: options.noSanitizer ? null : { sanitize(html, config) {
    sanitizerCalls.push({ html, config });
    const fragment = new Fragment();
    if (options.pipelineRows) {
      const details = new Element('details');
      details.className = 'search-trace';
      const table = new Element('table');
      table.className = 'trace-pipeline-events-table';
      const body = new Element('tbody');
      for (const values of options.pipelineRows) {
        const row = new Element('tr');
        for (const value of values) {
          const cell = new Element('td');
          cell.textContent = value;
          row.appendChild(cell);
        }
        body.appendChild(row);
      }
      table.appendChild(body);
      details.appendChild(table);
      fragment.appendChild(details);
      return fragment;
    }
    if (options.stepRows) {
      const details = new Element('details');
      details.className = 'search-trace';
      const panel = new Element('details');
      panel.className = 'trace-steps-panel';
      const controls = new Element('div');
      controls.className = 'trace-steps-controls';
      const table = new Element('table');
      table.className = 'trace-steps-table';
      const body = new Element('tbody');
      for (const values of options.stepRows) {
        const row = new Element('tr');
        for (const value of values) {
          const cell = new Element('td');
          cell.textContent = value;
          row.appendChild(cell);
        }
        body.appendChild(row);
      }
      table.appendChild(body);
      panel.append(controls, table);
      details.appendChild(panel);
      fragment.appendChild(details);
      return fragment;
    }
    if (options.metadataHeading) {
      if (options.metadataWrapped) fragment.appendChild(new Element('table'));
      const section = new Element('section');
      const heading = new Element('h3');
      heading.textContent = options.metadataHeading;
      section.appendChild(heading);
      for (let index = 0; index < (options.metadataLists || 1); index++) {
        const list = new Element('dl');
        const term = new Element('dt');
        term.textContent = 'Reason';
        const value = new Element('dd');
        value.textContent = 'safe summary';
        list.append(term, value);
        section.appendChild(list);
      }
      fragment.appendChild(section);
      return fragment;
    }
    const details = new Element('details');
    details.className = 'search-trace';
    details.textContent = 'sanitized body';
    fragment.appendChild(details);
    return fragment;
  } } };
  const context = vm.createContext({ document, window, AbortController, URL,
    setTimeout: options.setTimeout || setTimeout,
    clearTimeout: options.clearTimeout || clearTimeout,
    fetch: async (url, init) => {
      fetchCalls.push({ url, init });
      if (options.fetchImpl) return options.fetchImpl(url, init);
      return { url: new URL(url, window.location.href).href, redirected: false,
        headers: { get: name => name.toLowerCase() === 'content-type' ? 'text/html' : null },
        ...(options.fetchResponse || { ok: false, status: 404 }) };
    } });
  vm.runInContext(source, context);
  const assistant = () => {
    const node = new Element('div');
    root.appendChild(node);
    return node;
  };
  const setEnabled = value => {
    toggle.checked = value;
    documentListeners.change?.({
      target: { matches: selector => selector === '[data-chat-trace-toggle]' }
    });
  };
  return { root, assistant, ui: window.AwxChatTraceUi, sanitizerCalls, fetchCalls, toggle, setEnabled };
}

test('one panel per assistant is updated in place and keeps its open state', () => {
  const h = harness();
  const first = h.assistant();
  const second = h.assistant();
  const panelA = h.ui.upsert(first, { html: '<details class="search-trace">prefetch</details>' });
  panelA.open = true;
  assert.equal(h.ui.upsert(first, { html: '<details class="search-trace">final</details>' }), panelA);
  const panelB = h.ui.upsert(second, { html: '<details class="search-trace">other</details>' });
  assert.notEqual(panelA, panelB);
  assert.equal(panelA.open, true);
  assert.equal(h.root.children[1].className, 'awx-trace-slot');
  assert.equal(h.root.children[1].children[0], panelA);
  assert.equal(h.root.children[3].children[0], panelB);
  assert.equal(h.sanitizerCalls.length, 3);
  assert.equal(h.sanitizerCalls[0].config.RETURN_DOM_FRAGMENT, true);
  assert.ok(!h.sanitizerCalls[0].config.ALLOWED_TAGS.includes('script'));
  assert.ok(!h.sanitizerCalls[0].config.ALLOWED_ATTR.includes('href'));
});

test('signal and score diagnostics stay with their assistant and dedupe replayed events', () => {
  const h = harness();
  const first = h.assistant();
  const second = h.assistant();
  const trace = new Element('div');
  const score = new Element('div');
  const replay = new Element('div');
  const other = new Element('div');
  h.ui.upsertDiagnostic(first, 'trace', trace);
  h.ui.upsertDiagnostic(first, 'score', score, 'event-1');
  h.ui.upsertDiagnostic(first, 'score', replay, 'event-1');
  h.ui.upsertDiagnostic(second, 'score', other, 'event-1');
  assert.deepEqual(h.root.children[1].children[0].children[1].children[0].children,
    [trace, replay]);
  assert.deepEqual(h.root.children[3].children[0].children[1].children[0].children,
    [other]);
  assert.equal(score.parentElement, null);
});

test('score history is bounded and OFF does not insert diagnostics', () => {
  const h = harness();
  const assistant = h.assistant();
  for (let i = 0; i < 35; i++) {
    h.ui.upsertDiagnostic(assistant, 'score', new Element('div'), 'event-' + i);
  }
  const panel = h.root.children[1].children[0];
  assert.equal(panel.children[1].children[0].children.length, 32);
  const off = harness({ enabled: false });
  assert.equal(off.ui.upsertDiagnostic(off.assistant(), 'trace', new Element('div')), null);
  assert.equal(off.root.children.length, 1);
});

test('sanitized trace step controls filter and sort displayed cells without source scripts', () => {
  const h = harness({ stepRows: [
    ['1', 'redacted-a', '3', '0', '1,50s'],
    ['2', 'redacted-b', '2', '2', '50ms'],
    ['3', 'redacted-c', '1', '1', '?']
  ] });
  const panel = h.ui.upsert(h.assistant(), { html: '<details class="search-trace"></details>' });
  const steps = panel.querySelector('details.trace-steps-panel');
  const controls = steps.querySelector('.trace-steps-controls');
  const body = steps.querySelector('tbody');
  const [nonOk, slow, query, sort, direction] = controls.children;
  const original = [...body.children];
  assert.equal(controls.children.length, 5);
  nonOk.children[0].checked = true;
  nonOk.children[0].listeners.change();
  assert.deepEqual(body.children.map(row => row.hidden), [false, true, true]);
  nonOk.children[0].checked = false;
  slow.children[0].checked = true;
  slow.children[0].listeners.change();
  assert.deepEqual(body.children.map(row => row.hidden), [false, true, true]);
  slow.children[0].checked = false;
  query.value = 'redacted-b';
  query.listeners.input();
  assert.deepEqual(body.children.map(row => row.hidden), [true, false, true]);
  query.value = '';
  query.listeners.input();
  sort.value = 'took';
  direction.value = 'desc';
  sort.listeners.change();
  assert.deepEqual(body.children, [original[0], original[1], original[2]]);
  direction.value = 'asc';
  direction.listeners.change();
  assert.deepEqual(body.children, [original[1], original[0], original[2]]);
  assert.equal(h.sanitizerCalls[0].config.ALLOW_DATA_ATTR, false);
  assert.ok(!h.sanitizerCalls[0].config.ALLOWED_TAGS.includes('script'));
});

test('pipeline event stage, non-ok, slow filters and numeric sort use displayed cells', () => {
  const h = harness({ pipelineRows: [
    ['1', 'fetch', 'retrieve.web', 'query', 'success', 'returned=3 afterFilter=2 selected=2 ms=1500', '', ''],
    ['2', 'rerank', 'rerank.onnx', 'score', 'failed', 'returned=1 afterFilter=0 selected=0 ms=50', '', ''],
    ['3', 'fetch', 'retrieve.vector', 'query', 'success', 'returned=2 afterFilter=1 selected=1 ms=-', '', '']
  ] });
  const panel = h.ui.upsert(h.assistant(), { html: '<details class="search-trace"></details>' });
  const table = panel.querySelector('table.trace-pipeline-events-table');
  const controls = panel.querySelector('.trace-pipeline-controls');
  assert.ok(controls);
  const [nonOk, slow, stage, sort, direction] = controls.children;
  const body = table.querySelector('tbody');
  const original = [...body.children];
  nonOk.children[0].checked = true;
  nonOk.children[0].listeners.change();
  assert.deepEqual(body.children.map(row => row.hidden), [true, false, true]);
  nonOk.children[0].checked = false;
  slow.children[0].checked = true;
  slow.children[0].listeners.change();
  assert.deepEqual(body.children.map(row => row.hidden), [false, true, true]);
  slow.children[0].checked = false;
  stage.value = 'retrieve.vector';
  stage.listeners.change();
  assert.deepEqual(body.children.map(row => row.hidden), [true, true, false]);
  stage.value = '';
  stage.listeners.change();
  sort.value = 'returned';
  direction.value = 'desc';
  sort.listeners.change();
  assert.deepEqual(body.children, [original[0], original[2], original[1]]);
  assert.equal(h.sanitizerCalls[0].config.ALLOW_DATA_ATTR, false);
});

test('OFF sends no debug query and leaves no live detail panel', () => {
  const h = harness({ enabled: false });
  assert.equal(h.ui.withDebugQuery('/api/chat/stream?attach=true'), '/api/chat/stream?attach=true');
  assert.equal(h.ui.upsert(h.assistant(), { html: '<details>private</details>' }), null);
  assert.equal(h.root.querySelectorAll('[data-role="trace"]').length, 0);
  assert.equal(h.sanitizerCalls.length, 0);
});

test('restored snapshot stays hidden when diagnostics start OFF and appears on ON', () => {
  const h = harness({ enabled: false });
  const panel = h.ui.restore(h.assistant(), { snapshotId: 'saved-while-off', fields: {} });
  assert.ok(panel);
  assert.equal(panel.hidden, true);
  assert.equal(h.fetchCalls.length, 0);
  h.setEnabled(true);
  assert.equal(panel.hidden, false);
});

test('turning diagnostics OFF hides a restored snapshot and ON restores its summary', () => {
  const h = harness();
  const panel = h.ui.restore(h.assistant(), { snapshotId: 'saved-before-off', fields: {} });
  assert.equal(panel.hidden, false);
  h.setEnabled(false);
  assert.equal(panel.hidden, true);
  h.setEnabled(true);
  assert.equal(panel.hidden, false);
});

test('restore requests only the exact snapshot id on open and keeps summary on 404', async () => {
  const h = harness();
  const panel = h.ui.restore(h.assistant(), {
    turnId: 7, snapshotId: 'exact-snapshot-9', fields: { traceTurnId: '812' }
  });
  assert.ok(panel);
  assert.equal(h.fetchCalls.length, 0);
  panel.open = true;
  panel.listeners.toggle();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.fetchCalls.length, 1);
  assert.equal(h.fetchCalls[0].url, '/api/diagnostics/trace/snapshots/exact-snapshot-9/html');
  assert.equal(h.fetchCalls[0].init.credentials, 'same-origin');
  assert.equal(panel.children[1].children[0].children[1].textContent, '812');
  assert.match(panel.children[2].children[0].textContent, /찾을 수 없음/);
});

test('owned detail uses its session endpoint and never falls back globally on 404', async () => {
  const h = harness();
  const panel = h.ui.restore(h.assistant(), {
    turnId: 7, snapshotId: 'owned-snapshot', fields: { sessionId: '42' }
  });
  panel.open = true;
  panel.listeners.toggle();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.fetchCalls.length, 1);
  assert.equal(h.fetchCalls[0].url, '/api/chat/sessions/42/traces/owned-snapshot/html');
  const download = panel.children[1].children.find(child => child.className === 'awx-trace-download');
  assert.equal(download.href, '/api/chat/sessions/42/traces/owned-snapshot/html?format=bundle');
  assert.equal(download.download, 'answer-trace-bundle.zip');
  assert.equal(h.fetchCalls[0].init.cache, 'no-store');
  assert.match(panel.children[2].children[0].textContent, /찾을 수 없음/);
});

test('a redirected 200 login page is an unexpected response, not loaded trace', async () => {
  const h = harness({ fetchResponse: { ok: true, status: 200, redirected: true,
    url: 'http://localhost/login', text: async () => '<html><body>login</body></html>' } });
  const panel = h.ui.restore(h.assistant(), { snapshotId: 'owned', fields: { sessionId: '42' } });
  panel.open = true;
  panel.listeners.toggle();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(panel.dataset.traceError, 'unexpected_response');
  assert.equal(h.sanitizerCalls.length, 0);
});

test('a 200 response with the wrong content type is not treated as trace HTML', async () => {
  const h = harness({ fetchResponse: { ok: true, status: 200,
    headers: { get: () => 'application/json' },
    text: async () => '<details data-trace-redacted="1" class="search-trace">safe</details>' } });
  const panel = h.ui.restore(h.assistant(), { snapshotId: 'owned', fields: { sessionId: '42' } });
  panel.open = true;
  panel.listeners.toggle();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(panel.dataset.traceError, 'unexpected_response');
  assert.equal(h.sanitizerCalls.length, 0);
});

for (const storage of ['ring', 'durable_projection']) {
  test('owned trace identifies ' + storage + ' storage provenance', async () => {
    const h = harness({ fetchResponse: { ok: true, status: 200,
      headers: { get: name => name.toLowerCase() === 'content-type' ? 'text/html' : storage },
      text: async () => '<details data-trace-redacted="1" class="search-trace">safe</details>' } });
    const panel = h.ui.restore(h.assistant(), { snapshotId: 'owned', fields: { sessionId: '42' } });
    panel.open = true;
    panel.listeners.toggle();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(panel.dataset.traceStorage, storage);
    assert.match(panel.children[2].children[1].textContent,
      storage === 'ring' ? /메모리/ : /저장된 진단 요약/);
  });
}

test('invalid owned session bindings do not create a global snapshot request', () => {
  for (const sessionId of ['0', '-1', '../other', '01', '9007199254740992', 42, null]) {
    const h = harness();
    assert.equal(h.ui.restore(h.assistant(), { snapshotId: 'owned', fields: { sessionId } }), null);
    assert.equal(h.fetchCalls.length, 0);
  }
});

test('changing session ownership invalidates an in-flight request for the same snapshot', async () => {
  const h = harness();
  const assistant = h.assistant();
  const panel = h.ui.restore(assistant, { snapshotId: 'owned', fields: { sessionId: '42' } });
  panel.open = true;
  panel.listeners.toggle();
  await new Promise(resolve => setImmediate(resolve));
  h.ui.restore(assistant, { snapshotId: 'owned', fields: { sessionId: '43' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.fetchCalls.length, 2);
  assert.equal(h.fetchCalls[1].url, '/api/chat/sessions/43/traces/owned/html');
});

test('missing DOMPurify fails closed without inserting the supplied HTML', () => {
  const h = harness({ noSanitizer: true });
  const panel = h.ui.upsert(h.assistant(), { html: '<img src=x onerror=alert(1)>' });
  assert.ok(panel);
  assert.match(panel.children[2].children[0].textContent, /표시 불가/);
});

for (const [kind, heading] of [
  ['trace-memory', 'Trace Memory Checkpoint'],
  ['chat-harmony', 'Chat Harmony Trace']
]) {
  test('stored ' + kind + ' metadata-only section renders as summary only', async () => {
    const html = '<section data-trace="' + kind +
      '" data-kind="metadata-only"><h3>' + heading + '</h3><dl><dt>Reason</dt><dd>safe summary</dd></dl></section>';
    const h = harness({ metadataHeading: heading,
      fetchResponse: { ok: true, status: 200, text: async () => html } });
    const panel = h.ui.restore(h.assistant(), { snapshotId: 'snap-' + kind, fields: {} });
    panel.open = true;
    panel.listeners.toggle();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(panel.children[2].children[0].tagName, 'DETAILS');
    assert.match(panel.children[2].children[0].children[0].textContent, /진단 요약만 저장됨/);
  });
}

test('unrecognized section snapshot still fails closed', async () => {
  const h = harness({ metadataHeading: 'Trace Memory Checkpoint',
    fetchResponse: { ok: true, status: 200,
      text: async () => '<section data-trace="unknown" data-kind="metadata-only"><h3>Trace Memory Checkpoint</h3></section>' } });
  const panel = h.ui.restore(h.assistant(), { snapshotId: 'unknown-section', fields: {} });
  panel.open = true;
  panel.listeners.toggle();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(panel.children[2].children[0].textContent, /표시 불가/);
});

for (const [kind, heading, listCount] of [
  ['trace-memory', 'Trace Memory Checkpoint', 1],
  ['chat-harmony', 'Chat Harmony Trace', 3]
]) {
  test('producer-wrapped ' + kind + ' snapshot keeps only sanitized summary', async () => {
    const html = '<!doctype html><html><head><meta charset="utf-8"/>' +
      '<title>Trace Snapshot snap-1</title><style>body{display:block}</style></head><body>' +
      '<h2>Trace Snapshot</h2><table><tr><td>id</td><td>snap-1</td></tr></table>' +
      '<section data-trace="' + kind + '" data-kind="metadata-only"><h3>' +
      heading + '</h3>' + '<dl><dt>Reason</dt><dd>safe summary</dd></dl>'.repeat(listCount) +
      '</section></body></html>';
    const h = harness({ metadataHeading: heading, metadataWrapped: true, metadataLists: listCount,
      fetchResponse: { ok: true, status: 200, text: async () => html } });
    const panel = h.ui.restore(h.assistant(), { snapshotId: 'wrapped-' + kind, fields: {} });
    panel.open = true;
    panel.listeners.toggle();
    await new Promise(resolve => setImmediate(resolve));
    const shown = panel.children[2].children[0];
    assert.equal(shown.tagName, 'DETAILS');
    assert.equal(shown.children[1].tagName, 'SECTION');
    assert.equal(shown.children[1].children.filter(node => node.tagName === 'DL').length, listCount);
    assert.equal(h.sanitizerCalls.length, 1);
    assert.equal(h.sanitizerCalls[0].config.ALLOW_DATA_ATTR, false);
  });
}

test('unrecognized whole document containing a metadata marker is not projected', async () => {
  const html = '<html><body><section data-trace="trace-memory" data-kind="metadata-only">' +
    '<h3>Trace Memory Checkpoint</h3><dl><dt>Reason</dt><dd>safe</dd></dl></section></body></html>';
  const h = harness({ metadataHeading: 'Trace Memory Checkpoint',
    fetchResponse: { ok: true, status: 200, text: async () => html } });
  const panel = h.ui.restore(h.assistant(), { snapshotId: 'arbitrary-document', fields: {} });
  panel.open = true;
  panel.listeners.toggle();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(panel.children[2].children[0].textContent, /표시 불가/);
});

test('two stalled fetches time out, free both slots, and require an explicit retry', async () => {
  const timers = [];
  const h = harness({
    fetchImpl: () => new Promise(() => {}),
    setTimeout: callback => { timers.push(callback); return timers.length; },
    clearTimeout: () => {}
  });
  const panels = [1, 2, 3].map(id =>
    h.ui.restore(h.assistant(), { snapshotId: 'stalled-' + id, fields: {} }));
  panels.forEach(panel => { panel.open = true; panel.listeners.toggle(); });
  assert.equal(h.fetchCalls.length, 2);
  assert.match(panels[2].children[2].children[0].textContent, /진행 중/);
  timers[0]();
  timers[1]();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(panels[0].children[2].children[0].textContent, /시간 초과/);
  assert.match(panels[1].children[2].children[0].textContent, /시간 초과/);
  assert.equal(h.fetchCalls.length, 2);
  panels[2].listeners.toggle();
  assert.equal(h.fetchCalls.length, 3);
});
