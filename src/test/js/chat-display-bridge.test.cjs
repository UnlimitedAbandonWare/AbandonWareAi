'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const bridgePath = path.join(__dirname, '..', '..', '..', 'main', 'resources', 'static', 'js', 'chat-display-bridge.js');
const corePath = path.join(__dirname, '..', '..', '..', 'main', 'resources', 'static', 'assets', 'interview', 'interview-core.js');
const core = require(corePath);
const bridge = require(bridgePath);

function fakeClassList() {
  const set = new Set();
  return {
    add: c => set.add(c), remove: c => set.delete(c), contains: c => set.has(c),
    toggle: (c, f) => { if (f === undefined) { set.has(c) ? set.delete(c) : set.add(c); } else if (f) set.add(c); else set.delete(c); },
  };
}
function fakeElement(tag) {
  const el = {
    tagName: tag.toUpperCase(), nodeType: 1, children: [], childNodes: [],
    dataset: {}, style: {}, attributes: {}, listeners: {},
    _text: '', value: '', checked: false, disabled: false, hidden: false,
    classList: fakeClassList(),
    set className(v) { this._className = v; v.split(/\s+/).filter(Boolean).forEach(c => this.classList.add(c)); },
    get className() { return this._className || ''; },
    set textContent(v) { this._text = v; this.children = []; this.childNodes = []; },
    get textContent() { return this._text + this.childNodes.map(c => c.textContent || '').join(''); },
    appendChild(c) { this.children.push(c); this.childNodes.push(c); c.parentNode = this; return c; },
    append(...cs) { cs.forEach(c => this.appendChild(typeof c === 'string' ? { nodeType: 3, textContent: c, childNodes: [] } : c)); },
    setAttribute(k, v) { this.attributes[k] = String(v); },
    getAttribute(k) { return this.attributes[k] ?? null; },
    addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); },
    matches(sel) {
      const el2 = this;
      return sel.split(',').some(s => {
        s = s.trim();
        const m = s.match(/^([a-zA-Z0-9*_-]*)((?:\.[\w-]+|\[[^\]]*\])*)$/);
        if (!m) return false;
        if (m[1] && m[1] !== '*' && el2.tagName !== m[1].toUpperCase()) return false;
        const parts = m[2].match(/\.[\w-]+|\[[^\]]*\]/g) || [];
        return parts.every(p => {
          if (p.startsWith('.')) return el2.classList.contains(p.slice(1));
          const am = p.match(/\[([^=\]]+)(?:="([^"]*)")?\]/);
          if (!am) return false;
          const name = am[1], v = am[2];
          if (name.startsWith('data-')) {
            const key = name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
            return v === undefined ? el2.dataset[key] !== undefined : el2.dataset[key] === v;
          }
          return v === undefined ? el2.attributes[name] !== undefined : el2.attributes[name] === v;
        });
      });
    },
    querySelectorAll(sel) {
      const out = [];
      const walk = n => { (n.children || []).forEach(c => { if (c.matches && c.matches(sel)) out.push(c); walk(c); }); };
      walk(this); return out;
    },
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; },
    closest() { return null; },
  };
  return el;
}
function fakeDoc() {
  const byId = {};
  return {
    createElement: t => fakeElement(t),
    createTextNode: t => ({ nodeType: 3, textContent: t, childNodes: [] }),
    getElementById: id => byId[id] || null,
    querySelector: () => null,
    _byId: byId,
  };
}

test('resolveEnabled default false; query param and storage can flip', () => {
  assert.equal(bridge.resolveEnabled({ location: { search: '' }, document: fakeDoc(), window: {}, storage: null }), false);
  assert.equal(bridge.resolveEnabled({ location: { search: '?displayBridge=1' }, document: fakeDoc(), window: {}, storage: null }), true);
  assert.equal(bridge.resolveEnabled({ location: { search: '?displayBridge=0' }, document: fakeDoc(), window: { CHAT_DISPLAY_BRIDGE_ENABLED: true }, storage: null }), false);
  const storage = { getItem: () => 'true' };
  assert.equal(bridge.resolveEnabled({ location: { search: '' }, document: fakeDoc(), window: {}, storage }), true);
});

test('mount renders nothing when disabled', () => {
  const doc = fakeDoc();
  const mountEl = fakeElement('div');
  doc._byId['display-bridge-mount'] = mountEl;
  const r = bridge.mount({ document: doc, location: { search: '', protocol: 'http:', origin: 'http://localhost' }, window: {}, storage: null });
  assert.equal(r, null);
  assert.equal(mountEl.children.length, 0);
});

test('mount renders details panel when enabled and InterviewCore present', () => {
  const doc = fakeDoc();
  const mountEl = fakeElement('div');
  doc._byId['display-bridge-mount'] = mountEl;
  const fetchMock = () => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) });
  const r = bridge.mount({ document: doc, mountEl, location: { search: '?displayBridge=1', protocol: 'http:', origin: 'http://localhost' }, window: {}, storage: null, fetch: fetchMock });
  assert.ok(r && r.root);
  assert.equal(r.root.tagName, 'DETAILS');
  assert.equal(mountEl.children.length, 1);
});

test('shortHint delegation produces same result as InterviewCore (<=120 chars)', () => {
  const sample = '인공지능은 데이터로부터 패턴을 학습한다. 조건이 달라지면 결과도 달라진다. 그러므로 검증이 필요하다. ' .repeat(6);
  const viaBridge = bridge.shortHint(sample);
  const viaCore = core.shortHint(sample);
  assert.equal(viaBridge, viaCore);
  assert.ok(Array.from(viaBridge).length <= 120);
  assert.ok(Array.from(viaBridge).length >= 12);
});

test('cardText validation reused: over-120 hint throws, valid ok', () => {
  assert.throws(() => core.cardText('가'.repeat(121), 'hint'));
  assert.equal(core.cardText('짧은 힌트', 'hint'), '짧은 힌트');
});

test('extractAnswerText skips evidence rail and debug nodes', () => {
  const doc = fakeDoc();
  const chatWin = fakeElement('div');
  doc._byId['chatWindow'] = chatWin;
  const msg = fakeElement('div');
  msg.classList.add('message'); msg.dataset.speaker = 'assistant'; msg.dataset.state = 'ready';
  msg.append({ nodeType: 3, textContent: '답변 본문입니다.', childNodes: [] });
  const rail = fakeElement('div'); rail.classList.add('evidence-rail');
  rail.append({ nodeType: 3, textContent: '출처 A', childNodes: [] });
  msg.append(rail);
  chatWin.append(msg);
  assert.equal(bridge.extractAnswerText(doc), '답변 본문입니다.');
});
