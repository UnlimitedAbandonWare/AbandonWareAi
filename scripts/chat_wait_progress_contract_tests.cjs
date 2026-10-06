const assert = require('node:assert/strict');
const fs = require('node:fs');
const { test } = require('node:test');
const vm = require('node:vm');

const css = fs.readFileSync('main/resources/static/css/chat-style.css', 'utf8');
const template = fs.readFileSync('main/resources/templates/chat-ui.html', 'utf8');

test('pending bubble has one compact waiting label and a decorative spinner', () => {
  assert.match(css, /content:\s*"답변 준비 중"/);
  assert.match(css, /\.message\.assistant\[data-state="pending"\]::after\s*\{[^}]*animation:/s);
  assert.doesNotMatch(css, /content:\s*"Assistant is preparing"/);
});

test('reduced motion stops the waiting animation', () => {
  assert.ok(/@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{[^}]*\.chat-wait-spinner\s*\{[^}]*animation:\s*none/s.test(css));
});

function element() {
  return { dataset: {}, children: [], attrs: {}, textContent: '', title: '',
    setAttribute(name, value) { this.attrs[name] = value; },
    appendChild(child) { this.children.push(child); child.parent = this; return child; },
    append(...nodes) { nodes.forEach(node => this.appendChild(node)); },
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(n => n !== this); },
    querySelector(selector) {
      const key = selector.match(/^\[data-([\w-]+)\]$/)?.[1]?.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
      for (const child of this.children) {
        if (key && Object.hasOwn(child.dataset, key)) return child;
        const found = child.querySelector?.(selector); if (found) return found;
      }
      return null;
    }
  };
}
function harness() {
  const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
  const context = vm.createContext({ document: { createElement: element },
    isAssistantStreamStopped: node => node.dataset.state === 'stopped', STREAM_STALE_WAIT_MS: 30000 });
  for (const name of ['assistantWaitLabel', 'markAssistantClientWait', 'updateAssistantWaitProgress', 'clearAssistantPendingPlaceholder']) {
    const start = source.indexOf('function ' + name + '(');
    if (start >= 0) vm.runInContext(source.slice(start, source.indexOf('\n}', start) + 2), context);
  }
  return context;
}
test('client wait is immediate and elapsed is excluded from live announcements', () => {
  const h = harness(), node = element(); node.dataset.state = 'pending';
  h.markAssistantClientWait(node, 0);
  const label = node.querySelector('[data-wait-label]');
  assert.ok(label, 'pending bubble needs a visible request-local status line');
  assert.equal(label.textContent, '답변 준비 중');
  assert.equal(label.attrs['aria-live'], 'polite');
  h.markAssistantClientWait(node, 1250);
  assert.equal(label.textContent, '답변 준비 중');
  assert.equal(node.querySelector('[data-wait-elapsed]').textContent, '대기 1초');
  assert.equal(node.querySelector('[data-wait-elapsed]').attrs['aria-hidden'], 'true');
});
test('wait uses actual stage codes and completed retrieval cannot regress', () => {
  const h = harness(), node = element(); node.dataset.state = 'pending';
  h.markAssistantClientWait(node, 0);
  assert.equal(typeof h.updateAssistantWaitProgress, 'function');
  h.updateAssistantWaitProgress(node, {code:'model_wait'});
  assert.equal(node.querySelector('[data-wait-label]').textContent, '답변 준비 중');
  h.updateAssistantWaitProgress(node, {code:'web_search_running'});
  assert.equal(node.querySelector('[data-wait-label]').textContent, '검색 중');
  h.updateAssistantWaitProgress(node, {code:'retrieval_completed_empty'});
  h.updateAssistantWaitProgress(node, {code:'web_search_running'});
  assert.equal(node.querySelector('[data-wait-label]').textContent, '검색 종료 · 자료 미확보 · 답변 준비 중');
  h.updateAssistantWaitProgress(node, {code:'answer_generation_started'});
  assert.equal(node.querySelector('[data-wait-label]').textContent, '검색 종료 · 자료 미확보 · 답변 작성 중');
});
test('terminal and first answer remove waiting DOM and late status never restarts it', () => {
  const h = harness(), node = element(); node.dataset.state = 'pending';
  h.markAssistantClientWait(node, 900);
  h.clearAssistantPendingPlaceholder(node);
  node.dataset.state = 'ready';
  h.markAssistantClientWait(node, 2000);
  assert.equal(node.querySelector('[data-wait-progress]'), null);
  assert.equal(node.dataset.waitMs, undefined);
  node.dataset.state = 'stopped'; h.markAssistantClientWait(node, 3000);
  assert.equal(node.querySelector('[data-wait-progress]'), null);
});

test('search enhancement keeps the same unchecked input and RAG default', () => {
  const input = template.match(/<input\b[^>]*id="googleSearchRescueToggle"[^>]*>/)?.[0];
  assert.ok(input);
  assert.doesNotMatch(input, /\bchecked\b/);
  assert.match(input, /aria-label="검색 보강"/);
  assert.match(template, /id="googleSearchRescueToggle"[^>]*><span>검색 보강<\/span>/);
  assert.match(template, /id="useRagToggle"[^>]*\bchecked\b/);
});
