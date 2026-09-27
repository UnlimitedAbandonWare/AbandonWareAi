// Selection replay 진단 게이트 검증 — 실제 chat.js 를 vm 에 올려 ?debug= 유무로 카드 렌더를 확인한다.
// 기대: debug off → renderSelectionEntropyTrace null + 카드 미생성, debug on → 카드 렌더.
const path = require('path');
const fs = require('fs');
const vm = require('vm');

const CHAT_JS = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'chat.js');
const source = fs.readFileSync(CHAT_JS, 'utf8');

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

function dataSelectorDescriptor(selector) {
  const m = String(selector || '').match(/^\[data-([a-z0-9-]+)(?:=["']([^"']*)["'])?\]$/i);
  if (!m) return null;
  const key = m[1].replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());
  return { key, hasValue: m[2] !== undefined, value: m[2] };
}

function matchesSelector(node, selector) {
  const d = dataSelectorDescriptor(selector);
  if (d) {
    if (!Object.prototype.hasOwnProperty.call(node?.dataset || {}, d.key)) return false;
    return !d.hasValue || String(node.dataset[d.key]) === d.value;
  }
  return String(node?.tagName || '').toLowerCase() === String(selector || '').toLowerCase();
}

function fakeElement(tag) {
  const el = {
    tagName: String(tag || '').toUpperCase(),
    id: '',
    dataset: {},
    style: {},
    children: [],
    childNodes: [],
    parentElement: null,
    textContent: '',
    innerHTML: '',
    value: '',
    checked: false,
    disabled: false,
    title: '',
    className: '',
    open: false,
    listeners: {},
    appendChild(child) {
      if (child) child.parentElement = this;
      this.children.push(child);
      this.childNodes.push(child);
      return child;
    },
    append(...children) { children.forEach((c) => this.appendChild(c)); },
    replaceChildren(...children) {
      this.children.forEach((c) => { if (c) c.parentElement = null; });
      this.children = [];
      this.childNodes = [];
      children.forEach((c) => this.appendChild(c));
    },
    replaceWith(node) {
      const parent = this.parentElement;
      if (!parent) return;
      const idx = parent.children.indexOf(this);
      if (idx >= 0) parent.children.splice(idx, 1, node);
      const idx2 = parent.childNodes.indexOf(this);
      if (idx2 >= 0) parent.childNodes.splice(idx2, 1, node);
      if (node) node.parentElement = parent;
      this.parentElement = null;
    },
    remove() {
      const parent = this.parentElement;
      if (!parent) return;
      const idx = parent.children.indexOf(this);
      if (idx >= 0) parent.children.splice(idx, 1);
      const idx2 = parent.childNodes.indexOf(this);
      if (idx2 >= 0) parent.childNodes.splice(idx2, 1);
      this.parentElement = null;
    },
    querySelector(selector) {
      return deepQuery(this, selector, false);
    },
    querySelectorAll(selector) {
      return deepQuery(this, selector, true);
    },
    setAttribute(name, value) { this['attr_' + name] = String(value); },
    getAttribute(name) { return Object.prototype.hasOwnProperty.call(this, 'attr_' + name) ? this['attr_' + name] : null; },
    hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this, 'attr_' + name); },
    removeAttribute(name) { delete this['attr_' + name]; },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    removeEventListener() {},
    dispatchEvent() { return true; },
    click() {},
    focus() {},
    closest() { return null; },
    scrollIntoView() {},
    getBoundingClientRect() { return { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 }; }
  };
  return el;
}

function deepQuery(root, selector, all) {
  const out = [];
  const walk = (node) => {
    for (const child of node.children || []) {
      if (matchesSelector(child, selector)) {
        if (!all) return child;
        out.push(child);
      }
      const found = walk(child);
      if (found && !all) return found;
    }
    return null;
  };
  const r = walk(root);
  return all ? out : r;
}

const elementsById = new Map();
const documentStub = {
  getElementById(id) {
    if (!elementsById.has(id)) {
      const el = fakeElement('div');
      el.id = id;
      elementsById.set(id, el);
    }
    return elementsById.get(id);
  },
  querySelector() { return null; },
  querySelectorAll() { return []; },
  createElement(tag) { return fakeElement(tag); },
  createTextNode(text) { return { nodeType: 3, textContent: String(text ?? '') }; },
  addEventListener() {},
  removeEventListener() {},
  dispatchEvent() { return true; },
  body: fakeElement('body'),
  documentElement: fakeElement('html'),
  hidden: false,
  visibilityState: 'visible'
};

const storage = () => {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
    clear: () => m.clear()
  };
};

const windowStub = {
  location: { search: '', href: 'http://127.0.0.1/chat' },
  addEventListener() {},
  removeEventListener() {},
  sessionStorage: storage(),
  localStorage: storage(),
  setInterval: () => 0,
  clearInterval: () => {},
  setTimeout: (fn) => 0,
  clearTimeout: () => {},
  fetch: async () => ({ ok: false, status: 403, headers: { get: () => null }, json: async () => ({}), text: async () => '' }),
  matchMedia: () => ({ matches: false, addEventListener() {}, addListener() {} }),
  navigator: { userAgent: 'verify-harness' },
  innerWidth: 1280,
  innerHeight: 720
};

const context = vm.createContext({
  window: windowStub,
  document: documentStub,
  location: windowStub.location,
  navigator: windowStub.navigator,
  sessionStorage: windowStub.sessionStorage,
  localStorage: windowStub.localStorage,
  URLSearchParams,
  URL,
  console,
  setTimeout,
  clearTimeout,
  setInterval: () => 0,
  clearInterval: () => {},
  fetch: windowStub.fetch,
  performance,
  AbortController,
  EventSource: class { constructor() {} addEventListener() {} close() {} },
  MutationObserver: class { observe() {} disconnect() {} },
  ResizeObserver: class { observe() {} unobserve() {} disconnect() {} },
  IntersectionObserver: class { observe() {} unobserve() {} disconnect() {} },
  crypto: { randomUUID: () => '00000000-0000-4000-8000-000000000000' },
  CustomEvent,
  Event,
  requestAnimationFrame: (fn) => 0,
  matchMedia: windowStub.matchMedia
});
context.globalThis = context;

vm.runInContext(source, context, { filename: 'chat.js' });

const signal = {
  schema: 'awx.selection-entropy.v1',
  mode: 'replay',
  replayAccepted: true,
  coherenceStatus: 'matched',
  replayReference: '009e8892e5b3',
  algorithmVersion: 'selection-entropy-v1',
  decisionDigest: 'b'.repeat(64),
  decisionCount: 5,
  drawCount: 4,
  stableTieBreakCount: 2,
  candidateDriftCount: 0,
  routerDrawCount: 1,
  strategyDrawCount: 1,
  ensembleDrawCount: 2,
  completionOrderDeterministic: false,
  reasonCode: ''
};

// 1) diagnostics OFF: 카드 미렌더 + 기존 카드 정리
windowStub.location.search = '?codexSmoke=contract';
context.__assistant = fakeElement('div');
context.__signal = signal;
const preCard = fakeElement('section');
preCard.dataset.selectionEntropyCard = 'true';
context.__assistant.appendChild(preCard);
let result = vm.runInContext(
  'renderSelectionEntropyTrace({ selectionEntropySignal: globalThis.__signal }, globalThis.__assistant)',
  context);
assert(result === null, 'debug OFF must not render selection replay card');
assert(context.__assistant.querySelector('[data-selection-entropy-card]') === null,
  'debug OFF must clear any prior selection replay card');

// 2) diagnostics ON: 카드 렌더
windowStub.location.search = '?codexSmoke=contract&debug=true';
result = vm.runInContext(
  'renderSelectionEntropyTrace({ selectionEntropySignal: globalThis.__signal }, globalThis.__assistant)',
  context);
assert(result !== null && result.dataset.selectionEntropyCard === 'true',
  'debug ON must render selection replay card');
assert(context.__assistant.querySelector('[data-selection-entropy-card]') === result,
  'rendered card must be attached to the assistant bubble');
assert(result.getAttribute('aria-label')?.includes('Selection replay'),
  'card must keep the readable aria label');

// 3) 다시 OFF: 카드 제거
windowStub.location.search = '?codexSmoke=contract';
result = vm.runInContext(
  'renderSelectionEntropyTrace({ selectionEntropySignal: globalThis.__signal }, globalThis.__assistant)',
  context);
assert(result === null && context.__assistant.querySelector('[data-selection-entropy-card]') === null,
  'turning diagnostics off must clear the card again');

console.log('selection-entropy-gate: PASS (debug off -> hidden/cleared, debug on -> rendered)');
