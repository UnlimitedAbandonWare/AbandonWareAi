const fs = require('fs');
const path = require('path');
const vm = require('vm');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function fakeElement(tag) {
  return {
    tag,
    textContent: '',
    children: [],
    dataset: {},
    replaceChildren(...children) {
      this.children = children;
      this.textContent = children.map((child) => child.textContent || '').join('');
    },
    appendChild(child) {
      this.children.push(child);
      this.textContent += child.textContent || '';
      return child;
    }
  };
}

const listeners = {};
const brainRoot = fakeElement('brain-root');

const context = {
  document: {
    addEventListener(name, handler) {
      listeners[name] = handler;
    },
    querySelectorAll(selector) {
      return selector === '[data-brain-state-root]' ? [brainRoot] : [];
    },
    createElement(tag) {
      return fakeElement(tag);
    }
  }
};
context.globalThis = context;
vm.createContext(context);

const script = fs.readFileSync(path.join(__dirname, '..', 'main', 'resources', 'static', 'js', 'brain-state-ui.js'), 'utf8');
vm.runInContext(script, context, { filename: 'brain-state-ui.js' });

const css = fs.readFileSync(path.join(__dirname, '..', 'main', 'resources', 'static', 'css', 'chat-style.css'), 'utf8');
const compactHeightRule = css.match(/@media\s*\(max-height:\s*760px\)\s*{[\s\S]*$/);
assert(compactHeightRule, 'max-height 760px compact viewport rule is missing');
assert(
  !/\.diagnostics-stack\s*>\s*\.brain-state-panel--compact\s*{\s*display:\s*none\s*;/m.test(compactHeightRule[0]),
  'brain-state panel is hidden at common 720px browser height'
);
assert(
  /\.diagnostics-stack\s*>\s*\.brain-state-panel--compact\s*{[\s\S]*max-height:\s*(?:5[0-9]|6[0-9])px/m.test(compactHeightRule[0]),
  'brain-state panel should remain visible as a compact strip at short heights'
);
const compactWidthRule = css.slice(css.indexOf('@media (max-width: 760px)'), css.indexOf('@media (max-height: 760px)'));
assert(compactWidthRule.includes('@media (max-width: 760px)'), 'max-width 760px mobile rule is missing');
assert(
  /\.status-rail\s*{[\s\S]*grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/m.test(compactWidthRule),
  'mobile status rail should use 3 columns so composer stays in first viewport'
);
assert(
  /\.diagnostics-stack\s*>\s*\.brain-state-panel--compact\s*{[\s\S]*max-height:\s*(?:5[0-9]|6[0-9])px/m.test(compactWidthRule),
  'mobile brain-state panel should stay compact so composer remains visible'
);
const compactPhoneRuleStart = css.indexOf('@media (max-width: 760px) and (max-height: 760px)');
assert(compactPhoneRuleStart >= 0, 'combined compact phone viewport rule is missing');
const compactPhoneRule = css.slice(compactPhoneRuleStart);
assert(
  /#chatWindow\s*{[\s\S]*min-height:\s*120px/m.test(compactPhoneRule),
  'compact phone chat window should allow the composer to stay fully visible'
);
assert(
  /\.composer\s*{[\s\S]*padding:\s*6px 8px/m.test(compactPhoneRule),
  'compact phone composer should use tighter padding'
);
assert(
  /\.composer textarea\s*{[\s\S]*min-height:\s*44px/m.test(compactPhoneRule),
  'compact phone textarea should preserve the 44px mobile interaction target'
);

listeners['brain-state:answer']({
  detail: {
    learningContext: {
      signalCount: 4,
      summaryPresent: true,
      sourceTags: ['TRAINING_TASK', 'RAG_OPS'],
      degraded: true,
      degradedReason: 'rag_support_timeout'
    }
  }
});

const rendered = brainRoot.textContent;
assert(rendered.includes('sources2'), `source tag count was not rendered from learningContext: ${rendered}`);
assert(rendered.includes('recent4'), `signal count was not rendered from learningContext: ${rendered}`);
assert(rendered.includes('anchors1'), `summaryPresent was not rendered as anchor count: ${rendered}`);
assert(rendered.includes('queryfallback'), `degraded learning context did not mark query fallback: ${rendered}`);
assert(rendered.includes('failure=rag_support_timeout'), `degradedReason was not rendered: ${rendered}`);

listeners['brain-state:answer']({
  detail: {
    learningContext: {
      degraded: true,
      degradedReason: 'token=' + 'sk-' + 'D'.repeat(24) + ' Authorization:' + ' Bearer ' + 'E'.repeat(24)
    }
  }
});

const redactedRendered = brainRoot.textContent;
assert(!redactedRendered.includes('sk-'), `brain-state panel leaked OpenAI-style token prefix: ${redactedRendered}`);
assert(!redactedRendered.includes('Bearer'), `brain-state panel leaked bearer header prefix: ${redactedRendered}`);
assert(
  redactedRendered.includes('[redacted]') || redactedRendered.includes('[secret]'),
  `brain-state panel did not mark secret redaction: ${redactedRendered}`
);

console.log('[AWX][chat-ui] brain-state detail contract OK');
