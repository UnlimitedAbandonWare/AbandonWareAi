const fs = require('fs');
const path = require('path');
const vm = require('vm');

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function cssRuleAfter(text, scopeStart, selector) {
  const start = text.indexOf(selector, scopeStart);
  if (start < 0) return '';
  const end = text.indexOf('}', start);
  return end < 0 ? text.slice(start) : text.slice(start, end + 1);
}

function dataSelectorDescriptor(selector) {
  const match = String(selector || '').match(/^\[data-([a-z0-9-]+)(?:=["']([^"']*)["'])?\]$/i);
  if (!match) return null;
  const key = match[1].replace(/-([a-z0-9])/g, (_, letter) => letter.toUpperCase());
  return { key, hasValue: match[2] !== undefined, value: match[2] };
}

function matchesFakeSelector(node, selector) {
  const descriptor = dataSelectorDescriptor(selector);
  if (descriptor) {
    if (!Object.prototype.hasOwnProperty.call(node?.dataset || {}, descriptor.key)) return false;
    return !descriptor.hasValue || String(node.dataset[descriptor.key]) === descriptor.value;
  }
  return String(node?.tagName || '').toLowerCase() === String(selector || '').toLowerCase();
}

let focusedElement = null;

function fakeElement(id) {
  const listeners = {};
  const attributes = {};
  return {
    id,
    dataset: {},
    textContent: '',
    title: '',
    value: '',
    checked: false,
    disabled: false,
    style: {},
    children: [],
    parentElement: null,
    appendChild(child) {
      child.parentElement = this;
      this.children.push(child);
      return child;
    },
    append(...children) {
      children.forEach((child) => this.appendChild(child));
    },
    replaceChildren(...children) {
      this.children.forEach((child) => {
        if (child) child.parentElement = null;
      });
      this.children = [];
      children.forEach((child) => this.appendChild(child));
      this.textContent = children.map((child) => child.textContent || '').join('');
    },
    addEventListener(type, handler) {
      listeners[type] = handler;
    },
    setAttribute(name, value) {
      attributes[name] = String(value);
    },
    getAttribute(name) {
      return attributes[name] || null;
    },
    removeAttribute(name) {
      delete attributes[name];
    },
    click() {
      this.lastClickDefaultPrevented = false;
      const node = this;
      const event = {
        defaultPrevented: false,
        preventDefault() {
          event.defaultPrevented = true;
          node.lastClickDefaultPrevented = true;
        },
        currentTarget: this,
        target: this
      };
      const result = listeners.click && listeners.click(event);
      this.lastClickDefaultPrevented = event.defaultPrevented;
      return result;
    },
    before(node) {
      this.previousSibling = node || null;
    },
    replaceWith(node) {
      if (!this.parentElement?.children) return;
      const index = this.parentElement.children.indexOf(this);
      if (index >= 0) {
        if (node) node.parentElement = this.parentElement;
        this.parentElement.children.splice(index, 1, node);
      }
    },
    remove() {
      if (!this.parentElement?.children) return;
      this.parentElement.children = this.parentElement.children.filter((child) => child !== this);
      this.parentElement = null;
    },
    listeners,
    focus() {
      focusedElement = this;
    },
    scrollTop: 0,
    scrollHeight: 0,
    querySelector(selector) {
      return this.querySelectorAll(selector)[0] || null;
    },
    querySelectorAll(selector) {
      const matches = [];
      const stack = [...this.children];
      while (stack.length) {
        const current = stack.shift();
        if (matchesFakeSelector(current, selector)) matches.push(current);
        stack.push(...(current?.children || []));
      }
      return matches;
    }
  };
}

function nodeText(node) {
  if (!node) return '';
  const own = node.textContent || '';
  const childText = Array.isArray(node.children) ? node.children.map(nodeText).join('') : '';
  return `${own}${childText}`;
}

function findNodesById(node, id) {
  if (!node) return [];
  const matches = node.id === id ? [node] : [];
  for (const child of node.children || []) {
    matches.push(...findNodesById(child, id));
  }
  return matches;
}

function imageDomElement(tagName = 'div') {
  const attributes = {};
  const node = {
    tagName,
    dataset: {},
    children: [],
    parentElement: null,
    textContent: '',
    className: '',
    setAttribute(name, value) {
      attributes[name] = String(value);
    },
    getAttribute(name) {
      return attributes[name] || null;
    },
    appendChild(child) {
      child.parentElement = this;
      this.children.push(child);
      return child;
    },
    append(...children) {
      children.forEach((child) => this.appendChild(child));
    },
    replaceChildren(...children) {
      this.children = [];
      children.forEach((child) => this.appendChild(child));
    },
    querySelector(selector) {
      const roleMatch = String(selector || '').match(/\[data-role=['"]([^'"]+)['"]\]/);
      const imageDebug = String(selector || '') === '[data-image-job-debug]';
      const stack = [...this.children];
      while (stack.length) {
        const current = stack.shift();
        if (roleMatch && current?.dataset?.role === roleMatch[1]) return current;
        if (imageDebug && current?.dataset?.imageJobDebug) return current;
        stack.push(...(current?.children || []));
      }
      return null;
    }
  };
  return node;
}

function linkedChatNode(id, options = {}) {
  const { classes = [], dataset = {}, diagnostic = false } = options;
  const node = fakeElement(id);
  const classSet = new Set(classes);
  node.dataset = { ...dataset };
  node.classList = {
    contains(name) {
      return classSet.has(name);
    }
  };
  node.matches = (selector) => {
    const text = String(selector || '');
    return Boolean(
      diagnostic &&
        ((classSet.has('message-debug-fx') && text.includes('.message-debug-fx')) ||
          (classSet.has('evidence-rail') && text.includes('.evidence-rail')) ||
          (node.dataset.role === 'plan-mode' && text.includes('[data-role="plan-mode"]')) ||
          (node.dataset.answerModeBadge && text.includes('[data-answer-mode-badge]')))
    );
  };
  node.remove = () => {
    node.removed = true;
    if (node.parentElement?.children) {
      node.parentElement.children = node.parentElement.children.filter((child) => child !== node);
    }
  };
  return node;
}

function mountLinkedChatNodes(container, nodes) {
  container.children = nodes;
  nodes.forEach((node, index) => {
    node.parentElement = container;
    node.nextElementSibling = nodes[index + 1] || null;
  });
  container.querySelectorAll = (selector) => {
    if (selector === '.message') {
      return container.children.filter((node) => node.classList?.contains('message'));
    }
    if (selector === '[data-direct-literal-diagnostics]') {
      return container.children.filter((node) =>
        Object.prototype.hasOwnProperty.call(node.dataset || {}, 'directLiteralDiagnostics')
      );
    }
    if (selector === '[data-compact-external-proof-diagnostics]') {
      return container.children.filter((node) =>
        Object.prototype.hasOwnProperty.call(node.dataset || {}, 'compactExternalProofDiagnostics')
      );
    }
    if (selector === '[data-answer-only-diagnostics]') {
      return container.children.filter((node) =>
        Object.prototype.hasOwnProperty.call(node.dataset || {}, 'answerOnlyDiagnostics')
      );
    }
    if (dataSelectorDescriptor(selector)) {
      const matches = [];
      const stack = [...container.children];
      while (stack.length) {
        const current = stack.shift();
        if (matchesFakeSelector(current, selector)) matches.push(current);
        stack.push(...(current?.children || []));
      }
      return matches;
    }
    return [];
  };
}

function statusPill(label, valueElement) {
  const pill = fakeElement(`${valueElement.id}-pill`);
  const strong = fakeElement(`${valueElement.id}-label`);
  strong.textContent = label;
  pill.querySelector = (selector) => selector === 'strong' ? strong : null;
  valueElement.parentElement = pill;
  return pill;
}

function heartbeatCard(label, labelSelector = 'strong') {
  const labelNode = fakeElement(`${label}-${labelSelector}`);
  const small = fakeElement(`${label}-small`);
  const attributes = {};
  labelNode.textContent = label;
  return {
    style: {},
    dataset: { status: 'WARN' },
    get textContent() {
      return `${labelNode.textContent}${small.textContent}`;
    },
    querySelector(selector) {
      if (selector === 'strong, span' && (labelSelector === 'strong' || labelSelector === 'span')) return labelNode;
      if (selector === labelSelector) return labelNode;
      return selector === 'small' ? small : null;
    },
    querySelectorAll(selector) {
      if (selector === 'strong, span, small') return [labelNode, small];
      return [];
    },
    setAttribute(name, value) {
      attributes[name] = String(value);
    },
    getAttribute(name) {
      return attributes[name] || null;
    },
    strong: labelSelector === 'strong' ? labelNode : undefined,
    span: labelSelector === 'span' ? labelNode : undefined,
    labelNode,
    small
  };
}

function debugCell(label) {
  const strong = fakeElement(`${label}-strong`);
  const span = fakeElement(`${label}-span`);
  const small = fakeElement(`${label}-small`);
  const attributes = {};
  return {
    dataset: { status: 'warn' },
    querySelector(selector) {
      if (selector === 'strong') return strong;
      if (selector === 'span') return span;
      if (selector === 'small') return small;
      return null;
    },
    querySelectorAll(selector) {
      if (selector === 'strong, span, small') return [strong, span, small];
      return [];
    },
    setAttribute(name, value) {
      attributes[name] = String(value);
    },
    getAttribute(name) {
      return attributes[name] || null;
    },
    strong,
    span,
    small
  };
}

const heartbeatSummary = heartbeatCard('summary');
heartbeatSummary.strong.textContent = 'Core wait / UI wait / External wait';
heartbeatSummary.small.textContent = 'static-heartbeat';
const heartbeatFields = new Map();
for (const name of [
  'liveStream',
  'modelWait',
  'timeout',
  'cancel',
  'core',
  'ui',
  'external',
  'gate',
  'model',
  'search',
  'ladder',
  'trace',
  'harmony',
  'traceMemory',
  'metrics',
  'memory',
  'answer',
  'action',
  'lanes',
  'strategy',
  'failures',
  'qtx',
  'causal',
  'retrievers',
  'breaker',
  'supabase',
  'browser',
  'computer',
  'noether',
  'freshness',
  'patchdrop'
]) {
  heartbeatFields.set(name, heartbeatCard(name));
}
heartbeatFields.set('supabase', heartbeatCard('Supabase'));
heartbeatFields.set('browser', heartbeatCard('Browser'));
heartbeatFields.set('computer', heartbeatCard('Computer', 'span'));
const matrixCells = new Map([
  ['core-ui', debugCell('core-ui')],
  ['model-answer', debugCell('model-answer')],
  ['search-trace', debugCell('search-trace')],
  ['external-proof', debugCell('external-proof')]
]);
const cockpitCells = new Map([
  ['next', debugCell('next-cockpit')]
]);
const missionAxes = new Map([
  ['focus', debugCell('focus')]
]);
const flowSteps = new Map([
  ['input', debugCell('input-flow')],
  ['model', debugCell('model-flow')],
  ['search', debugCell('search-flow')],
  ['trace', debugCell('trace-flow')],
  ['external', debugCell('external-flow')],
  ['action', debugCell('action-flow')]
]);
const proofCells = new Map([
  ['local', debugCell('local-proof')],
  ['browser', debugCell('browser-proof')],
  ['computer', debugCell('computer-proof')],
  ['supabase', debugCell('supabase-proof')],
  ['producer', debugCell('producer-proof')],
  ['action', debugCell('action-proof')]
]);
const orchBadges = new Map([
  ['model', fakeElement('orch-model')],
  ['dpp', fakeElement('orch-dpp')],
  ['cfvm', fakeElement('orch-cfvm')],
  ['supabase', fakeElement('orch-supabase')]
]);

const elements = new Map();
const dispatchedEvents = [];
const transitionDebugCalls = [];
const vmConsole = Object.create(console);
vmConsole.debug = (...args) => transitionDebugCalls.push(args);
const intervalCallbacks = new Map();
const sessionStorageBacking = new Map();
const sessionModeList = fakeElement('sessionModeList');
const quickPromptButton = fakeElement('quickPromptButton');
quickPromptButton.dataset.searchMode = 'OFF';
quickPromptButton.dataset.useRag = 'false';
quickPromptButton.dataset.q = '운영 안정성 관점에서 Provider Guard, Trace, Fail-soft 구조를 정리해줘';
const pipelineStatusLink = fakeElement('pipelineStatusLink');
pipelineStatusLink.setAttribute('data-menu-action', 'open-pipeline-status');
pipelineStatusLink.setAttribute('href', '/admin/pipeline-status');
const fakeSessionStorage = {
  getItem(key) {
    const normalized = String(key);
    return sessionStorageBacking.has(normalized) ? sessionStorageBacking.get(normalized) : null;
  },
  setItem(key, value) {
    sessionStorageBacking.set(String(key), String(value));
  },
  removeItem(key) {
    sessionStorageBacking.delete(String(key));
  },
  clear() {
    sessionStorageBacking.clear();
  }
};
let nextIntervalId = 1;
for (const id of [
  'chatForm',
  'messageInput',
  'sendBtn',
  'stopBtn',
  'modelSelect',
  'searchModeSelect',
  'useRagToggle',
  'chatWindow',
  'coreStatusRail',
  'streamStatus',
  'modelStatus',
  'searchStatus',
  'ragStatus',
  'traceStatus',
  'qualityStatus',
  'healthStatus',
  'decisionRibbon',
  'responseSettingsSummary',
  'runtimeToolkitStatus',
  'runtimeToolkitServices',
  'diagnosticsSummaryControl',
  'diagnosticsSummary'
]) {
  elements.set(id, fakeElement(id));
}
const statusPills = {
  stream: statusPill('Stream', elements.get('streamStatus')),
  model: statusPill('Model', elements.get('modelStatus')),
  search: statusPill('Search', elements.get('searchStatus')),
  rag: statusPill('RAG', elements.get('ragStatus')),
  trace: statusPill('Trace', elements.get('traceStatus')),
  quality: statusPill('Quality', elements.get('qualityStatus')),
  health: statusPill('Health', elements.get('healthStatus'))
};
elements.get('modelSelect').value = 'local';
elements.get('searchModeSelect').options = [
  { value: 'AUTO', defaultSelected: true },
  { value: 'OFF', defaultSelected: false },
  { value: 'FORCE_LIGHT', defaultSelected: false },
  { value: 'FORCE_DEEP', defaultSelected: false }
];
elements.get('searchModeSelect').value = 'AUTO';
elements.get('useRagToggle').checked = false;
elements.get('useRagToggle').defaultChecked = false;
const currentModelBadge = fakeElement('currentModelBadge');
const fakeDocumentBody = fakeElement('document-body');
const fakeDocumentElement = fakeElement('document-element');
focusedElement = fakeDocumentBody;

const debugHeartbeatBar = fakeElement('debugHeartbeatBar');
debugHeartbeatBar.querySelector = (selector) => {
  if (selector === '[data-debug-heartbeat-summary]') {
    return heartbeatSummary;
  }
  const match = selector.match(/data-debug-heartbeat-field="([^"]+)"/);
  return match ? heartbeatFields.get(match[1]) || null : null;
};
elements.set('debugHeartbeatBar', debugHeartbeatBar);

const context = {
  crypto: require('node:crypto').webcrypto,
  console: vmConsole,
  setInterval() {},
  setTimeout(handler) {
    const id = nextIntervalId++;
    if (typeof handler === 'function') intervalCallbacks.set(id, handler);
    context.__latestTimeoutId = id;
    return id;
  },
  clearTimeout(id) {
    intervalCallbacks.delete(id);
  },
  performance: {
    now: () => context.__nowMs || 0
  },
  URL,
  URLSearchParams,
  TextDecoder,
  TextEncoder,
  Node: { TEXT_NODE: 3 },
  AbortController: class AbortController {
    constructor() {
      this.signal = { aborted: false };
    }
    abort() {
      context.__cancelOrder?.push('abort');
      this.signal.aborted = true;
    }
  },
  CustomEvent: function CustomEvent(name, init) {
    return { type: name, name, detail: init && init.detail };
  },
  document: {
    get activeElement() {
      return focusedElement;
    },
    body: fakeDocumentBody,
    documentElement: fakeDocumentElement,
    getElementById(id) {
      const registered = elements.get(id);
      if (registered) return registered;
      const dynamicMatch = findNodesById(elements.get('chatWindow'), id)[0];
      return dynamicMatch || fakeElement(id);
    },
    querySelector(selector) {
      if (selector === 'meta[name="_csrf"]') {
        return { content: 'csrf-token-123' };
      }
      if (selector === 'meta[name="_csrf_header"]') {
        return { content: 'X-CSRF-TOKEN' };
      }
      if (selector === '[data-current-model]') {
        return currentModelBadge;
      }
      const matrixMatch = selector.match(/data-debug-matrix-cell="([^"]+)"/);
      if (matrixMatch) {
        return matrixCells.get(matrixMatch[1]) || null;
      }
      const cockpitMatch = selector.match(/data-debug-cockpit-cell="([^"]+)"/);
      if (cockpitMatch) {
        return cockpitCells.get(cockpitMatch[1]) || null;
      }
      const missionMatch = selector.match(/data-debug-mission-axis="([^"]+)"/);
      if (missionMatch) {
        return missionAxes.get(missionMatch[1]) || null;
      }
      const flowMatch = selector.match(/data-debug-flow-step="([^"]+)"/);
      if (flowMatch) {
        return flowSteps.get(flowMatch[1]) || null;
      }
      const proofMatch = selector.match(/data-debug-proof-cell="([^"]+)"/);
      if (proofMatch) {
        return proofCells.get(proofMatch[1]) || null;
      }
      const orchBadgeMatch = selector.match(/data-orch-badge="([^"]+)"/);
      if (orchBadgeMatch) {
        return orchBadges.get(orchBadgeMatch[1]) || null;
      }
      if (selector === '[data-session-mode-list]') {
        return sessionModeList;
      }
      return null;
    },
    querySelectorAll(selector) {
      if (selector === '.qa[data-q]') {
        return [quickPromptButton];
      }
      if (selector === '[data-menu-action]') {
        return [pipelineStatusLink];
      }
      return [];
    },
    createElement(tag) {
      return fakeElement(tag);
    },
    createTextNode(text) {
      return { nodeType: 3, textContent: String(text ?? '') };
    },
    dispatchEvent(event) {
      dispatchedEvents.push(event);
    }
  },
  window: {
    addEventListener(type, handler) {
      this.listeners = this.listeners || {};
      this.listeners[type] = handler;
    },
    location: {
      search: '?codexSmoke=contract',
      href: 'http://127.0.0.1:19410/chat-ui?codexSmoke=contract',
      assigned: null,
      assign(url) {
        this.assigned = String(url);
      }
    },
    sessionStorage: fakeSessionStorage,
    setInterval(callback) {
      const id = nextIntervalId++;
      intervalCallbacks.set(id, callback);
      context.__latestIntervalId = id;
      return id;
    },
    clearInterval(id) {
      intervalCallbacks.delete(id);
    },
    setTimeout(handler) {
      return context.setTimeout(handler);
    },
    clearTimeout(id) {
      context.clearTimeout(id);
    },
    fetch: async () => ({
      ok: false,
      status: 403,
      headers: { get: () => 'application/json' },
      json: async () => ({}),
      text: async () => ''
    })
  }
};
const fetchCalls = [];
function fetchHeader(call, name) {
  const expected = String(name).toLowerCase();
  const headers = call?.headers || {};
  if (typeof headers.get === 'function') return headers.get(name);
  const entry = Object.entries(headers).find(([key]) => String(key).toLowerCase() === expected);
  return entry ? entry[1] : null;
}
const fallbackSyncResponse = {
  content: 'sync fallback answer',
  sessionId: 77,
  modelUsed: 'sync-model',
  answerMode: 'evidence_only',
  traceTurnId: 91,
  learningContext: {
    signalCount: 2,
    sourceTags: ['TRAINING_TASK'],
    summaryPresent: true,
    degraded: false
  },
  evidence: [{}, {}, {}],
  pipelineSnapshot: { status: 'ok' }
};
context.globalThis = context;
context.__tickLatestInterval = () => {
  const callback = intervalCallbacks.get(context.__latestIntervalId);
  if (callback) callback();
};
context.window.fetch = async (url, options = {}) => {
  fetchCalls.push({
    url: String(url),
    method: options.method || 'GET',
    body: options.body || '',
    headers: options.headers || {},
    cache: options.cache
  });
  if (String(url).includes('/api/chat/stream')) {
    if (context.__w2Scenario) {
      const scenario = context.__w2Scenario;
      const attachRequest = String(url).includes('?attach=true');
      context.__w2StreamRequestCount = (context.__w2StreamRequestCount || 0) + 1;
      if (scenario.networkError === true) {
        throw new TypeError(String(scenario.networkMessage || 'PRIVATE_W2_NETWORK_SENTINEL'));
      }
      const chunks = attachRequest && Array.isArray(scenario.attachBodyChunks)
        ? scenario.attachBodyChunks.slice()
        : Array.isArray(scenario.bodyChunks) ? scenario.bodyChunks.slice() : [];
      const readErrorName = attachRequest ? scenario.attachReadErrorName : scenario.readErrorName;
      const readErrorMessage = attachRequest ? scenario.attachReadErrorMessage : scenario.readErrorMessage;
      let readIndex = 0;
      let readErrorThrown = false;
      context.__w2BodyReadCount = 0;
      context.__w2BodyCancelCount = 0;
      context.__w2BodyReaderCount = 0;
      context.__w2DeliveredCharCount = 0;
      context.__w2FallbackBodyPassCount = 0;
      const body = scenario.missingBody === true ? null : {
        getReader() {
          context.__w2BodyReaderCount += 1;
          return {
            async read() {
              context.__w2BodyReadCount += 1;
              if (attachRequest) context.__w2AttachReadCount += 1;
              else context.__w2InitialReadCount += 1;
              if (readIndex >= chunks.length) {
                if (readErrorName && !readErrorThrown) {
                  readErrorThrown = true;
                  const error = readErrorName === 'TypeError'
                    ? new TypeError(String(readErrorMessage || 'PRIVATE_W2_READER_TYPE_ERROR'))
                    : new Error(String(readErrorMessage || 'PRIVATE_W2_READER_ERROR'));
                  error.name = String(readErrorName);
                  throw error;
                }
                return { done: true };
              }
              const chunk = chunks[readIndex++];
              const text = String(chunk);
              context.__w2DeliveredCharCount += text.length;
              return { done: false, value: new TextEncoder().encode(text) };
            },
            async cancel() {
              context.__w2BodyCancelCount += 1;
              readIndex = chunks.length;
            }
          };
        }
      };
      return {
        ok: scenario.ok === true,
        status: Number(scenario.status ?? (scenario.ok === true ? 200 : 503)),
        headers: { get: (name) => String(name).toLowerCase() === 'content-type'
          ? String(scenario.contentType || 'application/json')
          : null },
        body,
        async json() {
          context.__w2FallbackBodyPassCount += 1;
          return JSON.parse(chunks.join(''));
        },
        async text() {
          context.__w2FallbackBodyPassCount += 1;
          return chunks.join('');
        }
      };
    }
    if (context.__streamMode === 'known-token-prefix-replay') {
      const sid = context.__knownTokenSessionId;
      const token = context.__knownTokenRunToken;
      if (String(url).includes('?attach=true')) {
        let attachReads = 0;
        return {
          ok: true,
          status: 200,
          headers: { get: () => 'text/event-stream' },
          body: {
            getReader() {
              return {
                async read() {
                  attachReads += 1;
                  if (attachReads === 1) {
                    return {
                      done: false,
                      value: new TextEncoder().encode(
                        `event: token\ndata: ${JSON.stringify({ type: 'token', data: 'prefix' })}\n\n`
                      )
                    };
                  }
                  if (attachReads === 2) {
                    return new Promise((resolve) => {
                      context.__resolvePrefixReplayTail = () => resolve({
                        done: false,
                        value: new TextEncoder().encode(
                          `event: final\ndata: ${JSON.stringify({
                            type: 'final', sessionId: sid, data: 'prefix final', answerMode: 'streamed', evidence: []
                          })}\n\n`
                        )
                      });
                    });
                  }
                  return { done: true };
                }
              };
            }
          }
        };
      }
      let reads = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                reads += 1;
                if (reads === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode(
                      `event: session\ndata: ${JSON.stringify({ type: 'session', sessionId: sid, data: token })}\n\n`
                    )
                  };
                }
                if (reads === 2) {
                  return {
                    done: false,
                    value: new TextEncoder().encode(
                      `event: token\ndata: ${JSON.stringify({ type: 'token', data: 'prefix' })}\n\n`
                    )
                  };
                }
                throw new Error('known_token_prefix_transport_error');
              }
            };
          }
        }
      };
    }
    if (/^known-token-transport-(eof|error)$/.test(context.__streamMode || '')) {
      const sid = context.__knownTokenSessionId;
      const token = context.__knownTokenRunToken;
      if (String(url).includes('?attach=true')) {
        let attachReads = 0;
        return {
          ok: true,
          status: 200,
          headers: { get: () => 'text/event-stream' },
          body: {
            getReader() {
              return {
                async read() {
                  attachReads += 1;
                  if (attachReads === 1) {
                    return {
                      done: false,
                      value: new TextEncoder().encode(
                        `event: final\ndata: ${JSON.stringify({
                          type: 'final',
                          sessionId: sid,
                          data: `${context.__streamMode} replay answer`,
                          answerMode: 'streamed',
                          evidence: []
                        })}\n\n`
                      )
                    };
                  }
                  return { done: true };
                }
              };
            }
          }
        };
      }
      let transportReads = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                transportReads += 1;
                if (transportReads === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode(
                      `event: session\ndata: ${JSON.stringify({ type: 'session', sessionId: sid, data: token })}\n\n`
                    )
                  };
                }
                if (context.__streamMode.endsWith('-error')) {
                  throw new Error('known_token_transport_error');
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'deferred-session-token') {
      return new Promise((resolve) => {
        context.__releaseDeferredSessionTokenStream = () => {
          let reads = 0;
          resolve({
            ok: true,
            status: 200,
            headers: { get: () => 'text/event-stream' },
            body: {
              getReader() {
                return {
                  async read() {
                    reads += 1;
                    if (reads === 1) {
                      return {
                        done: false,
                        value: new TextEncoder().encode(
                          `event: session\ndata: ${JSON.stringify({
                            type: 'session',
                            sessionId: context.__deferredSessionId,
                            data: context.__deferredRunToken
                          })}\n\n`
                        )
                      };
                    }
                    return new Promise((tailResolve) => {
                      context.__resolveDeferredSessionTokenTail = () => tailResolve({ done: true });
                    });
                  }
                };
              }
            }
          });
        };
      });
    }
    if (context.__streamMode === 'resume-replay') {
      let readCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                readCount += 1;
                if (readCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode(
                      'event: session\ndata: {"type":"session","sessionId":322,"data":"run-322-token"}\n\n' +
                      'event: final\ndata: {"type":"final","sessionId":322,"data":"resumed exact answer","answerMode":"streamed","evidence":[]}\n\n'
                    )
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'pending-final') {
      return new Promise((resolve, reject) => {
        context.__resolvePendingStream = () => resolve({
          ok: true,
          status: 200,
          headers: { get: () => 'text/event-stream' },
          body: {
            getReader() {
              let readCount = 0;
              return {
                async read() {
                  readCount += 1;
                  if (readCount === 1) {
                    return {
                      done: false,
                      value: new TextEncoder().encode('event: final\ndata: {"type":"final","data":"pending final answer","answerMode":"streamed"}\n\n')
                    };
                  }
                  return { done: true };
                }
              };
            }
          }
        });
        context.__rejectPendingStream = () => {
          const error = new Error('aborted');
          error.name = 'AbortError';
          reject(error);
        };
        context.__rejectPendingStreamNonAbort = () => reject(new Error('stream_cancelled_by_server'));
      });
    }
    if (context.__streamMode === 'eof-session-only') {
      let readCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                readCount += 1;
                if (readCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: session\ndata: {"sessionId":99}\n\n')
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'error-late-terminal') {
      let readCount = 0;
      context.__terminalReadCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                readCount += 1;
                context.__terminalReadCount = readCount;
                if (readCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: error\r\ndata: {"type":"error","data":"redacted upstream failure"}\r\n\r\n')
                  };
                }
                if (readCount === 2) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: message\r\ndata: {"type":"token","data":"LATE_TOKEN_MUST_NOT_RENDER"}\r\n\r\n')
                  };
                }
                if (readCount === 3) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: final\r\ndata: {"type":"final","data":"LATE_FINAL_MUST_NOT_RENDER"}\r\n\r\n')
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'error-same-chunk-final') {
      context.__sameChunkTerminalReadCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                context.__sameChunkTerminalReadCount += 1;
                if (context.__sameChunkTerminalReadCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode(
                      'event: error\r\n' +
                      'data: {"type":"error","data":"redacted same-chunk failure"}\r\n\r\n' +
                      'event: final\r\n' +
                      'data: {"type":"final","data":"SAME_CHUNK_FINAL_MUST_NOT_RENDER"}\r\n\r\n'
                    )
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'status-complete-no-final') {
      let readCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                readCount += 1;
                if (readCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: status\ndata: {"type":"status","signal":{"phase":"stream","code":"complete","message":"stream complete","cancelled":false}}\n\n')
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'header-session-only') {
      let readCount = 0;
      return {
        ok: true,
        status: 200,
        headers: {
          get: (name) => ({
            'content-type': 'text/event-stream',
            'x-session-id': '222',
            'x-request-id': 'req-header-222',
            'x-model-used': 'header-model',
            'x-rag-used': 'false',
            'x-trace-snapshot-id': 'trace-header-222'
          })[String(name).toLowerCase()] || null
        },
        body: {
          getReader() {
            return {
              async read() {
                readCount += 1;
                if (readCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: final\ndata: {"type":"final","answerMode":"header_final","evidence":[]}\n\n')
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'local-safe-token') {
      let readCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                readCount += 1;
                if (readCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: message\ndata: {"type":"token","data":"기본 모델 응답이 지금 안정적으로 생성되지 않아 로컬 안전 응답으로 먼저 안내드립니다."}\n\n')
                  };
                }
                if (readCount === 2) {
                  return {
                    done: false,
                    value: new TextEncoder().encode('event: final\ndata: {"type":"final","answerMode":"fallback","evidence":[]}\n\n')
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'open-no-data') {
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                return new Promise(() => {});
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'empty-after-cancel') {
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                return { done: true };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'w1-sse-frames') {
      const chunks = Array.isArray(context.__w1StreamChunks) ? context.__w1StreamChunks.slice() : [];
      let readCount = 0;
      context.__w1StreamReadCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                if (readCount >= chunks.length) return { done: true };
                const value = chunks[readCount];
                readCount += 1;
                context.__w1StreamReadCount = readCount;
                return {
                  done: false,
                  value: value instanceof Uint8Array ? value : new TextEncoder().encode(String(value))
                };
              }
            };
          }
        }
      };
    }
    if (context.__streamMode === 'sse-message-event') {
      let readCount = 0;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body: {
          getReader() {
            return {
              async read() {
                readCount += 1;
                if (readCount === 1) {
                  return {
                    done: false,
                    value: new TextEncoder().encode(
                      'event: message\n' +
                      'data: {"type":"message","data":"message fallback chunk"}\n\n'
                    )
                  };
                }
                return { done: true };
              }
            };
          }
        }
      };
    }
    return {
      ok: false,
      status: 503,
      headers: { get: () => 'application/json' },
      json: async () => ({}),
      text: async () => 'stream unavailable'
    };
  }
  if (String(url).includes('/api/chat/ack')) {
    return {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => ({ acknowledged: true, reason: 'acknowledged' }),
      text: async () => ''
    };
  }
  if (String(url).includes('/api/chat/cancel')) {
    context.__cancelOrder?.push('cancel-request');
    if (context.__hangCancelResponse) {
      return new Promise(() => {});
    }
    if (context.__deferCancelResponse) {
      return new Promise((resolve) => setImmediate(() => {
        context.__cancelOrder?.push('cancel-response');
        resolve({
          ok: true,
          status: 200,
          headers: { get: () => 'application/json' },
          json: async () => context.__cancelResponse || ({ cancelled: true, reason: 'cancelled' }),
          text: async () => ''
        });
      }));
    }
    context.__cancelOrder?.push('cancel-response');
    return {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => context.__cancelResponse || ({ cancelled: true, reason: 'cancelled' }),
      text: async () => ''
    };
  }
  if (String(url).includes('/api/chat/state')) {
    if (context.__hangRunStateResponse) {
      return new Promise(() => {});
    }
    if (context.__deferRunStateResponse) {
      return new Promise((resolve) => {
        context.__resolveRunStateResponse = (payload) => resolve({
          ok: true,
          status: 200,
          headers: { get: () => 'application/json' },
          json: async () => payload,
          text: async () => JSON.stringify(payload)
        });
      });
    }
    return {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => context.__runStateResponse || {
        running: false,
        runStatus: 'missing_or_replaced',
        attachable: false,
        terminal: false
      },
      text: async () => ''
    };
  }
  if (String(url) === '/api/chat/sessions' && context.__w3ListScenario) {
    const scenario = context.__w3ListScenario;
    if (scenario.networkError === true) {
      throw new TypeError(String(scenario.networkMessage || 'PRIVATE_W3_LIST_NETWORK_SENTINEL'));
    }
    const makeResponse = () => ({
      ok: Number(scenario.status ?? 200) >= 200 && Number(scenario.status ?? 200) < 300,
      status: Number(scenario.status ?? 200),
      headers: { get: () => 'application/json' },
      async json() {
        if (scenario.deferJson === true) {
          return new Promise((resolve) => {
            context.__w3ResolveListJson = (payload = scenario.body) => resolve(payload);
          });
        }
        if (scenario.invalidJson === true) {
          throw new SyntaxError(String(scenario.invalidJsonMessage || 'PRIVATE_W3_LIST_JSON_SENTINEL'));
        }
        return scenario.body;
      },
      text: async () => JSON.stringify(scenario.body ?? null)
    });
    if (scenario.deferResponse === true) {
      return new Promise((resolve) => {
        context.__w3ResolveListResponse = () => resolve(makeResponse());
      });
    }
    return makeResponse();
  }
  const w3DetailMatch = String(url).match(/^\/api\/chat\/sessions\/(\d+)$/);
  if (w3DetailMatch && context.__w3DetailScenario) {
    const scenario = context.__w3DetailScenario;
    if (scenario.networkError === true) {
      throw new TypeError(String(scenario.networkMessage || 'PRIVATE_W3_DETAIL_NETWORK_SENTINEL'));
    }
    const makeResponse = () => ({
      ok: Number(scenario.status ?? 200) >= 200 && Number(scenario.status ?? 200) < 300,
      status: Number(scenario.status ?? 200),
      headers: { get: () => 'application/json' },
      async json() {
        if (scenario.deferJson === true) {
          return new Promise((resolve) => {
            context.__w3ResolveDetailJson = (payload = scenario.body) => resolve(payload);
          });
        }
        if (scenario.invalidJson === true) {
          throw new SyntaxError(String(scenario.invalidJsonMessage || 'PRIVATE_W3_DETAIL_JSON_SENTINEL'));
        }
        return scenario.body;
      },
      text: async () => JSON.stringify(scenario.body ?? null)
    });
    if (scenario.deferResponse === true) {
      return new Promise((resolve) => {
        context.__w3ResolveDetailResponse = () => resolve(makeResponse());
      });
    }
    return makeResponse();
  }
  if (String(url).includes('/api/chat/sessions/321')) {
    const detail = context.__sessionHydrationDetail || {
      id: 321,
      title: 'restored chat',
      messages: [
        { id: 1, role: 'user', content: 'restored user turn' },
        { id: 2, role: 'assistant', content: 'Response stopped' }
      ],
      modelUsed: 'gemma3:4b',
      settings: { model: 'gemma3:4b', searchMode: 'OFF', useRag: false }
    };
    const response = {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => {
        if (context.__deferSessionHydrationJson) {
          return new Promise((resolve) => {
            context.__resolveSessionHydrationJson = () => resolve(detail);
          });
        }
        return detail;
      },
      text: async () => JSON.stringify(detail)
    };
    if (context.__deferSessionHydration) {
      return new Promise((resolve) => {
        context.__resolveSessionHydration = () => resolve(
          context.__deferSessionHydrationStatus === 404
            ? { ...response, ok: false, status: 404 }
            : response
        );
      });
    }
    return response;
  }
  if (String(url).includes('/api/chat')) {
    if (context.__syncMode === 'fail') {
      return {
        ok: false,
        status: 500,
        headers: { get: () => 'application/json' },
        json: async () => ({ error: 'server_error' }),
        text: async () => 'server_error'
      };
    }
    if (context.__syncMode === 'missing-session') {
      const { sessionId, ...responseWithoutSession } = fallbackSyncResponse;
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => responseWithoutSession,
        text: async () => JSON.stringify(responseWithoutSession)
      };
    }
    return {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => fallbackSyncResponse,
      text: async () => JSON.stringify(fallbackSyncResponse)
    };
  }
  return {
    ok: false,
    status: 403,
    headers: { get: () => 'application/json' },
    json: async () => ({}),
    text: async () => ''
  };
};
context.fetch = (...args) => context.window.fetch(...args);
vm.createContext(context);

const script = fs.readFileSync(path.join(__dirname, '..', 'main', 'resources', 'static', 'js', 'chat.js'), 'utf8');
const imageJobUiSource = fs.readFileSync(path.join(__dirname, '..', 'main', 'resources', 'static', 'js', 'image-jobs-ui.js'), 'utf8');
const stylesheet = fs.readFileSync(path.join(__dirname, '..', 'main', 'resources', 'static', 'css', 'chat-style.css'), 'utf8');
const template = fs.readFileSync(path.join(__dirname, '..', 'main', 'resources', 'templates', 'chat-ui.html'), 'utf8');
const heartbeatProbe = fs.readFileSync(path.join(__dirname, '..', 'main', 'java', 'com', 'example', 'lms', 'web', 'ChatUiCoreHeartbeatProbe.java'), 'utf8');
const chatStreamSignalBuilder = fs.readFileSync(path.join(__dirname, '..', 'main', 'java', 'com', 'example', 'lms', 'api', 'ChatStreamSignalBuilder.java'), 'utf8');
const narrowMediaStart = stylesheet.indexOf('@media (max-width: 760px) {');
const shortMediaStart = stylesheet.indexOf('@media (max-height: 760px) {', narrowMediaStart);
const compactMediaStart = stylesheet.indexOf('@media (max-width: 760px) and (max-height: 760px) {');
const narrowHeartbeatRule = cssRuleAfter(stylesheet, narrowMediaStart, '.debug-heartbeat-bar {');
const narrowDetailRule = cssRuleAfter(stylesheet, narrowMediaStart, '.debug-heartbeat-card small,');
const compactHealthRule = cssRuleAfter(stylesheet, compactMediaStart, '.status-pill #healthStatus {');
const compactHeartbeatRule = cssRuleAfter(stylesheet, compactMediaStart, '.debug-heartbeat-bar {');
const conversationStylesStart = stylesheet.indexOf('/* Conversation-first console surfaces.');
const unqualifiedConversationTranscriptRule = stylesheet.indexOf('.chat-transcript-region {', conversationStylesStart);
const conversationTranscriptRule = cssRuleAfter(stylesheet, conversationStylesStart, '.chat-transcript-region {');
const conversationStatusRule = cssRuleAfter(stylesheet, conversationStylesStart, '.status-rail {');
const conversationHealthRule = cssRuleAfter(stylesheet, conversationStylesStart, '.status-pill[data-health-pill] {');
const finalNarrowMobileStart = stylesheet.lastIndexOf('@media (max-width: 760px) {');
const finalMobileStatusRule = cssRuleAfter(stylesheet, finalNarrowMobileStart, '.status-rail {');
const finalMobileHealthRule = cssRuleAfter(stylesheet, finalNarrowMobileStart, '.status-pill[data-health-pill] {');
const finalShortMobileStart = stylesheet.lastIndexOf('@media (max-width: 760px) and (max-height: 760px) {');
const finalShortMobileTranscriptRule = cssRuleAfter(stylesheet, finalShortMobileStart, '.chat-transcript-region {');
const finalShortMobileWindowRule = cssRuleAfter(stylesheet, finalShortMobileStart, '#chatWindow {');
const finalShortMobileDecisionRule = cssRuleAfter(stylesheet, finalShortMobileStart, '.decision-ribbon__stages {');
const decisionFirstStylesStart = stylesheet.indexOf('/* Decision-first evidence console surfaces. */');
const evidenceQualityRule = cssRuleAfter(stylesheet, decisionFirstStylesStart, '.evidence-quality-badge {');
const decisionRibbonRule = cssRuleAfter(stylesheet, decisionFirstStylesStart, '.status-rail > .decision-ribbon {');
const compactDecisionStylesStart = stylesheet.indexOf('/* Decision-first compact-phone geometry repair. */', decisionFirstStylesStart);
const compactDecisionHeaderRule = compactDecisionStylesStart >= 0
  ? cssRuleAfter(stylesheet, compactDecisionStylesStart, '.conversation-header {')
  : '';
const compactDecisionTitleRule = compactDecisionStylesStart >= 0
  ? cssRuleAfter(stylesheet, compactDecisionStylesStart, '.conversation-copy > h1 {')
  : '';
const compactDecisionActionRule = compactDecisionStylesStart >= 0
  ? cssRuleAfter(stylesheet, compactDecisionStylesStart, '.new-chat-action {')
  : '';
const compactDecisionBadgeRule = compactDecisionStylesStart >= 0
  ? cssRuleAfter(stylesheet, compactDecisionStylesStart, '.orch-signal-badges span {')
  : '';
const compactDecisionStageRule = compactDecisionStylesStart >= 0
  ? cssRuleAfter(stylesheet, compactDecisionStylesStart, '.decision-ribbon__stages {')
  : '';
const compactDiagnosticsSummaryRule = compactDecisionStylesStart >= 0
  ? cssRuleAfter(stylesheet, compactDecisionStylesStart, '#diagnosticsSummary {')
  : '';
const chatRegionIndex = template.indexOf('<section class="chat-area-wrapper"');
const projectIntroIndex = template.indexOf('<aside class="project-intro"');
assert(
  chatRegionIndex >= 0 && projectIntroIndex > chatRegionIndex,
  `conversation must lead DOM and visual order: chat=${chatRegionIndex} intro=${projectIntroIndex}`
);
assert(
  template.includes('<details class="admin-tools"') &&
    template.includes('<summary>Admin tools</summary>') &&
    template.includes('<details class="response-settings"') &&
    template.includes('id="responseSettingsSummary"') &&
    template.includes('<details class="diagnostics-disclosure"') &&
    template.includes('id="diagnosticsSummary"') &&
    template.includes('id="chatEmptyState"'),
  'chat console must use named native disclosures and a chat-local empty state'
);
assert(
  /<summary id="diagnosticsSummaryControl" aria-label="Diagnostics: Checking signals"><span>Diagnostics<\/span><span id="diagnosticsSummary"/.test(template),
  'the native Diagnostics disclosure control must own one bounded accessible name'
);
assert(
  !/<summary id="diagnosticsSummaryControl"[^>]*(role="status"|aria-live=)/.test(template),
  'Diagnostics must not become a second live status region'
);
assert(
  /<div class="diagnostics-stack">[\s\S]*data-debug-heartbeat="root"/.test(template),
  'detailed heartbeat panels must remain behind Diagnostics'
);
assert(
  /class="status-pill"[^>]*role="status"[^>]*aria-live="polite"[^>]*aria-atomic="true"[^>]*>[\s\S]*id="healthStatus"/.test(template),
  'primary Health must remain an atomic polite status message while Diagnostics is collapsed'
);
assert(
  /id="coreStatusRail"[\s\S]*id="healthStatus"[\s\S]*id="decisionRibbon"/.test(template),
  'Decision ribbon must refine the existing core status rail after Health'
);
assert(
  /class="status-pill"[^>]*data-health-pill[^>]*role="status"[^>]*aria-live="polite"[^>]*aria-atomic="true"[^>]*>[\s\S]*id="healthStatus"/.test(template),
  'Health must keep a semantic responsive hook and remain the only atomic polite status'
);
assert(
  (template.match(/data-decision-stage="/g) || []).length === 6,
  'Decision ribbon must expose exactly six observed stages'
);
assert(
  !/id="decisionRibbon"[^>]*(role="status"|aria-live=)/.test(template),
  'Decision ribbon must not duplicate the authoritative Health live region'
);
for (const id of ['modelSelect', 'searchModeSelect', 'useRagToggle', 'newChatBtn', 'chatWindow', 'messageInput', 'sendBtn', 'stopBtn']) {
  const count = (template.match(new RegExp(`id="${id}"`, 'g')) || []).length;
  assert(count === 1, `chat control id must remain unique: ${id} count=${count}`);
}
const openDivCount = (template.match(/<div\b[^>]*>/g) || []).length;
const closeDivCount = (template.match(/<\/div>/g) || []).length;
const openDetailsCount = (template.match(/<details\b[^>]*>/g) || []).length;
const closeDetailsCount = (template.match(/<\/details>/g) || []).length;
assert(
  openDivCount === closeDivCount && openDetailsCount === closeDetailsCount,
  `chat console disclosures must keep balanced div/details tags: div=${openDivCount}/${closeDivCount} details=${openDetailsCount}/${closeDetailsCount}`
);
assert(
  stylesheet.includes('.diagnostics-stack > .brain-state-panel--compact') &&
    !stylesheet.includes('.chat-area-wrapper > .brain-state-panel--compact'),
  'compact Brain panel styles must belong to the diagnostics stack after the console move'
);
assert(
  chatStreamSignalBuilder.includes('labels.put("verificationStatus"') &&
    chatStreamSignalBuilder.includes('labels.put("verificationFailureClass"') &&
    chatStreamSignalBuilder.includes('labels.put("verificationReason"'),
  'debug_fx backend should project only the allowlisted verification fail-soft labels'
);
for (const laneName of ['dppDiversityReranker', 'cfvmFailureRecorder', 'cfvmRawMatrixBuffer']) {
  assert(
    heartbeatProbe.includes(laneName),
    `chat UI core heartbeat should expose ${laneName} for top badge/core proof alignment`
  );
}
assert(
  heartbeatProbe.includes('supabase-context-probe.json') &&
    heartbeatProbe.includes('probeFileStatus'),
  'chat UI core heartbeat should expose Supabase read-only probe artifact status without reading secret values'
);
assert(
  heartbeatProbe.includes('var/codex-smoke/computer-use-smoke.json') &&
    heartbeatProbe.includes('smokeFileStatus') &&
    heartbeatProbe.includes('computer_use_supporting_evidence_current') &&
    heartbeatProbe.includes('targetableWindowCount'),
  'chat UI core heartbeat should parse count-only Computer Use smoke artifacts without storing app or window names'
);
assert(
  heartbeatProbe.includes('var/codex-smoke/browser-ui-smoke.json') &&
    heartbeatProbe.includes('browserUseExternal') &&
    heartbeatProbe.includes('targetContentVisible') &&
    heartbeatProbe.includes('browserSurface'),
  'chat UI core heartbeat should parse Browser smoke artifact status instead of hardcoding local browser proof'
);
assert(
  heartbeatProbe.includes('goal-next-auto.summary.json') &&
    heartbeatProbe.includes('goalNextExternal') &&
    heartbeatProbe.includes('localReady') &&
    heartbeatProbe.includes('completionReady') &&
    heartbeatProbe.includes('externalInputGateStatus') &&
    heartbeatProbe.includes('supabaseRequiredEnvNames') &&
    heartbeatProbe.includes('browserEvidenceNeeded') &&
    heartbeatProbe.includes('computerStale'),
  'chat UI core heartbeat should bridge goal_next_auto local/external readiness and Browser/Computer/Supabase evidence into the UI without external mutation'
);
assert(
  stylesheet.includes('#healthStatus') &&
    stylesheet.includes('white-space: normal') &&
    stylesheet.includes('overflow-wrap: anywhere'),
  'Health rail should allow the layered live/proof/external status to wrap instead of clipping'
);
assert(
  stylesheet.includes('.status-pill strong::after') &&
    stylesheet.includes('content: ":"'),
  'Status rail labels should show a visible separator before their live core values'
);
assert(
  script.includes("setDebugHeartbeatField('supabase', 'WARN', heartbeatReason)") &&
    script.includes("setDebugHeartbeatField('external', 'WARN', heartbeatReason)"),
  'Heartbeat fetch failure should degrade Supabase and External cards instead of leaving stale loading/evidence copy'
);
assert(
  stylesheet.includes('.debug-heartbeat-card[data-debug-heartbeat-field="supabase"]') &&
    stylesheet.includes('.debug-heartbeat-card[data-debug-heartbeat-field="browser"]') &&
    stylesheet.includes('.debug-heartbeat-card[data-debug-heartbeat-field="computer"]') &&
    stylesheet.includes('debug-heartbeat-mobile-priority') &&
    narrowHeartbeatRule.includes('grid-template-columns: repeat(3, minmax(0, 1fr))'),
  'Mobile heartbeat should prioritize Supabase, Browser, and Computer evidence cards instead of clipping arbitrary debug cards'
);
assert(
  /\.message\.assistant\[data-state="stopped"\][\s\S]*?border-left:\s*3px\s+solid\s+#8693a0/i.test(stylesheet) &&
    /\.message\.assistant\[data-state="stopped"\][\s\S]*?font-style:\s*normal/i.test(stylesheet),
  'Stopped assistant bubbles should have a distinct stable visual state'
);
assert(
  narrowMediaStart >= 0 && shortMediaStart > narrowMediaStart && compactMediaStart > shortMediaStart &&
  stylesheet.includes('debug-heartbeat-mobile-short-priority') &&
    stylesheet.includes('max-height: 44px') &&
    stylesheet.includes('min-height: 30px') &&
    stylesheet.includes('padding: 4px 6px') &&
    stylesheet.includes('chat-status-cards-wrap-on-narrow-viewports') &&
    narrowHeartbeatRule.includes('max-height: 104px;') &&
    narrowHeartbeatRule.includes('overflow-y: auto;') &&
    narrowDetailRule.includes('max-height: none;') &&
    narrowDetailRule.includes('text-overflow: clip;') &&
    narrowDetailRule.includes('overflow-wrap: anywhere;') &&
    narrowDetailRule.includes('white-space: normal;'),
  'Short mobile heartbeat should keep external evidence readable in a bounded scroll area without pushing the composer below the viewport'
);
assert(
  stylesheet.includes('chat-composer-viewport-lock') &&
    /\.chat-area-wrapper\s*{[\s\S]*height:\s*calc\(100vh\s*-\s*var\(--top-bar-min-height\)\s*-\s*20px\)/m.test(stylesheet) &&
    /\.chat-area-wrapper\s*{[\s\S]*overflow:\s*hidden/m.test(stylesheet) &&
    stylesheet.includes('.chat-area-wrapper:has(> .response-settings[open])') &&
    stylesheet.includes('.chat-area-wrapper:has(> .diagnostics-disclosure[open])') &&
    finalMobileStatusRule.includes('display: grid;') &&
    finalMobileStatusRule.includes('grid-template-columns: repeat(3, minmax(0, 1fr));') &&
    finalMobileStatusRule.includes('overflow-x: visible;') &&
    finalMobileStatusRule.includes('overflow-y: visible;') &&
    finalMobileHealthRule.includes('grid-column: 1 / -1;') &&
    /\.composer\s*{[\s\S]*flex:\s*0\s+0\s+auto/m.test(stylesheet) &&
    /\.composer\s*{[\s\S]*position:\s*static;[\s\S]*bottom:\s*auto;/m.test(stylesheet),
  'Chat area should own viewport height while the transcript scrolls and the mobile status grid keeps the composer reachable without a nested horizontal scroller'
);
assert(
  stylesheet.includes('chat-supporting-proof-scroll-lock') &&
    /\.debug-proof-strip\s*{[\s\S]*max-height:\s*58px;[\s\S]*overflow-y:\s*auto/m.test(stylesheet) &&
    /\.debug-flow-rail\s*{[\s\S]*max-height:\s*58px;[\s\S]*overflow-y:\s*auto/m.test(stylesheet) &&
    /\.diagnostics-stack\s*>\s*\.brain-state-panel--compact\s*{[\s\S]*max-height:\s*76px/m.test(stylesheet),
  'Desktop supporting proof rails should scroll internally so optional evidence cannot push the composer below the viewport'
);
assert(
  stylesheet.includes('conversation-first-grid') &&
    /\.chat-layout\s*{[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s+minmax\(240px,\s*320px\)/m.test(stylesheet) &&
    /\.chat-area-wrapper\s*{[\s\S]*grid-column:\s*1;/m.test(stylesheet) &&
    /\.project-intro\s*{[\s\S]*grid-column:\s*2;/m.test(stylesheet),
  'desktop layout must lead with a wide chat workspace and trail with context'
);
assert(
  stylesheet.includes('conversation-first-mobile') &&
    /@media \(max-width:\s*760px\)[\s\S]*\.chat-area-wrapper\s*{[\s\S]*grid-row:\s*1;/m.test(stylesheet) &&
    /@media \(max-width:\s*760px\)[\s\S]*\.project-intro\s*{[\s\S]*grid-row:\s*2;/m.test(stylesheet) &&
    cssRuleAfter(stylesheet, stylesheet.indexOf('/* conversation-first-mobile */'), '.orch-signal-badges')
      .includes('grid-column: 1 / -1') &&
    !/@media \(max-width:\s*760px\)[\s\S]*\.chat-area-wrapper\s*{[\s\S]*order:\s*1;/m.test(stylesheet),
  'mobile layout must use matching DOM/grid order without creating implicit header columns'
);
assert(
  /<header class="conversation-header">\s*<div class="conversation-copy">[\s\S]*?<\/div>\s*<div class="orch-signal-badges"/m.test(template) &&
    /\.conversation-copy\s*\{[\s\S]*grid-column:\s*1;[\s\S]*\}/m.test(stylesheet) &&
    /\.orch-signal-badges\s*\{[\s\S]*grid-column:\s*2;/m.test(stylesheet),
  'conversation header copy and status badges must occupy two explicit grid cells'
);
assert(
  /<div class="chat-transcript-region">\s*<div id="chatEmptyState"[\s\S]*?<div id="chatWindow" role="log"/m.test(template) &&
    /\.chat-transcript-region\s*\{[\s\S]*display:\s*grid;[\s\S]*overflow:\s*hidden;[\s\S]*\}/m.test(stylesheet) &&
    conversationTranscriptRule.includes('min-height: 0') &&
    /\.chat-transcript-region\s*>\s*\.chat-empty-state,\s*\.chat-transcript-region\s*>\s*#chatWindow\s*\{[\s\S]*grid-area:\s*1\s*\/\s*1;[\s\S]*\}/m.test(stylesheet),
  'empty prompt starters and the chat log must share one bounded transcript region'
);
assert(
  conversationStatusRule.includes('display: flex') &&
    conversationStatusRule.includes('flex-wrap: wrap') &&
    conversationStatusRule.includes('overflow-x: visible') &&
    conversationStatusRule.includes('overflow-y: visible') &&
    finalMobileStatusRule.includes('display: grid;') &&
    finalMobileStatusRule.includes('grid-template-columns: repeat(3, minmax(0, 1fr));') &&
    finalMobileStatusRule.includes('overflow-x: visible;') &&
    finalMobileStatusRule.includes('overflow-y: visible;') &&
    finalMobileHealthRule.includes('grid-column: 1 / -1;'),
  'conversation status evidence may wrap on desktop and must use a bounded three-column mobile grid without a nested scroller'
);
assert(
  /button,\s*\.admin-tools\s*>\s*summary,\s*\.response-settings\s*>\s*summary,\s*\.diagnostics-disclosure\s*>\s*summary\s*{[\s\S]*min-height:\s*44px/m.test(stylesheet),
  'interactive controls and disclosures must expose 44px targets'
);
assert(
  /@media \(max-width:\s*760px\)[\s\S]*\.composer textarea\s*{[\s\S]*height:\s*44px;[\s\S]*min-height:\s*44px;/m.test(stylesheet),
  'mobile composer input must preserve a 44px touch target'
);
assert(
  /@media \(max-width:\s*760px\) and \(max-height:\s*760px\)[\s\S]*\.composer textarea\s*\{[\s\S]*height:\s*44px;[\s\S]*min-height:\s*44px;/m.test(stylesheet) &&
    /@media \(max-width:\s*760px\) and \(max-height:\s*760px\)[\s\S]*button\s*\{[\s\S]*min-height:\s*44px;/m.test(stylesheet),
  'short mobile composer controls must preserve 44px touch targets'
);
assert(
  stylesheet.includes('.chat-area-wrapper:has(#chatWindow .message) .chat-empty-state') &&
    stylesheet.includes('.diagnostics-stack') &&
    stylesheet.includes('max-height: min(40vh, 360px)') &&
    stylesheet.includes('@media (prefers-reduced-motion: reduce)'),
  'empty state, bounded diagnostics, and reduced motion must be explicit'
);
assert(
  decisionFirstStylesStart >= 0 && evidenceQualityRule.includes('overflow-wrap: anywhere'),
  'Evidence badge styling must live in the final decision-first layer'
);
assert(
  decisionRibbonRule.includes('flex: 1 1 100%'),
  'Decision ribbon styling must refine the final status rail'
);
assert(
  stylesheet.includes('.decision-ribbon [data-state="not-observed"]'),
  'Decision ribbon must visibly distinguish not-observed'
);
assert(
  stylesheet.includes('@media (prefers-reduced-motion: reduce)'),
  'Decision-first UI must preserve reduced-motion handling'
);
assert(
  conversationHealthRule.includes('flex-basis: 240px;') &&
    finalMobileHealthRule.includes('grid-column: 1 / -1;'),
  'Final base and mobile Health layout must use the semantic data-health-pill hook'
);
assert(
  compactDecisionStylesStart > finalNarrowMobileStart &&
    compactDecisionStylesStart < finalShortMobileStart &&
    compactDecisionHeaderRule.includes('padding: 10px;') &&
    compactDecisionTitleRule.includes('font-size: 20px;') &&
    compactDecisionTitleRule.includes('line-height: 1.15;') &&
    compactDecisionActionRule.includes('min-width: 72px;') &&
    compactDecisionBadgeRule.includes('font-size: 10px;') &&
    compactDecisionBadgeRule.includes('line-height: 1.1;') &&
    compactDecisionStageRule.includes('grid-template-columns: repeat(3, minmax(0, 1fr));') &&
    compactDiagnosticsSummaryRule.includes('min-width: 0;') &&
    compactDiagnosticsSummaryRule.includes('font-size: 12px;') &&
    compactDiagnosticsSummaryRule.includes('line-height: 1.2;') &&
    finalShortMobileDecisionRule.includes('grid-template-columns: repeat(3, minmax(0, 1fr));'),
  'Compact phones must preserve a contained composer through explicit header, Decision density, and bounded Diagnostics summary copy'
);
assert(
  /\.admin-tools:not\(\[open\]\)\s*>\s*:not\(summary\),\s*\.response-settings:not\(\[open\]\)\s*>\s*:not\(summary\),\s*\.diagnostics-disclosure:not\(\[open\]\)\s*>\s*:not\(summary\)\s*{[\s\S]*display:\s*none/m.test(stylesheet),
  'closed native disclosures must explicitly hide direct content despite author display rules'
);
assert(
  /\.admin-tools-menu\s+a,\s*\.sign-in-link\s*{[\s\S]*min-height:\s*44px/m.test(stylesheet),
  'top-level sign-in links must share the 44px admin menu target'
);
assert(
  conversationStylesStart >= 0 &&
    unqualifiedConversationTranscriptRule > conversationStylesStart &&
    finalShortMobileStart > unqualifiedConversationTranscriptRule &&
    finalShortMobileTranscriptRule.includes('flex: 1 1 140px;') &&
    finalShortMobileTranscriptRule.includes('min-height: 0;') &&
    finalShortMobileWindowRule.includes('padding: 10px;'),
  'the final short-height mobile transcript override must remain shrinkable after the unqualified conversation rule'
);
assert(
  stylesheet.includes('chat-mobile-short-transcript-priority') &&
    /@media \(max-width:\s*760px\) and \(max-height:\s*760px\)[\s\S]*\.chat-transcript-region\s*{[\s\S]*flex:\s*1\s+1\s+140px;[\s\S]*min-height:\s*0/m.test(stylesheet) &&
    finalMobileStatusRule.includes('display: grid;') &&
    finalMobileStatusRule.includes('grid-template-columns: repeat(3, minmax(0, 1fr));') &&
    finalMobileStatusRule.includes('overflow-x: visible;') &&
    finalMobileStatusRule.includes('overflow-y: visible;') &&
    finalMobileHealthRule.includes('grid-column: 1 / -1;') &&
    /@media \(max-width:\s*760px\) and \(max-height:\s*760px\)[\s\S]*\.conversation-copy\s*>\s*p:not\(\.eyebrow\)\s*{[\s\S]*display:\s*none;/m.test(stylesheet) &&
    /@media \(max-width:\s*760px\) and \(max-height:\s*760px\)[\s\S]*\.quick-prompts\s*{[\s\S]*grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/m.test(stylesheet) &&
    stylesheet.includes('chat-status-cards-remain-readable-on-short-phones') &&
    compactHealthRule.includes('overflow-wrap: anywhere;') &&
    compactHealthRule.includes('white-space: normal;') &&
    !compactHealthRule.includes('white-space: nowrap;') &&
    !compactHealthRule.includes('overflow-wrap: normal;') &&
    compactHeartbeatRule.includes('max-height: 44px;') &&
    compactHeartbeatRule.includes('overflow-y: auto;') &&
    /@media \(max-width:\s*760px\) and \(max-height:\s*760px\)[\s\S]*\.debug-heartbeat-card\[data-debug-heartbeat-field="harmony"\][\s\S]*\.debug-heartbeat-card\[data-debug-heartbeat-field="traceMemory"\][\s\S]*display:\s*none/m.test(stylesheet),
  'Short mobile chat layout should reserve transcript height while keeping visible status evidence readable'
);
const compactHeightRule = stylesheet.slice(stylesheet.indexOf('@media (max-height: 760px)'), stylesheet.indexOf('@media (max-width: 760px) and (max-height: 760px)'));
assert(
  !/\.debug-heartbeat-card:nth-of-type/.test(compactHeightRule) &&
    /\.debug-heartbeat-card\s*{[\s\S]*display:\s*none/m.test(compactHeightRule) &&
    /\.debug-heartbeat-card\[data-debug-heartbeat-field="supabase"\][\s\S]*\.debug-heartbeat-card\[data-debug-heartbeat-field="browser"\][\s\S]*\.debug-heartbeat-card\[data-debug-heartbeat-field="computer"\][\s\S]*display:\s*grid/m.test(compactHeightRule),
  'Short-height heartbeat should use explicit external evidence selectors, not nth-of-type order, so Supabase/Browser/Computer remain visible'
);
assert(
  stylesheet.includes('chat-short-desktop-transcript-priority') &&
    /@media \(max-height:\s*760px\)[\s\S]*\.debug-heartbeat-summary\s*{[\s\S]*grid-column:\s*auto/m.test(stylesheet) &&
    /@media \(max-height:\s*760px\)[\s\S]*\.debug-heartbeat-card--wide\s*{[\s\S]*grid-column:\s*auto/m.test(stylesheet) &&
    /@media \(max-height:\s*760px\)[\s\S]*\.debug-heartbeat-bar\s*{[\s\S]*grid-template-columns:\s*repeat\(6,\s*minmax\(0,\s*1fr\)\)/m.test(stylesheet) &&
    /@media \(max-height:\s*760px\)[\s\S]*#chatWindow\s*{[\s\S]*flex:\s*1\s+1\s+180px;[\s\S]*min-height:\s*clamp\(150px,\s*24vh,\s*220px\)/m.test(stylesheet),
  'Short-height desktop chat layout should compact debug heartbeat to preserve a usable transcript pane'
);
assert(
  /\.message\.assistant\[data-state="pending"\][\s\S]*?border-left:\s*3px\s+solid\s+#5f7f95/i.test(stylesheet) &&
    /\.message\.assistant\[data-state="pending"\][\s\S]*?font-style:\s*italic/i.test(stylesheet) &&
    /\.message\.assistant\[data-state="ready"\][\s\S]*?border-left:\s*0/i.test(stylesheet),
  'Assistant message visual states should distinguish pending response bubbles and reset ready bubbles'
);
assert(
  /\.message\.assistant\[data-state="pending"\]::before[\s\S]*?content:\s*"Assistant is preparing"/i.test(stylesheet) &&
    /\.message\.assistant\[data-state="pending"\]::before[\s\S]*?display:\s*inline-block/i.test(stylesheet),
  'Pending assistant bubbles should show a short visible placeholder without mutating answer text'
);
{
  const imageContext = {
    document: {
      createElement: imageDomElement,
      createTextNode(text) {
        return { nodeType: 3, textContent: String(text ?? ''), children: [] };
      }
    }
  };
  vm.createContext(imageContext);
  vm.runInContext(
    imageJobUiSource
      .replace(/export async function (\w+)/g, 'globalThis.$1 = async function $1')
      .replace(/export function (\w+)/g, 'globalThis.$1 = function $1'),
    imageContext,
    { filename: 'image-jobs-ui.js' }
  );
  const target = imageDomElement('div');
  const card = vm.runInContext(
    "renderImageJobCard(globalThis.__target, { status: 'failed', reason: 'image plugin disabled', content: '[image job unavailable]' })",
    Object.assign(imageContext, { __target: target })
  );
  assert(
    card.getAttribute('aria-label')?.includes('Image request unavailable') &&
      card.getAttribute('aria-label')?.includes('status failed') &&
      card.getAttribute('aria-label')?.includes('reason image plugin disabled'),
    `image job card aria should preserve the redacted failure reason: ${card.getAttribute('aria-label')}`
  );
  const readableCardText = nodeText(card);
  assert(
    readableCardText.includes('Image job Image request unavailable status: failed id: - reason: image plugin disabled content: [image job unavailable]') &&
      !readableCardText.includes('id: pending') &&
      !readableCardText.includes('Image jobImage request') &&
      !readableCardText.includes('unavailablestatus') &&
      !readableCardText.includes('failedid'),
    `image job card visible text should preserve readable separators: ${readableCardText}`
  );
  vm.runInContext(
    "renderImageJobCard(globalThis.__target, { status: 'failed', reason: 'image_prompt_required', content: '[image job unavailable]' })",
    Object.assign(imageContext, { __target: target })
  );
  assert(
    target.children.filter((child) => child?.dataset?.imageJobDebug === 'true').length === 2,
    `image job render should preserve one card per request: cardCount=${target.children.length}`
  );
}
vm.runInContext(script, context, { filename: 'chat.js' });
assert(vm.runInContext(`
  dom.diagnosticsSummary.dataset.diagnosticCode = 'current-turn-failure';
  renderRuntimeToolkit({FULL_LOAD_READY:false, requiredReady:2, requiredTotal:3, optionalTotal:1, services:[{serviceId:'ollama-local', required:true, status:'COOLDOWN', primaryPathStatus:'NOT_READY', functionalStatus:'FALLBACK_ACTIVE', reasonCode:'GPU_UNAVAILABLE'}]});
  const failedText = document.getElementById('runtimeToolkitServices').textContent;
  const primaryKept = dom.diagnosticsSummary.dataset.diagnosticCode === 'current-turn-failure';
  renderRuntimeToolkit({FULL_LOAD_READY:true, requiredReady:3, requiredTotal:3, services:[{serviceId:'ollama-local', status:'READY', functionalStatus:'VERIFIED'}]});
  const recoveredText = document.getElementById('runtimeToolkitServices').textContent;
  delete dom.diagnosticsSummary.dataset.diagnosticCode;
  failedText.includes('FALLBACK_ACTIVE') && failedText.includes('GPU_UNAVAILABLE') && !recoveredText.includes('GPU_UNAVAILABLE') && primaryKept;
`, context), 'Runtime Diagnostics must distinguish fallback and recovery while preserving the primary diagnostic');

assert(vm.runInContext(`
  renderDebugHeartbeat({localLlmRecovery: {state:'COOLDOWN', fallbackUsed:true}});
  renderDebugHeartbeat({});
  lastLocalLlmRecovery === null;
`, context), 'A fresh heartbeat with no manager must clear stale local recovery state');


assert(
  vm.runInContext("typeof isChatTransitionDebugEnabled === 'function' && typeof beginChatTransitionDebugTurn === 'function' && typeof recordChatTransitionDebug === 'function'", context),
  'chat UI must expose one bounded debug-flag-gated transition recorder'
);
const transitionDebugEventCountBefore = dispatchedEvents.filter((event) => event.type === 'awx:chat-transition').length;
for (const debugDisabledSearch of [
  '?codexSmoke=contract',
  '?codexSmoke=contract&debug=false',
  '?codexSmoke=contract&debug=0',
  '?codexSmoke=contract&debug=unrelated'
]) {
  context.window.location.search = debugDisabledSearch;
  vm.runInContext(`
    chatTransitionDebugState.records.length = 0;
    chatTransitionDebugState.lastByKind = Object.create(null);
    chatTransitionDebugState.lastSignatureByKind = Object.create(null);
    beginChatTransitionDebugTurn('turn:7', 'new-turn');
    recordChatTransitionDebug({ kind: 'evidence', to: 'citation-backed', reasonCode: 'citation-backed' });
  `, context);
}
assert(
  vm.runInContext('chatTransitionDebugState.records.length', context) === 0 &&
    dispatchedEvents.filter((event) => event.type === 'awx:chat-transition').length === transitionDebugEventCountBefore &&
    transitionDebugCalls.length === 0,
  'chat transition diagnostics must remain silent when the existing debug query flag is absent'
);

for (const debugEnabledSearch of [
  '?codexSmoke=contract&debug=true',
  '?codexSmoke=contract&debug=1',
  '?codexSmoke=contract&debug=on'
]) {
  context.window.location.search = debugEnabledSearch;
  assert(
    vm.runInContext('isChatTransitionDebugEnabled()', context) === true,
    `chat transition diagnostics must honor the approved debug flag spelling: ${debugEnabledSearch}`
  );
}

const originalTransitionUrlSearchParams = context.URLSearchParams;
let transitionDebugQueryParseCount = 0;
context.URLSearchParams = function CountingURLSearchParams(value) {
  transitionDebugQueryParseCount += 1;
  return new originalTransitionUrlSearchParams(value);
};
context.window.location.search = '?codexSmoke=cache-enabled&debug=on';
vm.runInContext('isChatTransitionDebugEnabled(); isChatTransitionDebugEnabled(); isChatTransitionDebugEnabled();', context);
context.window.location.search = '?codexSmoke=cache-disabled&debug=0';
vm.runInContext('isChatTransitionDebugEnabled(); isChatTransitionDebugEnabled();', context);
context.URLSearchParams = originalTransitionUrlSearchParams;
assert(
  transitionDebugQueryParseCount === 2,
  `chat transition debug enablement must parse once per location.search value instead of once per stream observation: parses=${transitionDebugQueryParseCount}`
);

context.window.location.search = '?codexSmoke=contract&debug=true';
context.__nowMs = 1000;
transitionDebugCalls.length = 0;
vm.runInContext(`
  beginChatTransitionDebugTurn('turn:7', 'new-turn');
  globalThis.__nowMs = 1125;
  recordChatTransitionDebug({ kind: 'evidence', to: 'citation-backed', reasonCode: 'citation-backed' });
  recordChatTransitionDebug({
    kind: 'diagnostics',
    to: 'external-unavailable',
    reasonCode: 'private-provider-disabled-detail',
    rawPrompt: 'private-prompt-sentinel',
    rawResponse: 'private-response-sentinel'
  });
`, context);
const transitionDebugRecords = vm.runInContext('JSON.parse(JSON.stringify(chatTransitionDebugState.records))', context);
const transitionDebugEvents = dispatchedEvents
  .filter((event) => event.type === 'awx:chat-transition')
  .slice(transitionDebugEventCountBefore);
const transitionDebugExactKeys = [
  'turnId',
  'kind',
  'from',
  'to',
  'reasonCode',
  'terminalLatch',
  'lateEventBlocked',
  'resetReason',
  'timestampMs',
  'elapsedMs'
].sort();
assert(
  transitionDebugRecords.length === 3 &&
    transitionDebugEvents.length === 3 &&
    transitionDebugCalls.length === 3 &&
    transitionDebugCalls.every((call) => call[0] === '[AWX][chat-transition]') &&
    transitionDebugRecords.every((record) =>
      JSON.stringify(Object.keys(record).sort()) === JSON.stringify(transitionDebugExactKeys) &&
      Number.isInteger(record.timestampMs) && record.timestampMs >= 0 &&
      Number.isInteger(record.elapsedMs) && record.elapsedMs >= 0
    ),
  `debug-enabled transition diagnostics must emit one fixed event/schema per retained record: records=${JSON.stringify(transitionDebugRecords)} events=${transitionDebugEvents.length} console=${transitionDebugCalls.length}`
);
assert(
  transitionDebugRecords[0].turnId === 'turn:7' &&
    transitionDebugRecords[0].kind === 'reset' &&
    transitionDebugRecords[0].resetReason === 'new-turn' &&
    transitionDebugRecords[1].kind === 'evidence' &&
    transitionDebugRecords[1].from === 'not-observed' &&
    transitionDebugRecords[1].to === 'citation-backed' &&
    transitionDebugRecords[1].elapsedMs === 125 &&
    transitionDebugRecords[2].kind === 'diagnostics' &&
    transitionDebugRecords[2].reasonCode === 'not-observed',
  `transition diagnostics must preserve bounded categorical state, turn identity, reset reason, and elapsed time: ${JSON.stringify(transitionDebugRecords)}`
);
assert(
  !JSON.stringify(transitionDebugRecords).includes('private-provider-disabled-detail') &&
    !JSON.stringify(transitionDebugRecords).includes('private-prompt-sentinel') &&
    !JSON.stringify(transitionDebugRecords).includes('private-response-sentinel'),
  `transition diagnostics must never retain raw payload/provider content: ${JSON.stringify(transitionDebugRecords)}`
);
vm.runInContext(`
  for (let index = 0; index < 45; index += 1) {
    globalThis.__nowMs = 1200 + index;
    recordChatTransitionDebug({
      kind: 'diagnostics',
      to: index % 2 === 0 ? 'pending' : 'responding',
      reasonCode: index % 2 === 0 ? 'pending' : 'responding'
    });
  }
`, context);
assert(
  vm.runInContext('chatTransitionDebugState.records.length', context) === 40,
  `transition diagnostics must retain at most 40 records: ${vm.runInContext('chatTransitionDebugState.records.length', context)}`
);

const observerFaultAssistant = fakeElement('debug-observer-fault-assistant');
observerFaultAssistant.dataset.speaker = 'assistant';
observerFaultAssistant.dataset.state = 'pending';
context.__observerFaultAssistant = observerFaultAssistant;
const originalTransitionDispatchEvent = context.document.dispatchEvent;
const originalTransitionConsoleDebug = vmConsole.debug;
let observerFaultDispatchAttempts = 0;
let observerFaultConsoleAttempts = 0;
let observerFaultResult = null;
let observerFaultError = null;
elements.get('messageInput').disabled = false;
elements.get('messageInput').value = 'private-draft-sentinel';
context.document.dispatchEvent = (event) => {
  if (event?.type === 'awx:chat-transition') {
    observerFaultDispatchAttempts += 1;
    throw new Error('event-observer-fault');
  }
  return originalTransitionDispatchEvent.call(context.document, event);
};
vmConsole.debug = () => {
  observerFaultConsoleAttempts += 1;
  throw new Error('console-observer-fault');
};
try {
  observerFaultResult = vm.runInContext(`
    beginChatTransitionDebugTurn('turn:observer-fault', 'new-turn');
    const markResult = markAssistantStreamStopped(globalThis.__observerFaultAssistant);
    const newChatResult = startNewChatSession();
    ({ markResult, newChatResult });
  `, context);
} catch (error) {
  observerFaultError = error;
} finally {
  context.document.dispatchEvent = originalTransitionDispatchEvent;
  vmConsole.debug = originalTransitionConsoleDebug;
}
assert(
  observerFaultError === null &&
    observerFaultResult?.markResult === true &&
    observerFaultResult?.newChatResult === true &&
    observerFaultAssistant.dataset.state === 'stopped' &&
    observerFaultAssistant.dataset.streamCancelState === 'stopped' &&
    observerFaultAssistant.textContent === 'Response stopped' &&
    elements.get('messageInput').value === '' &&
    observerFaultDispatchAttempts >= 3 &&
    observerFaultConsoleAttempts === observerFaultDispatchAttempts,
  `throwing transition observers must not alter chat rendering, return values, terminal state, or new-chat reset: error=${observerFaultError?.message || 'none'} result=${JSON.stringify(observerFaultResult)} state=${observerFaultAssistant.dataset.state}/${observerFaultAssistant.dataset.streamCancelState}/${observerFaultAssistant.textContent} draft=${elements.get('messageInput').value} attempts=${observerFaultDispatchAttempts}/${observerFaultConsoleAttempts}`
);
context.window.location.search = '?codexSmoke=contract';
context.__nowMs = 0;
transitionDebugCalls.length = 0;
vm.runInContext(`
  chatTransitionDebugState.records.length = 0;
  chatTransitionDebugState.lastByKind = Object.create(null);
  chatTransitionDebugState.lastSignatureByKind = Object.create(null);
`, context);
delete context.__observerFaultAssistant;

const citedQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: 'cited', evidenceCount: 3 }))",
  context
));
assert(citedQuality.code === 'citation-backed', 'cited evidence must be visibly classified');
assert(citedQuality.visibleLabel === '인용 확인됨', 'cited evidence must use the approved visible label');

const partialQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: 'partially-cited', evidenceCount: 2 }))",
  context
));
assert(partialQuality.code === 'partial-citation', 'partial evidence must remain partial');

const uncitedQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: 'searched-but-uncited', evidenceCount: 5 }))",
  context
));
assert(uncitedQuality.code === 'retrieved-uncited', 'retrieved evidence must not imply citation');

const countOnlyQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: '', evidenceCount: 9 }))",
  context
));
assert(countOnlyQuality.code === 'not-observed', 'Evidence count must not promote citation quality');

const unavailableQuality = JSON.parse(vm.runInContext(
  "JSON.stringify(presentEvidenceQuality({ citationState: '', evidenceCount: 0, externalUnavailable: true }))",
  context
));
assert(unavailableQuality.code === 'external-unavailable', 'explicit disabled state must be distinguishable');
assert(!unavailableQuality.detail, 'raw disabled reason must not enter visible detail');

const badgeResult = vm.runInContext("(() => {" +
  "const holder = document.createElement('div');" +
  "const quality = presentEvidenceQuality({ citationState: 'searched-but-uncited', evidenceCount: 2 });" +
  "const badge = appendEvidenceQualityBadge(holder, quality);" +
  "return JSON.stringify({" +
    "text: badge.textContent," +
    "code: badge.dataset.evidenceQuality," +
    "tone: badge.dataset.tone," +
    "aria: badge.getAttribute('aria-label')," +
    "childCount: holder.children.length" +
  "});" +
"})()", context);
const badge = JSON.parse(badgeResult);
assert(badge.text === '검색됨 · 미인용', 'badge must expose readable evidence quality');
assert(badge.code === 'retrieved-uncited', 'badge must expose a finite evidence code');
assert(badge.aria === '근거 품질: 검색됨, 미인용', 'visible and accessible evidence meaning must agree');
assert(badge.childCount === 1, 'one evidence badge must be appended');

const emptyDecision = JSON.parse(vm.runInContext(
  "JSON.stringify(presentObservedDecision({ streamStatus: 'idle', healthOverlay: { kind: 'none' } }))",
  context
));
assert(
  emptyDecision.stages.every((stage) => stage.state === 'not-observed'),
  'idle Decision ribbon must reset every stage to not-observed'
);

const stoppedDecision = JSON.parse(vm.runInContext(
  "JSON.stringify(presentObservedDecision({" +
    "route: 'local'," +
    "citationState: 'cited'," +
    "answerMode: 'final'," +
    "streamStatus: 'cancelled'," +
    "healthOverlay: { kind: 'stopped' }" +
  "}))",
  context
));
const stoppedAnswer = stoppedDecision.stages.find((stage) => stage.code === 'answer');
const stoppedRecover = stoppedDecision.stages.find((stage) => stage.code === 'recover');
const stoppedContext = stoppedDecision.stages.find((stage) => stage.code === 'context');
assert(stoppedAnswer.state === 'observed-cancelled', 'stopped Health must outrank complete answer mode');
assert(stoppedRecover.state === 'observed-cancelled', 'cancelled turn must expose observed recovery');
assert(stoppedContext.state === 'not-observed', 'Context must not be inferred from controls');

const recoveredDecision = JSON.parse(vm.runInContext(
  "JSON.stringify(presentObservedDecision({" +
    "answerMode: 'chat'," +
    "streamStatus: 'final'," +
    "recoveryState: 'primary_failed_fallback_done'," +
    "healthOverlay: { kind: 'none' }" +
  "}))",
  context
));
const recoveredAnswer = recoveredDecision.stages.find((stage) => stage.code === 'answer');
const recoveredRecover = recoveredDecision.stages.find((stage) => stage.code === 'recover');
assert(recoveredAnswer.state === 'observed-complete', 'recovered fallback must retain the completed answer');
assert(recoveredRecover.state === 'observed-complete', 'explicit fallback success must expose observed recovery');
assert(
  recoveredRecover.sourceBasis === 'model-attempt-summary',
  'Recover must cite the bounded model attempt summary rather than infer from answer text'
);

const recoveredTransformerBadges = JSON.parse(vm.runInContext(
  "JSON.stringify(deriveTransformerBadges([" +
    "{ id: 'model', status: 'done', reason: 'primary_failed_fallback_done' }," +
    "{ id: 'recover', status: 'done', reason: 'primary_failed_fallback_done' }" +
  "]))",
  context
));
assert(
  recoveredTransformerBadges.recoveryState === 'primary_failed_fallback_done',
  'Transformer recovery block must project the bounded model-attempt recovery code'
);

const decisionStageNodes = ['route', 'context', 'retrieve', 'evidence', 'answer', 'recover'].map((code) => {
  const value = { textContent: '' };
  return {
    dataset: { decisionStage: code, state: 'not-observed' },
    attributes: {},
    querySelector: (selector) => selector === '[data-decision-state-label]' ? value : null,
    setAttribute(name, valueText) { this.attributes[name] = String(valueText); },
    getAttribute(name) { return this.attributes[name] || null; },
    value
  };
});
const decisionRoot = elements.get('decisionRibbon');
decisionRoot.querySelectorAll = (selector) =>
  selector === '[data-decision-stage]' ? decisionStageNodes : [];

context.__decisionPresentation = {
  stages: [
    { code: 'answer', label: 'Answer', state: 'observed-active', shouldUpdate: true },
    { code: 'recover', label: 'Recover', state: 'not-observed', shouldUpdate: false }
  ]
};
vm.runInContext('renderDecisionRibbon(__decisionPresentation)', context);
const renderedAnswer = decisionStageNodes.find((node) => node.dataset.decisionStage === 'answer');
assert(renderedAnswer.dataset.state === 'observed-active', 'renderer must set the bounded Answer state');
assert(renderedAnswer.value.textContent === 'Active', 'renderer must expose readable state text');

const decisionStage = (code) => decisionStageNodes.find((node) => node.dataset.decisionStage === code);
vm.runInContext(`
  resetCurrentTurnHealthOverlay();
  updateOrchestrationSignalBar({
    streamStatus: 'connecting',
    pipelineSnapshot: { route: 'local' }
  });
`, context);
assert(
  decisionStage('route').dataset.state === 'observed-complete' &&
    decisionStage('context').dataset.state === 'not-observed' &&
    decisionStage('retrieve').dataset.state === 'not-observed' &&
    decisionStage('answer').dataset.state === 'observed-active',
  `ordinary orchestration updates must render only observed Decision stages: ${decisionStageNodes.map((node) => `${node.dataset.decisionStage}:${node.dataset.state}`).join('|')}`
);

vm.runInContext(`
  resetCurrentTurnHealthOverlay();
  updateOrchestrationSignalBar({
    streamStatus: 'transformer',
    answerMode: 'chat',
    recoveryState: 'primary_failed_fallback_done'
  });
  updateOrchestrationSignalBar({
    streamStatus: 'final',
    answerMode: 'chat',
    model: 'qwen3:fixture-fallback'
  });
`, context);
assert(
  decisionStage('recover').dataset.state === 'observed-complete' &&
    decisionStage('recover').value.textContent === 'Observed',
  'observed fallback recovery must survive later final/model updates in the same turn'
);

vm.runInContext(
  "updateOrchestrationSignalBar({ streamStatus: 'connecting', answerMode: '' })",
  context
);
assert(
  decisionStage('recover').dataset.state === 'not-observed',
  'a new connecting turn must reset the previous turn recovery observation'
);

vm.runInContext(
  "renderDecisionRibbon(presentObservedDecision({ streamStatus: 'idle', healthOverlay: { kind: 'none' } }))",
  context
);
const emptyDecisionTarget = fakeElement('decision-empty-evidence-target');
context.__emptyDecisionTarget = emptyDecisionTarget;
vm.runInContext(`
  renderEvidenceRail([], globalThis.__emptyDecisionTarget, {
    answerMode: 'CHAT',
    pipeline: { route: 'fallback' }
  });
`, context);
assert(
  decisionStage('route').dataset.state === 'observed-complete',
  'empty evidence rendering must still expose an observed pipeline route'
);

vm.runInContext(
  "renderDecisionRibbon(presentObservedDecision({ streamStatus: 'idle', healthOverlay: { kind: 'none' } }))",
  context
);
const citedDecisionTarget = fakeElement('decision-cited-evidence-target');
context.__citedDecisionTarget = citedDecisionTarget;
vm.runInContext(`
  renderEvidenceRail(
    [{ marker: 'W1', title: 'Observed source', source: 'https://docs.example.test/observed' }],
    globalThis.__citedDecisionTarget,
    { answerMode: 'RAG', answerText: 'Supported by [W1]', pipeline: {} }
  );
`, context);
assert(
  decisionStage('evidence').dataset.state === 'observed-complete',
  'nonempty cited evidence rendering must expose observed Evidence'
);
vm.runInContext(`
  state.latestEvidenceRailItems = [];
  state.latestVisibleTurnEvidence = null;
  resetCurrentTurnHealthOverlay();
  renderDecisionRibbon(presentObservedDecision({ streamStatus: 'idle', healthOverlay: currentTurnHealthOverlay }));
`, context);

const pendingDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'pending' }," +
    "streamStatus: 'pending'," +
    "streamContext: 'client-wait'," +
    "isStopAvailable: true" +
  "}))",
  context
));
assert(pendingDiagnostic.code === 'pending', 'pending Health must own the primary summary');
assert(pendingDiagnostic.action.code === 'stop', 'only the enabled existing Stop control may become an action');
assert(
  pendingDiagnostic.summaryText === 'Response pending · current turn · Stop available',
  'primary summary must expose bounded fact and safe action'
);

const stoppedDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'stopped' }," +
    "answerMode: 'final'," +
    "isStopAvailable: false" +
  "}))",
  context
));
assert(stoppedDiagnostic.code === 'stopped', 'stopped Health must outrank final answer mode');
assert(stoppedDiagnostic.action.code === 'none', 'terminal cancellation must not invent a retry');

const attentionDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'attention' }," +
    "streamContext: 'retry-now raw-provider-text'," +
    "isStopAvailable: false" +
  "}))",
  context
));
assert(attentionDiagnostic.code === 'attention', 'attention must remain a bounded warning');
assert(attentionDiagnostic.action.code === 'none', 'raw next action must not become a command');
assert(!JSON.stringify(attentionDiagnostic).includes('raw-provider-text'), 'raw diagnostic text must not enter primary output');

const unavailableDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'none' }," +
    "answerMode: 'chat'," +
    "evidenceQualityCode: 'external-unavailable'" +
  "}))",
  context
));
assert(unavailableDiagnostic.code === 'external-unavailable', 'current unavailable evidence must outrank a neutral completion');
assert(unavailableDiagnostic.action.code === 'none', 'provider-disabled evidence must not invent an action');

const localSafeDiagnostic = JSON.parse(vm.runInContext(
  "JSON.stringify(selectPrimaryDiagnostic({" +
    "healthOverlay: { kind: 'none' }," +
    "answerMode: 'FALLBACK_LOCAL'," +
    "isStopAvailable: false" +
  "}))",
  context
));
assert(localSafeDiagnostic.code === 'model-unavailable', 'local safe fallback must expose the model-route failure');
assert(
  localSafeDiagnostic.summaryText === 'Model route unavailable · local safe response',
  'local safe fallback must keep the primary summary bounded and explicit'
);
assert(localSafeDiagnostic.action.code === 'none', 'model-route failure must not invent a retry action');

const selectionEntropyReplayFixture = Object.freeze({
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
});
const installSelectionEntropyCard = (assistant) => {
  context.__selectionEntropyAssistant = assistant;
  context.__selectionEntropySignal = selectionEntropyReplayFixture;
  return vm.runInContext(
    "renderSelectionEntropyTrace({ selectionEntropySignal: globalThis.__selectionEntropySignal }, globalThis.__selectionEntropyAssistant)",
    context
  );
};
{
  const chatWindow = elements.get('chatWindow');
  const newAssistant = (id) => {
    const assistant = fakeElement(id);
    chatWindow.appendChild(assistant);
    context.__selectionEntropyAssistant = assistant;
    return assistant;
  };
  const renderSelection = (assistant, signal) => {
    context.__selectionEntropySignal = signal;
    vm.runInContext(
      "renderChatEvent({ type: 'selection_entropy', selectionEntropySignal: globalThis.__selectionEntropySignal }, globalThis.__selectionEntropyAssistant, 'selection_entropy')",
      context
    );
    return assistant.querySelector('[data-selection-entropy-card]');
  };
  const standardSignal = {
    schema: 'awx.selection-entropy.v1',
    mode: 'standard',
    replayAccepted: false,
    coherenceStatus: 'not_requested',
    replayReference: null,
    algorithmVersion: 'selection-entropy-v1',
    decisionDigest: 'a'.repeat(64),
    decisionCount: 0,
    drawCount: 0,
    stableTieBreakCount: 0,
    candidateDriftCount: 0,
    routerDrawCount: 0,
    strategyDrawCount: 0,
    ensembleDrawCount: 0,
    completionOrderDeterministic: false,
    reasonCode: '',
    unknownFutureField: 'PRIVATE_SELECTION_ENTROPY_SENTINEL'
  };
  const replaySignal = {
    ...standardSignal,
    mode: 'replay',
    replayAccepted: true,
    coherenceStatus: 'matched',
    replayReference: '009e8892e5b3',
    decisionDigest: 'b'.repeat(64),
    decisionCount: 5,
    drawCount: 4,
    stableTieBreakCount: 2,
    routerDrawCount: 1,
    strategyDrawCount: 1,
    ensembleDrawCount: 2
  };

  chatWindow.replaceChildren();
  let assistant = newAssistant('selection-standard');
  let card = renderSelection(assistant, standardSignal);
  assert(card && card.id === 'section', 'selection entropy should render a semantic section');
  assert(card.children.some((child) => child.id === 'h3') && card.children.some((child) => child.id === 'dl'),
    'selection entropy should contain a heading and definition list');
  assert(card.dataset.status === 'not_requested', `standard status should be typed: ${card.dataset.status}`);
  assert(card.getAttribute('aria-label')?.includes('Selection replay'), 'selection entropy card needs a readable label');
  assert(card.getAttribute('aria-hidden') === null, 'selection entropy card must not be hidden from assistive technology');
  assert(nodeText(card).includes('Standard') && nodeText(card).includes('Not requested'),
    `standard selection text missing: ${nodeText(card)}`);
  assert(!nodeText(card).includes('PRIVATE_SELECTION_ENTROPY_SENTINEL'), 'unknown selection field leaked');
  assert(!nodeText(card).includes(standardSignal.decisionDigest), 'decision digest must not be visible');

  card = renderSelection(assistant, replaySignal);
  assert(assistant.querySelectorAll('[data-selection-entropy-card]').length === 1,
    'selection entropy updates must replace instead of duplicate');
  assert(card.dataset.status === 'matched' && nodeText(card).includes('009e8892e5b3'),
    `matched replay card missing reference/status: ${nodeText(card)}`);
  assert(nodeText(card).includes('Not deterministic'), 'completion ordering must not rely on color');

  card = renderSelection(assistant, {
    ...replaySignal,
    coherenceStatus: 'partial',
    candidateDriftCount: 1,
    reasonCode: 'selection_entropy_candidate_drift'
  });
  assert(card.dataset.status === 'partial' && nodeText(card).includes('Candidate drift'),
    `partial replay reason missing: ${nodeText(card)}`);
  card = renderSelection(assistant, {
    ...replaySignal,
    coherenceStatus: 'failed',
    reasonCode: 'selection_entropy_derivation_invalid'
  });
  assert(card.dataset.status === 'failed' && nodeText(card).includes('Derivation invalid'),
    `failed replay reason missing: ${nodeText(card)}`);

  vm.runInContext(
    "renderChatEvent({ type: 'final', data: 'final answer', modelUsed: 'local', answerMode: 'CHAT' }, globalThis.__selectionEntropyAssistant, 'final')",
    context
  );
  assert(assistant.querySelector('[data-selection-entropy-card]'), 'final replacement removed selection entropy card');
  assert(nodeText(assistant).includes('final answer'), 'final answer text missing after card restoration');

  const invalidSelectionSignals = [
    ['schema', { ...replaySignal, schema: 'awx.selection-entropy.v2' }],
    ['algorithm', { ...replaySignal, algorithmVersion: 'selection-entropy-v2' }],
    ['mode', { ...replaySignal, mode: 'other' }],
    ['coherence', { ...replaySignal, coherenceStatus: 'unknown' }],
    ['reason', { ...replaySignal, reasonCode: 'selection_entropy_unknown' }],
    ['replayAccepted-type', { ...replaySignal, replayAccepted: 'true' }],
    ['completionOrderDeterministic-type', { ...replaySignal, completionOrderDeterministic: 0 }],
    ['replay-reference-length', { ...replaySignal, replayReference: '009e8892e5b' }],
    ['replay-reference-hex', { ...replaySignal, replayReference: '009E8892E5B3' }],
    ['decision-digest-length', { ...replaySignal, decisionDigest: 'b'.repeat(63) }],
    ['decision-digest-hex', { ...replaySignal, decisionDigest: 'B'.repeat(64) }],
    ['standard-replay-accepted', { ...standardSignal, replayAccepted: true }],
    ['standard-coherence', { ...standardSignal, coherenceStatus: 'matched' }],
    ['standard-reference', { ...standardSignal, replayReference: '009e8892e5b3' }],
    ['standard-reason', { ...standardSignal, reasonCode: 'selection_entropy_candidate_drift' }],
    ['replay-not-accepted', { ...replaySignal, replayAccepted: false }],
    ['replay-not-requested', { ...replaySignal, coherenceStatus: 'not_requested' }],
    ['replay-missing-reference', { ...replaySignal, replayReference: null }]
  ];
  const selectionCountFields = [
    'decisionCount',
    'drawCount',
    'stableTieBreakCount',
    'candidateDriftCount',
    'routerDrawCount',
    'strategyDrawCount',
    'ensembleDrawCount'
  ];
  for (const field of selectionCountFields) {
    invalidSelectionSignals.push(
      [`${field}-negative`, { ...replaySignal, [field]: -1 }],
      [`${field}-overflow`, { ...replaySignal, [field]: 10001 }],
      [`${field}-type`, { ...replaySignal, [field]: '1' }]
    );
  }
  for (const [label, invalidSignal] of invalidSelectionSignals) {
    renderSelection(assistant, replaySignal);
    assert(assistant.querySelector('[data-selection-entropy-card]'),
      `invalid selection entropy ${label} precondition must install a prior card`);
    renderSelection(assistant, invalidSignal);
    assert(!assistant.querySelector('[data-selection-entropy-card]'),
      `invalid selection entropy ${label} must clear the prior card`);
  }
  renderSelection(assistant, replaySignal);
  context.__selectionEntropySignal = null;
  vm.runInContext(
    "renderChatEvent({ type: 'selection_entropy' }, globalThis.__selectionEntropyAssistant, 'selection_entropy')",
    context
  );
  assert(!assistant.querySelector('[data-selection-entropy-card]'), 'missing signal must clear the prior card');

  assistant = newAssistant('selection-cancel');
  renderSelection(assistant, replaySignal);
  vm.runInContext('markAssistantStreamStopped(globalThis.__selectionEntropyAssistant)', context);
  assert(!assistant.querySelector('[data-selection-entropy-card]'), 'local cancellation must clear selection card');

  assistant = newAssistant('selection-server-cancel');
  renderSelection(assistant, replaySignal);
  vm.runInContext(
    "renderChatEvent({ type: 'status', signal: { phase: 'stream', code: 'cancelled', cancelled: true } }, globalThis.__selectionEntropyAssistant, 'status')",
    context
  );
  assert(!assistant.querySelector('[data-selection-entropy-card]'),
    'server cancellation status must clear selection card');
  assert(
    decisionStage('answer').dataset.state === 'observed-cancelled' &&
      decisionStage('recover').dataset.state === 'observed-cancelled',
    `server cancellation must render observed Answer/Recover cancellation: ${decisionStageNodes.map((node) => `${node.dataset.decisionStage}:${node.dataset.state}`).join('|')}`
  );
  assert(
    elements.get('diagnosticsSummary').dataset.diagnosticCode === 'stopped' &&
      elements.get('diagnosticsSummary').textContent === 'Response stopped · user cancelled' &&
      !/stop available|retry/i.test(elements.get('diagnosticsSummary').textContent),
    `server cancellation must expose a bounded no-retry primary diagnostic: ${elements.get('diagnosticsSummary').dataset.diagnosticCode}/${elements.get('diagnosticsSummary').textContent}`
  );
  const postCancelEvidenceTarget = fakeElement('post-cancel-evidence-target');
  context.__postCancelEvidenceTarget = postCancelEvidenceTarget;
  vm.runInContext(`
    renderLiveDebugHeartbeat({});
    renderEvidenceRail([], globalThis.__postCancelEvidenceTarget, { answerMode: 'CHAT' });
  `, context);
  assert(
    vm.runInContext('currentTurnHealthOverlay.kind', context) === 'stopped' &&
      elements.get('diagnosticsSummary').dataset.diagnosticCode === 'stopped' &&
      elements.get('diagnosticsSummary').textContent === 'Response stopped · user cancelled',
    `background and evidence refreshes must retain the authoritative server-cancel state: overlay=${vm.runInContext('currentTurnHealthOverlay.kind', context)} diagnostic=${elements.get('diagnosticsSummary').dataset.diagnosticCode}/${elements.get('diagnosticsSummary').textContent}`
  );

  assistant = newAssistant('selection-error');
  renderSelection(assistant, replaySignal);
  vm.runInContext(
    "renderChatEvent({ type: 'error', code: 'backend_timeout' }, globalThis.__selectionEntropyAssistant, 'error')",
    context
  );
  assert(!assistant.querySelector('[data-selection-entropy-card]'), 'terminal error must clear selection card');

  assistant = newAssistant('selection-new-session');
  renderSelection(assistant, replaySignal);
  elements.get('messageInput').disabled = false;
  vm.runInContext('startNewChatSession()', context);
  assert(!assistant.querySelector('[data-selection-entropy-card]'), 'new session must clear selection card');
  assert(
    decisionStageNodes.every((node) => node.dataset.state === 'not-observed' && node.value.textContent === 'Not observed'),
    `new session must reset every Decision stage: ${decisionStageNodes.map((node) => `${node.dataset.decisionStage}:${node.dataset.state}:${node.value.textContent}`).join('|')}`
  );
  assert(
    elements.get('diagnosticsSummary').dataset.diagnosticCode === 'not-observed' &&
      elements.get('diagnosticsSummary').textContent === 'State not observed',
    `new session must reset the primary diagnostic: ${elements.get('diagnosticsSummary').dataset.diagnosticCode}/${elements.get('diagnosticsSummary').textContent}`
  );
  delete context.__selectionEntropyAssistant;
  delete context.__selectionEntropySignal;
}

{
  const transitionChatWindow = elements.get('chatWindow');
  const transitionAssistant = fakeElement('assistant-debug-transition');
  transitionAssistant.dataset.speaker = 'assistant';
  transitionAssistant.dataset.state = 'pending';
  transitionChatWindow.appendChild(transitionAssistant);
  const transitionEvidenceTarget = fakeElement('debug-transition-evidence-target');
  context.__transitionAssistant = transitionAssistant;
  context.__transitionEvidenceTarget = transitionEvidenceTarget;
  context.window.location.search = '?codexSmoke=contract&debug=true';
  context.__nowMs = 2000;
  transitionDebugCalls.length = 0;
  const transitionEventStart = dispatchedEvents.length;
  elements.get('stopBtn').hidden = false;
  elements.get('stopBtn').style.display = 'inline-flex';
  elements.get('stopBtn').disabled = false;
  vm.runInContext(`
    chatTransitionDebugState.records.length = 0;
    chatTransitionDebugState.lastByKind = Object.create(null);
    chatTransitionDebugState.lastSignatureByKind = Object.create(null);
    beginChatTransitionDebugTurn('turn:12', 'new-turn');
    updateOrchestrationSignalBar({
      streamStatus: 'model_wait',
      streamContext: 'client-wait:61000ms next:stop_or_wait',
      pipelineSnapshot: { route: 'local' }
    });
    globalThis.__nowMs = 2100;
    renderEvidenceRail(
      [{ marker: 'W1', title: 'Bounded source', source: 'https://docs.example.test/bounded' }],
      globalThis.__transitionEvidenceTarget,
      { answerMode: 'RAG', answerText: 'Supported by [W1]', pipeline: { route: 'local' } }
    );
    globalThis.__nowMs = 2200;
    renderChatEvent(
      { type: 'status', signal: { code: 'cancelled', cancelled: true, detail: 'private-cancel-sentinel' } },
      globalThis.__transitionAssistant,
      'status'
    );
    globalThis.__nowMs = 2300;
    renderChatEvent(
      { type: 'final', data: 'private-late-answer-sentinel', answerMode: 'CHAT' },
      globalThis.__transitionAssistant,
      'final'
    );
    globalThis.__nowMs = 2400;
    startNewChatSession();
  `, context);
  const lifecycleRecords = vm.runInContext('JSON.parse(JSON.stringify(chatTransitionDebugState.records))', context);
  const lifecycleEvents = dispatchedEvents
    .slice(transitionEventStart)
    .filter((event) => event.type === 'awx:chat-transition');
  const lifecycleDecisionKinds = new Set(
    lifecycleRecords.filter((record) => record.kind.startsWith('decision.')).map((record) => record.kind)
  );
  const requiredDecisionKinds = [
    'decision.route',
    'decision.context',
    'decision.retrieve',
    'decision.evidence',
    'decision.answer',
    'decision.recover'
  ];
  const recordIndex = (predicate) => lifecycleRecords.findIndex(predicate);
  const pendingIndex = recordIndex((record) => record.kind === 'diagnostics' && record.to === 'pending');
  const evidenceIndex = recordIndex((record) => record.kind === 'evidence' && record.to === 'citation-backed');
  const terminalIndex = recordIndex((record) => record.kind === 'terminal' && record.to === 'stopped' && record.terminalLatch === true);
  const lateIndex = recordIndex((record) => record.kind === 'late-event' && record.to === 'blocked' && record.lateEventBlocked === true);
  const resetIndex = lifecycleRecords.findIndex((record, index) =>
    index > lateIndex && record.kind === 'reset' && record.resetReason === 'new-chat'
  );
  assert(
    pendingIndex > 0 &&
      evidenceIndex > pendingIndex &&
      terminalIndex > evidenceIndex &&
      lateIndex > terminalIndex &&
      resetIndex > lateIndex,
    `debug transition lifecycle must remain ordered pending -> evidence -> terminal -> late block -> reset: ${JSON.stringify(lifecycleRecords)}`
  );
  assert(
    lifecycleRecords.some((record) => record.kind === 'decision.answer' && record.to === 'observed-active') &&
      lifecycleRecords.some((record) => record.kind === 'decision.answer' && record.to === 'observed-cancelled') &&
      requiredDecisionKinds.every((kind) => lifecycleDecisionKinds.has(kind)) &&
      lifecycleRecords.some((record) => record.kind === 'diagnostics' && record.to === 'stopped') &&
      lifecycleEvents.length === lifecycleRecords.length &&
      transitionDebugCalls.length === lifecycleRecords.length,
    `debug transition lifecycle must expose current Decision, Diagnostics, event, and console parity: records=${JSON.stringify(lifecycleRecords)} events=${lifecycleEvents.length} console=${transitionDebugCalls.length}`
  );
  assert(
    transitionAssistant.dataset.state === 'stopped' &&
      transitionAssistant.textContent === 'Response stopped' &&
      !JSON.stringify(lifecycleRecords).includes('private-cancel-sentinel') &&
      !JSON.stringify(lifecycleRecords).includes('private-late-answer-sentinel'),
    `late events must stay blocked behind the terminal latch without retaining raw content: assistant=${transitionAssistant.dataset.state}/${transitionAssistant.textContent} records=${JSON.stringify(lifecycleRecords)}`
  );

  const zeroEvidenceTarget = fakeElement('debug-zero-evidence-target');
  context.__zeroEvidenceTarget = zeroEvidenceTarget;
  vm.runInContext(`
    beginChatTransitionDebugTurn('turn:13', 'new-turn');
    renderEvidenceRail([], globalThis.__zeroEvidenceTarget, {
      answerMode: 'CHAT',
      answerText: 'No citation signal',
      pipeline: { webCount: 0 }
    });
  `, context);
  const zeroEvidenceRecord = vm.runInContext(
    "JSON.parse(JSON.stringify(chatTransitionDebugState.records.filter((record) => record.turnId === 'turn:13' && record.kind === 'evidence').at(-1)))",
    context
  );
  assert(
    zeroEvidenceRecord.to === 'not-observed' && zeroEvidenceRecord.reasonCode === 'not-observed',
    `webCount=0 alone must remain not-observed instead of inventing searched-empty or cited: ${JSON.stringify(zeroEvidenceRecord)}`
  );

  context.window.location.search = '?codexSmoke=contract';
  context.__nowMs = 0;
  transitionDebugCalls.length = 0;
  elements.get('stopBtn').disabled = true;
  vm.runInContext(`
    resetCurrentTurnHealthOverlay();
    renderPrimaryDiagnostic(selectPrimaryDiagnostic({
      healthOverlay: currentTurnHealthOverlay,
      streamStatus: 'idle',
      isStopAvailable: false
    }));
    state.latestEvidenceRailItems = [];
    state.latestVisibleTurnEvidence = null;
    chatTransitionDebugState.records.length = 0;
    chatTransitionDebugState.lastByKind = Object.create(null);
    chatTransitionDebugState.lastSignatureByKind = Object.create(null);
  `, context);
  delete context.__transitionAssistant;
  delete context.__transitionEvidenceTarget;
  delete context.__zeroEvidenceTarget;
}
const sessionListInitCallCount = fetchCalls.filter((call) => call.url === '/api/chat/sessions').length;
const chatUsageUnknown = vm.runInContext('chatUsageSummary({})', context);
assert(
  chatUsageUnknown === 'usage:unknown',
  `missing chat usage must remain unknown instead of becoming zero: ${chatUsageUnknown}`
);
context.__chatUsageFixture = {
  schemaVersion: 'awx.chat-usage.v1',
  captureEnabled: true,
  observed: true,
  modelInvocations: {
    attempts: 4,
    responseReceived: 3,
    latestNormalizedRequestCap: 2048,
    lastSuccessfulCapState: 'explicit',
    lastSuccessfulConfiguredCap: 2048,
    unknownAttemptCount: 1,
    capStateCounts: { explicit: 3, omitted: 0, provider_default_unknown: 1 },
    providerUsageObservedAttemptCount: 3,
    providerOutputTokensComplete: true,
    providerOutputTokens: 612
  },
  answerExpansion: {
    invocations: 2,
    accepted: 1,
    rejectedNumeric: 1,
    rejectedNoEvidence: 0,
    rejectedTooShort: 0,
    rejectedEmpty: 0,
    skippedBeforeModel: 0,
    rejectionRate: 0.5,
    rejectionRateDenominator: 2,
    providerUsageObservedAttemptCount: 2,
    providerOutputTokens: 120,
    rejectedProviderOutputTokensComplete: true,
    rejectedProviderOutputTokens: 90
  },
  ignoredRawPayload: 'private answer payload'
};
const chatUsageObserved = vm.runInContext('chatUsageSummary(globalThis.__chatUsageFixture)', context);
assert(
  chatUsageObserved === 'usage(proc) calls:4/3 cap:2048 unknown:1 tokens:612 expand:2 accepted:1 rejected:1 rejRate:50%/2 skip:0 rejectTok:90',
  `observed chat usage should render the count-only contract: ${chatUsageObserved}`
);
assert(
  chatUsageObserved.length <= 180 && !chatUsageObserved.includes('private answer payload'),
  `chat usage summary must stay bounded and payload-free: len=${chatUsageObserved.length} value=${chatUsageObserved}`
);
context.__chatUsageMissingProviderTokens = {
  schemaVersion: 'awx.chat-usage.v1',
  captureEnabled: true,
  observed: true,
  modelInvocations: { attempts: 1, responseReceived: 1, unknownAttemptCount: 1 },
  answerExpansion: { invocations: 0 }
};
const chatUsageMissingProviderTokens = vm.runInContext(
  'chatUsageSummary(globalThis.__chatUsageMissingProviderTokens)', context
);
context.__chatUsageOmittedAndMissingRejected = {
  schemaVersion: 'awx.chat-usage.v1',
  captureEnabled: true,
  observed: true,
  modelInvocations: {
    attempts: 2,
    responseReceived: 2,
    unknownAttemptCount: 2,
    capStateCounts: { explicit: 0, omitted: 1, provider_default_unknown: 1 },
    providerUsageObservedAttemptCount: 1,
    providerOutputTokensComplete: false,
    providerOutputTokens: 10
  },
  answerExpansion: {
    invocations: 2,
    accepted: 1,
    rejectedEmpty: 1,
    rejectionRate: 0.5,
    rejectionRateDenominator: 2,
    providerUsageObservedAttemptCount: 1,
    rejectedProviderOutputTokensComplete: false,
    rejectedProviderOutputTokens: 0
  }
};
const chatUsageOmittedAndMissingRejected = vm.runInContext(
  'chatUsageSummary(globalThis.__chatUsageOmittedAndMissingRejected)', context
);
assert(
  chatUsageOmittedAndMissingRejected.includes('unknown:1') &&
    chatUsageOmittedAndMissingRejected.includes('tokens:n/a') &&
    chatUsageOmittedAndMissingRejected.includes('rejectTok:n/a'),
  `omitted caps and partial usage must not be displayed as provider-unknown or zero cost: ${chatUsageOmittedAndMissingRejected}`
);
context.__chatUsageAcceptedOnly = {
  schemaVersion: 'awx.chat-usage.v1',
  captureEnabled: true,
  observed: true,
  modelInvocations: {
    attempts: 1,
    responseReceived: 1,
    capStateCounts: { explicit: 1, omitted: 0, provider_default_unknown: 0 },
    providerUsageObservedAttemptCount: 1,
    providerOutputTokensComplete: true,
    providerOutputTokens: 10
  },
  answerExpansion: {
    invocations: 1,
    accepted: 1,
    rejectionRate: 0,
    rejectionRateDenominator: 1,
    rejectedProviderOutputTokensComplete: false,
    rejectedProviderOutputTokens: 0
  }
};
const chatUsageAcceptedOnly = vm.runInContext(
  'chatUsageSummary(globalThis.__chatUsageAcceptedOnly)', context
);
assert(
  chatUsageAcceptedOnly.includes('rejectTok:n/a'),
  `accepted-only expansion has no rejected token cost observation: ${chatUsageAcceptedOnly}`
);
assert(
  chatUsageMissingProviderTokens.includes('tokens:n/a') &&
    chatUsageMissingProviderTokens.includes('rejRate:n/a') &&
    chatUsageMissingProviderTokens.includes('rejectTok:n/a'),
  `missing provider usage must remain n/a instead of zero: ${chatUsageMissingProviderTokens}`
);
vm.runInContext("setDebugHeartbeatField('answer', 'OK', chatUsageSummary(globalThis.__chatUsageFixture));", context);
assert(
  heartbeatFields.get('answer').small.textContent === chatUsageObserved,
  `Answer card must use textContent for the count-only summary: ${heartbeatFields.get('answer').small.textContent}`
);
vm.runInContext(`renderDebugHeartbeat({
  debugAiMetrics: { status: 'OK', chatUsage: globalThis.__chatUsageFixture },
  answerOutput: { status: 'OK', answerMode: 'CHAT', evidenceDocs: 0 }
})`, context);
assert(
  heartbeatFields.get('answer').small.textContent.startsWith('usage(proc) calls:4/3 cap:2048 unknown:1 tokens:612') &&
    heartbeatFields.get('answer').small.textContent.includes('mode:CHAT') &&
    heartbeatFields.get('answer').small.textContent.length <= 180,
  `heartbeat rendering must keep the existing Answer card bounded: ${heartbeatFields.get('answer').small.textContent}`
);
vm.runInContext("clearAssistantModelFallbackDebugOnSuccessfulAnswer({ answerMode: 'chat', model: 'qwen3:8b' }, 'OK');", context);
assert(
  heartbeatFields.get('answer').small.textContent.startsWith('usage(proc) calls:4/3 cap:2048 unknown:1 tokens:612') &&
    heartbeatFields.get('answer').small.textContent.includes('mode:chat'),
  `final answer updates must retain the last count-only usage prefix: ${heartbeatFields.get('answer').small.textContent}`
);
vm.runInContext(
  "currentTurnRecoveryState = 'primary_failed_fallback_done'; " +
    "clearAssistantModelFallbackDebugOnSuccessfulAnswer({ answerMode: 'chat', model: 'qwen3:fixture-fallback' }, 'OK');",
  context
);
assert(
  heartbeatFields.get('model').small.textContent.includes('fallback:local_device_recovered') &&
    !heartbeatFields.get('model').small.textContent.includes('fallback:cleared') &&
    heartbeatFields.get('answer').small.textContent.includes('fallback:local_device'),
  `successful local-device recovery must not be rewritten as cleared: model=${heartbeatFields.get('model').small.textContent} answer=${heartbeatFields.get('answer').small.textContent}`
);
vm.runInContext(`renderDebugHeartbeat({
  debugAiMetrics: { status: 'OK', chatUsage: globalThis.__chatUsageFixture },
  answerOutput: { status: 'OK', answerMode: 'CHAT', evidenceDocs: 0 },
  modelRuntime: { route: 'unknown', deliveryState: 'unknown' }
})`, context);
assert(
  heartbeatFields.get('model').small.textContent.includes('fallback:local_device_recovered') &&
    heartbeatFields.get('answer').small.textContent.includes('fallback:local_device'),
  `background heartbeat must preserve current-turn recovery evidence: model=${heartbeatFields.get('model').small.textContent} answer=${heartbeatFields.get('answer').small.textContent}`
);
vm.runInContext("currentTurnRecoveryState = '';", context);
vm.runInContext("setDebugHeartbeatSummary('OK', 'live:OK wait:none');", context);
assert(
  elements.get('diagnosticsSummary').dataset.status === 'OK' &&
    elements.get('diagnosticsSummary').textContent === 'Core signals live',
  `Diagnostics summary should collapse a healthy heartbeat into bounded copy: ${elements.get('diagnosticsSummary').dataset.status}/${elements.get('diagnosticsSummary').textContent}`
);
assert(
  elements.get('diagnosticsSummaryControl').getAttribute('aria-label') === 'Diagnostics: Core signals live' &&
    elements.get('diagnosticsSummary').getAttribute('aria-label') === null,
  `healthy heartbeat fallback must name the native control once: control=${elements.get('diagnosticsSummaryControl').getAttribute('aria-label')} child=${elements.get('diagnosticsSummary').getAttribute('aria-label')}`
);
vm.runInContext("setDebugHeartbeatSummary('WARN', 'external:WARN ' + 'x'.repeat(240));", context);
assert(
  elements.get('diagnosticsSummary').dataset.status === 'WARN' &&
    elements.get('diagnosticsSummary').textContent === 'External proof waiting',
  `Diagnostics summary should expose a bounded warning title instead of raw detail: ${elements.get('diagnosticsSummary').dataset.status}/${elements.get('diagnosticsSummary').textContent}`
);
assert(
  elements.get('diagnosticsSummaryControl').getAttribute('aria-label') === 'Diagnostics: External proof waiting' &&
    elements.get('diagnosticsSummary').getAttribute('aria-label') === null &&
    !elements.get('diagnosticsSummaryControl').getAttribute('aria-label').includes('xxx'),
  `warning heartbeat fallback must name the native control with bounded copy only: control=${elements.get('diagnosticsSummaryControl').getAttribute('aria-label')} child=${elements.get('diagnosticsSummary').getAttribute('aria-label')}`
);
context.__primaryDiagnostic = {
  code: 'pending',
  status: 'WARN',
  title: 'Response pending',
  summaryText: 'Response pending · current turn · Stop available',
  action: { code: 'stop' },
  supportingFacts: []
};
vm.runInContext('renderPrimaryDiagnostic(__primaryDiagnostic)', context);
assert(
  elements.get('diagnosticsSummary').dataset.diagnosticCode === 'pending',
  'Diagnostics summary must expose one bounded primary code'
);
assert(
  elements.get('diagnosticsSummary').textContent === 'Response pending · current turn · Stop available',
  'Diagnostics summary must expose bounded what/fact/action copy'
);
assert(
  elements.get('diagnosticsSummaryControl').getAttribute('aria-label') ===
      'Diagnostics: Response pending · current turn · Stop available',
  `the native Diagnostics control must expose the same bounded accessible name: ${elements.get('diagnosticsSummaryControl').getAttribute('aria-label')}`
);
assert(
  elements.get('diagnosticsSummary').getAttribute('aria-label') === null,
  'the visible Diagnostics state child must not duplicate the native control name'
);
context.__attentionDiagnostic = attentionDiagnostic;
vm.runInContext('renderPrimaryDiagnostic(__attentionDiagnostic)', context);
assert(
  elements.get('diagnosticsSummaryControl').getAttribute('aria-label') === 'Diagnostics: Needs attention · current turn' &&
    !elements.get('diagnosticsSummaryControl').getAttribute('aria-label').includes('raw-provider-text'),
  `raw stream/provider context must not enter the primary accessible name: ${elements.get('diagnosticsSummaryControl').getAttribute('aria-label')}`
);
vm.runInContext('renderPrimaryDiagnostic(__primaryDiagnostic)', context);
vm.runInContext("setDebugHeartbeatSummary('OK', 'live:OK wait:none')", context);
assert(
  elements.get('diagnosticsSummary').textContent === 'Response pending · current turn · Stop available',
  'background heartbeat must not overwrite the current-answer primary summary'
);
assert(
  elements.get('diagnosticsSummaryControl').getAttribute('aria-label') ===
      'Diagnostics: Response pending · current turn · Stop available' &&
    elements.get('diagnosticsSummary').getAttribute('aria-label') === null,
  `background heartbeat must preserve the native primary name: control=${elements.get('diagnosticsSummaryControl').getAttribute('aria-label')} child=${elements.get('diagnosticsSummary').getAttribute('aria-label')}`
);
delete elements.get('diagnosticsSummary').dataset.diagnosticCode;
elements.get('diagnosticsSummary').dataset.status = 'WARN';
elements.get('diagnosticsSummary').textContent = 'External proof waiting';
pipelineStatusLink.click();
assert(
  pipelineStatusLink.lastClickDefaultPrevented === true &&
    context.window.location.assigned === null &&
    elements.get('traceStatus').textContent === 'admin_sign_in_required target:/admin/pipeline-status',
  `operator admin link should stay in chat while auth is disabled for test mode: prevented=${pipelineStatusLink.lastClickDefaultPrevented} assigned=${context.window.location.assigned} trace=${elements.get('traceStatus').textContent}`
);
assert(
  elements.get('chatWindow').getAttribute('aria-busy') === 'false' &&
    elements.get('sendBtn').getAttribute('aria-label') === 'Send message' &&
      elements.get('sendBtn').textContent === 'Send' &&
      elements.get('sendBtn').disabled === true &&
      elements.get('stopBtn').getAttribute('aria-label') === 'Stop response' &&
      elements.get('stopBtn').textContent === 'Stop' &&
      elements.get('stopBtn').hidden === true &&
      elements.get('stopBtn').style.display === 'none',
  `initial composer state should hide inactive stop control without enabling blank sends: busy=${elements.get('chatWindow').getAttribute('aria-busy')} send=${elements.get('sendBtn').getAttribute('aria-label')}/${elements.get('sendBtn').textContent}/${elements.get('sendBtn').disabled} stop=${elements.get('stopBtn').getAttribute('aria-label')}/${elements.get('stopBtn').textContent}/${elements.get('stopBtn').hidden}/${elements.get('stopBtn').style.display}`
);
assert(
  typeof elements.get('messageInput').listeners.keydown === 'function',
  'message input should register a keyboard submit handler'
);
assert(
  typeof quickPromptButton.listeners.click === 'function',
  'quick prompt buttons should register click handlers'
);
  elements.get('coreStatusRail').dataset.coreStatus = 'stopped';
  elements.get('streamStatus').textContent = 'server cancel';
  elements.get('traceStatus').textContent = 'cancelled';
  elements.get('searchModeSelect').value = 'FORCE_DEEP';
  elements.get('useRagToggle').checked = true;
  context.window.sessionStorage.setItem('chat.controlSettings', JSON.stringify({
    model: 'qwen3:30b',
    searchMode: 'FORCE_DEEP',
    useRag: true
  }));
  elements.get('searchModeSelect').listeners.change();
  elements.get('useRagToggle').listeners.change();
  quickPromptButton.click();
  assert(
    elements.get('messageInput').value === quickPromptButton.dataset.q &&
      elements.get('sendBtn').disabled === false,
  `quick prompt click should fill the composer draft and enable send: ${elements.get('messageInput').value}/${elements.get('sendBtn').disabled}`
);
assert(
  elements.get('coreStatusRail').dataset.coreStatus === 'idle' &&
    elements.get('streamStatus').textContent === 'draft' &&
    elements.get('traceStatus').textContent === 'ready',
  `quick prompt should clear stale cancelled rail for the next draft: core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent} trace=${elements.get('traceStatus').textContent}`
);
  assert(
    elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
      elements.get('useRagToggle').checked === true &&
      elements.get('searchStatus').textContent === 'DEEP' &&
    elements.get('ragStatus').textContent === 'ON',
  `quick prompt must leave explicit response settings unchanged: search=${elements.get('searchModeSelect').value}/${elements.get('searchStatus').textContent} rag=${elements.get('useRagToggle').checked}/${elements.get('ragStatus').textContent}`
);
elements.get('messageInput').value = '';
elements.get('messageInput').listeners.input();
assert(
  elements.get('sendBtn').disabled === true &&
    elements.get('coreStatusRail').dataset.coreStatus === 'idle' &&
    elements.get('streamStatus').textContent === 'idle' &&
    elements.get('traceStatus').textContent === 'ready',
  `clearing the composer should disable blank sends and clear the draft rail: send=${elements.get('sendBtn').disabled} core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent} trace=${elements.get('traceStatus').textContent}`
);
elements.get('coreStatusRail').dataset.coreStatus = 'stopped';
elements.get('streamStatus').textContent = 'local stop';
elements.get('traceStatus').textContent = 'cancelled';
elements.get('messageInput').value = 'manual follow-up after stop';
elements.get('messageInput').listeners.input();
assert(
  elements.get('sendBtn').disabled === false &&
    elements.get('coreStatusRail').dataset.coreStatus === 'idle' &&
    elements.get('streamStatus').textContent === 'draft' &&
    elements.get('traceStatus').textContent === 'ready',
  `manual draft input should clear stale cancelled rail: send=${elements.get('sendBtn').disabled} core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent} trace=${elements.get('traceStatus').textContent}`
);
elements.get('coreStatusRail').dataset.coreStatus = 'fallback';
elements.get('streamStatus').textContent = 'image plugin disabled';
elements.get('traceStatus').textContent = 'admin sign-in required';
quickPromptButton.click();
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'idle' &&
      elements.get('streamStatus').textContent === 'draft' &&
      elements.get('traceStatus').textContent === 'ready',
    `quick prompt should clear stale terminal error rail for the next draft: core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent} trace=${elements.get('traceStatus').textContent}`
  );
  const quickPromptPersistedControlSettings = JSON.parse(context.window.sessionStorage.getItem('chat.controlSettings') || '{}');
  assert(
    quickPromptPersistedControlSettings.searchMode === 'FORCE_DEEP' &&
      quickPromptPersistedControlSettings.useRag === true,
    `quick prompt must preserve persisted explicit response settings: ${JSON.stringify(quickPromptPersistedControlSettings)}`
  );
  context.window.sessionStorage.removeItem('chat.controlSettings');
  elements.get('messageInput').value = '';
  elements.get('coreStatusRail').dataset.coreStatus = 'idle';
  elements.get('streamStatus').textContent = 'idle';
  elements.get('traceStatus').textContent = 'new chat';
  quickPromptButton.click();
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'idle' &&
      elements.get('streamStatus').textContent === 'draft' &&
      elements.get('traceStatus').textContent === 'ready',
    `quick prompt after New chat should mark the filled prompt as a ready draft: core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent} trace=${elements.get('traceStatus').textContent}`
  );
  context.window.sessionStorage.removeItem('chat.controlSettings');
  elements.get('messageInput').value = '';
  elements.get('messageInput').listeners.input();
const streamedAria = vm.runInContext(`
  (() => {
    const bubble = document.createElement('div');
    appendTextWithBreaks(bubble, 'C2468\\n다음 질문에서 코드 이름만.');
    appendTextWithBreaks(bubble, '\\n확인 완료');
    return {
      aria: bubble.getAttribute('aria-label') || '',
      source: bubble.dataset.ariaText || ''
    };
  })()
`, context);
assert(
  streamedAria.aria.includes('Assistant: C2468\n다음 질문에서 코드 이름만.\n확인 완료') &&
    !streamedAria.aria.includes('C2468다음') &&
    streamedAria.source.includes('C2468\n다음 질문'),
  `streamed assistant aria label should preserve line breaks between token chunks: ${streamedAria.aria}`
);
fetchCalls.length = 0;
elements.get('messageInput').value = 'draft line';
let shiftEnterPrevented = false;
elements.get('messageInput').listeners.keydown({
  key: 'Enter',
  shiftKey: true,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  preventDefault() {
    shiftEnterPrevented = true;
  }
});
assert(!shiftEnterPrevented && fetchCalls.length === 0, 'Shift+Enter should keep multiline draft editing local');
let composingPrevented = false;
elements.get('messageInput').listeners.keydown({
  key: 'Enter',
  isComposing: true,
  shiftKey: false,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  preventDefault() {
    composingPrevented = true;
  }
});
assert(!composingPrevented && fetchCalls.length === 0, 'IME composition Enter should not submit the chat draft');
fetchCalls.length = 0;
elements.get('messageInput').value = '   ';
let blankEnterPrevented = false;
elements.get('messageInput').listeners.keydown({
  key: 'Enter',
  shiftKey: false,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  preventDefault() {
    blankEnterPrevented = true;
  }
});
assert(
  blankEnterPrevented &&
    fetchCalls.length === 0 &&
    elements.get('messageInput').value === '',
  `blank Enter should be swallowed locally and clear whitespace draft: prevented=${blankEnterPrevented} calls=${fetchCalls.length} value=${JSON.stringify(elements.get('messageInput').value)}`
);
fetchCalls.length = 0;
elements.get('messageInput').value = ' \t ';
vm.runInContext('sendMessage({ preventDefault() {} })', context);
assert(
  fetchCalls.length === 0 &&
    elements.get('messageInput').value === '',
  `blank form submit should not call the API and should clear whitespace draft: calls=${fetchCalls.length} value=${JSON.stringify(elements.get('messageInput').value)}`
);
assert(
  template.includes('id="ragStatus"') &&
    template.includes('data-orch-field="rag"'),
  'Chat UI should expose a RAG status rail value connected to the RAG toggle'
);
assert(
  /<script\s+defer\s+src="\/js\/chat\.js\?v=chat-ui-[^"]+"/.test(template),
  'Chat UI should version the chat.js script URL so browser proof reloads the current debug UI asset'
);
assert(
  [
    '<strong>Route</strong> <span id="streamStatus"',
    '<strong>Model</strong> <span id="modelStatus"',
    '<strong>Context</strong> <span id="searchStatus"',
    '<strong>RAG</strong> <span id="ragStatus"',
    '<strong>Trace</strong> <span id="traceStatus"',
    '<strong>Quality</strong> <span id="qualityStatus"',
    '<strong>Health</strong> <span id="healthStatus"'
  ].every((snippet) => template.includes(snippet)),
  'Status rail labels and values should keep trace ids separate from quality signals'
);
assert(
  elements.get('modelStatus').textContent === 'local' &&
    currentModelBadge.textContent === 'local' &&
    elements.get('searchStatus').textContent === 'DEEP' &&
    elements.get('ragStatus').textContent === 'ON',
  `quick prompt must retain the user-selected control rail: model=${elements.get('modelStatus').textContent} badge=${currentModelBadge.textContent} search=${elements.get('searchStatus').textContent} rag=${elements.get('ragStatus').textContent}`
);
assert(
  elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
    elements.get('searchModeSelect').dataset.smokeProofDefault === 'local-only' &&
    context.window.sessionStorage.getItem('chat.controlSettings') === null,
  `codexSmoke should retain its default marker after a user-selected control override: value=${elements.get('searchModeSelect').value} marker=${elements.get('searchModeSelect').dataset.smokeProofDefault} stored=${context.window.sessionStorage.getItem('chat.controlSettings')}`
);
elements.get('searchModeSelect').value = 'AUTO';
elements.get('useRagToggle').checked = false;
vm.runInContext('syncControlStatus({ persist: false });', context);
assert(
  elements.get('responseSettingsSummary').textContent === 'local | Search AUTO | RAG OFF',
  `response settings summary should reflect the fixture controls: ${elements.get('responseSettingsSummary').textContent}`
);
elements.get('modelSelect').value = 'qwen3:30b';
elements.get('searchModeSelect').value = 'FORCE_DEEP';
elements.get('useRagToggle').checked = true;
elements.get('modelSelect').listeners.change();
elements.get('searchModeSelect').listeners.change();
elements.get('useRagToggle').listeners.change();
assert(
  elements.get('modelStatus').textContent === 'qwen3:30b' &&
    currentModelBadge.textContent === 'qwen3:30b' &&
    currentModelBadge.getAttribute('aria-label') === 'Current model: qwen3:30b' &&
    elements.get('searchStatus').textContent === 'DEEP' &&
    elements.get('ragStatus').textContent === 'ON' &&
    statusPills.rag.getAttribute('aria-label') === 'RAG: ON',
  `control changes should update status rail and header badge before send: model=${elements.get('modelStatus').textContent} badge=${currentModelBadge.textContent} search=${elements.get('searchStatus').textContent} rag=${elements.get('ragStatus').textContent} aria=${statusPills.rag.getAttribute('aria-label')}`
);
const persistedControlSettings = JSON.parse(context.window.sessionStorage.getItem('chat.controlSettings') || '{}');
assert(
  persistedControlSettings.model === 'qwen3:30b' &&
    persistedControlSettings.searchMode === 'FORCE_DEEP' &&
    persistedControlSettings.useRag === true,
  `control changes should persist before send for reload continuity: ${JSON.stringify(persistedControlSettings)}`
);
elements.get('modelSelect').value = 'local';
elements.get('searchModeSelect').value = 'OFF';
elements.get('useRagToggle').checked = false;
vm.runInContext('restoreStoredControlSettings();', context);
assert(
  elements.get('modelSelect').value === 'qwen3:30b' &&
    currentModelBadge.textContent === 'qwen3:30b' &&
    elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
    elements.get('useRagToggle').checked === true &&
    elements.get('searchStatus').textContent === 'DEEP' &&
    elements.get('ragStatus').textContent === 'ON',
  `stored control settings should restore before next send: model=${elements.get('modelSelect').value} badge=${currentModelBadge.textContent} search=${elements.get('searchModeSelect').value} rag=${elements.get('useRagToggle').checked} rail=${elements.get('searchStatus').textContent}/${elements.get('ragStatus').textContent}`
);

context.__syntheticSensitiveDetail = [
  'token=' + 'sk-' + 'A'.repeat(24),
  ['Authorization', ':'].join('') + ' Bearer ' + 'B'.repeat(24),
  'client_secret=' + 'C'.repeat(24)
].join(' ');
const redactedDetail = vm.runInContext('safeDebugCockpitDetail(globalThis.__syntheticSensitiveDetail)', context);
assert(!redactedDetail.includes('sk-'), `debug detail leaked OpenAI-style token prefix: ${redactedDetail}`);
assert(!redactedDetail.includes('Bearer'), `debug detail leaked auth header prefix: ${redactedDetail}`);
assert(!redactedDetail.includes('client_secret=' + 'C'.repeat(8)), `debug detail leaked client secret value: ${redactedDetail}`);
assert(redactedDetail.includes('[redacted]') || redactedDetail.includes('[secret]'), `debug detail did not mark redaction: ${redactedDetail}`);

heartbeatSummary.small.textContent = 'static-heartbeat';
vm.runInContext("updateOrchestrationSignalBar({ streamStatus: 'connecting', streamContext: 'OFF', model: 'local' });", context);

assert(
  heartbeatSummary.small.textContent.includes('stream:connecting'),
  `visible heartbeat summary did not reflect live stream state: ${heartbeatSummary.small.textContent}`
);
assert(
  heartbeatSummary.getAttribute('aria-label')?.includes('Attention needed: WARN - stream:connecting context:OFF'),
  `heartbeat summary aria should expose live stream status/detail: ${heartbeatSummary.getAttribute('aria-label')}`
);
assert(
  heartbeatFields.get('liveStream').small.textContent.includes('stream:connecting'),
  'hidden liveStream card did not receive stream state'
);
assert(
  elements.get('streamStatus').textContent === 'OFF',
  `status rail should retain stream context OFF, got: ${elements.get('streamStatus').textContent}`
);
assert(
  statusPills.stream.getAttribute('aria-label') === 'Stream: OFF' &&
    statusPills.model.getAttribute('aria-label') === 'Model: local',
  `status rail should expose label/value separators: ${statusPills.stream.getAttribute('aria-label')} / ${statusPills.model.getAttribute('aria-label')}`
);
const transformerHeartbeatAssistant = fakeElement('transformer-heartbeat-assistant');
context.__transformerHeartbeatAssistant = transformerHeartbeatAssistant;
vm.runInContext("updateOrchestrationSignalBar({ streamStatus: 'connecting', streamContext: 'OFF', model: 'qwen3:8b' });", context);
vm.runInContext("renderChatEvent({ type: 'transformer', status: 'running', transformerBlocks: [] }, globalThis.__transformerHeartbeatAssistant, 'transformer');", context);
assert(
  elements.get('streamStatus').textContent !== '-' &&
    elements.get('modelStatus').textContent === 'qwen3:8b',
  `transformer heartbeat without model detail should not overwrite route/model rails with placeholders: route=${elements.get('streamStatus').textContent} model=${elements.get('modelStatus').textContent}`
);
elements.get('modelSelect').value = 'qwen3:8b';
vm.runInContext('syncControlStatus({ persist: false });', context);
vm.runInContext("updateOrchestrationSignalBar({ streamStatus: 'UI-MODE:LOCAL_EVIDENCE', model: 'qwen3:8b', answerMode: 'UI-MODE:LOCAL_EVIDENCE' });", context);
assert(
  elements.get('streamStatus').textContent === 'ui mode: local evidence' &&
    elements.get('modelStatus').textContent === 'qwen3:8b' &&
    statusPills.model.getAttribute('aria-label') === 'Model: qwen3:8b',
  `local UI evidence mode should not overwrite the model rail: stream=${elements.get('streamStatus').textContent} model=${elements.get('modelStatus').textContent} aria=${statusPills.model.getAttribute('aria-label')}`
);
vm.runInContext("updateOrchestrationSignalBar({ streamStatus: 'UI-MODE:LOCAL_EVIDENCE', model: 'ui-mode:local:evidence', answerMode: 'UI-MODE:LOCAL_EVIDENCE' });", context);
assert(
  elements.get('modelStatus').textContent === 'qwen3:8b' &&
    statusPills.model.getAttribute('aria-label') === 'Model: qwen3:8b',
  `local UI evidence payload model marker should fall back to selected model: model=${elements.get('modelStatus').textContent} aria=${statusPills.model.getAttribute('aria-label')}`
);

heartbeatSummary.small.textContent = 'stale-summary';
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'supabase=read_only browser=local_ui_proof' }
    ],
    externalEvidence: [
      {
        service: 'supabase',
        status: 'WARN',
        evidenceScope: 'read-only',
        evidenceNeeded: 'supabase_project_scope_or_auth_unverified',
        nextAction: 'run_readonly_supabase_context_probe',
        projectRefEnvStatus: 'missing',
        authEnvStatus: 'missing',
        mcpConfigStatus: 'configured',
        probeFileStatus: 'missing'
      },
      {
        service: 'browser',
        status: 'WARN',
        evidenceScope: 'local-ui-proof',
        evidenceNeeded: 'browser_ui_smoke_not_persisted_to_runtime',
        nextAction: 'run_browser_ui_smoke'
      },
      {
        service: 'computer-use',
        status: 'OK',
        evidenceScope: 'gui-supporting-only',
        evidenceNeeded: null,
        nextAction: 'computer_use_supporting_evidence_current',
        smokeFileStatus: 'present',
        reachable: true,
        countOnly: true,
        appCount: 40,
        targetableWindowCount: 16,
        stale: false
      }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'promptBuilder', status: 'OK', enabled: true },
      { name: 'queryTransformer', status: 'OK', enabled: true },
      { name: 'webSearch', status: 'OK', enabled: true },
      { name: 'webFailSoftAspect', status: 'DISABLED', enabled: false, disabledReason: 'web_failsoft_aspect_missing' },
      { name: 'hybridEmptyFallbackAspect', status: 'DISABLED', enabled: false, disabledReason: 'hybrid_empty_fallback_aspect_missing' },
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true },
      { name: 'cancelShieldPostProcessor', status: 'DISABLED', enabled: false, disabledReason: 'cancel_shield_post_processor_missing' }
    ]
  });
`, context);

assert(
  heartbeatSummary.small.textContent.includes('live:OK'),
  `core-live summary should stay OK when only external proof is pending: ${heartbeatSummary.small.textContent}`
);
assert(
  heartbeatSummary.strong.textContent !== 'Core wait / UI wait / External wait',
  `heartbeat summary title should not stay on the static loading copy: ${heartbeatSummary.strong.textContent}`
);
assert(
  heartbeatSummary.small.textContent.includes('wait:none'),
  `core-live summary should show no model wait when no wait code is present: ${heartbeatSummary.small.textContent}`
);
assert(
  heartbeatSummary.getAttribute('aria-label')?.includes('Core live: OK - live:OK wait:none timeout:OK cancel:OK next:none'),
  `heartbeat summary aria should expose final live/wait state: ${heartbeatSummary.getAttribute('aria-label')}`
);
assert(
  heartbeatFields.get('liveStream').small.textContent.includes('live:OK'),
  `liveStream card should mirror heartbeat live state: ${heartbeatFields.get('liveStream').small.textContent}`
);
assert(
  heartbeatFields.get('modelWait').small.textContent.includes('wait:none'),
  `modelWait card should mirror no-wait heartbeat state: ${heartbeatFields.get('modelWait').small.textContent}`
);
assert(
  matrixCells.get('model-answer').small.textContent.includes('answer:OK') &&
    matrixCells.get('model-answer').small.textContent.includes('live:OK'),
  `model-answer matrix cell should preserve both answer and live state: ${matrixCells.get('model-answer').small.textContent}`
);
assert(
  matrixCells.get('model-answer').getAttribute('aria-label')?.includes('Model/Answer: OK - model:OK answer:OK live:OK') &&
    matrixCells.get('external-proof').getAttribute('aria-label')?.includes('External Proof: OK - supporting supabase:WARN browser:OK computer:OK'),
  `debug matrix cells should expose separated label/status/detail aria: ${matrixCells.get('model-answer').getAttribute('aria-label')} / ${matrixCells.get('external-proof').getAttribute('aria-label')}`
);
elements.get('streamStatus').textContent = 'idle';
elements.get('modelStatus').textContent = 'qwen3:8b';
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'OK', detail: 'proof=ready' }
    ],
    externalEvidence: [
      { service: 'goal-next-auto', status: 'OK', localReady: true, completionReady: true, sourceHealthExit: 0, completionAuditExit: 0, topActions: [] },
      { service: 'patchdrop', status: 'OK', activeTopLevelPatchCount: 0, nestedProducerPatchCount: 0, reportOnlyPendingCount: 0 },
      { service: 'browser', status: 'OK', evidenceScope: 'local-ui-proof', smokeFileStatus: 'present', stale: false, domReady: true, targetContentVisible: true, serverRuntimeProof: true },
      { service: 'computer-use', status: 'OK', evidenceScope: 'gui-supporting-only', smokeFileStatus: 'present', reachable: true, countOnly: true, appCount: 40, targetableWindowCount: 16, stale: false },
      { service: 'supabase', status: 'OK', evidenceScope: 'read-only', nextAction: 'none' }
    ],
    modelRuntime: { status: 'OK' },
    webProviders: [{ name: 'webSearch', status: 'OK', hasKey: true }],
    providerRuntime: { status: 'OK', awaitTimeoutCount: 0, cancelSuppressedCount: 0 },
    failSoftLadder: { status: 'WARN', reason: 'trace_not_observed', poolSafeEmpty: false, outCount: 0, tracePoolSize: 0 },
    traceSnapshotHealth: { status: 'OK', available: true },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  heartbeatFields.get('search').dataset.status === 'OK' &&
    heartbeatFields.get('ladder').dataset.status === 'OK' &&
    heartbeatFields.get('search').small.textContent.includes('idle:no_query') &&
    heartbeatFields.get('ladder').small.textContent.includes('idle:no_query') &&
    matrixCells.get('search-trace').dataset.status === 'ok' &&
    flowSteps.get('search').dataset.status === 'ok',
  `idle chat heartbeat should not show stale retrieval warnings before a query: search=${heartbeatFields.get('search').getAttribute('aria-label')} ladder=${heartbeatFields.get('ladder').getAttribute('aria-label')} matrix=${matrixCells.get('search-trace').getAttribute('aria-label')} flow=${flowSteps.get('search').getAttribute('aria-label')}`
);
elements.get('streamStatus').textContent = 'idle';
elements.get('modelStatus').textContent = 'qwen3:8b';
vm.runInContext(`
  renderDebugHeartbeat({
    streamStatus: 'FALLBACK_EVIDENCE',
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'OK', detail: 'proof=ready' }
    ],
    externalEvidence: [
      { service: 'goal-next-auto', status: 'OK', localReady: true, completionReady: true, sourceHealthExit: 0, completionAuditExit: 0, topActions: [] },
      { service: 'patchdrop', status: 'OK', activeTopLevelPatchCount: 0, nestedProducerPatchCount: 0, reportOnlyPendingCount: 0 },
      { service: 'browser', status: 'OK', evidenceScope: 'local-ui-proof', smokeFileStatus: 'present', stale: false, domReady: true, targetContentVisible: true, serverRuntimeProof: true },
      { service: 'computer-use', status: 'OK', evidenceScope: 'gui-supporting-only', smokeFileStatus: 'present', reachable: true, countOnly: true, appCount: 40, targetableWindowCount: 16, stale: false },
      { service: 'supabase', status: 'OK', evidenceScope: 'read-only', nextAction: 'none' }
    ],
    modelRuntime: { status: 'OK' },
    webProviders: [{ name: 'webSearch', status: 'OK', hasKey: true }],
    providerRuntime: { status: 'OK', awaitTimeoutCount: 0, cancelSuppressedCount: 0 },
    failSoftLadder: { status: 'WARN', reason: 'trace_not_observed', poolSafeEmpty: false, outCount: 0, tracePoolSize: 0 },
    traceSnapshotHealth: { status: 'OK', available: true },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  heartbeatFields.get('search').dataset.status === 'WARN' &&
    heartbeatFields.get('ladder').dataset.status === 'WARN' &&
    !heartbeatFields.get('search').small.textContent.includes('idle:no_query') &&
    !heartbeatFields.get('ladder').small.textContent.includes('idle:no_query'),
  `fallback-evidence heartbeat should not be mislabeled as idle no-query after a real turn: search=${heartbeatFields.get('search').getAttribute('aria-label')} ladder=${heartbeatFields.get('ladder').getAttribute('aria-label')}`
);
heartbeatFields.get('search').dataset.status = 'OK';
heartbeatFields.get('search').small.textContent = 'enabled:1/1 timeouts:0 cancels:0 cache:0 vector:no idle:no_query';
heartbeatFields.get('ladder').dataset.status = 'OK';
heartbeatFields.get('ladder').small.textContent = 'out:0 tracePool:0 rescue:no trigger:none idle:no_query';
vm.runInContext(`
  renderLiveDebugHeartbeat({
    streamStatus: 'ui-mode:local:evidence',
    streamContext: 'none'
  });
`, context);
assert(
  heartbeatFields.get('search').dataset.status === 'OK' &&
    heartbeatFields.get('ladder').dataset.status === 'OK' &&
    heartbeatFields.get('search').small.textContent.includes('answer:ui-mode:local:evidence') &&
    heartbeatFields.get('ladder').small.textContent.includes('answer:ui-mode:local:evidence') &&
    !heartbeatFields.get('search').small.textContent.includes('idle:no_query') &&
    !heartbeatFields.get('ladder').small.textContent.includes('idle:no_query'),
  `successful live evidence final should clear stale idle retrieval warmup details: search=${heartbeatFields.get('search').getAttribute('aria-label')} ladder=${heartbeatFields.get('ladder').getAttribute('aria-label')}`
);
const visibleProviderEvidenceTarget = fakeElement('visible-provider-evidence-target');
vm.runInContext(
  `
  renderEvidenceRail([
    { title: 'OpenAI Responses API', url: 'https://openai.com/index/responses-api', source: 'openai.com' },
    { title: 'Supabase MCP read-only', url: 'https://supabase.com/docs', source: 'supabase.com' }
  ], globalThis.__visibleProviderEvidenceTarget, { answerMode: 'rag', model: 'qwen3:8b' });
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'OK', detail: 'proof=ready' }
    ],
    externalEvidence: [
      { service: 'goal-next-auto', status: 'OK', localReady: true, completionReady: true, sourceHealthExit: 0, completionAuditExit: 0, topActions: [] },
      { service: 'patchdrop', status: 'OK', activeTopLevelPatchCount: 0, nestedProducerPatchCount: 0, reportOnlyPendingCount: 0 },
      { service: 'browser', status: 'OK', evidenceScope: 'local-ui-proof', smokeFileStatus: 'present', stale: false, domReady: true, targetContentVisible: true, serverRuntimeProof: true },
      { service: 'computer-use', status: 'OK', evidenceScope: 'gui-supporting-only', smokeFileStatus: 'present', reachable: true, countOnly: true, appCount: 40, targetableWindowCount: 16, stale: false },
      { service: 'supabase', status: 'OK', evidenceScope: 'read-only', nextAction: 'none' }
    ],
    modelRuntime: { status: 'OK' },
    webProviders: [
      { provider: 'hybrid', status: 'OK', hasKey: true },
      { provider: 'supplemental-multi-search', status: 'OK', optional: true, providerCount: 0 }
    ],
    providerRuntime: { status: 'OK', awaitTimeoutCount: 0, cancelSuppressedCount: 0 },
    failSoftLadder: { status: 'WARN', reason: 'trace_not_observed', poolSafeEmpty: false, outCount: 0, tracePoolSize: 0 },
    traceSnapshotHealth: { status: 'OK', available: true },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
  `,
  Object.assign(context, { __visibleProviderEvidenceTarget: visibleProviderEvidenceTarget })
);
assert(
  heartbeatFields.get('search').dataset.status === 'OK' &&
    heartbeatFields.get('ladder').dataset.status === 'OK' &&
    heartbeatFields.get('answer').dataset.status === 'OK' &&
    heartbeatFields.get('search').small.textContent.includes('answer:rag evidence:2 source:visible_turn') &&
    heartbeatFields.get('ladder').small.textContent.includes('answer:rag evidence:2 source:visible_turn') &&
    heartbeatFields.get('answer').small.textContent.includes('mode:rag') &&
    heartbeatFields.get('answer').small.textContent.includes('docs:2') &&
    matrixCells.get('search-trace').dataset.status === 'ok' &&
    flowSteps.get('search').dataset.status === 'ok',
  `visible provider evidence should override stale global out:0 heartbeat: search=${heartbeatFields.get('search').getAttribute('aria-label')} ladder=${heartbeatFields.get('ladder').getAttribute('aria-label')} answer=${heartbeatFields.get('answer').getAttribute('aria-label')} matrix=${matrixCells.get('search-trace').getAttribute('aria-label')} flow=${flowSteps.get('search').getAttribute('aria-label')}`
);
elements.get('streamStatus').textContent = 'direct literal';
elements.get('modelStatus').textContent = 'direct literal';
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'OK', detail: 'proof=ready' }
    ],
    externalEvidence: [
      { service: 'goal-next-auto', status: 'OK', localReady: true, completionReady: true, sourceHealthExit: 0, completionAuditExit: 0, topActions: [] },
      { service: 'patchdrop', status: 'OK', activeTopLevelPatchCount: 0, nestedProducerPatchCount: 0, reportOnlyPendingCount: 0 },
      { service: 'browser', status: 'OK', evidenceScope: 'local-ui-proof', smokeFileStatus: 'present', stale: false, domReady: true, targetContentVisible: true, serverRuntimeProof: true },
      { service: 'computer-use', status: 'OK', evidenceScope: 'gui-supporting-only', smokeFileStatus: 'present', reachable: true, countOnly: true, appCount: 40, targetableWindowCount: 16, stale: false },
      { service: 'supabase', status: 'OK', evidenceScope: 'read-only', nextAction: 'none' }
    ],
    modelRuntime: { status: 'OK' },
    webProviders: [{ name: 'webSearch', status: 'OK', hasKey: true }],
    providerRuntime: { status: 'OK', awaitTimeoutCount: 0, cancelSuppressedCount: 0 },
    failSoftLadder: { status: 'WARN', poolSafeEmpty: true, outCount: 0, tracePoolSize: 0 },
    traceSnapshotHealth: { status: 'OK', available: true },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  heartbeatFields.get('search').dataset.status === 'OK' &&
    heartbeatFields.get('ladder').dataset.status === 'OK' &&
    heartbeatFields.get('search').small.textContent.includes('bypassed:DIRECT_LITERAL') &&
    heartbeatFields.get('ladder').small.textContent.includes('bypassed:DIRECT_LITERAL') &&
    matrixCells.get('search-trace').dataset.status === 'ok' &&
    flowSteps.get('search').dataset.status === 'ok',
  `direct literal completion should bypass retrieval warnings in UI: search=${heartbeatFields.get('search').getAttribute('aria-label')} ladder=${heartbeatFields.get('ladder').getAttribute('aria-label')} matrix=${matrixCells.get('search-trace').getAttribute('aria-label')} flow=${flowSteps.get('search').getAttribute('aria-label')}`
);
vm.runInContext(`
  setDebugHeartbeatField('search', 'WARN', 'enabled:1/1 timeouts:0 cancels:0 cache:0 vector:no');
  setDebugHeartbeatField('ladder', 'WARN', 'out:0 tracePool:0 rescue:no trigger:none');
  setDebugMatrixCell('search-trace', 'WARN', 'Search/Trace', 'search:WARN trace:OK');
  setDebugFlowStep('search', 'WARN', 'Search', 'WARN', 'search:WARN trace:OK');
  renderLiveDebugHeartbeat({
    streamStatus: 'DIRECT_LITERAL',
    streamContext: 'OFF',
    answerMode: 'DIRECT_LITERAL',
    model: 'qwen3:8b'
  });
`, context);
assert(
  heartbeatFields.get('search').dataset.status === 'OK' &&
    heartbeatFields.get('ladder').dataset.status === 'OK' &&
    heartbeatFields.get('search').small.textContent.includes('bypassed:DIRECT_LITERAL') &&
    heartbeatFields.get('ladder').small.textContent.includes('bypassed:DIRECT_LITERAL') &&
    matrixCells.get('search-trace').dataset.status === 'ok' &&
    flowSteps.get('search').dataset.status === 'ok',
  `direct literal live final should clear stale retrieval warnings: search=${heartbeatFields.get('search').getAttribute('aria-label')} ladder=${heartbeatFields.get('ladder').getAttribute('aria-label')} matrix=${matrixCells.get('search-trace').getAttribute('aria-label')} flow=${flowSteps.get('search').getAttribute('aria-label')}`
);
const localSafeFallbackAssistant = fakeElement('local-safe-fallback-assistant');
vm.runInContext(
  "setMessageContent(globalThis.__localSafeFallbackAssistant, 'assistant', '기본 모델 응답이 지금 안정적으로 생성되지 않아 로컬 안전 응답으로 먼저 안내드립니다.');",
  Object.assign(context, { __localSafeFallbackAssistant: localSafeFallbackAssistant })
);
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'supporting evidence pending' }
    ],
    externalEvidence: [
      { service: 'goal-next-auto', status: 'WARN', decision: 'evidence_needed', nextAction: 'none', firstAction: 'none', sourceHealthExit: 0, completionAuditExit: 0, localReady: true, completionReady: false, externalInputGateStatus: 'local_or_unknown', localPatchJustified: true, topActions: [] },
      { service: 'patchdrop', status: 'OK', activeTopLevelPatchCount: 0, nestedProducerPatchCount: 0, reportOnlyPendingCount: 0 },
      { service: 'browser', status: 'OK', evidenceScope: 'local-ui-proof', evidenceNeeded: 'browser_ui_smoke_not_persisted_to_runtime', nextAction: 'browser_ui_smoke_current', smokeFileStatus: 'present', stale: false },
      { service: 'computer-use', status: 'OK', evidenceScope: 'gui-supporting-only', nextAction: 'computer_use_supporting_evidence_current', smokeFileStatus: 'present', reachable: true, countOnly: true, appCount: 40, targetableWindowCount: 16, stale: false },
      { service: 'supabase', status: 'WARN', evidenceScope: 'read-only', evidenceNeeded: 'supabase_project_scope_or_auth_unverified', nextAction: 'run_readonly_supabase_context_probe', projectRefEnvStatus: 'missing', authEnvStatus: 'missing', mcpConfigStatus: 'configured', probeFileStatus: 'missing' }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'webSearch', status: 'OK', enabled: true },
      { name: 'webFailSoftAspect', status: 'DISABLED', enabled: false, disabledReason: 'web_failsoft_aspect_missing' },
      { name: 'hybridEmptyFallbackAspect', status: 'DISABLED', enabled: false, disabledReason: 'hybrid_empty_fallback_aspect_missing' },
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true },
      { name: 'cancelShieldPostProcessor', status: 'DISABLED', enabled: false, disabledReason: 'cancel_shield_post_processor_missing' }
    ]
  });
`, context);
assert(
  heartbeatFields.get('model').dataset.status === 'WARN' &&
    heartbeatFields.get('model').getAttribute('aria-label')?.includes('model_unavailable') &&
    heartbeatFields.get('answer').dataset.status === 'WARN' &&
    matrixCells.get('model-answer').dataset.status === 'warn' &&
    matrixCells.get('model-answer').getAttribute('aria-label')?.includes('model:WARN answer:WARN'),
  `assistant local-safe fallback should mark model/answer debug as WARN: model=${heartbeatFields.get('model').getAttribute('aria-label')} answer=${heartbeatFields.get('answer').getAttribute('aria-label')} matrix=${matrixCells.get('model-answer').getAttribute('aria-label')}`
);
assert(
  proofCells.get('browser').getAttribute('aria-label')?.includes('Browser: OK - scope:local-ui-proof') &&
    !proofCells.get('browser').getAttribute('aria-label')?.includes('OK - OK') &&
    proofCells.get('supabase').getAttribute('aria-label')?.includes('Supabase: WARN - scope:read-only') &&
    !proofCells.get('supabase').getAttribute('aria-label')?.includes('WARN - WARN') &&
    !flowSteps.get('external').getAttribute('aria-label')?.includes('WARN - WARN') &&
    !missionAxes.get('focus').getAttribute('aria-label')?.includes('WARN - WARN'),
  `debug proof/flow/mission aria should not duplicate status values: ${proofCells.get('browser').getAttribute('aria-label')} / ${proofCells.get('supabase').getAttribute('aria-label')} / ${flowSteps.get('external').getAttribute('aria-label')} / ${missionAxes.get('focus').getAttribute('aria-label')}`
);
assert(
  heartbeatFields.get('lanes').small.textContent.includes('webFailSoftAspect') &&
    heartbeatFields.get('lanes').small.textContent.includes('hybridEmptyFallbackAspect') &&
    heartbeatFields.get('lanes').small.textContent.includes('+1'),
  `lanes card should identify missing layers, not only the first reason: ${heartbeatFields.get('lanes').small.textContent}`
);
assert(
  heartbeatFields.get('supabase').small.textContent.includes('scope:read-only') &&
    heartbeatFields.get('supabase').small.textContent.includes('needed:project/auth unverified') &&
    heartbeatFields.get('supabase').small.textContent.includes('projectScope:missing') &&
    heartbeatFields.get('supabase').small.textContent.includes('auth:missing') &&
    heartbeatFields.get('supabase').small.textContent.includes('mcp:configured') &&
    heartbeatFields.get('supabase').small.textContent.includes('probe:missing') &&
    heartbeatFields.get('supabase').small.textContent.includes('next:readonly probe') &&
    !heartbeatFields.get('supabase').small.textContent.includes('supabase_project_scope_or_auth_unverified') &&
    heartbeatFields.get('supabase').title.includes('supabase_project_scope_or_auth_unverified') &&
    heartbeatFields.get('supabase').title.includes('run_readonly_supabase_context_probe'),
  `supabase card should show evidence scope, blocker, and next action: ${heartbeatFields.get('supabase').small.textContent}`
);
assert(
  heartbeatFields.get('browser').dataset.status === 'OK' &&
    heartbeatFields.get('browser').small.textContent.includes('scope:local-ui-proof') &&
    heartbeatFields.get('browser').small.textContent.includes('proof:dom ready') &&
    heartbeatFields.get('browser').small.textContent.includes('server:runtime proof pending') &&
    heartbeatFields.get('browser').title.includes('browser_ui_smoke_not_persisted_to_runtime'),
  `browser card should show local browser proof while preserving server evidence limitation: ${heartbeatFields.get('browser').small.textContent}`
);
assert(
  heartbeatFields.get('computer').small.textContent.includes('scope:gui-supporting-only') &&
    heartbeatFields.get('computer').small.textContent.includes('needed:none') &&
    heartbeatFields.get('computer').small.textContent.includes('smoke:present') &&
    heartbeatFields.get('computer').small.textContent.includes('apps:40') &&
    heartbeatFields.get('computer').small.textContent.includes('windows:16') &&
    heartbeatFields.get('computer').small.textContent.includes('count-only:true') &&
    heartbeatFields.get('computer').small.textContent.includes('next:proof current') &&
    heartbeatFields.get('computer').title.includes('computer_use_supporting_evidence_current'),
  `computer card should show evidence scope, blocker, and next action: ${heartbeatFields.get('computer').small.textContent}`
);
assert(
  heartbeatFields.get('computer').textContent.includes('Computer scope:gui-supporting-only'),
  `heartbeat card text should keep a readable label/detail separator: ${heartbeatFields.get('computer').textContent}`
);
assert(
  heartbeatFields.get('supabase').getAttribute('aria-label')?.includes('Supabase: WARN - scope:read-only') &&
    heartbeatFields.get('supabase').getAttribute('aria-label')?.includes('needed:project/auth unverified') &&
    !heartbeatFields.get('supabase').getAttribute('aria-label')?.includes('WARN - WARN'),
  `supabase heartbeat aria should summarize status and compact evidence: ${heartbeatFields.get('supabase').getAttribute('aria-label')}`
);
assert(
  heartbeatFields.get('browser').getAttribute('aria-label')?.includes('Browser: OK - scope:local-ui-proof') &&
    !heartbeatFields.get('browser').getAttribute('aria-label')?.includes('OK - OK'),
  `browser heartbeat aria should summarize browser proof without status duplication: ${heartbeatFields.get('browser').getAttribute('aria-label')}`
);
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'OK', detail: 'proof=ready' }
    ],
    externalEvidence: [
      {
        service: 'supabase',
        status: 'OK',
        evidenceScope: 'read-only',
        evidenceNeeded: null,
        nextAction: 'none'
      },
      {
        service: 'browser',
        status: 'OK',
        evidenceScope: 'local-ui-proof',
        evidenceNeeded: null,
        nextAction: 'none',
        smokeFileStatus: 'present',
        stale: false,
        domReady: true,
        targetContentVisible: true,
        serverRuntimeProof: true
      },
      {
        service: 'computer-use',
        status: 'OK',
        evidenceScope: 'gui-supporting-only',
        evidenceNeeded: null,
        nextAction: 'none',
        smokeFileStatus: 'present',
        reachable: true,
        countOnly: true,
        appCount: 40,
        targetableWindowCount: 16,
        stale: false
      }
    ],
    modelRuntime: {
      status: 'WARN',
      localLlmOperatorAction: {
        triggered: true,
        triggerReason: 'threshold_exceeded',
        failureClass: 'model_blank',
        nextAction: 'inspect_ollama_runtime_capacity',
        actionScore: 100,
        scoreDelta: 85,
        negativeSignalCount: 4,
        upstreamStatus: 500,
        upstreamFailureClass: 'ollama_upstream_5xx',
        upstreamNextAction: 'inspect_ollama_runtime_capacity'
      }
    },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  matrixCells.get('model-answer').getAttribute('aria-label')?.includes('Model/Answer: WARN - model:WARN answer:OK live:WARN') &&
    matrixCells.get('model-answer').getAttribute('aria-label')?.includes('llm:model_blank') &&
    matrixCells.get('model-answer').getAttribute('aria-label')?.includes('next:inspect_ollama_runtime_capacity') &&
    matrixCells.get('model-answer').getAttribute('aria-label')?.includes('score:100') &&
    matrixCells.get('model-answer').getAttribute('aria-label')?.includes('upstream:ollama_upstream_5xx') &&
    matrixCells.get('model-answer').getAttribute('aria-label')?.includes('upstreamNext:inspect_ollama_runtime_capacity'),
  `model-answer matrix should expose local LLM operator action for agents: ${matrixCells.get('model-answer').getAttribute('aria-label')}`
);
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'browser=missing-smoke' }
    ],
    externalEvidence: [
      {
        service: 'supabase',
        status: 'WARN',
        evidenceScope: 'read-only',
        evidenceNeeded: 'supabase_project_scope_or_auth_unverified',
        nextAction: 'run_readonly_supabase_context_probe',
        projectRefEnvStatus: 'missing',
        authEnvStatus: 'missing',
        mcpConfigStatus: 'configured',
        probeFileStatus: 'missing'
      },
      {
        service: 'browser',
        status: 'WARN',
        evidenceScope: 'local-ui-proof',
        evidenceNeeded: 'browser_ui_smoke_missing',
        nextAction: 'run_browser_ui_smoke',
        smokeFileStatus: 'missing'
      },
      {
        service: 'computer-use',
        status: 'OK',
        evidenceScope: 'gui-supporting-only',
        evidenceNeeded: null,
        nextAction: 'computer_use_supporting_evidence_current',
        smokeFileStatus: 'present',
        reachable: true,
        countOnly: true,
        appCount: 40,
        targetableWindowCount: 16,
        stale: false
      }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  heartbeatFields.get('browser').dataset.status === 'WARN' &&
    heartbeatFields.get('browser').getAttribute('aria-label')?.includes('Browser: WARN - scope:local-ui-proof') &&
    heartbeatFields.get('browser').getAttribute('aria-label')?.includes('browser_ui_smoke_missing') &&
    proofCells.get('browser').getAttribute('aria-label')?.includes('Browser: WARN - scope:local-ui-proof') &&
    matrixCells.get('external-proof').getAttribute('aria-label')?.includes('browser:WARN'),
  `missing browser smoke should stay WARN, not be promoted by local-ui-proof scope: ${heartbeatFields.get('browser').dataset.status} / ${heartbeatFields.get('browser').getAttribute('aria-label')} / ${proofCells.get('browser').getAttribute('aria-label')}`
);
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'browser=stale-smoke' }
    ],
    externalEvidence: [
      {
        service: 'supabase',
        status: 'WARN',
        evidenceScope: 'read-only',
        evidenceNeeded: 'supabase_project_scope_or_auth_unverified',
        nextAction: 'run_readonly_supabase_context_probe',
        projectRefEnvStatus: 'missing',
        authEnvStatus: 'missing',
        mcpConfigStatus: 'configured',
        probeFileStatus: 'missing'
      },
      {
        service: 'browser',
        status: 'WARN',
        evidenceScope: 'local-ui-proof',
        evidenceNeeded: 'browser_ui_smoke_not_persisted_to_runtime',
        nextAction: 'rerun_browser_ui_smoke',
        smokeFileStatus: 'present',
        stale: true
      },
      {
        service: 'computer-use',
        status: 'OK',
        evidenceScope: 'gui-supporting-only',
        evidenceNeeded: null,
        nextAction: 'computer_use_supporting_evidence_current',
        smokeFileStatus: 'present',
        reachable: true,
        countOnly: true,
        appCount: 40,
        targetableWindowCount: 16,
        stale: false
      }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  heartbeatFields.get('browser').dataset.status === 'WARN' &&
    heartbeatFields.get('browser').getAttribute('aria-label')?.includes('Browser: WARN - scope:local-ui-proof') &&
    heartbeatFields.get('browser').getAttribute('aria-label')?.includes('stale:true') &&
    proofCells.get('browser').getAttribute('aria-label')?.includes('Browser: WARN - scope:local-ui-proof') &&
    matrixCells.get('external-proof').getAttribute('aria-label')?.includes('browser:WARN'),
  `stale browser smoke should stay WARN, not be promoted by runtime-pending local proof: ${heartbeatFields.get('browser').dataset.status} / ${heartbeatFields.get('browser').getAttribute('aria-label')} / ${proofCells.get('browser').getAttribute('aria-label')}`
);
assert(
  heartbeatFields.get('computer').getAttribute('aria-label')?.includes('Computer: OK - scope:gui-supporting-only') &&
    heartbeatFields.get('computer').getAttribute('aria-label')?.includes('count-only:true') &&
    !heartbeatFields.get('computer').getAttribute('aria-label')?.includes('OK - OK'),
  `computer heartbeat aria should summarize GUI proof counts without raw app/window names: ${heartbeatFields.get('computer').getAttribute('aria-label')}`
);
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'supporting browser/computer evidence stale' }
    ],
    externalEvidence: [
      {
        service: 'goal-next-auto',
        status: 'WARN',
        decision: 'evidence_needed',
        firstAction: 'none',
        nextAction: 'none',
        sourceHealthExit: 0,
        completionAuditExit: 0,
        localReady: true,
        completionReady: true,
        externalInputGateStatus: 'local_or_unknown',
        localPatchJustified: true,
        topActions: []
      },
      {
        service: 'patchdrop',
        status: 'OK',
        activeTopLevelPatchCount: 0,
        nestedProducerPatchCount: 0,
        reportOnlyPendingCount: 0
      },
      {
        service: 'supabase',
        status: 'WARN',
        evidenceScope: 'read-only',
        evidenceNeeded: 'supabase_project_scope_or_auth_unverified',
        nextAction: 'run_readonly_supabase_context_probe',
        projectRefEnvStatus: 'missing',
        authEnvStatus: 'missing',
        mcpConfigStatus: 'configured',
        probeFileStatus: 'missing'
      },
      {
        service: 'browser',
        status: 'WARN',
        evidenceScope: 'local-ui-proof',
        evidenceNeeded: 'browser_ui_smoke_stale',
        nextAction: 'run_browser_local_ui_smoke',
        smokeFileStatus: 'present',
        stale: true
      },
      {
        service: 'computer-use',
        status: 'WARN',
        evidenceScope: 'gui-supporting-only',
        evidenceNeeded: 'computer_use_smoke_stale',
        nextAction: 'run_computer_use_lightweight_smoke',
        smokeFileStatus: 'present',
        reachable: true,
        countOnly: true,
        appCount: 40,
        targetableWindowCount: 16,
        stale: true
      }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  heartbeatFields.get('browser').dataset.status === 'OK' &&
    heartbeatFields.get('browser').getAttribute('aria-label')?.includes('Browser: OK - supporting scope:local-ui-proof') &&
    heartbeatFields.get('browser').getAttribute('aria-label')?.includes('stale:true') &&
    proofCells.get('browser').getAttribute('aria-label')?.includes('Browser: OK - supporting scope:local-ui-proof') &&
    heartbeatFields.get('computer').dataset.status === 'OK' &&
    heartbeatFields.get('computer').getAttribute('aria-label')?.includes('Computer: OK - supporting scope:gui-supporting-only') &&
    heartbeatFields.get('computer').getAttribute('aria-label')?.includes('stale:true') &&
    matrixCells.get('external-proof').getAttribute('aria-label')?.includes('External Proof: OK - supporting'),
  `desktop-only heartbeat should demote stale Browser/Computer to supporting evidence without hiding stale detail: browser=${heartbeatFields.get('browser').getAttribute('aria-label')} computer=${heartbeatFields.get('computer').getAttribute('aria-label')} external=${matrixCells.get('external-proof').getAttribute('aria-label')}`
);
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'external evidence pending' }
    ],
    externalEvidence: [
      {
        service: 'goal-next-auto',
        status: 'WARN',
        decision: 'evidence_needed',
        firstAction: 'open_public_80_443_then_rerun_browser_public_domain_ui_smoke',
        firstActionSource: 'browser_use',
        sourceHealthExit: 0,
        completionAuditExit: 1,
        localReady: true,
        completionReady: false,
        desktopFinalProof: 'evidence_needed',
        externalInputGateStatus: 'local_or_unknown',
        localPatchJustified: true,
        topActions: [
          {
            source: 'browser_use',
            action: 'open_public_80_443_then_rerun_browser_public_domain_ui_smoke',
            decision: 'evidence_needed'
          },
          {
            source: 'supabase_apply',
            action: 'set_SUPABASE_PROJECT_REF',
            decision: 'evidence_needed'
          },
          {
            source: 'supabase_apply',
            action: 'complete_supabase_mcp_oauth_flow',
            decision: 'evidence_needed'
          }
        ]
      },
      {
        service: 'supabase',
        status: 'WARN',
        evidenceScope: 'read-only',
        evidenceNeeded: 'supabase_project_scope_or_auth_unverified',
        nextAction: 'run_readonly_supabase_context_probe',
        projectRefEnvStatus: 'missing',
        authEnvStatus: 'missing',
        mcpConfigStatus: 'configured',
        probeFileStatus: 'missing'
      },
      {
        service: 'browser',
        status: 'WARN',
        evidenceScope: 'iab',
        evidenceNeeded: 'public-listener-unreachable',
        nextAction: 'open_public_80_443_then_rerun_browser_public_domain_ui_smoke',
        smokeFileStatus: 'present',
        stale: true
      },
      {
        service: 'computer-use',
        status: 'OK',
        evidenceScope: 'gui-supporting-only',
        evidenceNeeded: null,
        nextAction: 'computer_use_supporting_evidence_current',
        smokeFileStatus: 'present',
        reachable: true,
        countOnly: true,
        appCount: 40,
        targetableWindowCount: 16,
        stale: false
      }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  proofCells.get('local').dataset.status === 'ok' &&
    proofCells.get('local').getAttribute('aria-label')?.includes('Local: OK - gate:local_or_unknown') &&
    proofCells.get('local').getAttribute('aria-label')?.includes('localReady:yes') &&
    proofCells.get('local').getAttribute('aria-label')?.includes('completionReady:no') &&
    heartbeatFields.get('action').querySelector('small').textContent.includes('next:public browser proof') &&
    heartbeatFields.get('action').title.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    proofCells.get('action').getAttribute('aria-label')?.includes('source:browser') &&
    proofCells.get('action').getAttribute('aria-label')?.includes('decision:evidence needed') &&
    proofCells.get('action').getAttribute('aria-label')?.includes('queue:browser:public browser proof') &&
    proofCells.get('action').getAttribute('aria-label')?.includes('supabase:set project ref') &&
    proofCells.get('action').getAttribute('aria-label')?.includes('supabase:complete OAuth') &&
    !proofCells.get('action').getAttribute('aria-label')?.includes('source:browser_use') &&
    !proofCells.get('action').getAttribute('aria-label')?.includes('decision:evidence_needed') &&
    proofCells.get('action').small.textContent.includes('next:public browser proof') &&
    proofCells.get('action').small.textContent.includes('queue:browser:public browser proof') &&
    proofCells.get('action').title.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    cockpitCells.get('next').small.textContent.includes('next:public browser proof') &&
    cockpitCells.get('next').small.textContent.includes('queue:browser:public browser proof') &&
    cockpitCells.get('next').getAttribute('aria-label')?.includes('next:public browser proof') &&
    !cockpitCells.get('next').getAttribute('aria-label')?.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    cockpitCells.get('next').title.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    flowSteps.get('action').small.textContent.includes('next:public browser proof') &&
    flowSteps.get('action').getAttribute('aria-label')?.includes('next:public browser proof') &&
    !flowSteps.get('action').getAttribute('aria-label')?.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    flowSteps.get('action').title.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    heartbeatFields.get('browser').small.textContent.includes('next:public browser proof') &&
    heartbeatFields.get('browser').title.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    proofCells.get('browser').small.textContent.includes('next:public browser proof') &&
    proofCells.get('browser').getAttribute('aria-label')?.includes('next:public browser proof') &&
    !proofCells.get('browser').getAttribute('aria-label')?.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    proofCells.get('browser').title.includes('open_public_80_443_then_rerun_browser_public_domain_ui_smoke') &&
    proofCells.get('supabase').small.textContent.includes('next:readonly probe') &&
    proofCells.get('supabase').getAttribute('aria-label')?.includes('next:readonly probe') &&
    !proofCells.get('supabase').getAttribute('aria-label')?.includes('run_readonly_supabase_context_probe') &&
    proofCells.get('supabase').title.includes('run_readonly_supabase_context_probe') &&
    matrixCells.get('external-proof').getAttribute('aria-label')?.includes('browser:WARN'),
  `goal-next local ready should not be hidden by external completion audit: local=${proofCells.get('local').getAttribute('aria-label')} action=${proofCells.get('action').getAttribute('aria-label')} external=${matrixCells.get('external-proof').getAttribute('aria-label')}`
);
vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'supporting evidence pending' }
    ],
    externalEvidence: [
      {
        service: 'goal-next-auto',
        status: 'WARN',
        decision: 'evidence_needed',
        nextAction: 'none',
        firstAction: 'none',
        sourceHealthExit: 0,
        completionAuditExit: 0,
        localReady: true,
        completionReady: false,
        externalInputGateStatus: 'local_or_unknown',
        localPatchJustified: true,
        topActions: []
      },
      {
        service: 'patchdrop',
        status: 'OK',
        activeTopLevelPatchCount: 0,
        nestedProducerPatchCount: 0,
        reportOnlyPendingCount: 0
      },
      {
        service: 'browser',
        status: 'OK',
        evidenceScope: 'local-ui-proof',
        evidenceNeeded: 'none',
        nextAction: 'browser_ui_smoke_current',
        smokeFileStatus: 'present',
        stale: false
      },
      {
        service: 'computer-use',
        status: 'OK',
        evidenceScope: 'gui-supporting-only',
        nextAction: 'computer_use_supporting_evidence_current',
        reachable: true,
        countOnly: true,
        appCount: 40,
        targetableWindowCount: 16,
        stale: false
      },
      {
        service: 'supabase',
        status: 'WARN',
        evidenceScope: 'read-only',
        evidenceNeeded: 'supabase_project_scope_or_auth_unverified',
        nextAction: 'run_readonly_supabase_context_probe',
        projectRefEnvStatus: 'missing',
        authEnvStatus: 'missing',
        mcpConfigStatus: 'configured',
        probeFileStatus: 'present'
      }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: [
      { name: 'dppDiversityReranker', status: 'OK', enabled: true },
      { name: 'cfvmFailureRecorder', status: 'OK', enabled: true },
      { name: 'cfvmRawMatrixBuffer', status: 'OK', enabled: true }
    ]
  });
`, context);
assert(
  proofCells.get('producer').dataset.status === 'ok' &&
    proofCells.get('producer').getAttribute('aria-label')?.includes('Producer: OK - optional') &&
    proofCells.get('producer').getAttribute('aria-label')?.includes('rows:0') &&
    proofCells.get('action').dataset.status === 'ok' &&
    proofCells.get('action').getAttribute('aria-label')?.includes('decision:desktop only ready') &&
    !proofCells.get('action').getAttribute('aria-label')?.includes('decision:evidence needed'),
  `desktop-only ready state should not warn on missing optional producer proof: producer=${proofCells.get('producer').getAttribute('aria-label')} action=${proofCells.get('action').getAttribute('aria-label')}`
);
assert(
  orchBadges.get('model').dataset.status === 'ok',
  `model badge should mirror model runtime status: ${JSON.stringify(orchBadges.get('model').dataset)}`
);
assert(
  orchBadges.get('model').textContent === 'Model live',
  `model badge text should summarize live/wait state: ${orchBadges.get('model').textContent}`
);
assert(
  orchBadges.get('dpp').dataset.status === 'ok' &&
    orchBadges.get('dpp').title.includes('lane:present') &&
    orchBadges.get('dpp').textContent === 'DPP lane present',
  `DPP badge should mirror dppDiversityReranker lane: ${orchBadges.get('dpp').title}`
);
assert(
  orchBadges.get('cfvm').dataset.status === 'ok' &&
    orchBadges.get('cfvm').title.includes('lanes:2/2') &&
    orchBadges.get('cfvm').textContent === 'CFVM 2/2',
  `CFVM badge should mirror recorder and raw buffer lanes: ${orchBadges.get('cfvm').title}`
);
assert(
  orchBadges.get('supabase').dataset.status === 'warn' &&
    orchBadges.get('supabase').title.includes('supabase_project_scope_or_auth_unverified') &&
    orchBadges.get('supabase').textContent === 'Supabase project/auth unverified',
  `Supabase badge should preserve read-only evidence_needed state: ${orchBadges.get('supabase').title}`
);
assert(
  elements.get('healthStatus').textContent === 'Live OK / external proof supporting' &&
    elements.get('healthStatus').dataset.status === 'ok' &&
    elements.get('healthStatus').title.includes('live:OK') &&
    elements.get('healthStatus').title.includes('proof:SUPPORTING external:SUPPORTING') &&
    statusPills.health.getAttribute('aria-label') === 'Health: Live OK / external proof supporting' &&
    !statusPills.health.getAttribute('aria-label')?.includes('raw:') &&
    statusPills.health.title.includes('raw: live:OK') &&
    statusPills.health.title.includes('proof:SUPPORTING external:SUPPORTING'),
  `Health rail should show a readable summary while preserving raw evidence: ${elements.get('healthStatus').textContent} / ${statusPills.health.getAttribute('aria-label')}`
);
vm.runInContext("setStatusRailHealth('WARN', 'live:WARN core:OK ui:OK model:WARN answer:OK proof:SUPPORTING external:SUPPORTING');", context);
assert(
  elements.get('healthStatus').textContent === 'Model needs attention / external proof supporting' &&
    elements.get('healthStatus').dataset.status === 'warn' &&
    elements.get('healthStatus').title.includes('model:WARN') &&
    statusPills.health.getAttribute('aria-label')?.includes('Health: Model needs attention / external proof supporting'),
  `Health rail should name the live warning source instead of a generic warning: ${elements.get('healthStatus').textContent} / ${statusPills.health.getAttribute('aria-label')}`
);
vm.runInContext("setStatusRailHealth('WARN', 'live:WARN core:OK ui:OK model:UNKNOWN answer:OK proof:SUPPORTING external:SUPPORTING');", context);
assert(
  elements.get('healthStatus').textContent === 'Model proof unavailable / external proof supporting' &&
    elements.get('healthStatus').dataset.status === 'warn' &&
    elements.get('healthStatus').title.includes('model:UNKNOWN') &&
    statusPills.health.getAttribute('aria-label')?.includes('Health: Model proof unavailable / external proof supporting'),
  `Health rail should keep stale model proof distinct from a current model warning: ${elements.get('healthStatus').textContent} / ${statusPills.health.getAttribute('aria-label')}`
);
assert(
  vm.runInContext("modelRuntimeStatus({ status: 'UNKNOWN', reason: 'stale_local_llm_debug_event' }, null)", context) === 'UNKNOWN' &&
    vm.runInContext("compactOrchSignalText('model', 'UNKNOWN', 'route:unknown delivery:unknown wait:none evidence:stale_local_llm_debug_event')", context) === 'Model check',
  'stale model evidence should remain an explicit UNKNOWN state in the UI contract'
);
assert(
  vm.runInContext("modelRuntimeStatus({ status: 'OK' }, null)", context) === 'OK' &&
    vm.runInContext("modelRuntimeStatus({ status: 'WARN' }, null)", context) === 'WARN' &&
    vm.runInContext("modelRuntimeStatus({ status: 'UNKNOWN' }, null)", context) === 'UNKNOWN' &&
    vm.runInContext("modelRuntimeStatus({ waiting: true }, null)", context) === 'WARN' &&
    vm.runInContext("modelRuntimeStatus({}, null)", context) === 'UNKNOWN' &&
    vm.runInContext("modelRuntimeStatus({ status: 'UNSUPPORTED' }, null)", context) === 'UNKNOWN',
  'model runtime status should preserve explicit states and fail closed when evidence is missing or unsupported'
);

vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'external input required' }
    ],
    externalEvidence: [
      {
        service: 'goal-next-auto',
        status: 'WARN',
        decision: 'evidence_needed',
        nextAction: 'collect_external_evidence_files',
        firstAction: 'none',
        sourceHealthExit: 0,
        completionAuditExit: 1,
        localReady: true,
        completionReady: false,
        externalInputGateStatus: 'external_input_needed',
        localPatchJustified: true,
        topActions: []
      }
    ],
    modelRuntime: {
      status: 'OK',
      localLlmOperatorAction: {
        triggered: false,
        triggerReason: 'recent_local_model_success',
        failureClass: 'none',
        nextAction: 'none'
      }
    },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: []
  });
`, context);
assert(
  vm.runInContext('debugHeartbeatSummaryState.nextAction', context) === 'collect_external_evidence_files',
  `a cleared local-LLM operator action must not mask the meaningful external next action: ${vm.runInContext('debugHeartbeatSummaryState.nextAction', context)}`
);

vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'supporting PatchDrop evidence pending' }
    ],
    externalEvidence: [
      {
        service: 'goal-next-auto',
        status: 'WARN',
        decision: 'evidence_needed',
        nextAction: 'none',
        firstAction: 'none',
        sourceHealthExit: 0,
        completionAuditExit: 1,
        localReady: true,
        completionReady: false,
        externalInputGateStatus: 'local_or_unknown',
        localPatchJustified: true,
        topActions: []
      },
      {
        service: 'patchdrop',
        status: 'WARN',
        activeTopLevelPatchCount: 0,
        nestedProducerPatchCount: 1,
        reportOnlyPendingCount: 1,
        pendingProducerNode: 'notebook',
        evidenceNeeded: 'patchdrop_report_only_pending',
        nextAction: 'classify_patchdrop_report_only_artifacts'
      },
      { service: 'noether', status: 'OK', waiting: false, responded: true, lastMessageKind: 'supporting' },
      { service: 'browser', status: 'OK', evidenceScope: 'local-ui-proof', smokeFileStatus: 'present', stale: false },
      { service: 'computer-use', status: 'OK', evidenceScope: 'gui-supporting-only', reachable: true, countOnly: true, stale: false },
      { service: 'supabase', status: 'WARN', evidenceScope: 'read-only', evidenceNeeded: 'supabase_project_scope_or_auth_unverified', nextAction: 'run_readonly_supabase_context_probe' }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: []
  });
`, context);
assert(
  heartbeatFields.get('patchdrop').dataset.status === 'OK' &&
    heartbeatFields.get('patchdrop').getAttribute('aria-label')?.includes('supporting top:0 nested:1 report:1') &&
    proofCells.get('producer').dataset.status === 'ok' &&
    proofCells.get('producer').getAttribute('aria-label')?.includes('Producer: OK - optional') &&
    proofCells.get('producer').getAttribute('aria-label')?.includes('rows:1') &&
    proofCells.get('action').dataset.status === 'ok' &&
    proofCells.get('action').getAttribute('aria-label')?.includes('decision:desktop only ready'),
  `nested/report-only PatchDrop evidence should stay supporting in Desktop-only mode: patchdrop=${heartbeatFields.get('patchdrop').getAttribute('aria-label')} producer=${proofCells.get('producer').getAttribute('aria-label')} action=${proofCells.get('action').getAttribute('aria-label')}`
);

vm.runInContext(`
  renderDebugHeartbeat({
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'WARN', detail: 'top-level PatchDrop apply candidate pending' }
    ],
    externalEvidence: [
      {
        service: 'goal-next-auto',
        status: 'WARN',
        decision: 'evidence_needed',
        nextAction: 'none',
        firstAction: 'none',
        sourceHealthExit: 0,
        completionAuditExit: 0,
        localReady: true,
        completionReady: true,
        externalInputGateStatus: 'local_or_unknown',
        localPatchJustified: true,
        topActions: []
      },
      {
        service: 'patchdrop',
        status: 'WARN',
        activeTopLevelPatchCount: 1,
        nestedProducerPatchCount: 0,
        reportOnlyPendingCount: 0,
        evidenceNeeded: 'patchdrop_apply_candidate_pending',
        nextAction: 'run_patchdrop_janitor_inventory'
      }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: {},
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' },
    lanes: []
  });
`, context);
assert(
  heartbeatFields.get('patchdrop').dataset.status === 'WARN' &&
    proofCells.get('producer').dataset.status === 'warn' &&
    proofCells.get('action').dataset.status === 'warn' &&
    proofCells.get('action').getAttribute('aria-label')?.includes('decision:evidence needed'),
  `top-level PatchDrop apply candidates must remain a Desktop hard gate: patchdrop=${heartbeatFields.get('patchdrop').getAttribute('aria-label')} producer=${proofCells.get('producer').getAttribute('aria-label')} action=${proofCells.get('action').getAttribute('aria-label')}`
);

const chatWindow = elements.get('chatWindow');
const assistantBubble = fakeElement('assistant');
assistantBubble.dataset.speaker = 'assistant';
assistantBubble.offsetTop = 420;
chatWindow.appendChild(assistantBubble);
context.__assistantBubble = assistantBubble;
const evidenceChildrenBefore = chatWindow.children.length;
chatWindow.scrollTop = 17;
chatWindow.scrollHeight = 640;
chatWindow.clientHeight = 220;
vm.runInContext(`
  renderChatEvent({
    type: 'evidence',
    evidence: [
      {
        title: 'Official guide',
        url: 'https://docs.example.test/policy',
        snippet: 'Public evidence snippet ' + ['Authorization', ':'].join('') + ' Bearer ' + 'SECRETSECRET'
      },
      {
        source: 'Vector memo',
        text: 'Local context chunk for the current answer'
      }
    ]
  }, globalThis.__assistantBubble);
`, context);
assert(
  chatWindow.children.length === evidenceChildrenBefore + 1,
  'stream evidence event did not append an evidence rail'
);
const evidenceRail = chatWindow.children[chatWindow.children.length - 1];
assert(
  evidenceRail.dataset.role === 'evidence' &&
    evidenceRail.dataset.count === '2' &&
    evidenceRail.getAttribute('aria-label')?.includes('Evidence: 2'),
  `stream evidence rail should expose count and aria metadata: ${JSON.stringify(evidenceRail.dataset)} / ${evidenceRail.getAttribute('aria-label')}`
);
assert(
  chatWindow.scrollTop === assistantBubble.offsetTop - 8,
  `stream evidence rail should keep latest rendered answer visible with padding: scrollTop=${chatWindow.scrollTop} assistantOffset=${assistantBubble.offsetTop}`
);
const evidenceList = evidenceRail.children.find((child) => child.className === 'evidence-list');
assert(
  evidenceList?.children?.[0]?.title.includes('docs.example.test') &&
    !evidenceList.children[0].title.includes('https://docs.example.test/policy') &&
    !evidenceList.children[0].title.includes('Bearer') &&
    evidenceList.children[1].title.includes('Vector memo') &&
    !evidenceList.children[1].title.includes('Vector memo - Vector memo'),
  `stream evidence rail should render safe source cards: ${evidenceList?.children?.map((child) => child.title).join('|')}`
);

const metadataOnlyAssistantBubble = fakeElement('assistant-metadata-only-evidence');
metadataOnlyAssistantBubble.parentElement = chatWindow;
context.__metadataOnlyAssistantBubble = metadataOnlyAssistantBubble;
const metadataOnlyChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'evidence',
    evidence: [
      { title: 'https://search.example.test/result-a', url: 'https://search.example.test/result-a' },
      { source: 'https://search.example.test/result-b', link: 'https://search.example.test/result-b', snippet: '' }
    ]
  }, globalThis.__metadataOnlyAssistantBubble);
`, context);
const metadataOnlyRail = chatWindow.children
  .slice(metadataOnlyChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence');
const metadataOnlyRailText = nodeText(metadataOnlyRail);
assert(
  metadataOnlyRail?.dataset?.count === '0' &&
    metadataOnlyRailText.includes('No external evidence') &&
    !metadataOnlyRailText.includes('metadata only') &&
    !metadataOnlyRailText.includes('[url]') &&
    !metadataOnlyRailText.includes('source:[url]'),
  `metadata-only evidence should collapse to a count-only empty rail: count=${metadataOnlyRail?.dataset?.count} text=${metadataOnlyRailText}`
);

const markerUrlEvidenceAssistantBubble = fakeElement('assistant-marker-url-evidence');
markerUrlEvidenceAssistantBubble.parentElement = chatWindow;
context.__markerUrlEvidenceAssistantBubble = markerUrlEvidenceAssistantBubble;
const markerUrlEvidenceChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'evidence',
    evidence: [
      {
        marker: 'W1',
        source: 'https://developers.openai.com/api/docs/guides/tools-web-search',
        rank: 1,
        confidence: 0.52
      }
    ]
  }, globalThis.__markerUrlEvidenceAssistantBubble);
`, context);
const markerUrlEvidenceRail = chatWindow.children
  .slice(markerUrlEvidenceChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence');
const markerUrlEvidenceText = nodeText(markerUrlEvidenceRail);
const markerUrlEvidenceChip = markerUrlEvidenceRail?.children
  ?.find((child) => child.className === 'evidence-list')
  ?.children?.[0];
assert(
  markerUrlEvidenceRail?.dataset?.count === '1' &&
    markerUrlEvidenceText.includes('W1') &&
    markerUrlEvidenceChip?.title.includes('developers.openai.com') &&
    findNodesById(markerUrlEvidenceRail, 'a').some((anchor) =>
      anchor.getAttribute('href') === 'https://developers.openai.com/api/docs/guides/tools-web-search' &&
      anchor.getAttribute('target') === '_blank' &&
      anchor.getAttribute('rel') === 'noopener noreferrer'
    ) &&
    markerUrlEvidenceChip?.title.includes('W1') &&
    !markerUrlEvidenceRail?.dataset?.context &&
    !markerUrlEvidenceRail?.getAttribute('aria-label')?.includes('no external evidence') &&
    !markerUrlEvidenceText.includes('No external evidence'),
  `marker + source URL evidence should render as citable external evidence, not collapse to zero or a no-evidence context: count=${markerUrlEvidenceRail?.dataset?.count} context=${markerUrlEvidenceRail?.dataset?.context} aria=${markerUrlEvidenceRail?.getAttribute('aria-label')} text=${markerUrlEvidenceText} title=${markerUrlEvidenceChip?.title}`
);

const citedMarkerEvidenceAssistantBubble = fakeElement('assistant-cited-marker-evidence');
citedMarkerEvidenceAssistantBubble.textContent = 'tool type: web_search [W5]';
citedMarkerEvidenceAssistantBubble.parentElement = chatWindow;
context.__citedMarkerEvidenceAssistantBubble = citedMarkerEvidenceAssistantBubble;
const citedMarkerEvidenceChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    modelUsed: 'qwen3:8b',
    answerMode: 'rag',
    evidence: [
      { marker: 'W1', source: 'https://developers.openai.com/api/docs/guides/tools-web-search', rank: 1 },
      { marker: 'W2', source: 'https://learn.microsoft.com/example', rank: 2 },
      { marker: 'W3', source: 'https://developers.openai.com/api/reference/responses', rank: 3 },
      { marker: 'W4', source: 'https://example.com/other', rank: 4 },
      { marker: 'W5', source: 'https://developers.openai.com/api/docs/guides/tools', rank: 5 }
    ]
  }, globalThis.__citedMarkerEvidenceAssistantBubble);
`, context);
const citedMarkerEvidenceRail = chatWindow.children
  .slice(citedMarkerEvidenceChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence');
const citedMarkerEvidenceChip = citedMarkerEvidenceRail?.children
  ?.find((child) => child.className === 'evidence-list')
  ?.children?.[0];
assert(
  citedMarkerEvidenceRail?.dataset?.count === '5' &&
    citedMarkerEvidenceChip?.title.includes('W5') &&
    citedMarkerEvidenceChip?.title.includes('developers.openai.com'),
  `cited evidence marker should be visible before folded uncited items: count=${citedMarkerEvidenceRail?.dataset?.count} firstTitle=${citedMarkerEvidenceChip?.title}`
);

const finalUnavailableAssistantBubble = fakeElement('assistant-final-external-unavailable');
finalUnavailableAssistantBubble.parentElement = chatWindow;
context.__finalUnavailableAssistantBubble = finalUnavailableAssistantBubble;
const finalUnavailableChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    data: 'safe local answer',
    modelUsed: 'qwen3:8b',
    answerMode: 'CHAT',
    pipelineSnapshot: { disabledReason: 'private-provider-disabled-detail' },
    evidence: []
  }, globalThis.__finalUnavailableAssistantBubble);
`, context);
const finalUnavailableRail = chatWindow.children
  .slice(finalUnavailableChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence');
const finalUnavailableBadge = finalUnavailableRail?.children
  ?.find((child) => child.dataset?.role === 'evidence-quality');
assert(
  finalUnavailableRail?.dataset?.evidenceQuality === 'external-unavailable' &&
    finalUnavailableBadge?.textContent === '외부 근거 사용 불가' &&
    !nodeText(finalUnavailableRail).includes('private-provider-disabled-detail'),
  `final pipeline disabledReason should map to a redacted external-unavailable badge: dataset=${JSON.stringify(finalUnavailableRail?.dataset)} text=${nodeText(finalUnavailableRail)}`
);
assert(
  elements.get('diagnosticsSummary').dataset.diagnosticCode === 'external-unavailable' &&
    elements.get('diagnosticsSummary').textContent === 'External evidence unavailable · current answer' &&
    !elements.get('diagnosticsSummary').textContent.includes('private-provider-disabled-detail'),
  `current-answer evidence degradation must outrank neutral completion without raw detail: ${elements.get('diagnosticsSummary').dataset.diagnosticCode}/${elements.get('diagnosticsSummary').textContent}`
);

assert(
  citedMarkerEvidenceRail?.dataset?.evidenceQuality === 'partial-citation' &&
    citedMarkerEvidenceRail.children[0]?.textContent === 'Evidence 5' &&
    citedMarkerEvidenceRail.children[1]?.dataset?.role === 'evidence-quality' &&
    citedMarkerEvidenceRail.children.indexOf(citedMarkerEvidenceRail.children[0]) <
      citedMarkerEvidenceRail.children.indexOf(citedMarkerEvidenceRail.children[1]),
  `final cited markers should expose finite quality with heading before badge: dataset=${JSON.stringify(citedMarkerEvidenceRail?.dataset)} order=${citedMarkerEvidenceRail?.children?.map((child) => child.dataset?.role || child.textContent).join('|')}`
);

const evidenceEventAuthorityAssistantBubble = fakeElement('assistant-evidence-event-authority');
evidenceEventAuthorityAssistantBubble.parentElement = chatWindow;
context.__evidenceEventAuthorityAssistantBubble = evidenceEventAuthorityAssistantBubble;
const evidenceEventAuthorityChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'evidence',
    pipelineSnapshot: { disabledReason: 'must-not-authorize-evidence-event' },
    evidence: [{ title: 'Unmarked local memo', source: 'local' }]
  }, globalThis.__evidenceEventAuthorityAssistantBubble);
`, context);
const evidenceEventAuthorityRail = chatWindow.children
  .slice(evidenceEventAuthorityChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence');
assert(
  evidenceEventAuthorityRail?.dataset?.evidenceQuality === 'not-observed' &&
    !nodeText(evidenceEventAuthorityRail).includes('must-not-authorize-evidence-event'),
  `evidence SSE must not inherit final pipeline authority: dataset=${JSON.stringify(evidenceEventAuthorityRail?.dataset)} text=${nodeText(evidenceEventAuthorityRail)}`
);

const lateEmptyEvidenceTarget = fakeElement('late-empty-evidence-target');
context.__lateEmptyEvidenceTarget = lateEmptyEvidenceTarget;
const lateEmptyPreserved = vm.runInContext(`
  (() => {
    const populated = renderEvidenceRail(
      [{ title: 'Persisted evidence', source: 'local' }],
      globalThis.__lateEmptyEvidenceTarget,
      {}
    );
    populated.matches = (selector) => selector === '.evidence-rail[data-role="evidence"]';
    const afterEmpty = renderEvidenceRail([], globalThis.__lateEmptyEvidenceTarget, {});
    return populated === afterEmpty;
  })()
`, context);
assert(
  lateEmptyPreserved &&
    lateEmptyEvidenceTarget.children.filter((child) => child.dataset?.role === 'evidence').length === 1 &&
    lateEmptyEvidenceTarget.children[0]?.dataset?.count === '1',
  `late empty evidence must preserve the populated mounted rail: preserved=${lateEmptyPreserved} rails=${lateEmptyEvidenceTarget.children.map((child) => JSON.stringify(child.dataset)).join('|')}`
);

const officialChangelogAnswerOrderAssistantBubble = fakeElement('assistant-official-changelog-answer-order');
officialChangelogAnswerOrderAssistantBubble.textContent = [
  'official changelog evidence summary',
  'https://platform.openai.com/docs/changelog',
  'https://help.openai.com/en/articles/6825453-chatgpt-release-notes',
  'https://developers.openai.com/codex/changelog'
].join(' ');
officialChangelogAnswerOrderAssistantBubble.parentElement = chatWindow;
context.__officialChangelogAnswerOrderAssistantBubble = officialChangelogAnswerOrderAssistantBubble;
const officialChangelogAnswerOrderChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    modelUsed: 'qwen3:8b',
    answerMode: 'rag',
    evidence: [
      { marker: 'W1', source: 'https://platform.openai.com/docs/changelog', rank: 1 },
      { marker: 'W2', source: 'https://github.com/openai/openai-python', rank: 2 },
      { marker: 'W3', source: 'https://github.com/anasfik/openai', rank: 3 },
      { marker: 'W4', source: 'https://help.openai.com/en/articles/6825453-chatgpt-release-notes', rank: 4 },
      { marker: 'W5', source: 'https://developers.openai.com/codex/changelog', rank: 5 }
    ]
  }, globalThis.__officialChangelogAnswerOrderAssistantBubble);
`, context);
const officialChangelogAnswerOrderRail = chatWindow.children
  .slice(officialChangelogAnswerOrderChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence');
const officialChangelogAnswerOrderTitles = officialChangelogAnswerOrderRail?.children
  ?.find((child) => child.className === 'evidence-list')
  ?.children?.map((chip) => chip.title) || [];
assert(
  officialChangelogAnswerOrderRail?.dataset?.count === '5' &&
    officialChangelogAnswerOrderTitles[0]?.includes('platform.openai.com') &&
    officialChangelogAnswerOrderTitles[1]?.includes('help.openai.com') &&
    officialChangelogAnswerOrderTitles[2]?.includes('developers.openai.com') &&
    !officialChangelogAnswerOrderTitles.slice(0, 3).some((title) => title.includes('github.com')),
  `official/changelog evidence rail should follow the answer URL order before folded community items: titles=${officialChangelogAnswerOrderTitles.join('|')}`
);

const streamedOfficialChangelogOrderAssistantBubble = fakeElement('assistant-streamed-official-changelog-order');
streamedOfficialChangelogOrderAssistantBubble.textContent = [
  'official changelog evidence summary',
  'https://platform.openai.com/docs/changelog',
  'https://help.openai.com/en/articles/6825453-chatgpt-release-notes',
  'https://developers.openai.com/codex/changelog'
].join(' ');
streamedOfficialChangelogOrderAssistantBubble.parentElement = chatWindow;
context.__streamedOfficialChangelogOrderAssistantBubble = streamedOfficialChangelogOrderAssistantBubble;
const streamedOfficialChangelogOrderChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'evidence',
    evidence: [
      { marker: 'W1', source: 'https://platform.openai.com/docs/changelog', rank: 1 },
      { marker: 'W2', source: 'https://github.com/openai/openai-python', rank: 2 },
      { marker: 'W3', source: 'https://github.com/anasfik/openai', rank: 3 },
      { marker: 'W4', source: 'https://help.openai.com/en/articles/6825453-chatgpt-release-notes', rank: 4 },
      { marker: 'W5', source: 'https://developers.openai.com/codex/changelog', rank: 5 }
    ]
  }, globalThis.__streamedOfficialChangelogOrderAssistantBubble);
  renderChatEvent({
    type: 'final',
    modelUsed: 'qwen3:8b',
    answerMode: 'rag',
    evidence: []
  }, globalThis.__streamedOfficialChangelogOrderAssistantBubble);
`, context);
const streamedOfficialChangelogOrderRail = chatWindow.children
  .slice(streamedOfficialChangelogOrderChildrenBefore)
  .filter((child) => child.dataset?.role === 'evidence')
  .at(-1);
const streamedOfficialChangelogOrderTitles = streamedOfficialChangelogOrderRail?.children
  ?.find((child) => child.className === 'evidence-list')
  ?.children?.map((chip) => chip.title) || [];
assert(
  streamedOfficialChangelogOrderRail?.dataset?.count === '5' &&
    streamedOfficialChangelogOrderTitles[0]?.includes('platform.openai.com') &&
    streamedOfficialChangelogOrderTitles[1]?.includes('help.openai.com') &&
    streamedOfficialChangelogOrderTitles[2]?.includes('developers.openai.com') &&
    !streamedOfficialChangelogOrderTitles.slice(0, 3).some((title) => title.includes('github.com')),
  `streamed evidence rail should be reordered from final answer text even when final evidence payload is empty: titles=${streamedOfficialChangelogOrderTitles.join('|')}`
);

const tokenStreamOfficialChangelogOrderAssistantBubble = fakeElement('assistant-token-stream-official-changelog-order');
tokenStreamOfficialChangelogOrderAssistantBubble.parentElement = chatWindow;
context.__tokenStreamOfficialChangelogOrderAssistantBubble = tokenStreamOfficialChangelogOrderAssistantBubble;
const tokenStreamOfficialChangelogOrderChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'evidence',
    evidence: [
      { marker: 'W1', source: 'https://platform.openai.com/docs/changelog', rank: 1 },
      { marker: 'W2', source: 'https://github.com/openai/openai-python', rank: 2 },
      { marker: 'W3', source: 'https://github.com/anasfik/openai', rank: 3 },
      { marker: 'W4', source: 'https://help.openai.com/en/articles/6825453-chatgpt-release-notes', rank: 4 },
      { marker: 'W5', source: 'https://developers.openai.com/codex/changelog', rank: 5 }
    ]
  }, globalThis.__tokenStreamOfficialChangelogOrderAssistantBubble);
  renderChatEvent({
    type: 'token',
    data: 'official changelog evidence summary https://platform.openai.com/docs/changelog https://help.openai.com/en/articles/6825453-chatgpt-release-notes https://developers.openai.com/codex/changelog'
  }, globalThis.__tokenStreamOfficialChangelogOrderAssistantBubble);
`, context);
const tokenStreamOfficialChangelogOrderRail = chatWindow.children
  .slice(tokenStreamOfficialChangelogOrderChildrenBefore)
  .filter((child) => child.dataset?.role === 'evidence')
  .at(-1);
const tokenStreamOfficialChangelogOrderTitles = tokenStreamOfficialChangelogOrderRail?.children
  ?.find((child) => child.className === 'evidence-list')
  ?.children?.map((chip) => chip.title) || [];
assert(
  tokenStreamOfficialChangelogOrderRail?.dataset?.count === '5' &&
    tokenStreamOfficialChangelogOrderTitles[0]?.includes('platform.openai.com') &&
    tokenStreamOfficialChangelogOrderTitles[1]?.includes('help.openai.com') &&
    tokenStreamOfficialChangelogOrderTitles[2]?.includes('developers.openai.com') &&
    !tokenStreamOfficialChangelogOrderTitles.slice(0, 3).some((title) => title.includes('github.com')),
  `token-streamed evidence rail should refresh from accumulated answer text without waiting for final evidence: titles=${tokenStreamOfficialChangelogOrderTitles.join('|')}`
);

const evidenceAfterTokenOfficialChangelogOrderAssistantBubble = fakeElement('assistant-evidence-after-token-official-changelog-order');
evidenceAfterTokenOfficialChangelogOrderAssistantBubble.parentElement = chatWindow;
context.__evidenceAfterTokenOfficialChangelogOrderAssistantBubble = evidenceAfterTokenOfficialChangelogOrderAssistantBubble;
const evidenceAfterTokenOfficialChangelogOrderChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'token',
    data: 'official changelog evidence summary https://platform.openai.com/docs/changelog https://help.openai.com/en/articles/6825453-chatgpt-release-notes https://developers.openai.com/codex/changelog'
  }, globalThis.__evidenceAfterTokenOfficialChangelogOrderAssistantBubble);
  renderChatEvent({
    type: 'evidence',
    evidence: [
      { marker: 'W1', source: 'https://platform.openai.com/docs/changelog', rank: 1 },
      { marker: 'W2', source: 'https://github.com/openai/openai-python', rank: 2 },
      { marker: 'W3', source: 'https://github.com/anasfik/openai', rank: 3 },
      { marker: 'W4', source: 'https://help.openai.com/en/articles/6825453-chatgpt-release-notes', rank: 4 },
      { marker: 'W5', source: 'https://developers.openai.com/codex/changelog', rank: 5 }
    ]
  }, globalThis.__evidenceAfterTokenOfficialChangelogOrderAssistantBubble);
`, context);
const evidenceAfterTokenOfficialChangelogOrderRail = chatWindow.children
  .slice(evidenceAfterTokenOfficialChangelogOrderChildrenBefore)
  .filter((child) => child.dataset?.role === 'evidence')
  .at(-1);
const evidenceAfterTokenOfficialChangelogOrderTitles = evidenceAfterTokenOfficialChangelogOrderRail?.children
  ?.find((child) => child.className === 'evidence-list')
  ?.children?.map((chip) => chip.title) || [];
assert(
  evidenceAfterTokenOfficialChangelogOrderRail?.dataset?.count === '5' &&
    evidenceAfterTokenOfficialChangelogOrderTitles[0]?.includes('platform.openai.com') &&
    evidenceAfterTokenOfficialChangelogOrderTitles[1]?.includes('help.openai.com') &&
    evidenceAfterTokenOfficialChangelogOrderTitles[2]?.includes('developers.openai.com') &&
    !evidenceAfterTokenOfficialChangelogOrderTitles.slice(0, 3).some((title) => title.includes('github.com')),
  `late evidence event should use accumulated answer text when rendering rail: titles=${evidenceAfterTokenOfficialChangelogOrderTitles.join('|')}`
);

const uncitedSearchEvidenceAssistantBubble = fakeElement('assistant-uncited-search-evidence');
uncitedSearchEvidenceAssistantBubble.textContent = '프로젝트-local 근거가 없어 evidence_needed로 분류합니다.';
uncitedSearchEvidenceAssistantBubble.parentElement = chatWindow;
context.__uncitedSearchEvidenceAssistantBubble = uncitedSearchEvidenceAssistantBubble;
const uncitedSearchEvidenceChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    modelUsed: 'qwen3:8b',
    answerMode: 'rag',
    evidence: [
      { marker: 'W1', source: 'https://developer.example.test/graphrag', rank: 1 },
      { marker: 'W2', source: 'https://docs.example.test/cfvm', rank: 2 }
    ]
  }, globalThis.__uncitedSearchEvidenceAssistantBubble);
`, context);
const uncitedSearchEvidenceRail = chatWindow.children
  .slice(uncitedSearchEvidenceChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence');
const uncitedSearchEvidenceText = nodeText(uncitedSearchEvidenceRail);
assert(
  uncitedSearchEvidenceRail?.dataset?.citationState === 'searched-but-uncited' &&
    uncitedSearchEvidenceRail?.getAttribute('aria-label')?.includes('searched but uncited') &&
    uncitedSearchEvidenceText.includes('searched, not cited'),
  `uncited searched evidence should be explicitly labeled so rejected web results are not mistaken for answer support: dataset=${JSON.stringify(uncitedSearchEvidenceRail?.dataset)} aria=${uncitedSearchEvidenceRail?.getAttribute('aria-label')} text=${uncitedSearchEvidenceText}`
);

const localFallbackAssistantBubble = fakeElement('assistant-local-fallback');
localFallbackAssistantBubble.textContent = 'local fallback answer';
localFallbackAssistantBubble.parentElement = chatWindow;
context.__localFallbackAssistantBubble = localFallbackAssistantBubble;
const localFallbackChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    sessionId: 124,
    modelUsed: 'qwen3:8b:fallback:local-lite',
    answerMode: 'FALLBACK_LOCAL',
    evidence: []
  }, globalThis.__localFallbackAssistantBubble);
`, context);
const localFallbackRail = chatWindow.children
  .slice(localFallbackChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence' && child.dataset?.count === '0');
assert(
  localFallbackRail,
  `local fallback final should still expose a count-only evidence rail: ${chatWindow.children
    .slice(localFallbackChildrenBefore)
    .map((child) => JSON.stringify(child.dataset))
    .join('|')}`
);
const localFallbackRailText = nodeText(localFallbackRail);
assert(
  localFallbackRailText.includes('Local fallback - no external evidence') &&
    !localFallbackRailText.includes('No evidence metadata') &&
    localFallbackRail.getAttribute('aria-label')?.includes('local fallback'),
  `local fallback empty evidence rail should not look like missing evidence metadata: ${localFallbackRailText} / ${localFallbackRail.getAttribute('aria-label')}`
);

const historyFallbackAssistantBubble = fakeElement('assistant-history-fallback');
historyFallbackAssistantBubble.textContent = 'recent history answer';
historyFallbackAssistantBubble.parentElement = chatWindow;
context.__historyFallbackAssistantBubble = historyFallbackAssistantBubble;
const historyFallbackChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    sessionId: 125,
    modelUsed: 'history:fallback:recent',
    answerMode: 'FALLBACK',
    evidence: []
  }, globalThis.__historyFallbackAssistantBubble);
`, context);
const historyFallbackRail = chatWindow.children
  .slice(historyFallbackChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence' && child.dataset?.count === '0');
assert(
  historyFallbackRail,
  `history fallback final should still expose a count-only evidence rail: ${chatWindow.children
    .slice(historyFallbackChildrenBefore)
    .map((child) => JSON.stringify(child.dataset))
    .join('|')}`
);
const historyFallbackRailText = nodeText(historyFallbackRail);
assert(
  historyFallbackRailText.includes('Recent history - no external evidence') &&
    !historyFallbackRailText.includes('No evidence metadata') &&
    historyFallbackRail.getAttribute('aria-label')?.includes('recent history'),
  `history fallback empty evidence rail should not look like missing evidence metadata: ${historyFallbackRailText} / ${historyFallbackRail.getAttribute('aria-label')}`
);

const genericEmptyEvidenceAssistantBubble = fakeElement('assistant-generic-empty-evidence');
genericEmptyEvidenceAssistantBubble.textContent = 'model answer without external evidence';
genericEmptyEvidenceAssistantBubble.parentElement = chatWindow;
context.__genericEmptyEvidenceAssistantBubble = genericEmptyEvidenceAssistantBubble;
const genericEmptyEvidenceChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    sessionId: 126,
    modelUsed: 'stream-model',
    answerMode: 'chat',
    evidence: []
  }, globalThis.__genericEmptyEvidenceAssistantBubble);
`, context);
const genericEmptyEvidenceRail = chatWindow.children
  .slice(genericEmptyEvidenceChildrenBefore)
  .find((child) => child.dataset?.role === 'evidence' && child.dataset?.count === '0');
assert(
  genericEmptyEvidenceRail,
  `generic empty final should still expose a count-only evidence rail: ${chatWindow.children
    .slice(genericEmptyEvidenceChildrenBefore)
    .map((child) => JSON.stringify(child.dataset))
    .join('|')}`
);
const genericEmptyEvidenceRailText = nodeText(genericEmptyEvidenceRail);
  assert(
    genericEmptyEvidenceRailText.includes('No external evidence') &&
      !genericEmptyEvidenceRailText.includes('Evidence 0No external evidence') &&
      !genericEmptyEvidenceRailText.includes('No evidence metadata') &&
      genericEmptyEvidenceRail.dataset.context === 'no-external-evidence' &&
      genericEmptyEvidenceRail.getAttribute('aria-label')?.includes('no external evidence'),
    `generic empty evidence rail should describe the user-facing evidence state: ${genericEmptyEvidenceRailText} / ${genericEmptyEvidenceRail.getAttribute('aria-label')}`
  );

  const unknownEventAssistantBubble = fakeElement('assistant-unknown-event');
  unknownEventAssistantBubble.textContent = 'normal answer before diagnostic';
  unknownEventAssistantBubble.parentElement = chatWindow;
  const unknownEventChildrenBefore = chatWindow.children.length;
  context.__unknownEventAssistantBubble = unknownEventAssistantBubble;
  vm.runInContext(
    "renderChatEvent({ type: 'unmapped_lane', data: ['Authorization', ':'].join('') + ' Bearer ' + 'private-token' }, globalThis.__unknownEventAssistantBubble);",
    context
  );
  const unknownDiagnostic = chatWindow.children[chatWindow.children.length - 1];
  assert(
    chatWindow.children.length === unknownEventChildrenBefore + 1 &&
      unknownDiagnostic.dataset.role === 'stream-diagnostic' &&
      unknownDiagnostic.textContent.includes('unmapped_lane') &&
      !unknownDiagnostic.textContent.includes('Authorization') &&
      !unknownDiagnostic.textContent.includes('private-token'),
    `unknown SSE events should render compact redacted diagnostics, not disappear or leak data: ${unknownDiagnostic?.textContent}`
  );

  const traceAssistantBubble = fakeElement('assistant-trace');
traceAssistantBubble.textContent = 'normal answer';
traceAssistantBubble.parentElement = chatWindow;
const traceChildrenBefore = chatWindow.children.length;
context.__traceAssistantBubble = traceAssistantBubble;
vm.runInContext("renderChatEvent({ type: 'trace_html', html: '<details>debug trace</details>' }, globalThis.__traceAssistantBubble);", context);
assert(
  traceAssistantBubble.textContent === 'normal answer',
  `trace_html should not overwrite normal answer text: ${traceAssistantBubble.textContent}`
);
assert(
  chatWindow.children.length === traceChildrenBefore + 1 &&
    chatWindow.children[chatWindow.children.length - 1].dataset.role === 'trace',
  'trace_html should append a trace block after the answer'
);
assert(
  chatWindow.children[chatWindow.children.length - 1].getAttribute('aria-hidden') === 'true' &&
    chatWindow.children[chatWindow.children.length - 1].getAttribute('role') === 'presentation',
  'trace_html diagnostic should stay out of the chat live-region announcement stream'
);

const traceSignalAssistantBubble = fakeElement('assistant-trace-signal');
traceSignalAssistantBubble.textContent = 'trace signal answer';
traceSignalAssistantBubble.parentElement = chatWindow;
context.__traceSignalAssistantBubble = traceSignalAssistantBubble;
const traceSignalChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'trace',
    signal: {
      traceIdHash: 'trace-1',
      stage: 'transformer',
      rawScoreDelta: '0.12',
      stageCounts: {
        transformer: 2,
        llm: 1
      }
    },
    pipelineSnapshot: {
      traceTurnId: 'turn-1'
    }
  }, globalThis.__traceSignalAssistantBubble);
`, context);
const traceSignalChildren = chatWindow.children.slice(traceSignalChildrenBefore);
const traceSignalDetail = traceSignalChildren.find((child) => child.dataset?.role === 'trace-signal-detail');
const scoreDeltaDetail = traceSignalChildren.find((child) => child.dataset?.role === 'score-delta-detail');
const traceSignalDetailText = nodeText(traceSignalDetail);
const scoreDeltaDetailText = nodeText(scoreDeltaDetail);
assert(
  traceSignalDetailText.includes('trace: trace-1') &&
    traceSignalDetailText.includes('transformer: 2') &&
    traceSignalDetailText.includes('llm: 1') &&
    !traceSignalDetailText.includes('tracetrace-1') &&
    !traceSignalDetailText.includes('transformer2'),
  `trace signal diagnostics should use readable label/value separators: ${traceSignalDetailText}`
);
assert(
  scoreDeltaDetailText.includes('rawScoreDelta: 0.12') &&
    scoreDeltaDetailText.includes('stage: transformer') &&
    !scoreDeltaDetailText.includes('rawScoreDelta:0.12') &&
    !scoreDeltaDetailText.includes('stage:transformer'),
  `score delta diagnostics should use readable label/value separators: ${scoreDeltaDetailText}`
);

const canonicalTraceSignalAssistantBubble = fakeElement('assistant-canonical-trace-signal');
canonicalTraceSignalAssistantBubble.textContent = 'canonical trace signal answer';
canonicalTraceSignalAssistantBubble.parentElement = chatWindow;
context.__canonicalTraceSignalAssistantBubble = canonicalTraceSignalAssistantBubble;
const canonicalTraceSignalChildrenBefore = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'trace',
    traceSignal: {
      requestIdHash: 'hash:0123456789ab'
    }
  }, globalThis.__canonicalTraceSignalAssistantBubble);
`, context);
const canonicalTraceSignalDetail = chatWindow.children
  .slice(canonicalTraceSignalChildrenBefore)
  .find((child) => child.dataset?.role === 'trace-signal-detail');
const canonicalTraceSignalDetailText = nodeText(canonicalTraceSignalDetail);
assert(
  canonicalTraceSignalDetailText.includes('request: hash:0123456789ab'),
  `canonical traceSignal payload should render its request hash: ${canonicalTraceSignalDetailText}`
);

const planModeChildrenBefore = chatWindow.children.length;
vm.runInContext(
  "renderPlanModeCard({ route: 'transformer' }, document.getElementById('chatWindow'));",
  context
);
const planModeCard = chatWindow.children[chatWindow.children.length - 1];
assert(
  chatWindow.children.length === planModeChildrenBefore + 1 &&
    planModeCard.dataset?.role === 'plan-mode' &&
    String(planModeCard.className || '').includes('message-debug-fx') &&
    planModeCard.textContent === 'Plan route: transformer' &&
    !/^(transformer|llm|fallback: evidence)$/.test(planModeCard.textContent.trim()),
  `plan-mode diagnostics should not expose raw route words as standalone chat nodes: class=${planModeCard?.className} text=${planModeCard?.textContent}`
);

const answerModeBadgeTarget = fakeElement('answer-mode-badge-target');
context.__answerModeBadgeTarget = answerModeBadgeTarget;
vm.runInContext("upsertAnswerModeBadge(globalThis.__answerModeBadgeTarget, 'FALLBACK_EVIDENCE');", context);
const answerModeBadge = answerModeBadgeTarget.children[0];
assert(
  answerModeBadge?.dataset?.answerModeBadge === 'true' &&
    String(answerModeBadge.className || '').includes('message-debug-fx') &&
    answerModeBadge.textContent === 'Answer mode: fallback: evidence' &&
    !/^(fallback: evidence)$/.test(answerModeBadge.textContent.trim()),
  `answer-mode badge should label fallback evidence instead of exposing a raw status word: class=${answerModeBadge?.className} text=${answerModeBadge?.textContent}`
);
assert(
  stylesheet.includes('.message-debug-fx'),
  'debug_fx operator traces should have a compact visible style'
);

const debugFxAssistantBubble = fakeElement('assistant-debug-fx');
debugFxAssistantBubble.textContent = 'normal answer remains';
debugFxAssistantBubble.parentElement = chatWindow;
const debugFxChildrenBefore = chatWindow.children.length;
context.__debugFxAssistantBubble = debugFxAssistantBubble;
vm.runInContext(`
  renderChatEvent({
    type: 'debug_fx',
    debugFxSignal: {
      phase: 'final',
      code: 'ok',
        labels: {
        chatHarmonyDegraded: 'true',
        chatHarmonyDecision: 'fallback_evidence',
        chatHarmonyReason: 'agent_visible_postprocess',
        debugAiMatrixCount: '300',
        debugAiMatrixChunkCount: '30',
        debugAiMatrixDecision: 'observe',
        verificationStatus: 'fail_soft',
        verificationFailureClass: 'catch',
        verificationReason: 'judge_call_failed',
        supabaseStatus: 'WARN',
        supabaseEvidenceNeeded: 'supabase_project_scope_or_auth_unverified',
        agentDbContextStatus: 'DISABLED',
        agentDbContextReason: 'agent_db_context_disabled',
        ollamaNativeGpuMode: 'cpu_fallback',
        ollamaNativeNumGpu: '0',
        browserStatus: 'OK',
        computerUseStatus: 'OK',
        debugAiNextAction: 'inspect_harmony_matrix',
        stageBoundaryStage: 'llm',
        stageBoundaryFailureClass: 'model_timeout',
        stageBoundaryReason: 'runner_terminated_cpu_probe',
        localLlmFailureClass: 'model_timeout',
        localLlmNextAction: 'inspect_model_route_or_start_local_llm',
        localLlmUpstreamStatus: '500',
        localLlmUpstreamFailureClass: 'runner_terminated_cpu_probe',
        localLlmUpstreamNextAction: 'inspect_ollama_runtime_capacity',
        rawSecret: ['Authorization', ':'].join('') + ' Bearer ' + 'private-token'
      }
    }
  }, globalThis.__debugFxAssistantBubble);
`, context);
const debugFxTrace = chatWindow.children[chatWindow.children.length - 1];
assert(
  debugFxAssistantBubble.textContent === 'normal answer remains',
  `debug_fx should not overwrite normal answer text: ${debugFxAssistantBubble.textContent}`
);
assert(
    chatWindow.children.length === debugFxChildrenBefore + 1 &&
    debugFxTrace.dataset.role === 'debug-fx' &&
    debugFxTrace.dataset.debugFxPhase === 'final' &&
    debugFxTrace.textContent.includes('Harmony degraded:true fallback_evidence') &&
    debugFxTrace.textContent.includes('Matrix 300/30 observe') &&
    debugFxTrace.textContent.includes('Verification fail_soft catch judge_call_failed') &&
    debugFxTrace.textContent.includes('MLA FINAL llm model_timeout runner_terminated_cpu_probe') &&
    debugFxTrace.textContent.includes('Supabase WARN') &&
    debugFxTrace.textContent.includes('Agent DB DISABLED') &&
    debugFxTrace.textContent.includes('Native cpu_fallback numGpu:0') &&
    debugFxTrace.textContent.includes('Local LLM runner_terminated_cpu_probe status:500 next:inspect_ollama_runtime_capacity') &&
    debugFxTrace.textContent.includes('Browser OK') &&
    debugFxTrace.textContent.includes('Computer OK') &&
    debugFxTrace.textContent.includes('Next inspect_ollama_runtime_capacity') &&
    !debugFxTrace.textContent.includes('private-token') &&
    !debugFxTrace.textContent.includes('Authorization') &&
    debugFxTrace.getAttribute('aria-hidden') === 'true' &&
    debugFxTrace.getAttribute('role') === 'presentation' &&
    debugFxTrace.getAttribute('aria-label')?.includes('Debug FX: Harmony degraded:true fallback_evidence') &&
    debugFxTrace.getAttribute('aria-label')?.includes('Verification fail_soft catch judge_call_failed'),
  `debug_fx should append a redacted agent-visible operator trace: text=${debugFxTrace.textContent} aria=${debugFxTrace.getAttribute('aria-label')}`
);

vm.runInContext(`
  renderChatEvent({
    type: 'debug_fx',
    debugFxSignal: {
      phase: 'pre_llm',
      code: 'timeout',
      labels: {
        stageBoundaryStage: 'llm',
        stageBoundaryFailureClass: 'timeout',
        stageBoundaryReason: 'model_timeout'
      }
    }
  }, globalThis.__debugFxAssistantBubble);
`, context);
const mlaPhaseRows = chatWindow.children.slice(debugFxChildrenBefore).filter(
  (node) => node.dataset?.role === 'debug-fx' &&
    (node.dataset?.debugFxPhase === 'pre_llm' || node.dataset?.debugFxPhase === 'final')
);
assert(
  mlaPhaseRows.filter((node) => node.dataset.debugFxPhase === 'pre_llm').length === 1 &&
    mlaPhaseRows.filter((node) => node.dataset.debugFxPhase === 'final').length === 1 &&
    mlaPhaseRows.some((node) => node.textContent.includes('MLA PRE llm timeout model_timeout')),
  `debug_fx should expose exactly one PRE and FINAL MLA row: ${mlaPhaseRows.map((node) => `${node.dataset.debugFxPhase}:${node.textContent}`).join(' | ')}`
);

vm.runInContext(`
  renderChatEvent({
    type: 'transformer',
    status: 'running',
    transformerBlocks: [
      {
        id: 'rewrite',
        label: 'Query Rewrite',
        phase: 'query',
        status: 'done',
        reason: 'models:3_asgn:3_titles:3_title-counts:2_supers:9_branches:9_axes:3_padded:2_lanes:2x3_temp:0.15x0.7_profile:balanced rawUserQuery=do-not-surface'
      }
    ]
  }, globalThis.__debugFxAssistantBubble);
`, context);
assert(
  heartbeatFields.get('qtx').dataset.status === 'OK' &&
    heartbeatFields.get('qtx').small.textContent.includes('branches:9') &&
    heartbeatFields.get('qtx').small.textContent.includes('axes:3') &&
    heartbeatFields.get('qtx').small.textContent.includes('models:3') &&
    heartbeatFields.get('qtx').small.textContent.includes('padded:2') &&
    heartbeatFields.get('qtx').small.textContent.includes('lanes:2x3') &&
    heartbeatFields.get('qtx').small.textContent.includes('temp:0.15x0.7') &&
    heartbeatFields.get('qtx').small.textContent.includes('profile:balanced') &&
    !heartbeatFields.get('qtx').getAttribute('aria-label')?.includes('rawUserQuery') &&
    !heartbeatFields.get('qtx').getAttribute('aria-label')?.includes('do-not-surface'),
  `transformer query rewrite should immediately promote compact QTX heartbeat detail: ${heartbeatFields.get('qtx').getAttribute('aria-label')}`
);

vm.runInContext(`
  renderDebugHeartbeat({
    debugAiMetrics: {
      status: 'WARN',
      totalEvents: 1,
      warnEvents: 0,
      errorEvents: 1,
      virtualMatrixCount: 300,
      virtualMatrixChunkCount: 30,
      virtualMatrixWeightedScore: 0.225,
      virtualMatrixScoreRole: 'evidence',
      virtualMatrixScoreTrusted: false,
      virtualMatrixDecision: 'probe_required',
      virtualMatrixActionAllowed: false,
      historyComparisonComparable: false,
      historyComparisonReason: 'sampling_policy_mismatch',
      currentWindowMs: 300000,
      previousWindowMs: 60000,
      currentSampleLimit: 80,
      previousSampleLimit: 10
    },
    queryRewrite: {
      status: 'OK',
      reason: 'latest_query_rewrite_trace',
      source: 'traceSnapshotStore',
      enabled: false,
      branchCount: 0,
      axisCount: 0,
      verificationLaneCount: 1,
      explorationLaneCount: 1,
      laneSummary: 'other:derived|verification:official_source|exploration:reference_docs',
      temperatureProfile: 'exploratory',
      validationTemperature: 0,
      explorationTemperature: 0.68,
      variantLaneTemperatureHints: [
        '0:other:derived@0.22#01caa87c0a74',
        '1:verification:official_source@0.22#96f2a6d54cbe',
        '2:exploration:reference_docs@0.68#a3df400a1d11'
      ],
      latestTraceEntryCount: 1997
    },
    lanes: [
      { name: 'queryTransformer', status: 'OK', enabled: true }
    ],
    debugOverview: [
      { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
      { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
      { name: 'externalEvidence', status: 'OK', detail: 'proof=ready' }
    ],
    modelRuntime: { status: 'OK' },
    providerRuntime: { status: 'OK' },
    failSoftLadder: { status: 'OK' },
    traceSnapshotHealth: { status: 'OK' },
    answerOutput: { status: 'OK' }
  });
`, context);
assert(
  heartbeatFields.get('qtx').dataset.status === 'OK' &&
    heartbeatFields.get('qtx').small.textContent.includes('profile:exploratory') &&
    heartbeatFields.get('qtx').small.textContent.includes('verification:official_source@0.22') &&
    heartbeatFields.get('qtx').small.textContent.includes('exploration:reference_docs@0.68') &&
    heartbeatFields.get('qtx').small.textContent.includes('trace:1997') &&
    !heartbeatFields.get('qtx').getAttribute('aria-label')?.includes('rawUserQuery') &&
    !heartbeatFields.get('qtx').getAttribute('aria-label')?.includes('01caa87c0a74'),
  `heartbeat query rewrite should surface redacted lane temperature hints without raw query hashes: ${heartbeatFields.get('qtx').getAttribute('aria-label')}`
);
assert(
  heartbeatFields.get('metrics').dataset.status === 'WARN' &&
    heartbeatFields.get('metrics').small.textContent.includes('decision:probe_required') &&
    heartbeatFields.get('metrics').small.textContent.includes('role:evidence') &&
    heartbeatFields.get('metrics').small.textContent.includes('trusted:false') &&
    heartbeatFields.get('metrics').small.textContent.includes('actionAllowed:false') &&
    heartbeatFields.get('metrics').small.textContent.includes('historyComparable:false') &&
    heartbeatFields.get('metrics').small.textContent.includes('compare:sampling_policy_mismatch') &&
    heartbeatFields.get('metrics').small.textContent.includes('window:300000/60000') &&
    heartbeatFields.get('metrics').small.textContent.includes('limit:80/10'),
  `debug AI heartbeat should expose evidence-only authority before probes: ${heartbeatFields.get('metrics').getAttribute('aria-label')}`
);

const priorDirectScopeBubble = linkedChatNode('prior-direct-scope-bubble', {
  classes: ['message'],
  dataset: { speaker: 'assistant' }
});
const priorDirectScopeDebug = linkedChatNode('prior-direct-scope-debug', {
  classes: ['message-debug-fx'],
  diagnostic: true
});
const currentDirectScopeBubble = linkedChatNode('current-direct-scope-bubble', {
  classes: ['message'],
  dataset: { speaker: 'assistant' }
});
const currentDirectScopeDebug = linkedChatNode('current-direct-scope-debug', {
  classes: ['message-debug-fx'],
  diagnostic: true
});
const currentDirectScopeEvidence = linkedChatNode('current-direct-scope-evidence', {
  classes: ['evidence-rail'],
  diagnostic: true
});
mountLinkedChatNodes(chatWindow, [
  priorDirectScopeBubble,
  priorDirectScopeDebug,
  currentDirectScopeBubble,
  currentDirectScopeDebug,
  currentDirectScopeEvidence
]);
context.__currentDirectScopeBubble = currentDirectScopeBubble;
const directLiteralSuppressed = vm.runInContext(
  "suppressDirectLiteralDiagnostics(globalThis.__currentDirectScopeBubble, 'DIRECT_LITERAL', 'direct:literal')",
  context
);
assert(directLiteralSuppressed === true, 'direct literal final should activate per-turn diagnostic suppression');
assert(
  currentDirectScopeBubble.dataset.directLiteralDiagnostics === 'suppressed',
  `direct literal assistant bubble should carry a scoped suppression marker: ${JSON.stringify(currentDirectScopeBubble.dataset)}`
);
assert(
  priorDirectScopeDebug.removed !== true && chatWindow.children.includes(priorDirectScopeDebug),
  'direct literal suppression must not remove diagnostics from an earlier assistant turn'
);
assert(
  currentDirectScopeDebug.removed === true &&
    currentDirectScopeEvidence.removed === true &&
    !chatWindow.children.includes(currentDirectScopeDebug) &&
    !chatWindow.children.includes(currentDirectScopeEvidence),
  'direct literal suppression should remove only diagnostics immediately following the current assistant bubble'
);
assert(
  vm.runInContext("shouldSuppressMessageDiagnostics(document.getElementById('chatWindow'))", context) === true,
  'new diagnostics for the current direct literal assistant turn should remain suppressed until the next send'
);
vm.runInContext('clearDirectLiteralDiagnosticsSuppression()', context);
assert(
  !Object.prototype.hasOwnProperty.call(currentDirectScopeBubble.dataset, 'directLiteralDiagnostics'),
  'starting the next send should clear direct literal suppression markers'
);

const priorAnswerOnlyBubble = linkedChatNode('prior-answer-only-bubble', {
  classes: ['message'],
  dataset: { speaker: 'assistant' }
});
const priorAnswerOnlyDebug = linkedChatNode('prior-answer-only-debug', {
  classes: ['message-debug-fx'],
  diagnostic: true
});
const answerOnlyBubble = linkedChatNode('answer-only-bubble', {
  classes: ['message'],
  dataset: { speaker: 'assistant' }
});
const answerOnlyDebug = linkedChatNode('answer-only-debug', {
  classes: ['message-debug-fx'],
  diagnostic: true
});
const answerOnlyEvidence = linkedChatNode('answer-only-evidence', {
  classes: ['evidence-rail'],
  diagnostic: true
});
mountLinkedChatNodes(chatWindow, [
  priorAnswerOnlyBubble,
  priorAnswerOnlyDebug,
  answerOnlyBubble,
  answerOnlyDebug,
  answerOnlyEvidence
]);
context.__answerOnlyBubble = answerOnlyBubble;
const answerOnlySuppressed = vm.runInContext(
  "suppressAnswerOnlyDiagnostics(globalThis.__answerOnlyBubble, '디버그 설명 없이 답변만 해줘')",
  context
);
assert(answerOnlySuppressed === true, 'answer-only user instruction should activate per-turn diagnostic suppression');
assert(
  answerOnlyBubble.dataset.answerOnlyDiagnostics === 'suppressed',
  `answer-only assistant bubble should carry a scoped suppression marker: ${JSON.stringify(answerOnlyBubble.dataset)}`
);
assert(
  priorAnswerOnlyDebug.removed !== true && chatWindow.children.includes(priorAnswerOnlyDebug),
  'answer-only suppression must not remove diagnostics from an earlier assistant turn'
);
assert(
  answerOnlyDebug.removed === true &&
    answerOnlyEvidence.removed === true &&
    !chatWindow.children.includes(answerOnlyDebug) &&
    !chatWindow.children.includes(answerOnlyEvidence),
  'answer-only suppression should remove only diagnostics immediately following the current assistant bubble'
);
assert(
  vm.runInContext("shouldSuppressMessageDiagnostics(document.getElementById('chatWindow'))", context) === true,
  'new diagnostics for the current answer-only assistant turn should remain suppressed until the next send'
);
vm.runInContext('clearDirectLiteralDiagnosticsSuppression()', context);
assert(
  !Object.prototype.hasOwnProperty.call(answerOnlyBubble.dataset, 'answerOnlyDiagnostics'),
  'starting the next send should clear answer-only suppression markers'
);

const priorCompactProofBubble = linkedChatNode('prior-compact-proof-bubble', {
  classes: ['message'],
  dataset: { speaker: 'assistant' }
});
const priorCompactProofDebug = linkedChatNode('prior-compact-proof-debug', {
  classes: ['message-debug-fx'],
  diagnostic: true
});
const compactProofBubble = linkedChatNode('compact-proof-bubble', {
  classes: ['message'],
  dataset: { speaker: 'assistant' }
});
compactProofBubble.textContent = [
  '- Browser: OK (evidence_needed=none; stale=false)',
  '- Computer: OK (evidence_needed=none; stale=false; count-only=true)',
  '- Supabase: evidence_needed (supabase_project_scope_or_auth_unverified; read-only only; DB verification not claimed)'
].join('\n');
const compactProofDebug = linkedChatNode('compact-proof-debug', {
  classes: ['message-debug-fx'],
  diagnostic: true
});
const compactProofEvidence = linkedChatNode('compact-proof-evidence', {
  classes: ['evidence-rail'],
  diagnostic: true
});
mountLinkedChatNodes(chatWindow, [
  priorCompactProofBubble,
  priorCompactProofDebug,
  compactProofBubble,
  compactProofDebug,
  compactProofEvidence
]);
context.__compactProofBubble = compactProofBubble;
vm.runInContext(`
  renderChatEvent({
    type: 'final',
    modelUsed: 'agent-debug:fallback:evidence',
    pipelineSnapshot: { answerMode: 'FALLBACK_EVIDENCE' },
    evidence: []
  }, globalThis.__compactProofBubble);
`, context);
assert(
  elements.get('modelStatus').textContent === 'fallback: evidence' &&
    elements.get('streamStatus').textContent === 'fallback: evidence',
  `fallback evidence final should use a readable rail label instead of raw pseudo-models: model=${elements.get('modelStatus').textContent} stream=${elements.get('streamStatus').textContent}`
);
assert(
  compactProofBubble.dataset.compactExternalProofDiagnostics === 'suppressed',
  `compact external-proof answer should carry scoped diagnostic suppression: ${JSON.stringify(compactProofBubble.dataset)}`
);
assert(
  priorCompactProofDebug.removed !== true && chatWindow.children.includes(priorCompactProofDebug),
  'compact external-proof suppression must not remove diagnostics from an earlier assistant turn'
);
assert(
  compactProofDebug.removed === true &&
    compactProofEvidence.removed === true &&
    !chatWindow.children.includes(compactProofDebug) &&
    !chatWindow.children.includes(compactProofEvidence),
  'compact external-proof suppression should remove only diagnostics immediately following the current assistant bubble'
);
const compactProofChildrenAfterFinal = chatWindow.children.length;
vm.runInContext(`
  renderChatEvent({
    type: 'debug_fx',
    debugFxSignal: {
      phase: 'final',
      code: 'ok',
      labels: {
        chatHarmonyDegraded: 'true',
        chatHarmonyDecision: 'fallback_evidence',
        debugAiMatrixCount: '300',
        debugAiMatrixChunkCount: '30',
        debugAiMatrixDecision: 'observe',
        supabaseStatus: 'WARN',
        browserStatus: 'OK',
        computerUseStatus: 'OK'
      }
    }
  }, globalThis.__compactProofBubble);
`, context);
assert(
  chatWindow.children.length === compactProofChildrenAfterFinal,
  'compact external-proof answers should suppress later per-message debug_fx diagnostics'
);
vm.runInContext('clearDirectLiteralDiagnosticsSuppression()', context);
assert(
  !Object.prototype.hasOwnProperty.call(compactProofBubble.dataset, 'compactExternalProofDiagnostics'),
  'starting the next send should clear compact external-proof suppression markers'
);

vm.runInContext("renderChatEvent({ sessionId: 84 }, null, 'session');", context);
const eventNameFallbackSession = dispatchedEvents.filter((event) => event.type === 'brain-state:session').at(-1);
  assert(
    eventNameFallbackSession?.detail?.sessionId === 84,
    `SSE event name fallback did not classify session payload: ${JSON.stringify(eventNameFallbackSession)}`
  );

(async () => {
  vm.runInContext(`
    imageJobUiModule = {
      renderImageJobCard(target, job = {}) {
        const card = document.createElement('section');
        card.dataset.imageJobDebug = 'true';
        card.jobUpdates = [job];
        target?.appendChild?.(card);
        return card;
      },
      updateImageJobCard(card, job = {}) {
        card.jobUpdates = card.jobUpdates || [];
        card.jobUpdates.push(job);
        card.dataset.status = job.status || 'pending';
        card.textContent = [job.status, job.reason, job.content].filter(Boolean).join(' ');
        return card;
      },
      attachImageJobDebug() {
        throw new Error('disabled image preflight should not poll a job');
      },
      async attachImageJobConfigDebug(card) {
        card.configChecked = true;
        return {
          "openai.image.enabled": false,
          imageServiceAvailable: false,
          disabledReason: "image plugin disabled",
          nextAction: "image plugin disabled"
        };
      }
    };
  `, context);

  fetchCalls.length = 0;
  const emptyImageAssistant = fakeElement('empty-image-assistant');
  const emptyImageParent = fakeElement('empty-image-parent');
  emptyImageAssistant.parentElement = emptyImageParent;
  const emptyImageError = await vm.runInContext(
    "submitImageJob('/image', globalThis.__emptyImageAssistant).catch((error) => error)",
    Object.assign(context, { __emptyImageAssistant: emptyImageAssistant })
  );
  assert(emptyImageError?.imageJobHandled === true, 'empty image prompt should be handled locally');
  assert(emptyImageError.reason === 'image_prompt_required', `empty image prompt should keep a count-only reason: ${emptyImageError.reason}`);
  assert(
    fetchCalls.length === 0,
    `empty image prompt should not call image job or diagnostics APIs: ${JSON.stringify(fetchCalls)}`
  );
  assert(
    emptyImageAssistant.textContent.includes('Image job unavailable: image_prompt_required'),
    `empty image prompt should render an actionable assistant message: ${emptyImageAssistant.textContent}`
  );

  fetchCalls.length = 0;
  elements.get('messageInput').value = '/image';
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  assert(
    elements.get('messageInput').value === '',
    `handled image prompt failure should clear the composer to avoid repeat local failures: ${JSON.stringify(elements.get('messageInput').value)}`
  );
  assert(
    !fetchCalls.some((call) => String(call.url).endsWith('/api/chat/stream') || String(call.url).endsWith('/api/chat')),
    `handled image prompt failure should not fall through to chat APIs: ${JSON.stringify(fetchCalls)}`
  );

  fetchCalls.length = 0;
  context.window.sessionStorage.clear();
  vm.runInContext('state.currentSessionId = null; activeSessionId = null; activeRunToken = null; streamCancelInFlight = null; streamController = null; activeStreamAssistant = null;', context);
  chatWindow.children = [];
  chatWindow.textContent = '';
  const koreanEmptyImageChildrenBefore = chatWindow.children.length;
  elements.get('messageInput').value = '\uC774\uBBF8\uC9C0 \uB9CC\uB4E4\uC5B4\uC918';
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const koreanEmptyImageChildren = chatWindow.children.slice(koreanEmptyImageChildrenBefore);
  const koreanEmptyImageText = koreanEmptyImageChildren.map(nodeText).join('|');
  assert(
    koreanEmptyImageText.includes('\uC774\uBBF8\uC9C0 \uB9CC\uB4E4\uC5B4\uC918'),
    `handled Korean image prompt should leave the user turn visible: ${koreanEmptyImageText}`
  );
  assert(
    koreanEmptyImageText.includes('Image job unavailable: image_prompt_required'),
    `handled Korean image prompt should leave an assistant diagnostic: ${koreanEmptyImageText}`
  );
  assert(
    koreanEmptyImageText.includes('image_prompt_required'),
    `handled Korean image prompt should leave image-card/debug evidence: ${koreanEmptyImageText}`
  );
  assert(
    elements.get('messageInput').value === '',
    `handled Korean image prompt failure should clear the composer: ${JSON.stringify(elements.get('messageInput').value)}`
  );
  assert(
    !fetchCalls.some((call) => String(call.url).endsWith('/api/chat/stream') || String(call.url).endsWith('/api/chat')),
    `handled Korean image prompt failure should not fall through to chat APIs: ${JSON.stringify(fetchCalls)}`
  );
  assert(
    !fetchCalls.some((call) => call.url === '/api/image-plugin/jobs'),
    `empty Korean image prompt should not POST a side-effect image job: ${JSON.stringify(fetchCalls)}`
  );
  const koreanEmptyImageCleanup = vm.runInContext('clearOrphanedSessionlessTranscript()', context);
  assert(
    koreanEmptyImageCleanup === false,
    `completed local Korean image diagnostic should not be cleared as an orphaned sessionless transcript: ${koreanEmptyImageCleanup}`
  );
  assert(
    chatWindow.children.slice(koreanEmptyImageChildrenBefore).map(nodeText).join('|').includes('Image job unavailable: image_prompt_required'),
    'completed local Korean image diagnostic should remain visible after orphan cleanup'
  );

  fetchCalls.length = 0;
  const disabledImageAssistant = fakeElement('disabled-image-assistant');
  const disabledImageParent = fakeElement('disabled-image-parent');
  disabledImageAssistant.parentElement = disabledImageParent;
  const disabledImageError = await vm.runInContext(
    "submitImageJob('/image blue robot icon', globalThis.__disabledImageAssistant).catch((error) => error)",
    Object.assign(context, { __disabledImageAssistant: disabledImageAssistant })
  );
  assert(disabledImageError?.imageJobHandled === true, 'disabled image service should be handled locally');
  assert(
    disabledImageError.reason === 'image plugin disabled',
    `disabled image service should report the config disabledReason: ${disabledImageError.reason}`
  );
  assert(
    !fetchCalls.some((call) => call.url === '/api/image-plugin/jobs'),
    `disabled image service should not POST a side-effect image job: ${JSON.stringify(fetchCalls)}`
  );
  assert(
    disabledImageAssistant.textContent.includes('Image job unavailable: image plugin disabled'),
    `disabled image service should render the diagnostics reason: ${disabledImageAssistant.textContent}`
  );

  fetchCalls.length = 0;
  elements.get('coreStatusRail').dataset.coreStatus = 'streaming';
  elements.get('streamStatus').textContent = 'connecting';
  elements.get('messageInput').value = '/image blue robot icon';
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  assert(
    elements.get('messageInput').value === '',
    `handled disabled image command should clear the composer: ${JSON.stringify(elements.get('messageInput').value)}`
  );
  assert(
    !fetchCalls.some((call) => String(call.url).endsWith('/api/chat/stream') || String(call.url).endsWith('/api/chat')),
    `handled disabled image command should not fall through to chat APIs: ${JSON.stringify(fetchCalls)}`
  );
  assert(
    !fetchCalls.some((call) => call.url === '/api/image-plugin/jobs'),
    `handled disabled image command should not POST a side-effect image job: ${JSON.stringify(fetchCalls)}`
  );
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'fallback',
    `handled disabled image command should not mark core chat runtime as error: ${elements.get('coreStatusRail').dataset.coreStatus}`
  );
  assert(
    elements.get('streamStatus').textContent === 'image plugin disabled',
    `handled disabled image command should keep the config reason visible: ${elements.get('streamStatus').textContent}`
  );

  fetchCalls.length = 0;
  vm.runInContext('imageJobUiModule = null;', context);
  const missingImageUiAssistant = fakeElement('missing-image-ui-assistant');
  const missingImageUiParent = fakeElement('missing-image-ui-parent');
  missingImageUiAssistant.parentElement = missingImageUiParent;
  const missingImageUiError = await vm.runInContext(
    "submitImageJob('/image', globalThis.__missingImageUiAssistant).catch((error) => error)",
    Object.assign(context, { __missingImageUiAssistant: missingImageUiAssistant })
  );
  assert(
    missingImageUiError?.imageJobHandled === true,
    `missing image UI module should be handled locally, not fall through to chat: ${missingImageUiError?.message}`
  );
  assert(
    !fetchCalls.some((call) => String(call.url).endsWith('/api/chat/stream') || String(call.url).endsWith('/api/chat')),
    `missing image UI module should not call chat APIs: ${JSON.stringify(fetchCalls)}`
  );
  assert(
    missingImageUiAssistant.textContent.includes('Image job unavailable: image_prompt_required'),
    `missing image UI module should still render an actionable image message: ${missingImageUiAssistant.textContent}`
  );

  const eofAssistant = fakeElement('assistant-eof');
  elements.set('assistant-eof', eofAssistant);
  context.__streamMode = 'eof-session-only';
  elements.get('coreStatusRail').dataset.coreStatus = 'streaming';
  elements.get('streamStatus').textContent = 'connecting';
  await vm.runInContext("streamChat({ message: 'eof-only' }, 'assistant-eof')", context);
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'error',
    `stream EOF without final should not look complete: ${elements.get('coreStatusRail').dataset.coreStatus}`
  );
  assert(
    elements.get('streamStatus').textContent === 'empty_stream_eof' &&
      eofAssistant.textContent.includes('Stream ended before an answer') &&
      !eofAssistant.textContent.includes('No response received before the stream ended'),
    `stream EOF without final should expose an empty-stream diagnostic: status=${elements.get('streamStatus').textContent} text=${eofAssistant.textContent}`
  );
  assert(
    elements.get('healthStatus').dataset.status === 'warn' &&
      elements.get('healthStatus').textContent === 'Needs attention / external proof supporting' &&
      elements.get('healthStatus').title.includes('state:attention') &&
      elements.get('healthStatus').title.includes('stream:empty_stream_eof'),
    `stream EOF without final must replace responding health with terminal attention: status=${elements.get('healthStatus').dataset.status} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
  );

  const completeNoFinalAssistant = fakeElement('assistant-complete-no-final');
  elements.set('assistant-complete-no-final', completeNoFinalAssistant);
  context.__streamMode = 'status-complete-no-final';
  elements.get('coreStatusRail').dataset.coreStatus = 'streaming';
  elements.get('streamStatus').textContent = 'connecting';
  await vm.runInContext("streamChat({ message: 'complete without final' }, 'assistant-complete-no-final')", context);
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'error' &&
      elements.get('streamStatus').textContent === 'empty_stream_eof' &&
      completeNoFinalAssistant.textContent.includes('Stream ended before an answer'),
    `status-complete EOF without final should not become a fake done state: core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent} text=${completeNoFinalAssistant.textContent}`
  );

  const headerSessionAssistant = fakeElement('assistant-header-session');
  elements.set('assistant-header-session', headerSessionAssistant);
  context.__streamMode = 'header-session-only';
  vm.runInContext('state.currentSessionId = null;', context);
  await vm.runInContext("streamChat({ message: 'header session only' }, 'assistant-header-session')", context);
  assert(
    vm.runInContext('state.currentSessionId', context) === null &&
      elements.get('modelStatus').textContent === 'header-model' &&
      elements.get('ragStatus').textContent === 'OFF' &&
      elements.get('traceStatus').textContent === 'trace-header-222',
    `stream headers must not replace canonical SSE session identity: session=${vm.runInContext('state.currentSessionId', context)} model=${elements.get('modelStatus').textContent} rag=${elements.get('ragStatus').textContent} trace=${elements.get('traceStatus').textContent}`
  );

  const localSafeStreamAssistant = fakeElement('assistant-local-safe-stream');
  elements.set('assistant-local-safe-stream', localSafeStreamAssistant);
  context.__streamMode = 'local-safe-token';
  await vm.runInContext("streamChat({ message: 'local safe stream' }, 'assistant-local-safe-stream')", context);
  vm.runInContext(`
    renderDebugHeartbeat({
      debugOverview: [
        { name: 'coreRuntime', status: 'OK', detail: 'promptBuilder=present' },
        { name: 'uiDebug', status: 'OK', detail: 'chat-ui=served' },
        { name: 'externalEvidence', status: 'WARN', detail: 'supporting evidence pending' }
      ],
      modelRuntime: { status: 'OK', route: 'unknown', deliveryState: 'unknown' },
      answerOutput: { status: 'OK', answerMode: 'none' },
      liveStream: { status: 'OK', currentState: 'completed' },
      localLlmSmoke: { status: 'OK', stale: false },
      pipelineReadiness: { status: 'OK', localReady: true, completionReady: true, patchDropStatus: 'OK', producerEvidenceRows: [] },
      externalProof: {
        browser: { status: 'OK', detail: 'scope:local-ui-proof' },
        computer: { status: 'OK', detail: 'scope:count-only' },
        supabase: { status: 'WARN', detail: 'scope:read-only needed:project/auth unverified' }
      },
      goalNext: { decision: 'evidence_needed', nextAction: 'none', firstAction: 'none', sourceHealthExit: 0, completionAuditExit: 0, localPatchJustified: true }
    });
  `, context);
  assert(
    heartbeatFields.get('model').dataset.status === 'WARN' &&
      heartbeatFields.get('answer').dataset.status === 'WARN' &&
      matrixCells.get('model-answer').dataset.status === 'warn' &&
      heartbeatFields.get('model').getAttribute('aria-label')?.includes('model_unavailable'),
    `SSE token local-safe fallback should keep model/answer WARN after heartbeat: model=${heartbeatFields.get('model').getAttribute('aria-label')} answer=${heartbeatFields.get('answer').getAttribute('aria-label')} matrix=${matrixCells.get('model-answer').getAttribute('aria-label')}`
  );

  for (const terminalState of ['ready', 'error', 'stopped']) {
    const terminalId = `assistant-stale-${terminalState}`;
    const terminalAssistant = fakeElement(terminalId);
    elements.set(terminalId, terminalAssistant);
    vm.runInContext(`
      markAssistantClientWait(document.getElementById('${terminalId}'), 65000);
      setMessageContent(document.getElementById('${terminalId}'), 'assistant', 'terminal answer', '${terminalState}');
    `, context);
    assert(
      terminalAssistant.dataset.waitMs == null && !String(terminalAssistant.title || '').includes('pending'),
      `terminal assistant must clear stale wait metadata: state=${terminalState} wait=${terminalAssistant.dataset.waitMs} title=${terminalAssistant.title}`
    );
  }

  const staleAssistant = fakeElement('assistant-stale');
  elements.set('assistant-stale', staleAssistant);
  context.__streamMode = 'open-no-data';
  context.__nowMs = 0;
  elements.get('coreStatusRail').dataset.coreStatus = 'streaming';
  const staleStream = vm.runInContext("streamChat({ message: 'slow local model', model: 'gemma4:26b' }, 'assistant-stale')", context);
  await new Promise((resolve) => setImmediate(resolve));
  context.__nowMs = 65000;
  context.__tickLatestInterval();
  assert(
    elements.get('streamStatus').textContent.includes('client-wait:65000ms') &&
      elements.get('streamStatus').textContent.includes('next:stop_or_wait') &&
      heartbeatFields.get('liveStream').small.textContent.includes('stream:model_wait') &&
      staleAssistant.dataset.state === 'pending' &&
      staleAssistant.dataset.waitMs === '65000' &&
      staleAssistant.getAttribute('aria-label')?.includes('client-wait:65000ms') &&
      staleAssistant.textContent.includes('Response still pending'),
    `open SSE without data should expose stale model wait without fake completion or a blank bubble: stream=${elements.get('streamStatus').textContent} state=${staleAssistant.dataset.state} wait=${staleAssistant.dataset.waitMs} label=${staleAssistant.getAttribute('aria-label')} text=${staleAssistant.textContent}`
  );
  assert(
    elements.get('healthStatus').dataset.status === 'warn' &&
      elements.get('healthStatus').textContent === 'Response pending / external proof supporting' &&
      elements.get('healthStatus').title.includes('state:pending') &&
      elements.get('healthStatus').title.includes('stream:model_wait') &&
      elements.get('healthStatus').title.includes('client-wait:65000ms'),
    `stale client wait must override server Live OK: status=${elements.get('healthStatus').dataset.status} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
  );
  vm.runInContext("setServerStatusRailHealth('OK', 'live:OK core:OK ui:OK model:OK answer:OK proof:SUPPORTING external:SUPPORTING');", context);
  assert(
    elements.get('healthStatus').textContent === 'Response pending / external proof supporting',
    `server heartbeat must not overwrite an active wait overlay: ${elements.get('healthStatus').textContent}`
  );
  const originalHeartbeatFetch = context.window.fetch;
  context.window.fetch = async () => {
    throw new Error('heartbeat_probe_failed');
  };
  try {
    await vm.runInContext('refreshDebugHeartbeat()', context);
    assert(
      elements.get('healthStatus').dataset.status === 'warn' &&
        elements.get('healthStatus').textContent === 'Response pending / external proof supporting' &&
        elements.get('healthStatus').title.includes('state:pending'),
      `heartbeat failure must not overwrite a pending turn overlay: status=${elements.get('healthStatus').dataset.status} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
    );
  } finally {
    context.window.fetch = originalHeartbeatFetch;
  }
  vm.runInContext("rememberActiveRunIdentity(99, 'run-99-slow'); state.currentSessionId = 99;", context);
  await elements.get('stopBtn').click();
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  context.__nowMs = 90000;
  context.__tickLatestInterval();
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'stopped' &&
      !elements.get('streamStatus').textContent.includes('client-wait:90000ms'),
    `cancelled stream heartbeat should not overwrite stopped UI state: core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent}`
  );
  assert(
    elements.get('healthStatus').dataset.status === 'ok' &&
      elements.get('healthStatus').textContent === 'Response stopped / external proof supporting' &&
      elements.get('healthStatus').title.includes('state:stopped') &&
      elements.get('healthStatus').title.includes('stream:cancelled') &&
      !elements.get('healthStatus').textContent.includes('Model failed'),
    `successful Stop must be a truthful non-failure terminal state: status=${elements.get('healthStatus').dataset.status} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
  );
  vm.runInContext("setServerStatusRailHealth('OK', 'live:OK core:OK ui:OK model:OK answer:OK proof:SUPPORTING external:SUPPORTING');", context);
  assert(
    elements.get('healthStatus').textContent === 'Response stopped / external proof supporting',
    `server heartbeat must not revive a stopped turn as Live OK: ${elements.get('healthStatus').textContent}`
  );
  context.window.fetch = async () => {
    throw new Error('heartbeat_probe_failed');
  };
  try {
    await vm.runInContext('refreshDebugHeartbeat()', context);
    assert(
      elements.get('healthStatus').dataset.status === 'ok' &&
        elements.get('healthStatus').textContent === 'Response stopped / external proof supporting' &&
        elements.get('healthStatus').title.includes('state:stopped'),
      `heartbeat failure must not overwrite a stopped turn overlay: status=${elements.get('healthStatus').dataset.status} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
    );
  } finally {
    context.window.fetch = originalHeartbeatFetch;
  }

  const deadlineAssistant = fakeElement('assistant-deadline');
  elements.set('assistant-deadline', deadlineAssistant);
  context.__streamMode = 'open-no-data';
  context.__nowMs = 0;
  context.__cancelOrder = [];
  elements.get('coreStatusRail').dataset.coreStatus = 'streaming';
  vm.runInContext("streamCancelRequested = false; streamRenderSuppressed = false;", context);
  const deadlineStream = vm.runInContext("streamChat({ message: 'slow unavailable local model', model: 'qwen3:8b' }, 'assistant-deadline')", context);
  deadlineStream.catch(() => {});
  await new Promise((resolve) => setImmediate(resolve));
  context.__nowMs = 95000;
  context.__tickLatestInterval();
  await new Promise((resolve) => setImmediate(resolve));
  assert(
    !context.__cancelOrder.includes('abort') &&
      deadlineAssistant.dataset.state === 'pending' &&
      deadlineAssistant.textContent.includes('Response still pending') &&
      elements.get('coreStatusRail').dataset.coreStatus === 'streaming' &&
      elements.get('streamStatus').textContent.includes('client-wait:95000ms') &&
      elements.get('streamStatus').textContent.includes('next:stop_or_wait'),
    `client wait should keep an open stream pending instead of fabricating Stop: order=${context.__cancelOrder.join('|')} state=${deadlineAssistant.dataset.state} text=${deadlineAssistant.textContent} core=${elements.get('coreStatusRail').dataset.coreStatus} stream=${elements.get('streamStatus').textContent}`
  );
  vm.runInContext("clearActiveStreamHeartbeat(); activeStreamAssistant = null; streamController = null; streamCancelRequested = false; streamRenderSuppressed = false;", context);
  chatWindow.children = chatWindow.children.filter((child) => child !== deadlineAssistant);
  const failSoftDeadlineMs = vm.runInContext("streamClientDeadlineMs({ message: 'slow unavailable local model' })", context);
  const evidenceDeadlineMs = vm.runInContext("streamClientDeadlineMs({ message: 'slow evidence stream', useRag: true, searchMode: 'FORCE_DEEP' })", context);
  const evidenceServerBudgetMs = vm.runInContext("streamServerBudgetMs({ useRag: true, searchMode: 'FORCE_DEEP' })", context);
  assert(
    failSoftDeadlineMs === null &&
      evidenceServerBudgetMs === 120000 &&
      evidenceDeadlineMs === null,
    `client deadline should be disabled for chat streams; stale wait remains visible and user-controlled: failSoft=${failSoftDeadlineMs} server=${evidenceServerBudgetMs} evidence=${evidenceDeadlineMs}`
  );

  context.__streamMode = 'fallback';
  fetchCalls.length = 0;

  vm.runInContext("state.currentSessionId = null;", context);
  const finalOnlyAssistant = fakeElement('assistant-final-only');
  chatWindow.appendChild(finalOnlyAssistant);
  context.__finalOnlyAssistant = finalOnlyAssistant;
  vm.runInContext(
    "renderChatEvent({ type: 'final', data: 'complete answer from final replay', sessionId: 123, modelUsed: 'stream-model', answerMode: 'streamed', evidence: [] }, globalThis.__finalOnlyAssistant);",
    context
  );
  assert(
    nodeText(finalOnlyAssistant).includes('complete answer from final replay'),
    `stream final event should restore its complete answer when token replay is incomplete: ${nodeText(finalOnlyAssistant)}`
  );
  const finalOnlySessionAfter = vm.runInContext('state.currentSessionId', context);
  assert(
    finalOnlySessionAfter === 123,
    `stream final event should persist sessionId for the next turn even without a prior session event: ${finalOnlySessionAfter}`
  );
  assert(
    context.window.sessionStorage.getItem('chat.currentSessionId') === '123',
    `stream final event should persist current session for reload continuity: ${context.window.sessionStorage.getItem('chat.currentSessionId')}`
  );
  assert(
    sessionModeList.children.some((child) => child.textContent.startsWith('session:123 streamed ')),
    `stream final event should render a session mode row: ${sessionModeList.children.map((child) => child.textContent).join('|')}`
  );

  vm.runInContext(`
    setServerStatusRailHealth('OK', 'live:OK core:OK ui:OK model:OK answer:OK proof:SUPPORTING external:SUPPORTING');
    renderLiveDebugHeartbeat({ streamStatus: 'streaming', streamContext: 'client-wait:1000ms' });
  `, context);
  const fallbackEvidenceFinalAssistant = fakeElement('assistant-fallback-evidence-final');
  chatWindow.appendChild(fallbackEvidenceFinalAssistant);
  context.__fallbackEvidenceFinalAssistant = fallbackEvidenceFinalAssistant;
  vm.runInContext(
    "renderChatEvent({ type: 'final', data: 'official evidence answer', sessionId: 124, modelUsed: 'fallback:evidence', answerMode: 'FALLBACK_EVIDENCE', evidence: [] }, globalThis.__fallbackEvidenceFinalAssistant);",
    context
  );
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'done' &&
      !elements.get('healthStatus').title.includes('state:responding') &&
      elements.get('healthStatus').textContent === 'Live OK / external proof supporting',
    `fallback-evidence final must clear the responding overlay: core=${elements.get('coreStatusRail').dataset.coreStatus} text=${elements.get('healthStatus').textContent} raw=${elements.get('healthStatus').title}`
  );

  context.window.sessionStorage.clear();
  vm.runInContext("state.currentSessionId = null; localControlOverrideActive = false;", context);
  chatWindow.children = [];
  chatWindow.textContent = '';
  const orphanedReloadUser = fakeElement('orphaned-reload-user');
  orphanedReloadUser.className = 'message user';
  orphanedReloadUser.dataset = { speaker: 'user', state: 'ready' };
  orphanedReloadUser.textContent = quickPromptButton.dataset.q;
  chatWindow.appendChild(orphanedReloadUser);
  const orphanedReloadCleared = vm.runInContext('clearOrphanedSessionlessTranscript()', context);
  assert(
    orphanedReloadCleared === true &&
      chatWindow.children.length === 0 &&
      chatWindow.textContent === '' &&
      vm.runInContext('state.currentSessionId', context) === null,
    `sessionless reload should clear stale user-only transcript before hydration: cleared=${orphanedReloadCleared} children=${chatWindow.children.length} text=${chatWindow.textContent} session=${vm.runInContext('state.currentSessionId', context)}`
  );

  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  context.window.sessionStorage.setItem('answerMode:321', 'streamed');
  vm.runInContext("state.currentSessionId = null; restoreCurrentSessionId();", context);
  const restoredSessionAfterReload = vm.runInContext('state.currentSessionId', context);
  assert(
    restoredSessionAfterReload === 321,
    `reload should restore current sessionId from sessionStorage: ${restoredSessionAfterReload}`
  );
  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  elements.get('modelSelect').value = 'gemma4:26b';
  elements.get('searchModeSelect').value = 'AUTO';
  elements.get('useRagToggle').checked = true;
  const hydrateChildrenBefore = chatWindow.children.length;
  context.window.location.search = '?codexSmoke=contract&debug=true';
  transitionDebugCalls.length = 0;
  vm.runInContext(`
    chatTransitionDebugState.records.length = 0;
    chatTransitionDebugState.lastByKind = Object.create(null);
    chatTransitionDebugState.lastSignatureByKind = Object.create(null);
    globalThis.__selectionHydrationClearCount = 0;
    globalThis.__originalSelectionHydrationClear = clearSelectionEntropyTrace;
    clearSelectionEntropyTrace = function (scope) {
      globalThis.__selectionHydrationClearCount += 1;
      return globalThis.__originalSelectionHydrationClear(scope);
    };
  `, context);
  focusedElement = fakeDocumentBody;
  await vm.runInContext('hydrateRestoredSessionTranscript()', context);
  const restoredSessionActiveElement = focusedElement;
  const reloadTransitionRecord = vm.runInContext(
    "JSON.parse(JSON.stringify(chatTransitionDebugState.records.find((record) => record.kind === 'reset' && record.resetReason === 'reload'))) ",
    context
  );
  const acceptedHydrationClearCount = vm.runInContext('globalThis.__selectionHydrationClearCount', context);
  vm.runInContext(`
    clearSelectionEntropyTrace = globalThis.__originalSelectionHydrationClear;
    delete globalThis.__originalSelectionHydrationClear;
    delete globalThis.__selectionHydrationClearCount;
  `, context);
  const hydrateCall = fetchCalls.find((call) => String(call.url).startsWith('/api/chat/sessions/321'));
  const hydratedChildren = chatWindow.children.slice(hydrateChildrenBefore);
  assert(hydrateCall, `reload hydration should fetch restored session detail once: ${JSON.stringify(fetchCalls)}`);
  assert(
    reloadTransitionRecord.turnId === 'session:321' &&
      reloadTransitionRecord.to === 'idle' &&
      reloadTransitionRecord.elapsedMs === 0,
    `restored-session hydration must expose one bounded reload reset transition: ${JSON.stringify(reloadTransitionRecord)}`
  );
  context.window.location.search = '?codexSmoke=contract';
  transitionDebugCalls.length = 0;
  vm.runInContext(`
    chatTransitionDebugState.records.length = 0;
    chatTransitionDebugState.lastByKind = Object.create(null);
    chatTransitionDebugState.lastSignatureByKind = Object.create(null);
  `, context);
  assert(
    hydratedChildren.length === 2 &&
      acceptedHydrationClearCount === 1 &&
      hydratedChildren[0].dataset.speaker === 'user' &&
      hydratedChildren[0].textContent === 'restored user turn' &&
      hydratedChildren[1].dataset.speaker === 'assistant' &&
      hydratedChildren[1].dataset.state === 'stopped' &&
      hydratedChildren[1].textContent === 'Response stopped' &&
      hydratedChildren[1].getAttribute('aria-label') === 'Assistant: Response stopped',
    `reload hydration should restore stopped assistant state: ${hydratedChildren.map((child) => `${child.dataset.speaker}:${child.dataset.state}:${child.textContent}:${child.getAttribute('aria-label')}`).join('|')}`
  );
  assert(
    restoredSessionActiveElement === elements.get('messageInput'),
    `successful reload hydration should restore composer focus: active=${restoredSessionActiveElement?.id || 'none'}`
  );
  elements.get('modelSelect').focus();
  const userSelectedFocusPreserved = vm.runInContext('focusRestoredComposerIfDocumentOwned()', context);
  assert(
    userSelectedFocusPreserved === false && focusedElement === elements.get('modelSelect'),
    `reload focus restoration must not steal a user-selected control: result=${userSelectedFocusPreserved} active=${focusedElement?.id || 'none'}`
  );
  assert(
    vm.runInContext('currentTurnHealthOverlay.kind', context) === 'stopped' &&
      elements.get('healthStatus').textContent === 'Response stopped / external proof supporting' &&
      decisionStage('answer').dataset.state === 'observed-cancelled' &&
      decisionStage('recover').dataset.state === 'observed-cancelled' &&
      elements.get('diagnosticsSummary').dataset.diagnosticCode === 'stopped' &&
      elements.get('diagnosticsSummary').textContent === 'Response stopped · user cancelled',
    `reload hydration must project the terminal stopped assistant into current-session Health, Decision, and Diagnostics: overlay=${vm.runInContext('currentTurnHealthOverlay.kind', context)} health=${elements.get('healthStatus').textContent} answer=${decisionStage('answer').dataset.state} recover=${decisionStage('recover').dataset.state} diagnostic=${elements.get('diagnosticsSummary').dataset.diagnosticCode}/${elements.get('diagnosticsSummary').textContent}`
  );
  const historicalStoppedMessage = fakeElement('historical-stopped-message');
  historicalStoppedMessage.dataset = { speaker: 'assistant', state: 'stopped' };
  const terminalReadyMessage = fakeElement('terminal-ready-message');
  terminalReadyMessage.dataset = { speaker: 'assistant', state: 'ready' };
  context.__historicalStoppedMessages = [historicalStoppedMessage, terminalReadyMessage];
  const historicalStoppedPromoted = vm.runInContext(`
    resetCurrentTurnHealthOverlay();
    renderDecisionRibbon(presentObservedDecision({ streamStatus: 'idle', healthOverlay: currentTurnHealthOverlay }));
    renderPrimaryDiagnostic(selectPrimaryDiagnostic({
      healthOverlay: currentTurnHealthOverlay,
      streamStatus: 'idle',
      isStopAvailable: false
    }));
    applyRestoredTerminalStoppedState({ id: 321 }, globalThis.__historicalStoppedMessages);
  `, context);
  assert(
    historicalStoppedPromoted === false &&
      vm.runInContext('currentTurnHealthOverlay.kind', context) === 'none' &&
      decisionStage('answer').dataset.state === 'not-observed' &&
      decisionStage('recover').dataset.state === 'not-observed' &&
      elements.get('diagnosticsSummary').dataset.diagnosticCode === 'not-observed',
    `a historical non-terminal stopped message must not become the current session state: promoted=${historicalStoppedPromoted} overlay=${vm.runInContext('currentTurnHealthOverlay.kind', context)} answer=${decisionStage('answer').dataset.state} recover=${decisionStage('recover').dataset.state} diagnostic=${elements.get('diagnosticsSummary').dataset.diagnosticCode}`
  );
  delete context.__historicalStoppedMessages;
  assert(
    elements.get('modelSelect').value === 'gemma3:4b' &&
      elements.get('searchModeSelect').value === 'OFF' &&
      elements.get('useRagToggle').checked === false,
    `reload hydration should restore saved controls before next turn: model=${elements.get('modelSelect').value} search=${elements.get('searchModeSelect').value} rag=${elements.get('useRagToggle').checked}`
  );
  assert(
    sessionModeList.children.some((child) => child.textContent === 'session:321 streamed -'),
    `reload hydration should restore persisted session mode diagnostics: ${sessionModeList.children.map((child) => child.textContent).join('|')}`
  );

  const restoreIdentityPriorControls = {
    model: elements.get('modelSelect').value,
    searchMode: elements.get('searchModeSelect').value,
    useRag: elements.get('useRagToggle').checked,
    stored: context.window.sessionStorage.getItem('chat.controlSettings')
  };
  const restoreIdentityCases = [
    { label: 'detail-id-mismatch', identity: { id: 42 } },
    { label: 'missing-detail-id', identity: {} }
  ];
  for (const restoreIdentityCase of restoreIdentityCases) {
    fetchCalls.length = 0;
    chatWindow.children = [];
    chatWindow.textContent = '';
    const restoreIdentitySelectionAssistant = fakeElement(`selection-restore-${restoreIdentityCase.label}`);
    chatWindow.appendChild(restoreIdentitySelectionAssistant);
    const restoreIdentitySelectionCard = installSelectionEntropyCard(restoreIdentitySelectionAssistant);
    assert(restoreIdentitySelectionCard,
      `${restoreIdentityCase.label} precondition must install a selection card`);
    sessionModeList.children = [];
    sessionModeList.textContent = '';
    elements.get('modelSelect').value = 'gemma4:26b';
    elements.get('searchModeSelect').value = 'AUTO';
    elements.get('useRagToggle').checked = true;
    vm.runInContext('syncControlStatus({ persist: false });', context);
    elements.get('traceStatus').textContent = 'restore identity baseline';
    const restoreIdentityControlsBefore = JSON.stringify({
      model: 'gemma4:26b',
      searchMode: 'AUTO',
      useRag: true,
      source: 'identity-baseline'
    });
    context.window.sessionStorage.setItem('chat.controlSettings', restoreIdentityControlsBefore);
    vm.runInContext(
      "rememberCurrentSessionId(321); restoredSessionHydrated = false; localControlOverrideActive = false; rememberActiveRunIdentity(321, 'run-321-identity');",
      context
    );
    const restoreIdentityStorageBefore = JSON.stringify(Array.from(sessionStorageBacking.entries()).sort());
    const restoreIdentityControlSurfaceBefore = JSON.stringify({
      model: elements.get('modelSelect').value,
      modelStatus: elements.get('modelStatus').textContent,
      searchMode: elements.get('searchModeSelect').value,
      searchStatus: elements.get('searchStatus').textContent,
      useRag: elements.get('useRagToggle').checked,
      ragStatus: elements.get('ragStatus').textContent
    });
    const restoreIdentityComposerBefore = JSON.stringify({
      inputDisabled: elements.get('messageInput').disabled,
      sendDisabled: elements.get('sendBtn').disabled,
      stopDisabled: elements.get('stopBtn').disabled
    });
    const restoreIdentitySentinel = `PRIVATE_CROSS_SESSION_TRANSCRIPT_SENTINEL_${restoreIdentityCase.label}`;
    context.__sessionHydrationDetail = {
      found: true,
      ...restoreIdentityCase.identity,
      title: 'mismatched restored chat',
      messages: [{ id: 42, role: 'assistant', content: restoreIdentitySentinel }],
      modelUsed: 'gemma3:4b',
      answerMode: 'mismatched',
      traceTurnId: 'trace-mismatched',
      settings: { model: 'gemma3:4b', searchMode: 'OFF', useRag: false }
    };
    const mismatchedRestoreResult = await vm.runInContext('hydrateRestoredSessionTranscript()', context);
    await new Promise((resolve) => setImmediate(resolve));
    const mismatchedRestoreCalls = fetchCalls.slice();
    delete context.__sessionHydrationDetail;
    const restoreIdentityStorageAfter = JSON.stringify(Array.from(sessionStorageBacking.entries()).sort());
    const restoreIdentityControlSurfaceAfter = JSON.stringify({
      model: elements.get('modelSelect').value,
      modelStatus: elements.get('modelStatus').textContent,
      searchMode: elements.get('searchModeSelect').value,
      searchStatus: elements.get('searchStatus').textContent,
      useRag: elements.get('useRagToggle').checked,
      ragStatus: elements.get('ragStatus').textContent
    });
    const restoreIdentityComposerAfter = JSON.stringify({
      inputDisabled: elements.get('messageInput').disabled,
      sendDisabled: elements.get('sendBtn').disabled,
      stopDisabled: elements.get('stopBtn').disabled
    });
    assert(
      mismatchedRestoreResult === false &&
        vm.runInContext('state.currentSessionId', context) === 321 &&
        restoreIdentityStorageAfter === restoreIdentityStorageBefore,
      `${restoreIdentityCase.label} must preserve session, storage, and active-run identity: result=${mismatchedRestoreResult} session=${vm.runInContext('state.currentSessionId', context)} before=${restoreIdentityStorageBefore} after=${restoreIdentityStorageAfter}`
    );
    assert(
      chatWindow.children.length === 1 &&
        restoreIdentitySelectionAssistant.querySelector('[data-selection-entropy-card]') === restoreIdentitySelectionCard &&
        !nodeText(chatWindow).includes(restoreIdentitySentinel) &&
        sessionModeList.children.length === 0 &&
        !nodeText(sessionModeList).includes('42'),
      `${restoreIdentityCase.label} must preserve the prior selection card without rendering transcript or mode state: children=${chatWindow.children.length} sentinel=${nodeText(chatWindow).includes(restoreIdentitySentinel)} modeRows=${sessionModeList.children.length} messages=${nodeText(chatWindow)} modes=${nodeText(sessionModeList)}`
    );
    assert(
      restoreIdentityControlSurfaceAfter === restoreIdentityControlSurfaceBefore &&
        restoreIdentityComposerAfter === restoreIdentityComposerBefore &&
        elements.get('traceStatus').textContent === 'session restore unavailable',
      `${restoreIdentityCase.label} must preserve controls and expose only the fixed safe status: controls=${restoreIdentityControlSurfaceAfter} composer=${restoreIdentityComposerAfter} trace=${elements.get('traceStatus').textContent}`
    );
    assert(
      !mismatchedRestoreCalls.some((call) => String(call.url).includes('sessionId=42') || String(call.url).includes('/stream?attach=true')),
      `${restoreIdentityCase.label} must never resume the returned session identity: ${mismatchedRestoreCalls.map((call) => call.url).join('|')}`
    );
  }

  fetchCalls.length = 0;
  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  elements.get('modelSelect').value = 'gemma4:26b';
  elements.get('searchModeSelect').value = 'AUTO';
  elements.get('useRagToggle').checked = true;
  vm.runInContext('syncControlStatus({ persist: false });', context);
  elements.get('traceStatus').textContent = 'restore identity alias baseline';
  context.window.sessionStorage.setItem('chat.controlSettings', JSON.stringify({
    model: 'gemma4:26b',
    searchMode: 'AUTO',
    useRag: true,
    source: 'identity-alias-baseline'
  }));
  vm.runInContext(
    'clearActiveRunIdentity(); rememberCurrentSessionId(321); restoredSessionHydrated = false; localControlOverrideActive = false;',
    context
  );
  const restoreIdentityAliasSentinel = 'PRIVATE_CANONICAL_SESSION_TRANSCRIPT_SENTINEL';
  context.__sessionHydrationDetail = {
    found: true,
    id: 321,
    sessionId: 42,
    title: 'canonical restored chat',
    messages: [{ id: 321, role: 'assistant', content: restoreIdentityAliasSentinel }],
    modelUsed: 'gemma3:4b',
    answerMode: 'canonical',
    traceTurnId: 'trace-canonical',
    settings: { model: 'gemma3:4b', searchMode: 'OFF', useRag: false }
  };
  const aliasedRestoreResult = await vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  const aliasedRestoreCalls = fetchCalls.slice();
  delete context.__sessionHydrationDetail;
  assert(
    aliasedRestoreResult === true &&
      vm.runInContext('state.currentSessionId', context) === 321 &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === '321' &&
      context.window.sessionStorage.getItem('chat.activeRun') === null,
    `matching canonical detail id must ignore a conflicting sessionId alias: result=${aliasedRestoreResult} session=${vm.runInContext('state.currentSessionId', context)} stored=${context.window.sessionStorage.getItem('chat.currentSessionId')} active=${context.window.sessionStorage.getItem('chat.activeRun')}`
  );
  assert(
    chatWindow.children.length === 1 &&
      nodeText(chatWindow).includes(restoreIdentityAliasSentinel) &&
      sessionModeList.children.some((child) => child.textContent === 'session:321 canonical trace-canonical') &&
      !nodeText(sessionModeList).includes('session:42'),
    `matching canonical detail id must render only session 321 state: messages=${nodeText(chatWindow)} modes=${nodeText(sessionModeList)}`
  );
  assert(
    elements.get('modelSelect').value === 'gemma3:4b' &&
      elements.get('searchModeSelect').value === 'OFF' &&
      elements.get('useRagToggle').checked === false &&
      !aliasedRestoreCalls.some((call) => String(call.url).includes('sessionId=42') || String(call.url).includes('/stream?attach=true')),
    `matching canonical detail id must never adopt or resume alias 42: controls=${elements.get('modelSelect').value}/${elements.get('searchModeSelect').value}/${elements.get('useRagToggle').checked} calls=${aliasedRestoreCalls.map((call) => call.url).join('|')}`
  );
  elements.get('modelSelect').value = restoreIdentityPriorControls.model;
  elements.get('searchModeSelect').value = restoreIdentityPriorControls.searchMode;
  elements.get('useRagToggle').checked = restoreIdentityPriorControls.useRag;
  if (restoreIdentityPriorControls.stored === null) {
    context.window.sessionStorage.removeItem('chat.controlSettings');
  } else {
    context.window.sessionStorage.setItem('chat.controlSettings', restoreIdentityPriorControls.stored);
  }
  vm.runInContext('syncControlStatus({ persist: false });', context);
  vm.runInContext('clearActiveRunIdentity(); restoredSessionHydrated = false;', context);

  fetchCalls.length = 0;
  context.__streamMode = 'resume-replay';
  context.__runStateResponse = {
    running: true,
    runStatus: 'running',
    attachable: true,
    terminal: false
  };
  vm.runInContext(
    "state.currentSessionId = 322; rememberActiveRunIdentity(322, 'run-322-token');",
    context
  );
  const resumed = await vm.runInContext('resumeStoredRunIfNeeded(322)', context);
  const resumeStateCall = fetchCalls.find((call) => call.url.includes('/api/chat/state?sessionId=322'));
  const resumeStreamCall = fetchCalls.find((call) => call.url.includes('/api/chat/stream?attach=true'));
  assert(
    resumed === true && resumeStateCall && resumeStreamCall,
    `reload should state-check then exact-attach without new generation: resumed=${resumed} calls=${JSON.stringify(fetchCalls)}`
  );
  assert(
    String(fetchHeader(resumeStateCall, 'x-chat-run-token')) === 'run-322-token' &&
      String(fetchHeader(resumeStreamCall, 'x-chat-run-token')) === 'run-322-token' &&
      JSON.parse(resumeStreamCall.body || '{}').attach === true,
    `reload exact attach should carry the same opaque run token: state=${JSON.stringify(resumeStateCall?.headers)} stream=${JSON.stringify(resumeStreamCall?.headers)} body=${resumeStreamCall?.body}`
  );
  assert(
    context.window.sessionStorage.getItem('chat.activeRun') === null,
    `terminal replay should clear the active-run capability: ${context.window.sessionStorage.getItem('chat.activeRun')}`
  );

  fetchCalls.length = 0;
  context.__deferRunStateResponse = true;
  vm.runInContext(`
    restoredRunResumeInFlight = null;
    activeStreamAssistant = null;
    state.currentSessionId = 701;
    rememberActiveRunIdentity(701, 'run-r1');
  `, context);
  const staleR1Resume = vm.runInContext('resumeStoredRunIfNeeded(701)', context);
  await new Promise((resolve) => setImmediate(resolve));
  vm.runInContext("rememberActiveRunIdentity(701, 'run-r2');", context);
  context.__resolveRunStateResponse({
    running: true,
    runStatus: 'running',
    attachable: true,
    terminal: false
  });
  const staleR1Result = await staleR1Resume;
  const storedR2 = JSON.parse(context.window.sessionStorage.getItem('chat.activeRun') || 'null');
  const staleStateCall = fetchCalls.find((call) => call.url.includes('/api/chat/state?sessionId=701'));
  assert(
    staleR1Result === false &&
      vm.runInContext('activeRunToken', context) === 'run-r2' &&
      storedR2?.sessionId === 701 && storedR2?.runToken === 'run-r2' &&
      String(fetchHeader(staleStateCall, 'x-chat-run-token')) === 'run-r1' &&
      !fetchCalls.some((call) => call.url.includes('/api/chat/stream?attach=true')),
    `stale R1 state must not clear or attach over R2: result=${staleR1Result} active=${vm.runInContext('activeRunToken', context)} stored=${JSON.stringify(storedR2)} calls=${fetchCalls.map((call) => call.url).join('|')}`
  );
  context.__deferRunStateResponse = false;

  for (const [failure, sid, token] of [
    ['eof', 711, 'run-711-eof'],
    ['error', 712, 'run-712-error']
  ]) {
    fetchCalls.length = 0;
    context.__streamMode = `known-token-transport-${failure}`;
    context.__knownTokenSessionId = sid;
    context.__knownTokenRunToken = token;
    context.__runStateResponse = {
      running: true,
      runStatus: 'running',
      attachable: true,
      terminal: false
    };
    vm.runInContext(`state.currentSessionId = ${sid}; clearActiveRunIdentity();`, context);
    elements.get('messageInput').value = `known token ${failure}`;
    await vm.runInContext('sendMessage()', context);

    const normalStream = fetchCalls.find((call) => call.url === '/api/chat/stream');
    const stateCall = fetchCalls.find((call) => call.url === `/api/chat/state?sessionId=${sid}`);
    const exactAttach = fetchCalls.find((call) => call.url === '/api/chat/stream?attach=true');
    const acknowledgement = fetchCalls.find((call) => call.url === '/api/chat/ack');
    assert(
      normalStream && stateCall && exactAttach &&
        !fetchCalls.some((call) => call.url === '/api/chat') &&
        fetchCalls.indexOf(normalStream) < fetchCalls.indexOf(stateCall) &&
        fetchCalls.indexOf(stateCall) < fetchCalls.indexOf(exactAttach),
      `known-token ${failure} must recover only through state then exact attach: ${fetchCalls.map((call) => call.url).join('|')}`
    );
    assert(
      String(fetchHeader(stateCall, 'x-chat-run-token')) === token &&
        String(fetchHeader(exactAttach, 'x-chat-run-token')) === token &&
        String(fetchHeader(normalStream, 'x-chat-run-ack-required')) === '1' &&
        String(fetchHeader(acknowledgement, 'x-chat-run-token')) === token &&
        JSON.parse(exactAttach.body || '{}').attach === true &&
        JSON.parse(exactAttach.body || '{}').runToken === token,
      `known-token ${failure} recovery must preserve one exact token: state=${JSON.stringify(stateCall?.headers)} attach=${JSON.stringify(exactAttach?.headers)} body=${exactAttach?.body}`
    );
  }

  fetchCalls.length = 0;
  context.__streamMode = 'known-token-prefix-replay';
  context.__knownTokenSessionId = 713;
  context.__knownTokenRunToken = 'run-713-prefix';
  context.__runStateResponse = {
    running: true,
    runStatus: 'running',
    attachable: true,
    terminal: false
  };
  vm.runInContext('state.currentSessionId = 713; clearActiveRunIdentity();', context);
  const prefixChildrenBefore = chatWindow.children.length;
  elements.get('messageInput').value = 'known token partial prefix';
  const prefixRecoverySend = vm.runInContext('sendMessage()', context);
  for (let attempt = 0; attempt < 10 && !context.__resolvePrefixReplayTail; attempt += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
  const prefixAssistant = chatWindow.children.slice(prefixChildrenBefore)
    .find((child) => child?.dataset?.speaker === 'assistant');
  const prefixReplayText = nodeText(prefixAssistant);
  assert(
    prefixReplayText === 'prefix',
    `exact replay must rebuild rather than duplicate the rendered prefix: ${prefixReplayText}`
  );
  context.__resolvePrefixReplayTail?.();
  await prefixRecoverySend;

  context.__runStateResponse = null;
  context.__streamMode = 'fallback';
  vm.runInContext("rememberCurrentSessionId(321); clearActiveRunIdentity();", context);
  fetchCalls.length = 0;
  elements.get('messageInput').value = 'reload continuity next turn question';
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const reloadStreamCall = fetchCalls.find((call) => call.url.includes('/api/chat/stream'));
  const reloadStreamBody = JSON.parse(reloadStreamCall?.body || '{}');
  assert(
    reloadStreamBody.sessionId === 321,
    `next-turn stream payload should preserve restored sessionId 321 after reload: ${reloadStreamCall?.body}`
  );
  assert(
    fetchHeader(reloadStreamCall, 'accept') === 'text/event-stream' &&
      /^req-|^[0-9a-f-]{36}$/i.test(String(fetchHeader(reloadStreamCall, 'x-request-id') || '')) &&
      String(fetchHeader(reloadStreamCall, 'x-session-id')) === '321',
    `stream call should carry SSE accept plus request/session correlation headers: ${JSON.stringify(reloadStreamCall?.headers)}`
  );
  assert(
    reloadStreamBody.model === 'gemma3:4b' &&
      reloadStreamBody.searchMode === 'OFF' &&
      reloadStreamBody.useWebSearch === false &&
      reloadStreamBody.useRag === false,
    `next-turn stream payload should preserve restored controls after reload: ${reloadStreamCall?.body}`
  );
  assert(
    !Object.prototype.hasOwnProperty.call(reloadStreamBody, 'useVerification'),
    `stream payload should leave verification policy to the server default: ${reloadStreamCall?.body}`
  );

  elements.get('modelSelect').value = 'qwen3:30b';
  elements.get('searchModeSelect').value = 'FORCE_DEEP';
  elements.get('useRagToggle').checked = true;
  elements.get('modelSelect').listeners.change();
  elements.get('searchModeSelect').listeners.change();
  elements.get('useRagToggle').listeners.change();
  elements.get('modelSelect').value = 'gemma4:26b';
  elements.get('searchModeSelect').value = 'AUTO';
  elements.get('useRagToggle').checked = false;
  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  vm.runInContext('state.currentSessionId = 321; restoredSessionHydrated = false; restoreStoredControlSettings();', context);
  await vm.runInContext('hydrateRestoredSessionTranscript()', context);
  assert(
    elements.get('modelSelect').value === 'qwen3:30b' &&
      elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
      elements.get('useRagToggle').checked === true &&
      elements.get('searchStatus').textContent === 'DEEP' &&
      elements.get('ragStatus').textContent === 'ON',
    `user-updated controls should survive reload hydration: model=${elements.get('modelSelect').value} search=${elements.get('searchModeSelect').value} rag=${elements.get('useRagToggle').checked} rail=${elements.get('searchStatus').textContent}/${elements.get('ragStatus').textContent}`
  );

  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  vm.runInContext('state.currentSessionId = 321; restoredSessionHydrated = false; localControlOverrideActive = false;', context);
  context.__deferSessionHydration = true;
  const staleHydration = vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  assert(
    typeof context.__resolveSessionHydration === 'function',
    'deferred hydration fixture should capture the pending restored-session response'
  );
  const newChatStartedDuringHydration = vm.runInContext('startNewChatSession()', context);
  elements.get('modelSelect').value = 'qwen3:30b';
  elements.get('searchModeSelect').value = 'FORCE_DEEP';
  elements.get('useRagToggle').checked = true;
  context.__resolveSessionHydration();
  await staleHydration;
  context.__deferSessionHydration = false;
  delete context.__resolveSessionHydration;
  assert(newChatStartedDuringHydration === true, 'new chat should start while restored-session hydration is pending');
  assert(
    vm.runInContext('state.currentSessionId', context) === null &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === null &&
      chatWindow.children.length === 0 &&
      sessionModeList.children.length === 0 &&
      elements.get('modelSelect').value === 'qwen3:30b' &&
      elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
      elements.get('useRagToggle').checked === true,
    `late restored-session hydration must not resurrect a discarded chat: session=${vm.runInContext('state.currentSessionId', context)} stored=${context.window.sessionStorage.getItem('chat.currentSessionId')} messages=${chatWindow.children.length} modes=${sessionModeList.children.length} controls=${elements.get('modelSelect').value}/${elements.get('searchModeSelect').value}/${elements.get('useRagToggle').checked}`
  );

  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  vm.runInContext('state.currentSessionId = 321; restoredSessionHydrated = false; localControlOverrideActive = false;', context);
  context.__deferSessionHydration = true;
  context.__deferSessionHydrationStatus = 200;
  const supersededHydration = vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  vm.runInContext('rememberCurrentSessionId(654)', context);
  const newerSessionMessage = fakeElement('newer-session-message');
  newerSessionMessage.className = 'message user';
  newerSessionMessage.dataset = { speaker: 'user', state: 'ready' };
  newerSessionMessage.textContent = 'newer session turn';
  chatWindow.appendChild(newerSessionMessage);
  const newerSessionSelectionCard = installSelectionEntropyCard(newerSessionMessage);
  assert(newerSessionSelectionCard, 'stale hydration precondition must install a newer-session selection card');
  elements.get('modelSelect').value = 'qwen3:30b';
  elements.get('searchModeSelect').value = 'FORCE_DEEP';
  elements.get('useRagToggle').checked = true;
  context.__resolveSessionHydration();
  await supersededHydration;
  assert(
    vm.runInContext('state.currentSessionId', context) === 654 &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === '654' &&
      chatWindow.children.length === 1 &&
      chatWindow.children[0].textContent === 'newer session turn' &&
      newerSessionMessage.querySelector('[data-selection-entropy-card]') === newerSessionSelectionCard &&
      !sessionModeList.children.some((child) => child.textContent.startsWith('session:321 ')) &&
      elements.get('modelSelect').value === 'qwen3:30b' &&
      elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
      elements.get('useRagToggle').checked === true,
    `late hydration must not replace a newer active session: session=${vm.runInContext('state.currentSessionId', context)} stored=${context.window.sessionStorage.getItem('chat.currentSessionId')} messages=${chatWindow.children.map((child) => child.textContent).join('|')} modes=${sessionModeList.children.map((child) => child.textContent).join('|')} controls=${elements.get('modelSelect').value}/${elements.get('searchModeSelect').value}/${elements.get('useRagToggle').checked}`
  );

  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  vm.runInContext('state.currentSessionId = 321; restoredSessionHydrated = false;', context);
  context.__deferSessionHydrationStatus = 404;
  const supersededNotFoundHydration = vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  vm.runInContext("rememberCurrentSessionId(655); setStatusRailValue(dom.traceStatus, 'newer session ready');", context);
  context.__resolveSessionHydration();
  await supersededNotFoundHydration;
  context.__deferSessionHydration = false;
  context.__deferSessionHydrationStatus = null;
  delete context.__resolveSessionHydration;
  assert(
    vm.runInContext('state.currentSessionId', context) === 655 &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === '655' &&
      elements.get('traceStatus').textContent === 'newer session ready',
    `late 404 hydration must not clear or relabel a newer session: session=${vm.runInContext('state.currentSessionId', context)} stored=${context.window.sessionStorage.getItem('chat.currentSessionId')} trace=${elements.get('traceStatus').textContent}`
  );

  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  vm.runInContext('state.currentSessionId = 321; restoredSessionHydrated = false; localControlOverrideActive = false;', context);
  context.__deferSessionHydration = true;
  context.__deferSessionHydrationStatus = 200;
  const sameSessionHydration = vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  const sameSessionLiveTurn = fakeElement('same-session-live-turn');
  sameSessionLiveTurn.className = 'message user';
  sameSessionLiveTurn.dataset = { speaker: 'user', state: 'ready' };
  sameSessionLiveTurn.textContent = 'same session live turn';
  chatWindow.appendChild(sameSessionLiveTurn);
  elements.get('modelSelect').value = 'qwen3:30b';
  elements.get('searchModeSelect').value = 'FORCE_DEEP';
  elements.get('useRagToggle').checked = true;
  context.__resolveSessionHydration();
  await sameSessionHydration;
  assert(
    vm.runInContext('state.currentSessionId', context) === 321 &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === '321' &&
      chatWindow.children.length === 1 &&
      chatWindow.children[0].textContent === 'same session live turn' &&
      sessionModeList.children.length === 0 &&
      elements.get('modelSelect').value === 'qwen3:30b' &&
      elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
      elements.get('useRagToggle').checked === true,
    `late hydration must not mutate a same-session live turn: messages=${chatWindow.children.map((child) => child.textContent).join('|')} modes=${sessionModeList.children.map((child) => child.textContent).join('|')} controls=${elements.get('modelSelect').value}/${elements.get('searchModeSelect').value}/${elements.get('useRagToggle').checked}`
  );

  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  vm.runInContext('state.currentSessionId = 321; restoredSessionHydrated = false;', context);
  context.__deferSessionHydrationStatus = 404;
  const sameSessionNotFoundHydration = vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  const sameSessionNotFoundLiveTurn = fakeElement('same-session-not-found-live-turn');
  sameSessionNotFoundLiveTurn.textContent = 'same session survives 404';
  chatWindow.appendChild(sameSessionNotFoundLiveTurn);
  vm.runInContext("setStatusRailValue(dom.traceStatus, 'same session live');", context);
  context.__resolveSessionHydration();
  await sameSessionNotFoundHydration;
  assert(
    vm.runInContext('state.currentSessionId', context) === 321 &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === '321' &&
      chatWindow.children.length === 1 &&
      elements.get('traceStatus').textContent === 'same session live',
    `late same-session 404 must not clear the live turn: session=${vm.runInContext('state.currentSessionId', context)} stored=${context.window.sessionStorage.getItem('chat.currentSessionId')} messages=${chatWindow.children.map((child) => child.textContent).join('|')} trace=${elements.get('traceStatus').textContent}`
  );

  chatWindow.children = [];
  chatWindow.textContent = '';
  sessionModeList.children = [];
  sessionModeList.textContent = '';
  context.window.sessionStorage.setItem('chat.currentSessionId', '321');
  vm.runInContext('state.currentSessionId = 321; restoredSessionHydrated = false;', context);
  context.__deferSessionHydration = false;
  context.__deferSessionHydrationJson = true;
  const sameSessionJsonHydration = vm.runInContext('hydrateRestoredSessionTranscript()', context);
  await new Promise((resolve) => setImmediate(resolve));
  assert(typeof context.__resolveSessionHydrationJson === 'function', 'deferred hydration JSON fixture should pause response parsing');
  const sameSessionJsonLiveTurn = fakeElement('same-session-json-live-turn');
  sameSessionJsonLiveTurn.textContent = 'same session during json';
  chatWindow.appendChild(sameSessionJsonLiveTurn);
  elements.get('modelSelect').value = 'qwen3:30b';
  elements.get('searchModeSelect').value = 'FORCE_DEEP';
  elements.get('useRagToggle').checked = true;
  context.__resolveSessionHydrationJson();
  await sameSessionJsonHydration;
  context.__deferSessionHydrationJson = false;
  delete context.__resolveSessionHydrationJson;
  assert(
    chatWindow.children.length === 1 &&
      chatWindow.children[0].textContent === 'same session during json' &&
      sessionModeList.children.length === 0 &&
      elements.get('modelSelect').value === 'qwen3:30b' &&
      elements.get('searchModeSelect').value === 'FORCE_DEEP' &&
      elements.get('useRagToggle').checked === true,
    `late hydration JSON must not mutate a same-session live turn: messages=${chatWindow.children.map((child) => child.textContent).join('|')} modes=${sessionModeList.children.map((child) => child.textContent).join('|')} controls=${elements.get('modelSelect').value}/${elements.get('searchModeSelect').value}/${elements.get('useRagToggle').checked}`
  );
  context.__deferSessionHydrationStatus = null;
  delete context.__resolveSessionHydration;

  context.window.sessionStorage.clear();
  vm.runInContext("state.currentSessionId = 123;", context);
  fetchCalls.length = 0;
  elements.get('modelSelect').value = 'qwen3:8b';
  elements.get('searchModeSelect').value = 'OFF';
  elements.get('useRagToggle').checked = false;
  elements.get('messageInput').value = 'ordinary local model turn';
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const ordinaryStreamCall = fetchCalls.find((call) => call.url.includes('/api/chat/stream'));
  const ordinaryBudgetMs = Number(fetchHeader(ordinaryStreamCall, 'x-budget-ms') || 0);
  assert(
    ordinaryBudgetMs === 90000,
    `ordinary stream call should request the verified bounded server budget for a local model response: ${JSON.stringify(ordinaryStreamCall?.headers)}`
  );

  context.window.sessionStorage.clear();
  vm.runInContext("state.currentSessionId = 123;", context);
  fetchCalls.length = 0;
  elements.get('searchModeSelect').value = 'FORCE_DEEP';
  elements.get('useRagToggle').checked = true;
  elements.get('messageInput').value = 'final only next turn question';
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const finalOnlyStreamCall = fetchCalls.find((call) => call.url.includes('/api/chat/stream'));
  const finalOnlyStreamBody = JSON.parse(finalOnlyStreamCall?.body || '{}');
  assert(
    finalOnlyStreamBody.sessionId === 123,
    `next-turn stream payload should preserve final-only sessionId 123: ${finalOnlyStreamCall?.body}`
  );
  assert(
    String(fetchHeader(finalOnlyStreamCall, 'x-session-id')) === '123',
    `final-only stream call should preserve backend-proven x-session-id header: ${JSON.stringify(finalOnlyStreamCall?.headers)}`
  );
  assert(
    Number(fetchHeader(finalOnlyStreamCall, 'x-budget-ms') || 0) >= 30000,
    `evidence-heavy stream call should request enough server budget for web/RAG retrieval: ${JSON.stringify(finalOnlyStreamCall?.headers)}`
  );

  fetchCalls.length = 0;
  vm.runInContext("renderChatEvent({ type: 'session', sessionId: 42, data: 'run-42-token' }, null);", context);
  elements.get('stopBtn').click();
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  const cancelCall = fetchCalls.find((call) => call.url.includes('/api/chat/cancel'));
  assert(cancelCall, 'stop button did not call /api/chat/cancel after sessionReady');
  const cancelBody = JSON.parse(cancelCall.body || '{}');
  assert(
    cancelBody.sessionId === 42 && cancelBody.runToken === 'run-42-token',
    `cancel body did not carry the exact session/run pair: ${cancelCall.body}`
  );
  assert(
    String(fetchHeader(cancelCall, 'x-session-id')) === '42' &&
      String(fetchHeader(cancelCall, 'x-chat-run-token')) === 'run-42-token' &&
      /^req-|^[0-9a-f-]{36}$/i.test(String(fetchHeader(cancelCall, 'x-request-id') || '')),
    `cancel call should preserve session/request correlation headers: ${JSON.stringify(cancelCall.headers)}`
  );

  fetchCalls.length = 0;
  context.__streamMode = 'header-session-only';
  elements.get('messageInput').value = 'keyboard send question';
  elements.get('messageInput').listeners.input();
  let enterPrevented = false;
  const keyboardChildrenBefore = chatWindow.children.length;
  await elements.get('messageInput').listeners.keydown({
    key: 'Enter',
    shiftKey: false,
    ctrlKey: false,
    metaKey: false,
    altKey: false,
    preventDefault() {
      enterPrevented = true;
    }
  });
  assert(enterPrevented, 'plain Enter should prevent the textarea newline and submit');
  assert(
    fetchCalls.some((call) => call.url.includes('/api/chat/stream')),
    'plain Enter should use the same streaming send path as form submit'
  );
  const keyboardStreamCall = fetchCalls.find((call) => call.url.includes('/api/chat/stream'));
  const keyboardStreamBody = JSON.parse(keyboardStreamCall?.body || '{}');
  assert(
    keyboardStreamBody.sessionId === 42,
    `next-turn stream payload should preserve current sessionId 42: ${keyboardStreamCall?.body}`
  );
  assert(
    fetchHeader(keyboardStreamCall, 'accept') === 'text/event-stream' &&
      String(fetchHeader(keyboardStreamCall, 'x-session-id')) === '42' &&
      /^req-|^[0-9a-f-]{36}$/i.test(String(fetchHeader(keyboardStreamCall, 'x-request-id') || '')),
    `keyboard stream call should carry SSE accept plus current session/request headers: ${JSON.stringify(keyboardStreamCall?.headers)}`
  );
  assert(
    !fetchCalls.some((call) => call.url === '/api/chat'),
    `stream delivery must not start a duplicate sync generation: ${fetchCalls.map((call) => call.url).join('|')}`
  );
  assert(
    !Object.prototype.hasOwnProperty.call(keyboardStreamBody, 'useVerification'),
    `stream payload should preserve server-owned verification policy: stream=${keyboardStreamCall?.body}`
  );
  assert(
    chatWindow.children.slice(keyboardChildrenBefore).some((child) => child.textContent === 'keyboard send question'),
    'plain Enter should append the user draft through the normal chat flow'
  );
  fetchCalls.length = 0;
  elements.get('sendBtn').disabled = true;
  elements.get('messageInput').value = 'busy keyboard draft';
  let busyEnterPrevented = false;
  await elements.get('messageInput').listeners.keydown({
    key: 'Enter',
    shiftKey: false,
    ctrlKey: false,
    metaKey: false,
    altKey: false,
    preventDefault() {
      busyEnterPrevented = true;
    }
  });
  elements.get('sendBtn').disabled = false;
  assert(busyEnterPrevented && fetchCalls.length === 0, 'plain Enter while busy should be swallowed without a second send');

  fetchCalls.length = 0;
  context.__streamMode = 'fallback';
  vm.runInContext('clearActiveRunIdentity();', context);
  elements.get('messageInput').value = 'stream unavailable question';
  const failedStreamChildrenBefore = chatWindow.children.length;
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const streamCall = fetchCalls.find((call) => call.url.includes('/api/chat/stream'));
  assert(streamCall?.headers?.['X-CSRF-TOKEN'] === 'csrf-token-123', `stream POST did not include CSRF header: ${JSON.stringify(streamCall?.headers)}`);
  const failedStreamNodes = chatWindow.children.slice(failedStreamChildrenBefore);
  const failedStreamUser = failedStreamNodes.find((child) => child.textContent === 'stream unavailable question');
  const failedStreamAssistant = failedStreamNodes.find((child) => child.textContent === 'message_failed');
  assert(
    failedStreamAssistant && !fetchCalls.some((call) => call.url === '/api/chat'),
    `ambiguous stream failure must not start sync generation: ${fetchCalls.map((call) => call.url).join('|')}`
  );
  assert(
    failedStreamUser?.dataset?.speaker === 'user' &&
      failedStreamUser.getAttribute('data-testid') === 'chat-message' &&
      failedStreamUser.dataset?.messageRole === 'user' &&
      failedStreamUser.getAttribute('role') === 'article' &&
      failedStreamUser.getAttribute('aria-label')?.startsWith('User: stream unavailable question'),
    `failed-stream user bubble should remain accessible: label=${failedStreamUser?.getAttribute('aria-label')}`
  );
  assert(
    failedStreamAssistant?.dataset?.speaker === 'assistant' &&
      failedStreamAssistant.getAttribute('data-testid') === 'chat-message' &&
      failedStreamAssistant.dataset?.state === 'error',
    `failed-stream assistant must expose a safe terminal error: state=${failedStreamAssistant?.dataset?.state}`
  );

  fetchCalls.length = 0;
  context.__streamMode = 'empty-after-cancel';
  vm.runInContext('clearActiveRunIdentity(); pendingStopBeforeToken = null;', context);
  elements.get('messageInput').value = 'post cancel empty stream question';
  const emptyStreamChildrenBefore = chatWindow.children.length;
  const emptyStreamAnswerEventsBefore = dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length;
  const emptyStreamSyncRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat').length;
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const emptyStreamChildren = chatWindow.children.slice(emptyStreamChildrenBefore);
  const emptyStreamAssistant = emptyStreamChildren
    .find((child) => child?.dataset?.speaker === 'assistant');
  const emptyStreamAnswerEventsAfter = dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length;
  const emptyStreamSyncRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat').length;
  assert(
    emptyStreamAnswerEventsAfter === emptyStreamAnswerEventsBefore &&
      emptyStreamSyncRequestsAfter === emptyStreamSyncRequestsBefore &&
      emptyStreamAssistant?.dataset?.state === 'error' &&
      emptyStreamAssistant.textContent.includes('Stream ended before an answer'),
    `empty SSE must fail visibly without answer dispatch or duplicate sync generation: children=${emptyStreamChildren.length} answers=${emptyStreamAnswerEventsBefore}->${emptyStreamAnswerEventsAfter} sync=${emptyStreamSyncRequestsBefore}->${emptyStreamSyncRequestsAfter} calls=${fetchCalls.map((call) => call.url).join('|')} state=${emptyStreamAssistant?.dataset?.state} text=${emptyStreamAssistant?.textContent}`
  );

  fetchCalls.length = 0;
  context.__streamMode = 'error-late-terminal';
  context.__syncMode = 'ok';
  vm.runInContext("globalThis.__terminalOriginalDateNow = Date.now; Date.now = () => 424242;", context);
  elements.get('messageInput').value = 'terminal error race question';
  const terminalErrorChildrenBefore = chatWindow.children.length;
  const terminalErrorAnswerEventsBefore = dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length;
  const terminalErrorSyncRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat').length;
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const terminalErrorChildren = chatWindow.children.slice(terminalErrorChildrenBefore);
  const terminalErrorAssistant = terminalErrorChildren.find((child) => child?.dataset?.speaker === 'assistant');
  const terminalErrorAnswerEventsAfter = dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length;
  const terminalErrorSyncRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat').length;
  assert(
    terminalErrorAssistant?.dataset?.state === 'error' && terminalErrorAssistant.textContent === 'message_failed',
    `terminal SSE error should own the assistant bubble: state=${terminalErrorAssistant?.dataset?.state} text=${terminalErrorAssistant?.textContent} id=${terminalErrorAssistant?.id} idMatches=${findNodesById(chatWindow, terminalErrorAssistant?.id).length}`
  );
  assert(
    context.__terminalReadCount === 1 &&
      !terminalErrorChildren.some((child) => nodeText(child).includes('LATE_TOKEN_MUST_NOT_RENDER')) &&
      !terminalErrorChildren.some((child) => nodeText(child).includes('LATE_FINAL_MUST_NOT_RENDER')),
    `terminal SSE error should stop before late token/final: reads=${context.__terminalReadCount} text=${terminalErrorChildren.map(nodeText).join('|')}`
  );
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'error' &&
      terminalErrorAnswerEventsAfter === terminalErrorAnswerEventsBefore &&
      terminalErrorSyncRequestsAfter === terminalErrorSyncRequestsBefore,
    `stale post-terminal SSE events should preserve error state without answer dispatch or sync fallback: core=${elements.get('coreStatusRail').dataset.coreStatus} answers=${terminalErrorAnswerEventsBefore}->${terminalErrorAnswerEventsAfter} sync=${terminalErrorSyncRequestsBefore}->${terminalErrorSyncRequestsAfter} calls=${fetchCalls.map((call) => call.url).join('|')}`
  );
  fetchCalls.length = 0;
  context.__streamMode = 'error-same-chunk-final';
  elements.get('messageInput').value = 'same chunk terminal error question';
  const sameChunkTerminalChildrenBefore = chatWindow.children.length;
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const sameChunkTerminalChildren = chatWindow.children.slice(sameChunkTerminalChildrenBefore);
  const sameChunkTerminalAssistant = sameChunkTerminalChildren.find((child) => child?.dataset?.speaker === 'assistant');
  assert(
    sameChunkTerminalAssistant?.dataset?.state === 'error' &&
      sameChunkTerminalAssistant.textContent === 'message_failed' &&
      !sameChunkTerminalChildren.some((child) => nodeText(child).includes('SAME_CHUNK_FINAL_MUST_NOT_RENDER')),
    `same-chunk terminal SSE error must suppress a later final data line: state=${sameChunkTerminalAssistant?.dataset?.state} text=${sameChunkTerminalAssistant?.textContent} all=${sameChunkTerminalChildren.map(nodeText).join('|')}`
  );
  assert(
    context.__sameChunkTerminalReadCount === 1 &&
      elements.get('coreStatusRail').dataset.coreStatus === 'error' &&
      !fetchCalls.some((call) => call.url === '/api/chat'),
    `same-chunk terminal SSE error must remain terminal without sync fallback: reads=${context.__sameChunkTerminalReadCount} core=${elements.get('coreStatusRail').dataset.coreStatus} calls=${fetchCalls.map((call) => call.url).join('|')}`
  );
  context.__streamMode = 'error-late-terminal';
  elements.get('messageInput').value = 'terminal error same millisecond question';
  const sameMillisecondChildrenBefore = chatWindow.children.length;
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const sameMillisecondChildren = chatWindow.children.slice(sameMillisecondChildrenBefore);
  const sameMillisecondAssistant = sameMillisecondChildren.find((child) => child?.dataset?.speaker === 'assistant');
  assert(
    sameMillisecondAssistant?.dataset?.state === 'error' && sameMillisecondAssistant.textContent === 'message_failed',
    `same-millisecond terminal errors must keep distinct active assistants: state=${sameMillisecondAssistant?.dataset?.state} text=${sameMillisecondAssistant?.textContent} firstId=${terminalErrorAssistant?.id} secondId=${sameMillisecondAssistant?.id}`
  );
  assert(
    terminalErrorAssistant?.id !== sameMillisecondAssistant?.id,
    `assistant loader ids must be unique when Date.now is unchanged: firstId=${terminalErrorAssistant?.id} secondId=${sameMillisecondAssistant?.id}`
  );
  vm.runInContext("Date.now = globalThis.__terminalOriginalDateNow; delete globalThis.__terminalOriginalDateNow;", context);

  fetchCalls.length = 0;
  context.__streamMode = 'fallback';
  vm.runInContext("renderChatEvent({ type: 'session', sessionId: 42 }, null);", context);
  elements.get('messageInput').value = 'fresh turn missing session question';
  const missingSessionChildrenBefore = chatWindow.children.length;
  const missingSessionAnswerEventsBefore = dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length;
  const missingSessionStreamRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat/stream').length;
  const missingSessionSyncRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat').length;
  await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const missingSessionChildren = chatWindow.children.slice(missingSessionChildrenBefore);
  const missingSessionAssistants = missingSessionChildren.filter((child) => child?.dataset?.speaker === 'assistant');
  const missingSessionAnswerEventsAfter = dispatchedEvents.filter((event) => event.type === 'brain-state:answer');
  const missingSessionStreamRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat/stream');
  const missingSessionSyncRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat');
  assert(
    missingSessionAnswerEventsAfter.length === missingSessionAnswerEventsBefore &&
      missingSessionStreamRequestsAfter.length === missingSessionStreamRequestsBefore + 1 &&
      missingSessionSyncRequestsAfter.length === missingSessionSyncRequestsBefore &&
      missingSessionAssistants.length === 1 &&
      missingSessionAssistants[0]?.dataset?.state === 'error' &&
      missingSessionAssistants[0]?.textContent === 'message_failed' &&
      vm.runInContext('state.currentSessionId', context) === 42,
    `fresh stream failure must issue one stream request without reusing a stale answer or starting sync, and must preserve the seeded session: children=${missingSessionChildren.length} assistants=${missingSessionAssistants.length} answers=${missingSessionAnswerEventsBefore}->${missingSessionAnswerEventsAfter.length} stream=${missingSessionStreamRequestsBefore}->${missingSessionStreamRequestsAfter.length} sync=${missingSessionSyncRequestsBefore}->${missingSessionSyncRequestsAfter.length} session=${vm.runInContext('state.currentSessionId', context)}`
  );

  fetchCalls.length = 0;
  context.__streamMode = 'pending-final';
  elements.get('messageInput').value = 'single flight question';
  const singleFlightChildrenBefore = chatWindow.children.length;
  const freshSelectionCard = installSelectionEntropyCard(chatWindow);
  assert(freshSelectionCard, 'fresh-send precondition must install a prior selection card');
  const freshSelectionVisibleCount = vm.runInContext(
    "dom.chatMessages.querySelectorAll('[data-selection-entropy-card]').length",
    context
  );
  assert(freshSelectionVisibleCount === 1,
    `fresh-send precondition must expose one selection card to the transcript clear scope: ${freshSelectionVisibleCount}`);
  const firstSingleFlightSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  const secondSingleFlightSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  await new Promise((resolve) => setImmediate(resolve));
  assert(freshSelectionCard.parentElement === null,
    'fresh send must clear the prior selection entropy card before creating the next turn');
  const singleFlightCalls = fetchCalls.filter((call) => call.url.includes('/api/chat/stream'));
  const singleFlightChildren = chatWindow.children.slice(singleFlightChildrenBefore);
  assert(
    singleFlightCalls.length === 1 &&
      singleFlightChildren.filter((child) => child?.dataset?.speaker === 'user').length === 1 &&
      singleFlightChildren.filter((child) => child?.dataset?.speaker === 'assistant').length === 1,
    `same-tick duplicate send must create one request and one message pair: calls=${singleFlightCalls.length} users=${singleFlightChildren.filter((child) => child?.dataset?.speaker === 'user').length} assistants=${singleFlightChildren.filter((child) => child?.dataset?.speaker === 'assistant').length}`
  );
  context.__resolvePendingStream();
  await Promise.all([firstSingleFlightSend, secondSingleFlightSend]);

  context.__streamMode = 'pending-final';
  elements.get('messageInput').value = 'busy state question';
  const pendingSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  await new Promise((resolve) => setImmediate(resolve));
  const pendingAssistant = chatWindow.children.at(-1);
  assert(
    elements.get('messageInput').value === '',
    `send start should clear the submitted composer draft while preserving it for failure recovery: ${JSON.stringify(elements.get('messageInput').value)}`
  );
  assert(
    chatWindow.getAttribute('aria-busy') === 'true' &&
      elements.get('sendBtn').getAttribute('aria-label') === 'Sending message' &&
      elements.get('sendBtn').textContent === 'Sending' &&
      elements.get('stopBtn').getAttribute('aria-label') === 'Stop streaming response' &&
      elements.get('stopBtn').textContent === 'Stop' &&
      elements.get('stopBtn').hidden === false &&
      elements.get('stopBtn').style.display === '',
    `send start should expose composer busy state: busy=${chatWindow.getAttribute('aria-busy')} send=${elements.get('sendBtn').getAttribute('aria-label')}/${elements.get('sendBtn').textContent} stop=${elements.get('stopBtn').getAttribute('aria-label')}/${elements.get('stopBtn').textContent}/${elements.get('stopBtn').hidden}/${elements.get('stopBtn').style.display}`
  );
  assert(
    elements.get('diagnosticsSummary').dataset.diagnosticCode === 'responding' &&
      elements.get('diagnosticsSummary').textContent === 'Responding · current turn · Stop available',
    `send start may advertise only the visible enabled Stop control: ${elements.get('diagnosticsSummary').dataset.diagnosticCode}/${elements.get('diagnosticsSummary').textContent}`
  );
  assert(
    pendingAssistant?.dataset?.speaker === 'assistant' &&
      pendingAssistant?.dataset?.state === 'pending' &&
      pendingAssistant.getAttribute('role') === 'article' &&
      pendingAssistant.getAttribute('aria-label') === 'Assistant response pending',
    `pending assistant bubble should expose speaker and pending state: speaker=${pendingAssistant?.dataset?.speaker} state=${pendingAssistant?.dataset?.state} role=${pendingAssistant?.getAttribute('role')} label=${pendingAssistant?.getAttribute('aria-label')}`
  );
  context.__resolvePendingStream();
  await pendingSend;
  assert(
    chatWindow.getAttribute('aria-busy') === 'false' &&
      elements.get('sendBtn').getAttribute('aria-label') === 'Send message' &&
      elements.get('sendBtn').textContent === 'Send' &&
      elements.get('stopBtn').getAttribute('aria-label') === 'Stop response' &&
      elements.get('stopBtn').textContent === 'Stop' &&
      elements.get('stopBtn').hidden === true &&
      elements.get('stopBtn').style.display === 'none',
    `send completion should clear composer busy state: busy=${chatWindow.getAttribute('aria-busy')} send=${elements.get('sendBtn').getAttribute('aria-label')}/${elements.get('sendBtn').textContent} stop=${elements.get('stopBtn').getAttribute('aria-label')}/${elements.get('stopBtn').textContent}/${elements.get('stopBtn').hidden}/${elements.get('stopBtn').style.display}`
  );

  const attachSelectionAssistant = fakeElement('selection-attach-assistant');
  attachSelectionAssistant.className = 'message assistant';
  attachSelectionAssistant.dataset = { speaker: 'assistant', state: 'streaming' };
  elements.set('selection-attach-assistant', attachSelectionAssistant);
  const attachSelectionCard = installSelectionEntropyCard(attachSelectionAssistant);
  assert(attachSelectionCard, 'attach precondition must install a selection card');
  context.__streamMode = 'pending-final';
  const attachRequestBaseline = fetchCalls.length;
  const selectionAttach = vm.runInContext(
    "streamChat({ message: 'attach continuation', attach: true, sessionId: 711, runToken: 'run-711-selection' }, 'selection-attach-assistant')",
    context
  );
  await new Promise((resolve) => setImmediate(resolve));
  assert(
    attachSelectionAssistant.querySelector('[data-selection-entropy-card]') === attachSelectionCard &&
      fetchCalls.slice(attachRequestBaseline).some((call) => String(call.url).includes('attach=true')),
    'attach stream must retain the selection card while exact-run recovery is pending'
  );
  context.__resolvePendingStream();
  await selectionAttach;
  assert(
    attachSelectionAssistant.querySelector('[data-selection-entropy-card]') === attachSelectionCard &&
      attachSelectionAssistant.querySelectorAll('[data-selection-entropy-card]').length === 1,
    'attach final must preserve exactly one selection card until an explicit reset'
  );

  fetchCalls.length = 0;
  context.__cancelOrder = [];
  context.__cancelResponse = { cancelled: false, reason: 'run_not_found_or_not_cancellable' };
  context.__cancelFalseAssistant = fakeElement('cancel-false-assistant');
  vm.runInContext(`
    streamController = new AbortController();
    activeStreamAssistant = globalThis.__cancelFalseAssistant;
    streamCancelRequested = false;
    streamRenderSuppressed = false;
    rememberActiveRunIdentity(721, 'run-721-live');
    state.currentSessionId = 721;
  `, context);
  await vm.runInContext('cancelActiveStream()', context);
  const cancelFalseStored = JSON.parse(context.window.sessionStorage.getItem('chat.activeRun') || 'null');
  assert(
    vm.runInContext('streamController.signal.aborted', context) === false &&
      vm.runInContext('activeRunToken', context) === 'run-721-live' &&
      cancelFalseStored?.sessionId === 721 && cancelFalseStored?.runToken === 'run-721-live' &&
      vm.runInContext('!streamCancelRequested && !streamRenderSuppressed', context) &&
      !context.__cancelOrder.includes('abort') &&
      context.__cancelFalseAssistant.dataset?.state !== 'stopped',
    `cancelled:false must preserve capability, rendering, and controller: active=${vm.runInContext('activeRunToken', context)} stored=${JSON.stringify(cancelFalseStored)} aborted=${vm.runInContext('streamController.signal.aborted', context)} order=${context.__cancelOrder.join('|')}`
  );
  context.__cancelResponse = null;
  vm.runInContext('activeStreamAssistant = null; streamController = null; clearActiveRunIdentity();', context);

  fetchCalls.length = 0;
  context.__streamMode = 'deferred-session-token';
  context.__deferredSessionId = 731;
  context.__deferredRunToken = 'run-731-late';
  context.__cancelOrder = [];
  vm.runInContext(`
    state.currentSessionId = 731;
    clearActiveRunIdentity();
    streamCancelRequested = false;
    streamRenderSuppressed = false;
    pendingStopBeforeToken = null;
  `, context);
  elements.get('messageInput').value = 'stop before capability';
  const earlyStopSend = vm.runInContext('sendMessage()', context);
  await new Promise((resolve) => setImmediate(resolve));
  elements.get('stopBtn').click();
  await new Promise((resolve) => setImmediate(resolve));
  const keptControlBeforeToken = !context.__cancelOrder.includes('abort') &&
    !fetchCalls.some((call) => call.url === '/api/chat/cancel');
  context.__releaseDeferredSessionTokenStream();
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  const exactLateCancel = fetchCalls.find((call) => call.url === '/api/chat/cancel');
  const lateCancelBody = JSON.parse(exactLateCancel?.body || '{}');
  context.__resolveDeferredSessionTokenTail?.();
  await earlyStopSend;
  const lateCancelResponseIndex = context.__cancelOrder.indexOf('cancel-response');
  const lateAbortIndex = context.__cancelOrder.indexOf('abort');
  assert(
    keptControlBeforeToken &&
      lateCancelBody.sessionId === 731 && lateCancelBody.runToken === 'run-731-late' &&
      String(fetchHeader(exactLateCancel, 'x-chat-run-token')) === 'run-731-late' &&
      lateCancelResponseIndex >= 0 && lateAbortIndex > lateCancelResponseIndex &&
      !fetchCalls.some((call) => call.url === '/api/chat/ack') &&
      !fetchCalls.some((call) => call.url === '/api/chat'),
    `Stop before token must skip ACK, retain control, then exact-cancel before abort: kept=${keptControlBeforeToken} body=${exactLateCancel?.body} order=${context.__cancelOrder.join('|')} calls=${fetchCalls.map((call) => call.url).join('|')}`
  );

  const originalChatWindowQuerySelector = chatWindow.querySelector;
  chatWindow.querySelector = (selector) => {
    if (selector === '[data-role="transformer-core-rail"]') {
      return chatWindow.children.find((child) => child?.dataset?.role === 'transformer-core-rail') || null;
    }
    return originalChatWindowQuerySelector.call(chatWindow, selector);
  };
  const priorTransformerRail = vm.runInContext(
    "renderTransformerCoreRail(document.getElementById('chatWindow'), [{ label: 'Intake', status: 'done' }, { label: 'Stream', status: 'done' }], { status: 'done' })",
    context
  );
  const priorTransformerRailText = nodeText(priorTransformerRail);
  assert(
    priorTransformerRail.getAttribute('aria-hidden') === 'true' &&
      priorTransformerRail.getAttribute('role') === 'presentation',
    `transformer rail should be hidden from live-region announcements: hidden=${priorTransformerRail.getAttribute('aria-hidden')} role=${priorTransformerRail.getAttribute('role')}`
  );

  context.__streamMode = 'pending-final';
  context.__deferCancelResponse = true;
  context.__cancelOrder = [];
  elements.get('messageInput').value = 'stop state question';
  const stoppedSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  await new Promise((resolve) => setImmediate(resolve));
  const stoppedAssistant = chatWindow.children.at(-1);
  elements.get('traceStatus').textContent = 'transformer';
  vm.runInContext("activeSessionId = 42; activeRunToken = 'run-42-stop'; state.currentSessionId = 42;", context);
  elements.get('stopBtn').click();
  context.__rejectPendingStream();
  await stoppedSend;
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  const cancelResponseIndex = context.__cancelOrder.indexOf('cancel-response');
  const abortIndex = context.__cancelOrder.indexOf('abort');
  assert(
    cancelResponseIndex >= 0 && abortIndex > cancelResponseIndex,
    `stop should wait for server cancel response before aborting the stream: ${context.__cancelOrder.join('|')}`
  );
  context.__deferCancelResponse = false;
  assert(
    stoppedAssistant?.dataset?.speaker === 'assistant' &&
      stoppedAssistant?.dataset?.state === 'stopped' &&
      stoppedAssistant.textContent === 'Response stopped' &&
      stoppedAssistant.getAttribute('aria-label') === 'Assistant: Response stopped',
    `stopped assistant bubble should expose stopped state: speaker=${stoppedAssistant?.dataset?.speaker} state=${stoppedAssistant?.dataset?.state} text=${stoppedAssistant?.textContent} label=${stoppedAssistant?.getAttribute('aria-label')}`
  );
  assert(
    elements.get('traceStatus').textContent === 'cancelled',
    `stop should clear active transformer trace rail: trace=${elements.get('traceStatus').textContent}`
  );
  const stoppedRail = chatWindow.children.filter((child) => child?.dataset?.role === 'transformer-core-rail').at(-1);
  const stoppedRailText = nodeText(stoppedRail);
  const stoppedRails = chatWindow.children.filter((child) => child?.dataset?.role === 'transformer-core-rail');
  const stoppedAssistantIndex = chatWindow.children.indexOf(stoppedAssistant);
  const stoppedRailIndex = chatWindow.children.indexOf(stoppedRail);
  assert(
    stoppedRails.length >= 2 &&
      priorTransformerRail !== stoppedRail &&
      nodeText(priorTransformerRail) === priorTransformerRailText &&
      stoppedRailIndex > stoppedAssistantIndex,
    `stopped transformer rail should attach to the stopped assistant without overwriting prior rails: rails=${stoppedRails.length} prior=${nodeText(priorTransformerRail)} stopped=${stoppedRailText} assistantIndex=${stoppedAssistantIndex} railIndex=${stoppedRailIndex}`
  );
  assert(
    stoppedRailText.includes('Intake stopped') &&
      stoppedRailText.includes('Stream cancelled') &&
      stoppedRailText.includes('Model server-cancel') &&
      stoppedRailText.includes('Next none') &&
      !stoppedRailText.includes('Intakestopped'),
    `stopped transformer rail should keep readable label/value separators: ${stoppedRailText}`
  );
  chatWindow.querySelector = originalChatWindowQuerySelector;
  context.__deferCancelResponse = false;

  fetchCalls.length = 0;
  context.__streamMode = 'pending-final';
  context.__deferCancelResponse = true;
  context.__cancelOrder = [];
  elements.get('messageInput').value = 'stop late final race question';
  const lateFinalChildrenBefore = chatWindow.children.length;
  const lateFinalStoppedSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  await new Promise((resolve) => setImmediate(resolve));
  const lateFinalStoppedAssistant = chatWindow.children.slice(lateFinalChildrenBefore)
    .find((child) => child?.dataset?.speaker === 'assistant');
  vm.runInContext("activeSessionId = 44; activeRunToken = 'run-44-stop'; state.currentSessionId = 44;", context);
  elements.get('stopBtn').click();
  context.__resolvePendingStream();
  await lateFinalStoppedSend;
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert(
    lateFinalStoppedAssistant?.dataset?.speaker === 'assistant' &&
      lateFinalStoppedAssistant?.dataset?.state !== 'stopped' &&
      !lateFinalStoppedAssistant.textContent.includes('Response stopped'),
    `a cancel without an acknowledged win must not fabricate Stop: speaker=${lateFinalStoppedAssistant?.dataset?.speaker} state=${lateFinalStoppedAssistant?.dataset?.state} text=${lateFinalStoppedAssistant?.textContent}`
  );
  context.__deferCancelResponse = false;

  fetchCalls.length = 0;
  context.__streamMode = 'pending-final';
  context.__hangCancelResponse = true;
  context.__cancelOrder = [];
  context.__runStateResponse = {
    running: true,
    runStatus: 'running',
    attachable: true,
    terminal: false
  };
  elements.get('messageInput').value = 'stop hanging cancel question';
  const hangingCancelSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  await new Promise((resolve) => setImmediate(resolve));
  vm.runInContext("activeSessionId = 43; activeRunToken = 'run-43-stop'; state.currentSessionId = 43; streamCancelRequested = false; streamRenderSuppressed = false;", context);
  const timeoutIdsBeforeStop = new Set(intervalCallbacks.keys());
  elements.get('stopBtn').click();
  const cancelTimeoutId = [...intervalCallbacks.keys()].find((id) => !timeoutIdsBeforeStop.has(id));
  assert(cancelTimeoutId, 'server cancel should schedule a bounded timeout before waiting indefinitely');
  intervalCallbacks.get(cancelTimeoutId)?.();
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  const timeoutStateCall = fetchCalls.find((call) => call.url === '/api/chat/state?sessionId=43');
  assert(
    !context.__cancelOrder.includes('abort') &&
      vm.runInContext("activeRunToken === 'run-43-stop' && !streamCancelRequested && !streamRenderSuppressed", context) &&
      elements.get('stopBtn').disabled === false &&
      String(fetchHeader(timeoutStateCall, 'x-chat-run-token')) === 'run-43-stop',
    `hanging cancel timeout must state-check and preserve retryable Stop: order=${context.__cancelOrder.join('|')} token=${vm.runInContext('activeRunToken', context)} disabled=${elements.get('stopBtn').disabled} state=${timeoutStateCall?.url}`
  );
  context.__runStateResponse = {
    running: false,
    runStatus: 'missing_or_replaced',
    attachable: false,
    terminal: false
  };
  context.__rejectPendingStream();
  await hangingCancelSend;
  await new Promise((resolve) => setImmediate(resolve));
  assert(
    vm.runInContext("streamCancelInFlight === null", context),
    'hanging server cancel should clear streamCancelInFlight after timeout'
  );
  context.__hangCancelResponse = false;

  fetchCalls.length = 0;
  context.__streamMode = 'pending-final';
  context.__hangCancelResponse = true;
  context.__hangRunStateResponse = true;
  context.__cancelOrder = [];
  elements.get('messageInput').value = 'stop hanging cancel and state question';
  const hangingCancelAndStateSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  await new Promise((resolve) => setImmediate(resolve));
  vm.runInContext("activeSessionId = 45; activeRunToken = 'run-45-stop'; state.currentSessionId = 45; streamCancelRequested = false; streamRenderSuppressed = false;", context);
  const timersBeforeHangingStateStop = new Set(intervalCallbacks.keys());
  elements.get('stopBtn').click();
  const firstBoundedCancelTimer = [...intervalCallbacks.keys()]
    .find((id) => !timersBeforeHangingStateStop.has(id));
  assert(firstBoundedCancelTimer, 'the first exact cancel attempt must remain bounded');
  intervalCallbacks.get(firstBoundedCancelTimer)?.();
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  const boundedStateTimer = [...intervalCallbacks.keys()]
    .find((id) => !timersBeforeHangingStateStop.has(id) && id !== firstBoundedCancelTimer);
  assert(boundedStateTimer, 'the exact state reconciliation must also remain bounded');
  intervalCallbacks.get(boundedStateTimer)?.();
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert(
    vm.runInContext("streamCancelInFlight === null && activeRunToken === 'run-45-stop'", context) &&
      elements.get('stopBtn').disabled === false,
    `a hanging state check must release retry control: inFlight=${vm.runInContext('streamCancelInFlight !== null', context)} token=${vm.runInContext('activeRunToken', context)} disabled=${elements.get('stopBtn').disabled}`
  );
  const cancelAttemptsBeforeRetry = fetchCalls.filter((call) => call.url === '/api/chat/cancel').length;
  context.__hangCancelResponse = false;
  context.__hangRunStateResponse = false;
  context.__cancelResponse = { cancelled: true, reason: 'cancelled' };
  elements.get('stopBtn').click();
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  assert(
    fetchCalls.filter((call) => call.url === '/api/chat/cancel').length === cancelAttemptsBeforeRetry + 1 &&
      vm.runInContext('streamCancelInFlight === null', context),
    `a retryable Stop must issue a fresh exact cancel: calls=${fetchCalls.map((call) => call.url).join('|')} inFlight=${vm.runInContext('streamCancelInFlight !== null', context)}`
  );
  context.__cancelResponse = null;
  context.__rejectPendingStream();
  await hangingCancelAndStateSend;
  context.__hangRunStateResponse = false;

  fetchCalls.length = 0;
  context.__streamMode = 'pending-final';
  context.__syncMode = 'success';
  elements.get('messageInput').value = 'stop non-abort stream question';
  const stoppedNonAbortSend = vm.runInContext("sendMessage({ preventDefault() {} })", context);
  await new Promise((resolve) => setImmediate(resolve));
  const stoppedNonAbortAssistant = chatWindow.children.at(-1);
  elements.get('stopBtn').click();
  context.__rejectPendingStreamNonAbort();
  await stoppedNonAbortSend;
  assert(
    stoppedNonAbortAssistant?.dataset?.state === 'error' &&
      stoppedNonAbortAssistant.textContent === 'message_failed',
    `an unacknowledged tokenless Stop must report the transport failure, not fabricate cancellation: state=${stoppedNonAbortAssistant?.dataset?.state} text=${stoppedNonAbortAssistant?.textContent}`
  );
  assert(
    !fetchCalls.some((call) => String(call.url).endsWith('/api/chat')),
    `non-Abort stream failure after stop should not start sync fallback: ${fetchCalls.map((call) => call.url).join('|')}`
  );

  fetchCalls.length = 0;
  context.__streamMode = 'fallback';
  elements.get('messageInput').value = 'preserve failed question';
  const failedChildrenBefore = chatWindow.children.length;
  const failedAnswerEventsBefore = dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length;
  const failedStreamRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat/stream').length;
  const failedSyncRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat').length;
  let rejected = false;
  try {
    await vm.runInContext("sendMessage({ preventDefault() {} })", context);
  } catch {
    rejected = true;
  }
  const failedChildren = chatWindow.children.slice(failedChildrenBefore);
  const failedAssistants = failedChildren.filter((child) => child?.dataset?.speaker === 'assistant');
  const failedAnswerEventsAfter = dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length;
  const failedStreamRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat/stream').length;
  const failedSyncRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat').length;
  assert(!rejected, 'sendMessage should not reject on ambiguous stream failure');
  assert(
    elements.get('messageInput').value === 'preserve failed question',
    `failed send should preserve draft text, got: ${elements.get('messageInput').value}`
  );
  assert(
    failedAnswerEventsAfter === failedAnswerEventsBefore &&
      failedStreamRequestsAfter === failedStreamRequestsBefore + 1 &&
      failedSyncRequestsAfter === failedSyncRequestsBefore &&
      failedAssistants.length === 1 &&
      failedAssistants[0]?.dataset?.state === 'error' &&
      failedAssistants[0]?.textContent === 'message_failed',
    `ambiguous stream failure must issue one stream request, no answer/sync, and one failed assistant: answers=${failedAnswerEventsBefore}->${failedAnswerEventsAfter} stream=${failedStreamRequestsBefore}->${failedStreamRequestsAfter} sync=${failedSyncRequestsBefore}->${failedSyncRequestsAfter} assistants=${failedAssistants.length} children=${failedChildren.map((child) => child.textContent).join('|')}`
  );
  const failedRail = failedChildren
    .find((child) => child?.dataset?.role === 'transformer-core-rail');
  const failedRailText = nodeText(failedRail);
  assert(
    failedRailText.includes('Message failed') &&
      failedRailText.includes('Stream') &&
      failedRailText.includes('Sync not_attempted') &&
      failedRailText.includes('Next retry_or_check_model') &&
      !/Authorization|Bearer|sk-[A-Za-z0-9_-]{12,}/.test(failedRailText),
    `failed send should render redacted diagnostic rail: ${failedRailText}`
  );
  assert(
    elements.get('coreStatusRail').dataset.coreStatus === 'error',
    `failed send should mark core status error: ${elements.get('coreStatusRail').dataset.coreStatus}`
  );

  fetchCalls.length = 0;
  vm.runInContext('clearActiveRunIdentity(); pendingStopBeforeToken = null; streamCancelRequested = false; streamRenderSuppressed = false;', context);
  context.__streamMode = 'sse-message-event';
  const messageEventChildrenBefore = chatWindow.children.length;
  const messageEventStreamRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat/stream').length;
  const messageEventSyncRequestsBefore = fetchCalls.filter((call) => call.url === '/api/chat').length;
  elements.get('messageInput').value = 'message event probe';

  await vm.runInContext('sendMessage()', context);

  const messageEventAdded = chatWindow.children.slice(messageEventChildrenBefore);
  const messageEventAssistants = messageEventAdded.filter((node) => node?.dataset?.speaker === 'assistant');
  const messageEventDiagnostics = messageEventAdded.filter((node) => node?.dataset?.role === 'stream-diagnostic');
  const messageEventStreamRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat/stream').length;
  const messageEventSyncRequestsAfter = fetchCalls.filter((call) => call.url === '/api/chat').length;
  const messageEventLiteral = 'message fallback chunk';
  const messageEventAssistantText = nodeText(messageEventAssistants[0]);
  const messageEventLiteralCount = messageEventAssistantText.split(messageEventLiteral).length - 1;

  assert(
    messageEventStreamRequestsAfter === messageEventStreamRequestsBefore + 1 &&
      messageEventSyncRequestsAfter === messageEventSyncRequestsBefore &&
      messageEventAssistants.length === 1 &&
      messageEventAssistantText === messageEventLiteral &&
      messageEventLiteralCount === 1 &&
      messageEventDiagnostics.length === 0,
    `message-event-unhandled: stream=${messageEventStreamRequestsBefore}->${messageEventStreamRequestsAfter} sync=${messageEventSyncRequestsBefore}->${messageEventSyncRequestsAfter} assistants=${messageEventAssistants.length} text=${JSON.stringify(messageEventAssistantText)} count=${messageEventLiteralCount} diagnostics=${messageEventDiagnostics.length}`
  );

  let defaultMessageEvents = [];
  try {
    defaultMessageEvents = vm.runInContext(`
      (() => {
        const parser = createSseEventParser();
        return parser.push(new TextEncoder().encode('data: {"data":"default"}\\n\\n'));
      })()
    `, context);
  } catch {}
  assert(
    defaultMessageEvents.length === 1 &&
      defaultMessageEvents[0]?.type === 'message' &&
      defaultMessageEvents[0]?.data === '{"data":"default"}',
    'sse-default-message-type-unhandled'
  );

  const resetTypeEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      return parser.push(new TextEncoder().encode(
        'event: custom\\ndata: first\\n\\ndata: second\\n\\n'
      ));
    })()
  `, context);
  assert(
    resetTypeEvents.length === 2 &&
      resetTypeEvents[0]?.type === 'custom' &&
      resetTypeEvents[1]?.type === 'message',
    'sse-event-type-not-reset'
  );

  const multilineDataEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      return parser.push(new TextEncoder().encode('data: alpha\\ndata: beta\\n\\n'));
    })()
  `, context);
  assert(
    multilineDataEvents.length === 1 && multilineDataEvents[0]?.data === 'alpha\nbeta',
    'sse-data-lines-not-aggregated'
  );

  const whitespaceEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      return parser.push(new TextEncoder().encode('data:  keep-one-leading-space\\n\\n'));
    })()
  `, context);
  assert(
    whitespaceEvents.length === 1 && whitespaceEvents[0]?.data === ' keep-one-leading-space',
    'sse-field-whitespace-overtrimmed'
  );

  const lfEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      return parser.push(new TextEncoder().encode('event: lf\\ndata: ok\\n\\n'));
    })()
  `, context);
  assert(
    lfEvents.length === 1 && lfEvents[0]?.type === 'lf' && lfEvents[0]?.data === 'ok',
    'sse-lf-line-ending-unhandled'
  );

  const crlfEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      const out = [];
      out.push(...parser.push(new TextEncoder().encode('event: crlf\\r')));
      out.push(...parser.push(new TextEncoder().encode('\\ndata: ok\\r\\n\\r')));
      out.push(...parser.push(new TextEncoder().encode('\\n')));
      return out;
    })()
  `, context);
  assert(
    crlfEvents.length === 1 && crlfEvents[0]?.type === 'crlf' && crlfEvents[0]?.data === 'ok',
    'sse-crlf-line-ending-unhandled'
  );

  const crEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      return parser.push(new TextEncoder().encode('event: cr\\rdata: ok\\r\\rignored: tail\\r'));
    })()
  `, context);
  assert(
    crEvents.length === 1 && crEvents[0]?.type === 'cr' && crEvents[0]?.data === 'ok',
    'sse-cr-line-ending-unhandled'
  );

  function renderW1ScoreDelta(signal) {
    fetchCalls.length = 0;
    dispatchedEvents.length = 0;
    elements.get('traceStatus').textContent = 'ready';
    const target = fakeElement('w1-score-target');
    const assistant = fakeElement('w1-score-assistant');
    target.appendChild(assistant);
    context.__w1ScoreSignal = signal;
    context.__w1ScoreAssistant = assistant;
    context.__w1Console = [];
    const originalConsole = context.console;
    context.console = {
      log: (...args) => context.__w1Console.push(args.map(String).join(' ')),
      warn: (...args) => context.__w1Console.push(args.map(String).join(' ')),
      error: (...args) => context.__w1Console.push(args.map(String).join(' '))
    };
    try {
      vm.runInContext(
        "renderChatEvent({ type: 'scoreDelta', scoreDelta: globalThis.__w1ScoreSignal }, globalThis.__w1ScoreAssistant);",
        context
      );
    } finally {
      context.console = originalConsole;
    }
    return {
      target,
      detail: target.children.find((child) => child?.dataset?.role === 'score-delta-detail'),
      consoleOutput: context.__w1Console.slice()
    };
  }

  const backendScoreDeltaSignal = {
    scoreDelta: 0.20,
    dropRatio: 0.10,
    maxDrawdown: 0.30,
    expectedDelta: 0.15,
    rawScoreDelta: 0.12,
    clampName: 'cvar',
    stage: 'final',
    guard: 'risk-k',
    eventId: 7
  };
  const scoreDeltaOwnerProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(Boolean(scoreDeltaOwnerProbe.detail), 'score-delta-event-unhandled');

  const scoreDeltaValueProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaValueProbe.detail).includes('scoreDelta: 0.2'),
    `score-delta-value-missing: ${JSON.stringify(nodeText(scoreDeltaValueProbe.detail))}`
  );

  const scoreDeltaDropRatioProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaDropRatioProbe.detail).includes('dropRatio: 0.1'),
    'score-delta-drop-ratio-missing'
  );

  const scoreDeltaDrawdownProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaDrawdownProbe.detail).includes('maxDrawdown: 0.3'),
    'score-delta-max-drawdown-missing'
  );

  const scoreDeltaExpectedProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaExpectedProbe.detail).includes('expectedDelta: 0.15'),
    'score-delta-expected-value-missing'
  );

  const scoreDeltaRawProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaRawProbe.detail).includes('rawScoreDelta: 0.12'),
    'score-delta-raw-value-missing'
  );

  const scoreDeltaClampProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaClampProbe.detail).includes('clampName: cvar'),
    'score-delta-clamp-label-missing'
  );

  const scoreDeltaStageProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaStageProbe.detail).includes('stage: final'),
    'score-delta-stage-label-missing'
  );

  const scoreDeltaGuardProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaGuardProbe.detail).includes('guard: risk-k'),
    'score-delta-guard-label-missing'
  );

  const scoreDeltaEventIdProbe = renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(
    nodeText(scoreDeltaEventIdProbe.detail).includes('eventId: 7'),
    'score-delta-event-id-missing'
  );

  renderW1ScoreDelta(backendScoreDeltaSignal);
  assert(elements.get('traceStatus').textContent === 'scoreDelta', 'score-delta-rail-missing');

  function w1NodeSurface(node) {
    if (!node) return '';
    const attributes = ['aria-label', 'role', 'title']
      .map((name) => node.getAttribute?.(name) || '')
      .filter(Boolean);
    const datasetValues = Object.values(node.dataset || {}).map(String);
    return [node.textContent || '', node.title || '', ...attributes, ...datasetValues]
      .concat((node.children || []).map(w1NodeSurface))
      .join('|');
  }

  async function runW1UnknownStreamProbe(eventName, payload) {
    fetchCalls.length = 0;
    dispatchedEvents.length = 0;
    chatWindow.replaceChildren();
    elements.get('traceStatus').textContent = 'ready';
    elements.get('coreStatusRail').dataset.coreStatus = 'idle';
    vm.runInContext(
      'clearActiveRunIdentity(); pendingStopBeforeToken = null; streamCancelRequested = false; streamRenderSuppressed = false;',
      context
    );
    context.__streamMode = 'w1-sse-frames';
    context.__w1StreamChunks = [
      `event: ${eventName}\ndata: ${JSON.stringify(payload)}\n\n` +
        'event: token\ndata: {"type":"token","data":"SAFE_FOLLOWUP_TOKEN"}\n\n' +
        'event: final\ndata: {"type":"final","data":"","answerMode":"streamed","evidence":[]}\n\n'
    ];
    context.__w1Console = [];
    const originalConsole = context.console;
    context.console = {
      log: (...args) => context.__w1Console.push(args.map(String).join(' ')),
      warn: (...args) => context.__w1Console.push(args.map(String).join(' ')),
      error: (...args) => context.__w1Console.push(args.map(String).join(' '))
    };
    elements.get('messageInput').value = 'unknown event continuation probe';
    try {
      await vm.runInContext('sendMessage()', context);
    } finally {
      context.console = originalConsole;
    }
    const diagnostic = chatWindow.children.find((node) => node?.dataset?.role === 'stream-diagnostic');
    const answerEvents = dispatchedEvents.filter((event) => event.type === 'brain-state:answer');
    const allStatusSurface = [...elements.values()].map(w1NodeSurface).join('|');
    return {
      diagnostic,
      surface: `${w1NodeSurface(chatWindow)}|${allStatusSurface}|${context.__w1Console.join('|')}`,
      assistantText: chatWindow.children
        .filter((node) => node?.dataset?.speaker === 'assistant')
        .map(nodeText)
        .join('|'),
      answerEventCount: answerEvents.length,
      syncRequestCount: fetchCalls.filter((call) => call.url === '/api/chat').length
    };
  }

  const unknownDataSentinel = 'PRIVATE_QUERY_DATA_SENTINEL';
  const unknownDataProbe = await runW1UnknownStreamProbe('unmapped_lane', {
    type: 'unmapped_lane',
    data: unknownDataSentinel
  });
  assert(
    nodeText(unknownDataProbe.diagnostic) === 'Stream event unmapped_lane' &&
      !unknownDataProbe.surface.includes(unknownDataSentinel) &&
      unknownDataProbe.assistantText.includes('SAFE_FOLLOWUP_TOKEN') &&
      unknownDataProbe.answerEventCount === 1 &&
      unknownDataProbe.syncRequestCount === 0,
    'unknown-event-data-not-omitted'
  );

  const unknownMessageSentinel = 'PRIVATE_QUERY_MESSAGE_SENTINEL';
  const unknownMessageProbe = await runW1UnknownStreamProbe('unmapped_lane', {
    type: 'unmapped_lane',
    message: unknownMessageSentinel
  });
  assert(
    nodeText(unknownMessageProbe.diagnostic) === 'Stream event unmapped_lane' &&
      !unknownMessageProbe.surface.includes(unknownMessageSentinel) &&
      unknownMessageProbe.assistantText.includes('SAFE_FOLLOWUP_TOKEN') &&
      unknownMessageProbe.answerEventCount === 1 &&
      unknownMessageProbe.syncRequestCount === 0,
    'unknown-event-message-not-omitted'
  );

  const unknownReasonSentinel = 'PRIVATE_QUERY_REASON_SENTINEL';
  const unknownReasonProbe = await runW1UnknownStreamProbe('unmapped_lane', {
    type: 'unmapped_lane',
    reason: unknownReasonSentinel
  });
  assert(
    nodeText(unknownReasonProbe.diagnostic) === 'Stream event unmapped_lane' &&
      !unknownReasonProbe.surface.includes(unknownReasonSentinel) &&
      unknownReasonProbe.assistantText.includes('SAFE_FOLLOWUP_TOKEN') &&
      unknownReasonProbe.answerEventCount === 1 &&
      unknownReasonProbe.syncRequestCount === 0,
    'unknown-event-reason-not-omitted'
  );

  const unknownOtherSentinel = 'PRIVATE_QUERY_OTHER_SENTINEL';
  const unknownOtherProbe = await runW1UnknownStreamProbe('unmapped_lane', {
    type: 'unmapped_lane',
    query: unknownOtherSentinel
  });
  assert(
    nodeText(unknownOtherProbe.diagnostic) === 'Stream event unmapped_lane' &&
      !unknownOtherProbe.surface.includes(unknownOtherSentinel) &&
      unknownOtherProbe.assistantText.includes('SAFE_FOLLOWUP_TOKEN') &&
      unknownOtherProbe.answerEventCount === 1 &&
      unknownOtherProbe.syncRequestCount === 0,
    'unknown-event-other-detail-not-omitted'
  );

  const unsafeTypeName = 'unsafe/type';
  const unsafeTypeProbe = await runW1UnknownStreamProbe(unsafeTypeName, { type: unsafeTypeName });
  assert(
    nodeText(unsafeTypeProbe.diagnostic) === 'Stream event unknown' &&
      !unsafeTypeProbe.surface.includes(unsafeTypeName) &&
      unsafeTypeProbe.assistantText.includes('SAFE_FOLLOWUP_TOKEN') &&
      unsafeTypeProbe.answerEventCount === 1 &&
      unsafeTypeProbe.syncRequestCount === 0,
    'unknown-event-name-character-not-bounded'
  );

  const overlongTypeName = 'A'.repeat(65);
  const overlongTypeProbe = await runW1UnknownStreamProbe(overlongTypeName, { type: overlongTypeName });
  assert(
    nodeText(overlongTypeProbe.diagnostic) === 'Stream event unknown' &&
      !overlongTypeProbe.surface.includes(overlongTypeName) &&
      overlongTypeProbe.assistantText.includes('SAFE_FOLLOWUP_TOKEN') &&
      overlongTypeProbe.answerEventCount === 1 &&
      overlongTypeProbe.syncRequestCount === 0,
    'unknown-event-name-length-not-bounded'
  );

  const sameChunkOverflow = vm.runInContext(`
    (() => {
      const parser = createSseEventParser({ maxEventUtf8Bytes: 4 });
      let terminal = null;
      try {
        parser.push(new TextEncoder().encode('data: 12345LATER_DECODED_DATA'));
      } catch (error) {
        terminal = { name: error?.name, message: error?.message, code: error?.streamFailureCode };
      }
      try {
        return { terminal, recovered: parser.push(new TextEncoder().encode('\\n\\n')), recoveryError: null };
      } catch (error) {
        return { terminal, recovered: [], recoveryError: error?.message || 'error' };
      }
    })()
  `, context);
  const sameChunkOverflowSurface = JSON.stringify(sameChunkOverflow);
  assert(
    sameChunkOverflow?.terminal?.name === 'StreamProtocolError' &&
      sameChunkOverflow?.terminal?.message === 'stream_failed' &&
      sameChunkOverflow?.terminal?.code === 'sse_event_too_large' &&
      sameChunkOverflow?.recoveryError === null &&
      sameChunkOverflow?.recovered?.length === 1 &&
      sameChunkOverflow?.recovered[0]?.data === '1234' &&
      !sameChunkOverflowSurface.includes('LATER_DECODED_DATA'),
    'sse-event-byte-cap-missing'
  );

  fetchCalls.length = 0;
  dispatchedEvents.length = 0;
  chatWindow.replaceChildren();
  elements.get('coreStatusRail').dataset.coreStatus = 'idle';
  vm.runInContext(
    'clearActiveRunIdentity(); pendingStopBeforeToken = null; streamCancelRequested = false; streamRenderSuppressed = false;',
    context
  );
  const overflowSentinel = 'PRIVATE_OVERFLOW_SENTINEL';
  const oversizedPayload = JSON.stringify({
    type: 'token',
    data: overflowSentinel + 'X'.repeat(131073)
  });
  context.__streamMode = 'w1-sse-frames';
  context.__w1StreamChunks = [
    'event: token\n',
    `data: ${oversizedPayload}`,
    '\n\nevent: final\ndata: {"type":"final","data":"LATE_FINAL"}\n\n'
  ];
  context.__w1Console = [];
  const overflowOriginalConsole = context.console;
  context.console = {
    log: (...args) => context.__w1Console.push(args.map(String).join(' ')),
    warn: (...args) => context.__w1Console.push(args.map(String).join(' ')),
    error: (...args) => context.__w1Console.push(args.map(String).join(' '))
  };
  elements.get('messageInput').value = 'overflow stream probe';
  try {
    await vm.runInContext('sendMessage()', context);
  } finally {
    context.console = overflowOriginalConsole;
  }
  const overflowAssistant = chatWindow.children.find((node) => node?.dataset?.speaker === 'assistant');
  const overflowSurface = `${w1NodeSurface(chatWindow)}|${[...elements.values()].map(w1NodeSurface).join('|')}|${context.__w1Console.join('|')}`;
  assert(
    overflowAssistant?.dataset?.state === 'error' &&
      overflowAssistant?.textContent === 'message_failed' &&
      elements.get('coreStatusRail').dataset.coreStatus === 'error' &&
      context.__w1StreamReadCount === 2 &&
      !overflowSurface.includes(overflowSentinel) &&
      !overflowSurface.includes('LATE_FINAL') &&
      dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length === 0 &&
      fetchCalls.filter((call) => call.url === '/api/chat').length === 0,
    'sse-overflow-not-terminal'
  );

  const splitUtf8Events = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      const bytes = new TextEncoder().encode('data: 한\\n\\n');
      const out = [];
      out.push(...parser.push(bytes.slice(0, 7)));
      out.push(...parser.push(bytes.slice(7)));
      return out;
    })()
  `, context);
  assert(
    splitUtf8Events.length === 1 && splitUtf8Events[0]?.data === '한',
    'sse-utf8-split-corrupted'
  );

  const eofTailEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      const out = parser.push(new TextEncoder().encode('event: token\\ndata: UNTERMINATED_TAIL'));
      out.push(...parser.finish());
      return out;
    })()
  `, context);
  assert(eofTailEvents.length === 0, 'sse-eof-tail-dispatched');

  const crEofBlankEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      return parser.push(new TextEncoder().encode('event: cr-eof\\rdata: ok\\r\\r'));
    })()
  `, context);
  assert(
    crEofBlankEvents.length === 1 &&
      crEofBlankEvents[0]?.type === 'cr-eof' &&
      crEofBlankEvents[0]?.data === 'ok',
    'sse-cr-eof-blank-termination'
  );

  const ignoredFieldEvents = vm.runInContext(`
    (() => {
      const parser = createSseEventParser();
      return parser.push(new TextEncoder().encode(
        ': comment\\nretry: 1000\\nid: ignored\\nevent: custom  \\ndata: value  \\n\\n'
      ));
    })()
  `, context);
  assert(
    ignoredFieldEvents.length === 1 &&
      ignoredFieldEvents[0]?.type === 'custom  ' &&
      ignoredFieldEvents[0]?.data === 'value  ',
    'sse-comment-unsupported-or-trailing-space-regression'
  );

  const joinedByteBoundary = vm.runInContext(`
    (() => {
      const parser = createSseEventParser({ maxEventUtf8Bytes: 4 });
      return parser.push(new TextEncoder().encode('data: a\\ndata: bc\\n\\n'));
    })()
  `, context);
  assert(
    joinedByteBoundary.length === 1 && joinedByteBoundary[0]?.data === 'a\nbc',
    'sse-joined-data-byte-boundary-regression'
  );

  const zeroScoreDeltaProbe = renderW1ScoreDelta({ scoreDelta: 0, rawScoreDelta: null });
  assert(
    nodeText(zeroScoreDeltaProbe.detail).includes('scoreDelta: 0'),
    'score-delta-zero-without-raw-regression'
  );

  const payloadUnsafeType = 'unsafe/type';
  const payloadUnsafeTypeProbe = await runW1UnknownStreamProbe('safe_event', {
    type: payloadUnsafeType,
    query: 'PRIVATE_EFFECTIVE_TYPE_SENTINEL'
  });
  assert(
    nodeText(payloadUnsafeTypeProbe.diagnostic) === 'Stream event unknown' &&
      !payloadUnsafeTypeProbe.surface.includes(payloadUnsafeType) &&
      !payloadUnsafeTypeProbe.surface.includes('PRIVATE_EFFECTIVE_TYPE_SENTINEL') &&
      payloadUnsafeTypeProbe.assistantText.includes('SAFE_FOLLOWUP_TOKEN') &&
      payloadUnsafeTypeProbe.answerEventCount === 1,
    'unknown-effective-type-not-bounded'
  );

  async function runW1TerminalStreamProbe(chunks) {
    fetchCalls.length = 0;
    dispatchedEvents.length = 0;
    chatWindow.replaceChildren();
    elements.get('traceStatus').textContent = 'ready';
    elements.get('coreStatusRail').dataset.coreStatus = 'idle';
    vm.runInContext(
      'clearActiveRunIdentity(); pendingStopBeforeToken = null; streamCancelRequested = false; streamRenderSuppressed = false;',
      context
    );
    context.__streamMode = 'w1-sse-frames';
    context.__w1StreamChunks = chunks;
    context.__w1Console = [];
    const originalConsole = context.console;
    context.console = {
      log: (...args) => context.__w1Console.push(args.map(String).join(' ')),
      warn: (...args) => context.__w1Console.push(args.map(String).join(' ')),
      error: (...args) => context.__w1Console.push(args.map(String).join(' '))
    };
    elements.get('messageInput').value = 'terminal ownership probe';
    try {
      await vm.runInContext('sendMessage()', context);
    } finally {
      context.console = originalConsole;
    }
    const assistants = chatWindow.children.filter((node) => node?.dataset?.speaker === 'assistant');
    return {
      assistants,
      assistantText: assistants.map(nodeText).join('|'),
      surface: `${w1NodeSurface(chatWindow)}|${[...elements.values()].map(w1NodeSurface).join('|')}|${context.__w1Console.join('|')}`,
      answerEventCount: dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length,
      syncRequestCount: fetchCalls.filter((call) => call.url === '/api/chat').length,
      readCount: context.__w1StreamReadCount,
      coreStatus: elements.get('coreStatusRail').dataset.coreStatus
    };
  }

  const malformedTerminalSentinel = 'PRIVATE_MALFORMED_TERMINAL_SENTINEL';
  const malformedTerminalProbe = await runW1TerminalStreamProbe([
    'event: final\n' +
      `data: {"type":"final","data":"${malformedTerminalSentinel}"\n\n` +
      'event: token\ndata: {"type":"token","data":"LATE_AFTER_MALFORMED"}\n\n'
  ]);
  assert(
    malformedTerminalProbe.assistants.length === 1 &&
      malformedTerminalProbe.assistants[0]?.dataset?.state === 'error' &&
      malformedTerminalProbe.assistants[0]?.textContent === 'message_failed' &&
      malformedTerminalProbe.coreStatus === 'error' &&
      malformedTerminalProbe.answerEventCount === 0 &&
      malformedTerminalProbe.syncRequestCount === 0 &&
      malformedTerminalProbe.readCount === 1 &&
      !malformedTerminalProbe.surface.includes(malformedTerminalSentinel) &&
      !malformedTerminalProbe.surface.includes('LATE_AFTER_MALFORMED'),
    'sse-malformed-terminal-not-rawless-once'
  );

  const effectiveFinalProbe = await runW1TerminalStreamProbe([
    'event: token\n' +
      'data: {"type":"final","data":"EFFECTIVE_FINAL","answerMode":"streamed","evidence":[]}\n\n' +
      'event: token\ndata: {"type":"token","data":"LATE_AFTER_EFFECTIVE_FINAL"}\n\n'
  ]);
  assert(
    effectiveFinalProbe.assistants.length === 1 &&
      effectiveFinalProbe.assistantText === 'EFFECTIVE_FINAL' &&
      effectiveFinalProbe.coreStatus === 'done' &&
      effectiveFinalProbe.answerEventCount === 1 &&
      effectiveFinalProbe.syncRequestCount === 0 &&
      effectiveFinalProbe.readCount === 1 &&
      !effectiveFinalProbe.surface.includes('LATE_AFTER_EFFECTIVE_FINAL'),
    'sse-payload-effective-type-terminal-ownership'
  );

  const lateOversizedSentinel = 'LATE_OVERFLOW_PAYLOAD';
  const finalBeforeOverflowProbe = await runW1TerminalStreamProbe([
    'event: final\n' +
      'data: {"type":"final","data":"FINAL_BEFORE_OVERFLOW","answerMode":"streamed","evidence":[]}\n\n' +
      'event: token\n' +
      `data: ${JSON.stringify({ type: 'token', data: lateOversizedSentinel + 'X'.repeat(131073) })}`
  ]);
  assert(
    finalBeforeOverflowProbe.assistants.length === 1 &&
      finalBeforeOverflowProbe.assistantText === 'FINAL_BEFORE_OVERFLOW' &&
      finalBeforeOverflowProbe.assistants[0]?.dataset?.state !== 'error' &&
      finalBeforeOverflowProbe.coreStatus === 'done' &&
      finalBeforeOverflowProbe.answerEventCount === 1 &&
      finalBeforeOverflowProbe.syncRequestCount === 0 &&
      finalBeforeOverflowProbe.readCount === 1 &&
      !finalBeforeOverflowProbe.surface.includes('message_failed') &&
      !finalBeforeOverflowProbe.surface.includes(lateOversizedSentinel),
    'sse-terminal-before-late-overflow-not-owned'
  );

  async function runW2FailureProbe(scenario, options = {}) {
    fetchCalls.length = 0;
    dispatchedEvents.length = 0;
    chatWindow.replaceChildren();
    for (const id of [
      'streamStatus',
      'modelStatus',
      'searchStatus',
      'ragStatus',
      'traceStatus',
      'qualityStatus',
      'healthStatus'
    ]) {
      const element = elements.get(id);
      element.textContent = '';
      element.title = '';
      element.dataset = {};
    }
    elements.get('traceStatus').textContent = 'ready';
    elements.get('coreStatusRail').textContent = '';
    elements.get('coreStatusRail').title = '';
    elements.get('coreStatusRail').dataset = {};
    elements.get('coreStatusRail').dataset.coreStatus = 'idle';
    sessionStorageBacking.clear();
    vm.runInContext(
      'clearActiveRunIdentity(); forgetCurrentSessionId(); pendingStopBeforeToken = null; ' +
        'streamCancelRequested = false; streamRenderSuppressed = false; sendMessageInFlight = false; ' +
        'state.latestEvidenceRailItems = []; state.latestVisibleTurnEvidence = null; ' +
        'lastAssistantModelFallback = null; resetCurrentTurnHealthOverlay();',
      context
    );
    const sessionId = Number(options.sessionId || 321);
    vm.runInContext(`rememberCurrentSessionId(${sessionId});`, context);
    context.__w2Scenario = scenario;
    context.__w2StreamRequestCount = 0;
    context.__w2InitialReadCount = 0;
    context.__w2AttachReadCount = 0;
    context.__runStateResponse = options.runStateResponse || null;
    context.__w2Console = [];
    const originalConsole = context.console;
    context.console = {
      log: (...args) => context.__w2Console.push(args.map(String).join(' ')),
      warn: (...args) => context.__w2Console.push(args.map(String).join(' ')),
      error: (...args) => context.__w2Console.push(args.map(String).join(' '))
    };
    const draft = String(options.draft || 'W2 preserved draft');
    elements.get('messageInput').value = draft;
    try {
      await vm.runInContext('sendMessage()', context);
    } finally {
      context.console = originalConsole;
      context.__w2Scenario = null;
    }
    const assistants = chatWindow.children.filter((node) => node?.dataset?.speaker === 'assistant');
    const assistant = assistants[assistants.length - 1] || null;
    return {
      assistant,
      assistants,
      assistantText: nodeText(assistant),
      userMessageCount: chatWindow.children.filter((node) => node?.dataset?.speaker === 'user').length,
      chatChildCount: chatWindow.children.length,
      draft,
      draftAfter: elements.get('messageInput').value,
      surface: `${w1NodeSurface(chatWindow)}|${[...elements.values()].map(w1NodeSurface).join('|')}|${context.__w2Console.join('|')}`,
      answerEventCount: dispatchedEvents.filter((event) => event.type === 'brain-state:answer').length,
      syncRequestCount: fetchCalls.filter((call) => call.url === '/api/chat').length,
      streamRequestCount: context.__w2StreamRequestCount,
      stateRequestCount: fetchCalls.filter((call) => call.url === `/api/chat/state?sessionId=${sessionId}`).length,
      attachRequestCount: fetchCalls.filter((call) => call.url === '/api/chat/stream?attach=true').length,
      initialReadCount: context.__w2InitialReadCount,
      attachReadCount: context.__w2AttachReadCount,
      readCount: context.__w2BodyReadCount,
      cancelCount: context.__w2BodyCancelCount,
      readerCount: context.__w2BodyReaderCount,
      deliveredCharCount: context.__w2DeliveredCharCount,
      fallbackBodyPassCount: context.__w2FallbackBodyPassCount,
      currentSessionId: vm.runInContext('state.currentSessionId', context),
      persistedSessionId: sessionStorageBacking.get('chat.currentSessionId') || null,
      activeRun: vm.runInContext('activeRunIdentitySnapshot()', context),
      coreStatus: elements.get('coreStatusRail').dataset.coreStatus
    };
  }

  async function runW2DirectBoundedProbe(text, { rejectCancel = false } = {}) {
    let delivered = false;
    let cancelCount = 0;
    context.__w2DirectResponse = {
      headers: { get: () => 'text/plain; charset=utf-8' },
      body: {
        getReader() {
          return {
            async read() {
              if (delivered) return { done: true };
              delivered = true;
              return { done: false, value: new TextEncoder().encode(text) };
            },
            async cancel() {
              cancelCount += 1;
              if (rejectCancel) throw new Error('PRIVATE_W2_CANCEL_REJECTION');
            }
          };
        }
      }
    };
    let result = null;
    let error = null;
    try {
      result = await vm.runInContext('readBoundedFailureBody(globalThis.__w2DirectResponse, 1200)', context);
    } catch (caught) {
      error = caught;
    } finally {
      delete context.__w2DirectResponse;
    }
    return { result, error, cancelCount };
  }

  const boundedFailureLateSentinel = 'PRIVATE_W2_LATE_BODY_SENTINEL';
  const boundedFailureProbe = await runW2FailureProbe({
    status: 503,
    contentType: 'application/json',
    bodyChunks: [
      `{"code":"backend_unavailable","private":"${'X'.repeat(1300)}${boundedFailureLateSentinel}"}`
    ]
  });
  const astralBoundarySentinel = 'PRIVATE_W2_ASTRAL_BOUNDARY_SENTINEL';
  const astralBoundaryProbe = await runW2DirectBoundedProbe(
    `${'A'.repeat(1199)}😀${astralBoundarySentinel}`,
    { rejectCancel: true }
  );
  const astralBoundaryText = astralBoundaryProbe.result?.text || '';
  const astralBoundaryLastUnit = astralBoundaryText.charCodeAt(astralBoundaryText.length - 1);
  assert(
    boundedFailureProbe.assistant?.textContent === 'message_failed' &&
      boundedFailureProbe.assistant?.dataset?.state === 'error' &&
      boundedFailureProbe.assistant?.dataset?.failureKind === 'service_unavailable' &&
      boundedFailureProbe.assistant?.dataset?.failureRetryable === 'true' &&
      boundedFailureProbe.assistant?.dataset?.failureStatus === '503' &&
      !boundedFailureProbe.assistant?.dataset?.failureCode &&
      boundedFailureProbe.assistant?.dataset?.failureNextAction === 'retry' &&
      boundedFailureProbe.draftAfter === boundedFailureProbe.draft &&
      boundedFailureProbe.currentSessionId === 321 &&
      boundedFailureProbe.persistedSessionId === '321' &&
      boundedFailureProbe.readCount === 1 &&
      boundedFailureProbe.cancelCount === 1 &&
      boundedFailureProbe.readerCount === 1 &&
      boundedFailureProbe.fallbackBodyPassCount === 0 &&
      boundedFailureProbe.syncRequestCount === 0 &&
      boundedFailureProbe.answerEventCount === 0 &&
      !boundedFailureProbe.surface.includes(boundedFailureLateSentinel) &&
      !boundedFailureProbe.surface.includes('backend_unavailable') &&
      astralBoundaryProbe.error === null &&
      astralBoundaryProbe.cancelCount === 1 &&
      astralBoundaryProbe.result?.truncated === true &&
      astralBoundaryText.length <= 1200 &&
      !(astralBoundaryLastUnit >= 0xD800 && astralBoundaryLastUnit <= 0xDBFF) &&
      !astralBoundaryText.includes('😀') &&
      !astralBoundaryText.includes(astralBoundarySentinel),
    'bounded-failure-body-cap-missing'
  );

  const typed503Sentinel = 'PRIVATE_W2_HTTP_503_SENTINEL';
  const typed503Probe = await runW2FailureProbe({
    status: 503,
    contentType: 'application/json; charset=utf-8',
    bodyChunks: [JSON.stringify({ code: 'backend_unavailable', detail: typed503Sentinel })]
  });
  context.__w2ClassifierInput = {
    status: 503,
    boundedText: JSON.stringify({ code: 'backend_unavailable', detail: typed503Sentinel }),
    contentType: 'application/json',
    truncated: false
  };
  const typed503Meta = vm.runInContext('classifyChatFailure(globalThis.__w2ClassifierInput)', context);
  const typed503Error = vm.runInContext('chatFailureError(classifyChatFailure(globalThis.__w2ClassifierInput))', context);
  delete context.__w2ClassifierInput;
  assert(
    typed503Probe.assistant?.textContent === 'message_failed' &&
      typed503Probe.assistant?.dataset?.state === 'error' &&
      typed503Probe.assistant?.dataset?.failureKind === 'service_unavailable' &&
      typed503Probe.assistant?.dataset?.failureRetryable === 'true' &&
      typed503Probe.assistant?.dataset?.failureStatus === '503' &&
      typed503Probe.assistant?.dataset?.failureCode === 'backend_unavailable' &&
      typed503Probe.assistant?.dataset?.failureNextAction === 'retry' &&
      typed503Probe.draftAfter === typed503Probe.draft &&
      typed503Probe.currentSessionId === 321 &&
      typed503Probe.persistedSessionId === '321' &&
      typed503Probe.readerCount === 1 &&
      typed503Probe.cancelCount === 0 &&
      typed503Probe.fallbackBodyPassCount === 0 &&
      typed503Probe.syncRequestCount === 0 &&
      typed503Probe.answerEventCount === 0 &&
      !typed503Probe.surface.includes(typed503Sentinel) &&
      Object.isFrozen(typed503Meta) &&
      Object.keys(typed503Meta).join('|') === 'failureKind|retryable|status|serverCode|nextAction' &&
      typed503Meta.serverCode === 'backend_unavailable' &&
      typed503Error.message === 'chat_failure' &&
      Object.keys(typed503Error).join('|') === 'chatFailure' &&
      !Object.prototype.hasOwnProperty.call(typed503Error, 'status') &&
      !Object.prototype.hasOwnProperty.call(typed503Error, 'serverCode') &&
      !Object.prototype.hasOwnProperty.call(typed503Error, 'cause'),
    'typed-http-503-missing'
  );

  const typed403ForbiddenSentinel = 'PRIVATE_W2_HTTP_403_FORBIDDEN_SENTINEL';
  const typed403ForbiddenProbe = await runW2FailureProbe({
    status: 403,
    contentType: 'application/problem+json',
    bodyChunks: [JSON.stringify({ error: 'forbidden', detail: typed403ForbiddenSentinel })]
  });
  assert(
    typed403ForbiddenProbe.assistant?.textContent === 'message_failed' &&
      typed403ForbiddenProbe.assistant?.dataset?.state === 'error' &&
      typed403ForbiddenProbe.assistant?.dataset?.failureKind === 'access_denied' &&
      typed403ForbiddenProbe.assistant?.dataset?.failureRetryable === 'false' &&
      typed403ForbiddenProbe.assistant?.dataset?.failureStatus === '403' &&
      typed403ForbiddenProbe.assistant?.dataset?.failureCode === 'forbidden' &&
      typed403ForbiddenProbe.assistant?.dataset?.failureNextAction === 'sign_in_or_change_session' &&
      typed403ForbiddenProbe.assistant?.getAttribute('aria-label') ===
        'Message failed. access_denied. sign_in_or_change_session.' &&
      typed403ForbiddenProbe.draftAfter === typed403ForbiddenProbe.draft &&
      typed403ForbiddenProbe.currentSessionId === 321 &&
      typed403ForbiddenProbe.persistedSessionId === '321' &&
      typed403ForbiddenProbe.readerCount === 1 &&
      typed403ForbiddenProbe.cancelCount === 0 &&
      typed403ForbiddenProbe.fallbackBodyPassCount === 0 &&
      typed403ForbiddenProbe.syncRequestCount === 0 &&
      typed403ForbiddenProbe.answerEventCount === 0 &&
      !typed403ForbiddenProbe.surface.includes(typed403ForbiddenSentinel),
    'typed-http-403-forbidden-missing'
  );

  const typed403SessionProbe = await runW2FailureProbe({
    status: 403,
    contentType: 'text/plain; charset=utf-8',
    bodyChunks: [' \r\n session_forbidden \t ']
  });
  assert(
    typed403SessionProbe.assistant?.textContent === 'message_failed' &&
      typed403SessionProbe.assistant?.dataset?.state === 'error' &&
      typed403SessionProbe.assistant?.dataset?.failureKind === 'access_denied' &&
      typed403SessionProbe.assistant?.dataset?.failureRetryable === 'false' &&
      typed403SessionProbe.assistant?.dataset?.failureStatus === '403' &&
      typed403SessionProbe.assistant?.dataset?.failureCode === 'session_forbidden' &&
      typed403SessionProbe.assistant?.dataset?.failureNextAction === 'choose_or_start_session' &&
      typed403SessionProbe.assistant?.getAttribute('aria-label') ===
        'Message failed. access_denied. choose_or_start_session.' &&
      typed403SessionProbe.draftAfter === typed403SessionProbe.draft &&
      typed403SessionProbe.currentSessionId === null &&
      typed403SessionProbe.persistedSessionId === null &&
      typed403SessionProbe.userMessageCount === 1 &&
      typed403SessionProbe.assistants.length === 1 &&
      typed403SessionProbe.chatChildCount >= 2 &&
      typed403SessionProbe.readerCount === 1 &&
      typed403SessionProbe.cancelCount === 0 &&
      typed403SessionProbe.fallbackBodyPassCount === 0 &&
      typed403SessionProbe.syncRequestCount === 0 &&
      typed403SessionProbe.answerEventCount === 0,
    'typed-http-403-session-denial-missing'
  );

  const typed403StatusOnlySentinel = 'PRIVATE_W2_HTTP_403_STATUS_ONLY_SENTINEL';
  const typed403StatusOnlyProbe = await runW2FailureProbe({
    status: 403,
    contentType: 'application/json',
    bodyChunks: [JSON.stringify({ detail: typed403StatusOnlySentinel })]
  });
  context.__w2StaleDatasetAssistant = typed403ForbiddenProbe.assistant;
  context.__w2StatusOnlyMeta = vm.runInContext(
    'classifyChatFailure({ status: 403, boundedText: "{}", contentType: "application/json", truncated: false })',
    context
  );
  vm.runInContext('applyChatFailureState(globalThis.__w2StaleDatasetAssistant, globalThis.__w2StatusOnlyMeta)', context);
  delete context.__w2StaleDatasetAssistant;
  delete context.__w2StatusOnlyMeta;
  assert(
    typed403StatusOnlyProbe.assistant?.textContent === 'message_failed' &&
      typed403StatusOnlyProbe.assistant?.dataset?.state === 'error' &&
      typed403StatusOnlyProbe.assistant?.dataset?.failureKind === 'access_denied' &&
      typed403StatusOnlyProbe.assistant?.dataset?.failureRetryable === 'false' &&
      typed403StatusOnlyProbe.assistant?.dataset?.failureStatus === '403' &&
      !typed403StatusOnlyProbe.assistant?.dataset?.failureCode &&
      typed403StatusOnlyProbe.assistant?.dataset?.failureNextAction === 'sign_in_or_change_session' &&
      typed403StatusOnlyProbe.draftAfter === typed403StatusOnlyProbe.draft &&
      typed403StatusOnlyProbe.currentSessionId === 321 &&
      typed403StatusOnlyProbe.persistedSessionId === '321' &&
      typed403StatusOnlyProbe.userMessageCount === 1 &&
      typed403StatusOnlyProbe.assistants.length === 1 &&
      typed403StatusOnlyProbe.syncRequestCount === 0 &&
      typed403StatusOnlyProbe.answerEventCount === 0 &&
      !typed403StatusOnlyProbe.surface.includes(typed403StatusOnlySentinel) &&
      !typed403ForbiddenProbe.assistant?.dataset?.failureCode,
    'typed-http-403-status-session-preservation-missing'
  );

  const typed401Failures = [];
  for (const [code, nextAction, preservesSession] of [
    ['forbidden', 'sign_in_or_change_session', true],
    ['session_forbidden', 'choose_or_start_session', false]
  ]) {
    const sentinel = 'PRIVATE_W2_HTTP_401_SENTINEL';
    const probe = await runW2FailureProbe({
      status: 401,
      contentType: 'application/problem+json',
      bodyChunks: [JSON.stringify({ code, detail: sentinel })]
    });
    const d = probe.assistant?.dataset || {};
    if (!(probe.assistant?.textContent === 'message_failed' && d.state === 'error' &&
      d.failureKind === 'access_denied' && d.failureRetryable === 'false' &&
      d.failureStatus === '401' && d.failureCode === code && d.failureNextAction === nextAction &&
      probe.currentSessionId === (preservesSession ? 321 : null) &&
      probe.persistedSessionId === (preservesSession ? '321' : null) &&
      probe.draftAfter === probe.draft && probe.readerCount === 1 &&
      probe.syncRequestCount === 0 && probe.answerEventCount === 0 &&
      !probe.surface.includes(sentinel))) typed401Failures.push(code);
  }
  assert(typed401Failures.length === 0, 'typed-http-401-auth-denial-missing:' + typed401Failures.join(','));

  const typed504Sentinel = 'PRIVATE_W2_HTTP_504_SENTINEL';
  const typed504Probe = await runW2FailureProbe({
    status: 504,
    contentType: 'application/json',
    bodyChunks: [JSON.stringify({ reason: 'backend_timeout', detail: typed504Sentinel })]
  });
  assert(
    typed504Probe.assistant?.textContent === 'message_failed' &&
      typed504Probe.assistant?.dataset?.state === 'error' &&
      typed504Probe.assistant?.dataset?.failureKind === 'timeout' &&
      typed504Probe.assistant?.dataset?.failureRetryable === 'true' &&
      typed504Probe.assistant?.dataset?.failureStatus === '504' &&
      typed504Probe.assistant?.dataset?.failureCode === 'backend_timeout' &&
      typed504Probe.assistant?.dataset?.failureNextAction === 'retry' &&
      typed504Probe.draftAfter === typed504Probe.draft &&
      typed504Probe.currentSessionId === 321 &&
      typed504Probe.persistedSessionId === '321' &&
      typed504Probe.readerCount === 1 &&
      typed504Probe.cancelCount === 0 &&
      typed504Probe.fallbackBodyPassCount === 0 &&
      typed504Probe.syncRequestCount === 0 &&
      typed504Probe.answerEventCount === 0 &&
      !typed504Probe.surface.includes(typed504Sentinel),
    'typed-http-504-missing'
  );

  const typedNetworkSentinel = 'PRIVATE_W2_NATIVE_TYPE_ERROR_SENTINEL';
  const typedNetworkProbe = await runW2FailureProbe({
    networkError: true,
    networkMessage: typedNetworkSentinel
  });
  assert(
    typedNetworkProbe.assistant?.textContent === 'message_failed' &&
      typedNetworkProbe.assistant?.dataset?.state === 'error' &&
      typedNetworkProbe.assistant?.dataset?.failureKind === 'network_error' &&
      typedNetworkProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !typedNetworkProbe.assistant?.dataset?.failureStatus &&
      !typedNetworkProbe.assistant?.dataset?.failureCode &&
      typedNetworkProbe.assistant?.dataset?.failureNextAction === 'check_connection' &&
      typedNetworkProbe.assistant?.getAttribute('aria-label') ===
        'Message failed. network_error. check_connection.' &&
      typedNetworkProbe.draftAfter === typedNetworkProbe.draft &&
      typedNetworkProbe.currentSessionId === 321 &&
      typedNetworkProbe.persistedSessionId === '321' &&
      typedNetworkProbe.syncRequestCount === 0 &&
      typedNetworkProbe.answerEventCount === 0 &&
      typedNetworkProbe.streamRequestCount === 1 &&
      !typedNetworkProbe.surface.includes(typedNetworkSentinel),
    'typed-network-error-missing'
  );

  const readerRecoverySentinel = 'PRIVATE_W2_READER_TYPEERROR_RECOVERY_SENTINEL';
  const readerTypeErrorRecoveryProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      'event: session\n' +
        'data: {"type":"session","sessionId":321,"data":"w2-reader-typeerror-run"}\n\n'
    ],
    readErrorName: 'TypeError',
    readErrorMessage: readerRecoverySentinel,
    attachBodyChunks: [
      'event: final\n' +
        'data: {"type":"final","sessionId":321,"data":"W2 recovered after reader TypeError","answerMode":"streamed","evidence":[]}\n\n'
    ]
  }, {
    runStateResponse: {
      running: true,
      runStatus: 'running',
      attachable: true,
      terminal: false
    }
  });
  assert(
    readerTypeErrorRecoveryProbe.assistants.length === 1 &&
      readerTypeErrorRecoveryProbe.assistantText === 'W2 recovered after reader TypeError' &&
      !readerTypeErrorRecoveryProbe.assistant?.dataset?.failureKind &&
      !readerTypeErrorRecoveryProbe.assistant?.dataset?.failureRetryable &&
      !readerTypeErrorRecoveryProbe.assistant?.dataset?.failureStatus &&
      !readerTypeErrorRecoveryProbe.assistant?.dataset?.failureCode &&
      !readerTypeErrorRecoveryProbe.assistant?.dataset?.failureNextAction &&
      readerTypeErrorRecoveryProbe.draftAfter === '' &&
      readerTypeErrorRecoveryProbe.currentSessionId === 321 &&
      readerTypeErrorRecoveryProbe.persistedSessionId === '321' &&
      readerTypeErrorRecoveryProbe.activeRun === null &&
      readerTypeErrorRecoveryProbe.coreStatus === 'done' &&
      readerTypeErrorRecoveryProbe.streamRequestCount === 2 &&
      readerTypeErrorRecoveryProbe.stateRequestCount === 1 &&
      readerTypeErrorRecoveryProbe.attachRequestCount === 1 &&
      readerTypeErrorRecoveryProbe.initialReadCount === 2 &&
      readerTypeErrorRecoveryProbe.attachReadCount === 1 &&
      readerTypeErrorRecoveryProbe.syncRequestCount === 0 &&
      readerTypeErrorRecoveryProbe.answerEventCount === 1 &&
      !readerTypeErrorRecoveryProbe.surface.includes('message_failed') &&
      !readerTypeErrorRecoveryProbe.surface.includes(readerRecoverySentinel),
    'typed-network-exact-run-recovery-precedence-missing'
  );

  const malformedFailureSentinel = 'PRIVATE_W2_MALFORMED_JSON_SENTINEL';
  const malformedFailureProbe = await runW2FailureProbe({
    status: 503,
    contentType: 'application/json',
    bodyChunks: [`{"code":"backend_unavailable","detail":"${malformedFailureSentinel}"`]
  });
  assert(
    malformedFailureProbe.assistant?.textContent === 'message_failed' &&
      malformedFailureProbe.assistant?.dataset?.failureKind === 'service_unavailable' &&
      malformedFailureProbe.assistant?.dataset?.failureRetryable === 'true' &&
      malformedFailureProbe.assistant?.dataset?.failureStatus === '503' &&
      !malformedFailureProbe.assistant?.dataset?.failureCode &&
      malformedFailureProbe.assistant?.dataset?.failureNextAction === 'retry' &&
      malformedFailureProbe.draftAfter === malformedFailureProbe.draft &&
      malformedFailureProbe.currentSessionId === 321 &&
      malformedFailureProbe.persistedSessionId === '321' &&
      malformedFailureProbe.readerCount === 1 &&
      malformedFailureProbe.cancelCount === 0 &&
      malformedFailureProbe.fallbackBodyPassCount === 0 &&
      malformedFailureProbe.syncRequestCount === 0 &&
      malformedFailureProbe.answerEventCount === 0 &&
      !malformedFailureProbe.surface.includes(malformedFailureSentinel) &&
      !malformedFailureProbe.surface.includes('backend_unavailable'),
    'bounded-failure-body-malformed-json-missing'
  );

  const unknownFailureSentinel = 'PRIVATE_W2_UNKNOWN_TOKEN_SENTINEL';
  const unknownFailureProbe = await runW2FailureProbe({
    status: 503,
    contentType: 'text/plain',
    bodyChunks: [`prefix backend_unavailable suffix ${unknownFailureSentinel}`]
  });
  context.__w2UntrustedBodyCases = [
    { status: 503, boundedText: 'prefix backend_unavailable suffix', contentType: 'text/plain' },
    { status: 503, boundedText: JSON.stringify({ code: { value: 'backend_unavailable' } }), contentType: 'application/json' },
    { status: 503, boundedText: JSON.stringify([{ code: 'backend_unavailable' }]), contentType: 'application/json' },
    { status: 503, boundedText: JSON.stringify({ code: 503 }), contentType: 'application/json' },
    {
      status: 503,
      boundedText: JSON.stringify({ code: 'backend_unavailable', error: 'forbidden' }),
      contentType: 'application/json'
    }
  ];
  const untrustedBodyCodes = vm.runInContext(
    'globalThis.__w2UntrustedBodyCases.map((input) => classifyChatFailure(input).serverCode)',
    context
  );
  delete context.__w2UntrustedBodyCases;
  assert(
    unknownFailureProbe.assistant?.textContent === 'message_failed' &&
      unknownFailureProbe.assistant?.dataset?.failureKind === 'service_unavailable' &&
      unknownFailureProbe.assistant?.dataset?.failureRetryable === 'true' &&
      unknownFailureProbe.assistant?.dataset?.failureStatus === '503' &&
      !unknownFailureProbe.assistant?.dataset?.failureCode &&
      unknownFailureProbe.assistant?.dataset?.failureNextAction === 'retry' &&
      unknownFailureProbe.draftAfter === unknownFailureProbe.draft &&
      unknownFailureProbe.currentSessionId === 321 &&
      unknownFailureProbe.persistedSessionId === '321' &&
      unknownFailureProbe.readerCount === 1 &&
      unknownFailureProbe.cancelCount === 0 &&
      unknownFailureProbe.fallbackBodyPassCount === 0 &&
      unknownFailureProbe.syncRequestCount === 0 &&
      unknownFailureProbe.answerEventCount === 0 &&
      !unknownFailureProbe.surface.includes(unknownFailureSentinel) &&
      !unknownFailureProbe.surface.includes('backend_unavailable') &&
      untrustedBodyCodes.length === 5 &&
      untrustedBodyCodes.every((code) => code === null),
    'bounded-failure-body-unknown-token-missing'
  );

  const sseTimeoutSentinel = 'PRIVATE_W2_SSE_TIMEOUT_SENTINEL';
  const sseTimeoutProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      'event: session\n' +
        'data: {"type":"session","sessionId":321,"data":"w2-timeout-run"}\n\n' +
        'event: error\n' +
        `data: ${JSON.stringify({ type: 'error', data: 'backend_timeout', detail: sseTimeoutSentinel })}\n\n` +
        'event: token\ndata: {"type":"token","data":"LATE_W2_TIMEOUT_TOKEN"}\n\n' +
        'event: final\ndata: {"type":"final","data":"LATE_W2_TIMEOUT_FINAL"}\n\n'
    ]
  });
  assert(
    sseTimeoutProbe.assistants.length === 1 &&
      sseTimeoutProbe.assistant?.textContent === 'message_failed' &&
      sseTimeoutProbe.assistant?.dataset?.state === 'error' &&
      sseTimeoutProbe.assistant?.dataset?.failureKind === 'timeout' &&
      sseTimeoutProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !sseTimeoutProbe.assistant?.dataset?.failureStatus &&
      sseTimeoutProbe.assistant?.dataset?.failureCode === 'backend_timeout' &&
      sseTimeoutProbe.assistant?.dataset?.failureNextAction === 'retry' &&
      sseTimeoutProbe.draftAfter === sseTimeoutProbe.draft &&
      sseTimeoutProbe.currentSessionId === 321 &&
      sseTimeoutProbe.persistedSessionId === '321' &&
      sseTimeoutProbe.activeRun === null &&
      sseTimeoutProbe.coreStatus === 'error' &&
      sseTimeoutProbe.streamRequestCount === 1 &&
      sseTimeoutProbe.readCount === 1 &&
      sseTimeoutProbe.syncRequestCount === 0 &&
      sseTimeoutProbe.answerEventCount === 0 &&
      !sseTimeoutProbe.surface.includes(sseTimeoutSentinel) &&
      !sseTimeoutProbe.surface.includes('LATE_W2_TIMEOUT_TOKEN') &&
      !sseTimeoutProbe.surface.includes('LATE_W2_TIMEOUT_FINAL'),
    'typed-sse-backend-timeout-missing'
  );

  const sseSessionSentinel = 'PRIVATE_W2_SSE_SESSION_SENTINEL';
  const sseSessionProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      'event: session\n' +
        'data: {"type":"session","sessionId":321,"data":"w2-session-denial-run"}\n\n' +
        'event: error\n' +
        `data: ${JSON.stringify({ type: 'error', code: 'session_forbidden', detail: sseSessionSentinel })}\n\n` +
        'event: final\ndata: {"type":"final","data":"LATE_W2_SESSION_FINAL"}\n\n'
    ]
  });
  assert(
    sseSessionProbe.assistants.length === 1 &&
      sseSessionProbe.assistant?.textContent === 'message_failed' &&
      sseSessionProbe.assistant?.dataset?.failureKind === 'access_denied' &&
      sseSessionProbe.assistant?.dataset?.failureRetryable === 'false' &&
      !sseSessionProbe.assistant?.dataset?.failureStatus &&
      sseSessionProbe.assistant?.dataset?.failureCode === 'session_forbidden' &&
      sseSessionProbe.assistant?.dataset?.failureNextAction === 'choose_or_start_session' &&
      sseSessionProbe.draftAfter === sseSessionProbe.draft &&
      sseSessionProbe.currentSessionId === null &&
      sseSessionProbe.persistedSessionId === null &&
      sseSessionProbe.userMessageCount === 1 &&
      sseSessionProbe.activeRun === null &&
      sseSessionProbe.coreStatus === 'error' &&
      sseSessionProbe.streamRequestCount === 1 &&
      sseSessionProbe.readCount === 1 &&
      sseSessionProbe.syncRequestCount === 0 &&
      sseSessionProbe.answerEventCount === 0 &&
      !sseSessionProbe.surface.includes(sseSessionSentinel) &&
      !sseSessionProbe.surface.includes('LATE_W2_SESSION_FINAL'),
    'typed-sse-session-denial-missing'
  );

  const sseConflictUnknownCode = 'unknown_backend_failure';
  const sseConflictSentinel = 'PRIVATE_W2_SSE_FIXED_KEY_CONFLICT_SENTINEL';
  const sseFixedKeyConflictProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      'event: session\n' +
        'data: {"type":"session","sessionId":321,"data":"w2-conflict-run"}\n\n' +
        'event: error\n' +
        `data: ${JSON.stringify({
          type: 'error',
          code: sseConflictUnknownCode,
          data: 'session_forbidden',
          detail: sseConflictSentinel
        })}\n\n` +
        'event: final\ndata: {"type":"final","data":"LATE_W2_CONFLICT_FINAL"}\n\n'
    ]
  });
  assert(
    sseFixedKeyConflictProbe.assistants.length === 1 &&
      sseFixedKeyConflictProbe.assistant?.textContent === 'message_failed' &&
      sseFixedKeyConflictProbe.assistant?.dataset?.failureKind === 'stream_failed' &&
      sseFixedKeyConflictProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !sseFixedKeyConflictProbe.assistant?.dataset?.failureStatus &&
      !sseFixedKeyConflictProbe.assistant?.dataset?.failureCode &&
      sseFixedKeyConflictProbe.assistant?.dataset?.failureNextAction === 'retry_or_check_model' &&
      sseFixedKeyConflictProbe.draftAfter === sseFixedKeyConflictProbe.draft &&
      sseFixedKeyConflictProbe.currentSessionId === 321 &&
      sseFixedKeyConflictProbe.persistedSessionId === '321' &&
      sseFixedKeyConflictProbe.activeRun === null &&
      sseFixedKeyConflictProbe.coreStatus === 'error' &&
      sseFixedKeyConflictProbe.streamRequestCount === 1 &&
      sseFixedKeyConflictProbe.readCount === 1 &&
      sseFixedKeyConflictProbe.syncRequestCount === 0 &&
      sseFixedKeyConflictProbe.answerEventCount === 0 &&
      !sseFixedKeyConflictProbe.surface.includes(sseConflictUnknownCode) &&
      !sseFixedKeyConflictProbe.surface.includes('session_forbidden') &&
      !sseFixedKeyConflictProbe.surface.includes(sseConflictSentinel) &&
      !sseFixedKeyConflictProbe.surface.includes('LATE_W2_CONFLICT_FINAL'),
    'sse-fixed-key-conflict-fail-closed-missing'
  );
  context.__w2DuplicatedSseCode = { code: 'backend_timeout', data: 'backend_timeout' };
  const duplicatedAllowlistedSseCode = vm.runInContext(
    'sseFailureCode(globalThis.__w2DuplicatedSseCode)',
    context
  );
  delete context.__w2DuplicatedSseCode;
  assert(
    duplicatedAllowlistedSseCode === 'backend_timeout',
    'sse-fixed-key-duplicate-allowlisted-regression'
  );

  const sseUnknownCode = 'unknown_backend_failure';
  const sseUnknownSentinel = 'PRIVATE_W2_SSE_UNKNOWN_SENTINEL';
  const sseUnknownProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      'event: session\n' +
        'data: {"type":"session","sessionId":321,"data":"w2-unknown-run"}\n\n' +
        'event: error\n' +
        `data: ${JSON.stringify({ type: 'error', code: sseUnknownCode, detail: sseUnknownSentinel })}\n\n` +
        'event: token\ndata: {"type":"token","data":"LATE_W2_UNKNOWN_TOKEN"}\n\n'
    ]
  });
  const missingBodyProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    missingBody: true
  });
  assert(
    sseUnknownProbe.assistants.length === 1 &&
      sseUnknownProbe.assistant?.textContent === 'message_failed' &&
      sseUnknownProbe.assistant?.dataset?.failureKind === 'stream_failed' &&
      sseUnknownProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !sseUnknownProbe.assistant?.dataset?.failureStatus &&
      !sseUnknownProbe.assistant?.dataset?.failureCode &&
      sseUnknownProbe.assistant?.dataset?.failureNextAction === 'retry_or_check_model' &&
      sseUnknownProbe.draftAfter === sseUnknownProbe.draft &&
      sseUnknownProbe.currentSessionId === 321 &&
      sseUnknownProbe.persistedSessionId === '321' &&
      sseUnknownProbe.activeRun === null &&
      sseUnknownProbe.coreStatus === 'error' &&
      sseUnknownProbe.streamRequestCount === 1 &&
      sseUnknownProbe.readCount === 1 &&
      sseUnknownProbe.syncRequestCount === 0 &&
      sseUnknownProbe.answerEventCount === 0 &&
      !sseUnknownProbe.surface.includes(sseUnknownCode) &&
      !sseUnknownProbe.surface.includes(sseUnknownSentinel) &&
      !sseUnknownProbe.surface.includes('LATE_W2_UNKNOWN_TOKEN') &&
      missingBodyProbe.assistant?.textContent === 'message_failed' &&
      missingBodyProbe.assistant?.dataset?.failureKind === 'stream_failed' &&
      missingBodyProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !missingBodyProbe.assistant?.dataset?.failureStatus &&
      !missingBodyProbe.assistant?.dataset?.failureCode &&
      missingBodyProbe.assistant?.dataset?.failureNextAction === 'retry_or_check_model' &&
      missingBodyProbe.draftAfter === missingBodyProbe.draft &&
      missingBodyProbe.currentSessionId === 321 &&
      missingBodyProbe.persistedSessionId === '321' &&
      missingBodyProbe.streamRequestCount === 1 &&
      missingBodyProbe.syncRequestCount === 0 &&
      missingBodyProbe.answerEventCount === 0,
    'typed-sse-unknown-error-missing'
  );

  const streamFailedPartialSentinel = 'PRIVATE_W2_STREAM_FAILED_PARTIAL_SENTINEL';
  const streamFailedProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      `event: token\ndata: ${JSON.stringify({ type: 'token', data: streamFailedPartialSentinel })}\n\n` +
        'event: stream_failed\ndata: {"type":"stream_failed","code":"unknown_remote_failure"}\n\n'
    ]
  });
  const overflowTerminalSentinel = 'PRIVATE_W2_TYPED_OVERFLOW_SENTINEL';
  const overflowTerminalProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      'event: session\n' +
        'data: {"type":"session","sessionId":321,"data":"w2-overflow-run"}\n\n' +
        'event: token\n' +
        `data: ${JSON.stringify({ type: 'token', data: overflowTerminalSentinel + 'X'.repeat(131073) })}\n\n` +
        'event: final\ndata: {"type":"final","data":"LATE_W2_OVERFLOW_FINAL"}\n\n'
    ]
  });
  assert(
    streamFailedProbe.assistants.length === 1 &&
      streamFailedProbe.assistant?.textContent === 'message_failed' &&
      streamFailedProbe.assistant?.dataset?.failureKind === 'stream_failed' &&
      streamFailedProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !streamFailedProbe.assistant?.dataset?.failureStatus &&
      !streamFailedProbe.assistant?.dataset?.failureCode &&
      streamFailedProbe.assistant?.dataset?.failureNextAction === 'retry_or_check_model' &&
      streamFailedProbe.draftAfter === streamFailedProbe.draft &&
      streamFailedProbe.currentSessionId === 321 &&
      streamFailedProbe.persistedSessionId === '321' &&
      streamFailedProbe.activeRun === null &&
      streamFailedProbe.coreStatus === 'error' &&
      streamFailedProbe.streamRequestCount === 1 &&
      streamFailedProbe.readCount === 1 &&
      streamFailedProbe.syncRequestCount === 0 &&
      streamFailedProbe.answerEventCount === 0 &&
      !streamFailedProbe.surface.includes(streamFailedPartialSentinel) &&
      overflowTerminalProbe.assistants.length === 1 &&
      overflowTerminalProbe.assistant?.textContent === 'message_failed' &&
      overflowTerminalProbe.assistant?.dataset?.failureKind === 'stream_failed' &&
      overflowTerminalProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !overflowTerminalProbe.assistant?.dataset?.failureStatus &&
      overflowTerminalProbe.assistant?.dataset?.failureCode === 'sse_event_too_large' &&
      overflowTerminalProbe.assistant?.dataset?.failureNextAction === 'retry_or_check_model' &&
      overflowTerminalProbe.draftAfter === overflowTerminalProbe.draft &&
      overflowTerminalProbe.currentSessionId === 321 &&
      overflowTerminalProbe.persistedSessionId === '321' &&
      overflowTerminalProbe.activeRun === null &&
      overflowTerminalProbe.coreStatus === 'error' &&
      overflowTerminalProbe.streamRequestCount === 1 &&
      overflowTerminalProbe.readCount === 1 &&
      overflowTerminalProbe.syncRequestCount === 0 &&
      overflowTerminalProbe.answerEventCount === 0 &&
      !overflowTerminalProbe.surface.includes(overflowTerminalSentinel) &&
      !overflowTerminalProbe.surface.includes('LATE_W2_OVERFLOW_FINAL'),
    'stream-failed-terminal-missing'
  );

  const malformedFinalSentinel = 'PRIVATE_W2_MALFORMED_FINAL_SENTINEL';
  const malformedFinalProbe = await runW2FailureProbe({
    ok: true,
    status: 200,
    contentType: 'text/event-stream',
    bodyChunks: [
      'event: session\n' +
        'data: {"type":"session","sessionId":321,"data":"w2-malformed-final-run"}\n\n' +
        'event: token\ndata: {"type":"token","data":"PARTIAL_BEFORE_MALFORMED_FINAL"}\n\n' +
        'event: final\n' +
        `data: {"type":"final","data":"${malformedFinalSentinel}"\n\n` +
        'event: token\ndata: {"type":"token","data":"LATE_W2_MALFORMED_TOKEN"}\n\n'
    ]
  });
  assert(
    malformedFinalProbe.assistants.length === 1 &&
      malformedFinalProbe.assistant?.textContent === 'message_failed' &&
      malformedFinalProbe.assistant?.dataset?.failureKind === 'stream_failed' &&
      malformedFinalProbe.assistant?.dataset?.failureRetryable === 'true' &&
      !malformedFinalProbe.assistant?.dataset?.failureStatus &&
      malformedFinalProbe.assistant?.dataset?.failureCode === 'malformed_terminal_event' &&
      malformedFinalProbe.assistant?.dataset?.failureNextAction === 'retry_or_check_model' &&
      malformedFinalProbe.draftAfter === malformedFinalProbe.draft &&
      malformedFinalProbe.currentSessionId === 321 &&
      malformedFinalProbe.persistedSessionId === '321' &&
      malformedFinalProbe.activeRun === null &&
      malformedFinalProbe.coreStatus === 'error' &&
      malformedFinalProbe.streamRequestCount === 1 &&
      malformedFinalProbe.readCount === 1 &&
      malformedFinalProbe.syncRequestCount === 0 &&
      malformedFinalProbe.answerEventCount === 0 &&
      !malformedFinalProbe.surface.includes(malformedFinalSentinel) &&
      !malformedFinalProbe.surface.includes('PARTIAL_BEFORE_MALFORMED_FINAL') &&
      !malformedFinalProbe.surface.includes('LATE_W2_MALFORMED_TOKEN'),
    'malformed-final-fail-closed-missing'
  );

  const w3 = {
    listRequest: false,
    listCache: false,
    listRequestId: false,
    listCorrelation: false,
    noFabricatedSession: false,
    validRow: false,
    missingIdRejected: false,
    zeroIdRejected: false,
    negativeIdRejected: false,
    fractionalIdRejected: false,
    stringIdRejected: false,
    bounded: false,
    empty: false,
    diagnosticOwnership: false,
    forbidden: false,
    unavailable: false,
    invalidJson: false,
    nonArray: false,
    network: false,
    activeRunGuard: false,
    detailUrl: false,
    detailRequestId: false,
    detailCorrelation: false,
    precommit: false,
    mismatchAtomic: false,
    invalidAtomic: false,
    failureAtomic: false,
    staleSelectionAtomic: false,
    atomicCommit: false,
    terminalRefresh: false,
    refreshOverlap: false,
    staleRefresh: false
  };
  const w3FailureRows = [];
  const w3Quality = {
    ownershipCycleAtomic: false,
    newChatSelectionCleared: false,
    duplicateIdCollapsed: false
  };
  const w3QualityFailureRows = [];
  const w3CapturedConsole = [];
  const w3OriginalContextConsole = context.console;
  const w3Check = (condition, reason, detail = '') => {
    if (!condition) w3FailureRows.push({ reason, detail: String(detail || '') });
  };
  const w3FunctionsAvailable = vm.runInContext(
    "typeof strictBackendSessionId === 'function' && " +
      "typeof renderSessionList === 'function' && " +
      "typeof syncSessionSelectionCapability === 'function' && " +
      "typeof refreshSessionList === 'function' && " +
      "typeof validateSessionDetail === 'function' && " +
      "typeof selectSessionCandidate === 'function'",
    context
  );

  const w3OwnedRows = (selector) => Array.from(sessionModeList.querySelectorAll(selector));
  const w3NodeSurface = (root) => {
    const rows = [];
    const stack = [root];
    while (stack.length) {
      const node = stack.shift();
      if (!node) continue;
      rows.push(JSON.stringify({
        text: node.textContent || '',
        title: node.title || '',
        dataset: node.dataset || {},
        aria: node.getAttribute?.('aria-label') || '',
        disabled: node.disabled === true
      }));
      stack.push(...(node.children || []));
    }
    return rows.join('|');
  };
  const resetW3List = () => {
    sessionModeList.replaceChildren();
    sessionModeList.dataset = {};
  };
  const w3VisibleSnapshot = () => JSON.stringify({
    sessionId: vm.runInContext('state.currentSessionId', context),
    storedSessionId: context.window.sessionStorage.getItem('chat.currentSessionId'),
    model: elements.get('modelSelect').value,
    searchMode: elements.get('searchModeSelect').value,
    useRag: elements.get('useRagToggle').checked,
    transcript: elements.get('chatWindow').children.map((node) => ({
      text: node.textContent,
      role: node.dataset?.messageRole,
      state: node.dataset?.state
    })),
    list: sessionModeList.children.map((node) => ({
      text: node.textContent,
      dataset: { ...node.dataset },
      selected: node.getAttribute?.('aria-pressed') || null,
      disabled: node.disabled === true
    }))
  });
  const w3ListCalls = () => fetchCalls.filter((call) => call.url === '/api/chat/sessions');
  const w3DetailCalls = () => fetchCalls.filter((call) => /^\/api\/chat\/sessions\/\d+$/.test(call.url));
  const waitForW3Turn = () => new Promise((resolve) => setImmediate(resolve));
  const runW3ListScenario = async (scenario) => {
    resetW3List();
    context.__w3ListScenario = scenario;
    await vm.runInContext("refreshSessionList('w3-state-probe')", context);
    return {
      state: sessionModeList.dataset.sessionListState,
      retryable: sessionModeList.dataset.sessionListRetryable,
      rows: w3OwnedRows('[data-session-list-state]'),
      surface: w3NodeSurface(sessionModeList)
    };
  };

  if (w3FunctionsAvailable) {
    context.console = {
      log: (...args) => w3CapturedConsole.push(args.join(' ')),
      info: (...args) => w3CapturedConsole.push(args.join(' ')),
      warn: (...args) => w3CapturedConsole.push(args.join(' ')),
      error: (...args) => w3CapturedConsole.push(args.join(' ')),
      debug: (...args) => w3CapturedConsole.push(args.join(' '))
    };
    const initListCallCount = sessionListInitCallCount;
    resetW3List();
    vm.runInContext("clearActiveRunIdentity(); rememberCurrentSessionId(42);", context);
    const listSecret = 'W3_PRIVATE_LIST_SENTINEL@example.invalid';
    context.__w3ListScenario = {
      status: 200,
      body: [
        { id: 101, title: `Primary ${listSecret} https://private.invalid/row`, answerMode: 'CHAT', lastTraceTurnId: 501 },
        { title: 'missing' },
        { id: 0, title: 'zero' },
        { id: -1, title: 'negative' },
        { id: 1.5, title: 'fractional' },
        { id: '102', title: 'string' },
        { id: Number.MAX_SAFE_INTEGER + 1, title: 'unsafe' }
      ]
    };
    const listCallBaseline = w3ListCalls().length;
    await vm.runInContext("refreshSessionList('w3-primary')", context);
    const primaryListCalls = w3ListCalls().slice(listCallBaseline);
    const primaryListCall = primaryListCalls[0];
    const primaryRows = w3OwnedRows('[data-session-list-row]');
    w3.listRequest = initListCallCount === 1 && primaryListCalls.length === 1 &&
      primaryListCall?.method === 'GET' && primaryListCall?.url === '/api/chat/sessions';
    w3.listCache = primaryListCall?.cache === 'no-store';
    w3.listRequestId = Boolean(fetchHeader(primaryListCall, 'x-request-id'));
    w3.listCorrelation = fetchHeader(primaryListCall, 'x-session-id') === '42';
    w3.validRow = primaryRows.length === 1 && primaryRows[0].dataset.sessionId === '101' &&
      primaryRows[0].textContent.includes('Primary') &&
      !w3NodeSurface(sessionModeList).includes(listSecret) &&
      !w3NodeSurface(sessionModeList).includes('private.invalid');
    w3.missingIdRejected = vm.runInContext('strictBackendSessionId(undefined) === null', context);
    w3.zeroIdRejected = vm.runInContext('strictBackendSessionId(0) === null', context);
    w3.negativeIdRejected = vm.runInContext('strictBackendSessionId(-1) === null', context);
    w3.fractionalIdRejected = vm.runInContext('strictBackendSessionId(1.5) === null', context);
    w3.stringIdRejected = vm.runInContext("strictBackendSessionId('102') === null", context);

    vm.runInContext("state.currentSessionId = '42';", context);
    context.window.sessionStorage.removeItem('chat.currentSessionId');
    context.__w3ListScenario = { status: 200, body: [] };
    const noSessionBaseline = w3ListCalls().length;
    await vm.runInContext("refreshSessionList('w3-no-session')", context);
    const noSessionCall = w3ListCalls()[noSessionBaseline];
    w3.noFabricatedSession = Boolean(noSessionCall) && fetchHeader(noSessionCall, 'x-session-id') == null;

    resetW3List();
    context.__w3BoundRows = Array.from({ length: 15 }, (_, index) => ({
      id: index + 1,
      title: `Session ${index + 1}`,
      answerMode: index % 2 ? 'RAG' : 'CHAT',
      lastTraceTurnId: 700 + index
    }));
    vm.runInContext("renderSessionList({ rows: globalThis.__w3BoundRows, state: 'ready', retryable: false })", context);
    const boundedRows = w3OwnedRows('[data-session-list-row]');
    w3.bounded = boundedRows.length === 12 && boundedRows[0].dataset.sessionId === '1' &&
      boundedRows[11].dataset.sessionId === '12' &&
      vm.runInContext('strictBackendSessionId(Number.MAX_SAFE_INTEGER + 1) === null', context);
    delete context.__w3BoundRows;

    resetW3List();
    vm.runInContext("renderSessionList({ rows: [], state: 'ready', retryable: false })", context);
    const emptyRows = w3OwnedRows('[data-session-list-state]');
    w3.empty = sessionModeList.dataset.sessionListState === 'empty' && emptyRows.length === 1 &&
      Boolean(emptyRows[0].textContent) && !emptyRows[0].textContent.includes('undefined');

    resetW3List();
    const diagnosticRow = fakeElement('w3-diagnostic-row');
    diagnosticRow.dataset.sessionModeRow = 'true';
    diagnosticRow.dataset.sessionModeSessionId = '42';
    diagnosticRow.textContent = 'diagnostic-safe';
    sessionModeList.appendChild(diagnosticRow);
    context.__w3CollisionRows = [{ id: 42, title: 'Collision check', answerMode: 'CHAT', lastTraceTurnId: 808 }];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3CollisionRows, state: 'ready', retryable: false })", context);
    const diagnosticPreservedByRender = w3OwnedRows('[data-session-mode-row]').length === 1 &&
      w3OwnedRows('[data-session-list-row]').length === 1;
    vm.runInContext('clearSessionModeDiagnostics()', context);
    w3.diagnosticOwnership = diagnosticPreservedByRender &&
      w3OwnedRows('[data-session-mode-row]').length === 0 &&
      w3OwnedRows('[data-session-list-row]').length === 1;
    delete context.__w3CollisionRows;

    const forbiddenSentinel = 'PRIVATE_W3_FORBIDDEN_SENTINEL';
    const forbiddenProbe = await runW3ListScenario({ status: 403, body: { detail: forbiddenSentinel } });
    w3.forbidden = forbiddenProbe.state === 'session_list_forbidden' &&
      forbiddenProbe.retryable === 'false' && forbiddenProbe.rows.length === 1 &&
      !forbiddenProbe.surface.includes(forbiddenSentinel);
    const unavailableSentinel = 'PRIVATE_W3_UNAVAILABLE_SENTINEL';
    const unavailableProbe = await runW3ListScenario({ status: 503, body: { detail: unavailableSentinel } });
    w3.unavailable = unavailableProbe.state === 'session_list_unavailable' &&
      unavailableProbe.retryable === 'true' && unavailableProbe.rows.length === 1 &&
      !unavailableProbe.surface.includes(unavailableSentinel);
    const invalidJsonSentinel = 'PRIVATE_W3_INVALID_JSON_SENTINEL';
    const invalidJsonProbe = await runW3ListScenario({
      status: 200,
      invalidJson: true,
      invalidJsonMessage: invalidJsonSentinel
    });
    w3.invalidJson = invalidJsonProbe.state === 'session_list_invalid_response' &&
      invalidJsonProbe.retryable === 'true' && invalidJsonProbe.rows.length === 1 &&
      !invalidJsonProbe.surface.includes(invalidJsonSentinel);
    const nonArraySentinel = 'PRIVATE_W3_NON_ARRAY_SENTINEL';
    const nonArrayProbe = await runW3ListScenario({ status: 200, body: { detail: nonArraySentinel } });
    w3.nonArray = nonArrayProbe.state === 'session_list_invalid_response' &&
      nonArrayProbe.retryable === 'true' && nonArrayProbe.rows.length === 1 &&
      !nonArrayProbe.surface.includes(nonArraySentinel);
    const networkSentinel = 'PRIVATE_W3_NETWORK_SENTINEL';
    const networkProbe = await runW3ListScenario({ networkError: true, networkMessage: networkSentinel });
    w3.network = networkProbe.state === 'session_list_network_error' &&
      networkProbe.retryable === 'true' && networkProbe.rows.length === 1 &&
      !networkProbe.surface.includes(networkSentinel);

    resetW3List();
    context.__w3GuardRows = [{ id: 202, title: 'Guarded session', answerMode: 'CHAT', lastTraceTurnId: 901 }];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3GuardRows, state: 'ready', retryable: false }); rememberActiveRunIdentity(42, 'w3-active-run'); syncSessionSelectionCapability();", context);
    const guardedButton = w3OwnedRows('[data-session-list-row]')[0];
    const guardedDetailBaseline = w3DetailCalls().length;
    await vm.runInContext('selectSessionCandidate(202)', context);
    const exactRunGuarded = w3DetailCalls().length === guardedDetailBaseline && guardedButton?.disabled === true &&
      guardedButton?.getAttribute('aria-disabled') === 'true';
    vm.runInContext('clearActiveRunIdentity()', context);
    context.__w3ActiveStreamAssistant = fakeElement('w3-active-stream-assistant');
    vm.runInContext('activeStreamAssistant = globalThis.__w3ActiveStreamAssistant; syncSessionSelectionCapability()', context);
    const streamGuardBaseline = w3DetailCalls().length;
    await vm.runInContext('selectSessionCandidate(202)', context);
    w3.activeRunGuard = exactRunGuarded && w3DetailCalls().length === streamGuardBaseline &&
      guardedButton?.disabled === true && guardedButton?.getAttribute('aria-disabled') === 'true';
    vm.runInContext('activeStreamAssistant = null; syncSessionSelectionCapability()', context);
    delete context.__w3ActiveStreamAssistant;
    delete context.__w3GuardRows;

    resetW3List();
    elements.get('chatWindow').replaceChildren();
    elements.get('modelSelect').value = 'local';
    elements.get('searchModeSelect').value = 'AUTO';
    elements.get('useRagToggle').checked = true;
    vm.runInContext("rememberCurrentSessionId(42); appendMessage('user', 'before selection');", context);
    const priorSelectionAssistant = fakeElement('selection-prior-session-candidate');
    priorSelectionAssistant.className = 'message assistant';
    priorSelectionAssistant.dataset = { speaker: 'assistant', state: 'ready' };
    elements.get('chatWindow').appendChild(priorSelectionAssistant);
    const priorSessionSelectionCard = installSelectionEntropyCard(priorSelectionAssistant);
    assert(priorSessionSelectionCard, 'accepted session-selection precondition must install a selection card');
    context.__w3SelectionRows = [{ id: 202, title: 'Selected session', answerMode: 'RAG', lastTraceTurnId: 902 }];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3SelectionRows, state: 'ready', retryable: false })", context);
    const selectionBefore = w3VisibleSnapshot();
    context.__w3DetailScenario = {
      status: 200,
      deferJson: true,
      body: {
        id: 202,
        title: 'Selected session',
        messages: [
          { role: 'user', content: 'restored user' },
          { role: 'system', content: 'must be filtered' },
          { role: 'assistant', content: 'restored assistant' },
          { role: 'assistant', content: 7 }
        ],
        modelUsed: 'gemma3:4b',
        settings: { model: 'gemma3:4b', searchMode: 'OFF', useRag: false }
      }
    };
    const detailCallBaseline = w3DetailCalls().length;
    const pendingSelection = vm.runInContext('selectSessionCandidate(202)', context);
    await waitForW3Turn();
    const detailCall = w3DetailCalls()[detailCallBaseline];
    w3.detailUrl = detailCall?.url === '/api/chat/sessions/202' && detailCall?.cache === undefined;
    w3.detailRequestId = Boolean(fetchHeader(detailCall, 'x-request-id'));
    w3.detailCorrelation = fetchHeader(detailCall, 'x-session-id') === '202';
    w3.precommit = typeof context.__w3ResolveDetailJson === 'function' && w3VisibleSnapshot() === selectionBefore;
    context.__w3ResolveDetailJson();
    const selectionCommitted = await pendingSelection;
    const selectedRows = w3OwnedRows('[data-session-list-row]');
    const selectedModeRows = w3OwnedRows('[data-session-mode-row]');
    const restoredMessages = elements.get('chatWindow').children;
    w3.atomicCommit = selectionCommitted === true && vm.runInContext('state.currentSessionId', context) === 202 &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === '202' &&
      elements.get('modelSelect').value === 'gemma3:4b' &&
      elements.get('searchModeSelect').value === 'OFF' && elements.get('useRagToggle').checked === false &&
      restoredMessages.length === 2 && restoredMessages[0].dataset.messageRole === 'user' &&
      restoredMessages[1].dataset.messageRole === 'assistant' &&
      !priorSelectionAssistant.querySelector('[data-selection-entropy-card]') &&
      selectedRows.length === 1 && selectedRows[0].getAttribute('aria-pressed') === 'true' &&
      selectedModeRows.length === 1 && selectedModeRows[0].dataset.sessionModeSessionId === '202';

    const rejectedSelectionCard = installSelectionEntropyCard(restoredMessages[1]);
    assert(rejectedSelectionCard, 'rejected session-selection precondition must install a selection card');
    const atomicBaseline = w3VisibleSnapshot();
    context.__w3DetailScenario = {
      status: 200,
      body: { id: 999, messages: [{ role: 'user', content: 'mismatch' }], settings: {} }
    };
    const mismatchResult = await vm.runInContext('selectSessionCandidate(203)', context);
    w3.mismatchAtomic = mismatchResult === false && w3VisibleSnapshot() === atomicBaseline &&
      restoredMessages[1].querySelector('[data-selection-entropy-card]') === rejectedSelectionCard;

    context.__w3DetailScenario = {
      status: 200,
      body: { id: 203, messages: null, settings: 'invalid' }
    };
    const invalidResult = await vm.runInContext('selectSessionCandidate(203)', context);
    context.__w3AbsentSettingsDetail = { id: 204, messages: [] };
    context.__w3FoundFalseDetail = { id: 204, found: false, messages: [], settings: {} };
    context.__w3ArraySettingsDetail = { id: 204, messages: [], settings: [] };
    const pureValidationShapes = vm.runInContext(
      'validateSessionDetail(204, globalThis.__w3AbsentSettingsDetail)?.id === 204 && ' +
        'validateSessionDetail(204, globalThis.__w3FoundFalseDetail) === null && ' +
        'validateSessionDetail(204, globalThis.__w3ArraySettingsDetail) === null',
      context
    );
    w3.invalidAtomic = invalidResult === false && w3VisibleSnapshot() === atomicBaseline && pureValidationShapes &&
      restoredMessages[1].querySelector('[data-selection-entropy-card]') === rejectedSelectionCard;
    delete context.__w3AbsentSettingsDetail;
    delete context.__w3FoundFalseDetail;
    delete context.__w3ArraySettingsDetail;

    context.__w3DetailScenario = { status: 503, body: { detail: 'PRIVATE_W3_DETAIL_FAILURE_SENTINEL' } };
    const failureResult = await vm.runInContext('selectSessionCandidate(203)', context);
    w3.failureAtomic = failureResult === false && w3VisibleSnapshot() === atomicBaseline &&
      restoredMessages[1].querySelector('[data-selection-entropy-card]') === rejectedSelectionCard;

    resetW3List();
    context.__w3StaleRows = [
      { id: 301, title: 'Older selection', answerMode: 'CHAT', lastTraceTurnId: 930 },
      { id: 302, title: 'Newer selection', answerMode: 'RAG', lastTraceTurnId: 931 }
    ];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3StaleRows, state: 'ready', retryable: false })", context);
    context.__w3DetailScenario = {
      status: 200,
      deferJson: true,
      body: { id: 301, messages: [{ role: 'assistant', content: 'older' }], settings: {} }
    };
    const staleFirst = vm.runInContext('selectSessionCandidate(301)', context);
    await waitForW3Turn();
    const resolveStaleFirst = context.__w3ResolveDetailJson;
    context.__w3DetailScenario = {
      status: 200,
      body: { id: 302, messages: [{ role: 'assistant', content: 'newer' }], settings: {} }
    };
    const staleSecondResult = await vm.runInContext('selectSessionCandidate(302)', context);
    const afterNewerSelection = w3VisibleSnapshot();
    resolveStaleFirst();
    const staleFirstResult = await staleFirst;
    w3.staleSelectionAtomic = staleSecondResult === true && staleFirstResult === false &&
      vm.runInContext('state.currentSessionId', context) === 302 &&
      w3VisibleSnapshot() === afterNewerSelection;

    context.__w3ListScenario = { status: 200, body: [] };
    vm.runInContext('clearActiveRunIdentity()', context);
    const terminalAssistant = fakeElement('w3-terminal-assistant');
    elements.get('chatWindow').appendChild(terminalAssistant);
    const terminalBaseline = w3ListCalls().length;
    context.__w3TerminalAssistant = terminalAssistant;
    vm.runInContext("renderChatEvent({ type: 'final', sessionId: 302, data: 'terminal answer', answerMode: 'CHAT', evidence: [] }, globalThis.__w3TerminalAssistant, 'final')", context);
    await waitForW3Turn();
    const afterValidFinal = w3ListCalls().length;
    vm.runInContext('state.currentSessionId = null;', context);
    context.window.sessionStorage.removeItem('chat.currentSessionId');
    const unboundFinalAssistant = fakeElement('w3-unbound-final');
    elements.get('chatWindow').appendChild(unboundFinalAssistant);
    context.__w3UnboundFinalAssistant = unboundFinalAssistant;
    vm.runInContext("renderChatEvent({ type: 'final', data: 'unbound final', answerMode: 'CHAT', evidence: [] }, globalThis.__w3UnboundFinalAssistant, 'final')", context);
    await waitForW3Turn();
    const afterUnboundFinal = w3ListCalls().length;
    const terminalFailureAssistant = fakeElement('w3-terminal-failure');
    elements.get('chatWindow').appendChild(terminalFailureAssistant);
    context.__w3TerminalFailureAssistant = terminalFailureAssistant;
    vm.runInContext("renderChatEvent({ type: 'error', code: 'backend_timeout' }, globalThis.__w3TerminalFailureAssistant, 'error'); renderChatEvent({ type: 'stream_failed', code: 'stream_failed' }, globalThis.__w3TerminalFailureAssistant, 'stream_failed')", context);
    await waitForW3Turn();
    w3.terminalRefresh = afterValidFinal === terminalBaseline + 1 &&
      afterUnboundFinal === afterValidFinal && w3ListCalls().length === afterUnboundFinal;
    delete context.__w3TerminalAssistant;
    delete context.__w3UnboundFinalAssistant;
    delete context.__w3TerminalFailureAssistant;

    resetW3List();
    context.__w3ListScenario = { status: 200, deferResponse: true, body: [{ id: 401, title: 'Coalesced' }] };
    const overlapBaseline = w3ListCalls().length;
    const overlapFirst = vm.runInContext("refreshSessionList('overlap-first')", context);
    const overlapSecond = vm.runInContext("refreshSessionList('overlap-second')", context);
    await waitForW3Turn();
    const overlapCallCount = w3ListCalls().length - overlapBaseline;
    context.__w3ResolveListResponse();
    const overlapResults = await Promise.all([overlapFirst, overlapSecond]);
    w3.refreshOverlap = overlapFirst === overlapSecond && overlapCallCount === 1 &&
      overlapResults[0] === overlapResults[1] && w3OwnedRows('[data-session-list-row]').length === 1;

    context.__w3ListScenario = {
      status: 200,
      deferResponse: true,
      body: [{ id: 999, title: 'PRIVATE_W3_STALE_LIST_SENTINEL' }]
    };
    const staleRefresh = vm.runInContext("refreshSessionList('stale-before-selection')", context);
    await waitForW3Turn();
    context.__w3DetailScenario = {
      status: 200,
      body: { id: 402, messages: [{ role: 'assistant', content: 'fresh selection' }], settings: {} }
    };
    const refreshSelectionResult = await vm.runInContext('selectSessionCandidate(402)', context);
    context.__w3ResolveListResponse();
    await staleRefresh;
    w3.staleRefresh = refreshSelectionResult === true && vm.runInContext('state.currentSessionId', context) === 402 &&
      w3OwnedRows('[data-session-list-row]').every((row) => row.dataset.sessionId !== '999') &&
      !w3NodeSurface(sessionModeList).includes('PRIVATE_W3_STALE_LIST_SENTINEL');

    w3.validRow = w3.validRow &&
      template.includes('aria-label="Session history and mode diagnostics"') &&
      !template.includes('aria-label="Session mode history"');
    const w3PublicSurface = [
      w3NodeSurface(sessionModeList),
      w3NodeSurface(elements.get('chatWindow')),
      ...[
        'streamStatus', 'modelStatus', 'searchStatus', 'ragStatus',
        'traceStatus', 'qualityStatus', 'healthStatus', 'coreStatusRail'
      ].map((id) => w3NodeSurface(elements.get(id))),
      ...w3CapturedConsole
    ].join('|');
    w3.network = w3.network && !/(?:PRIVATE_W3_|W3_PRIVATE_LIST_SENTINEL)/.test(w3PublicSurface);

    resetW3List();
    elements.get('chatWindow').replaceChildren();
    vm.runInContext("activeStreamAssistant = null; clearActiveRunIdentity(); rememberCurrentSessionId(500);", context);
    context.__w3QualityOwnershipRows = [{ id: 501, title: 'Older candidate', answerMode: 'CHAT', lastTraceTurnId: 950 }];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3QualityOwnershipRows, state: 'ready', retryable: false })", context);
    context.__w3DetailScenario = {
      status: 200,
      deferJson: true,
      body: { id: 501, messages: [{ role: 'assistant', content: 'older exact-run detail' }], settings: {} }
    };
    const exactOwnershipSelection = vm.runInContext('selectSessionCandidate(501)', context);
    await waitForW3Turn();
    const resolveExactOwnershipDetail = context.__w3ResolveDetailJson;
    elements.get('chatWindow').replaceChildren();
    vm.runInContext("appendMessage('user', 'newer exact-run user'); appendMessage('assistant', 'newer exact-run answer'); rememberActiveRunIdentity(500, 'quality-newer-run'); clearActiveRunIdentity();", context);
    const exactOwnershipSnapshot = w3VisibleSnapshot();
    resolveExactOwnershipDetail();
    const exactOwnershipResult = await exactOwnershipSelection;
    const exactOwnershipPreserved = exactOwnershipResult === false &&
      vm.runInContext('state.currentSessionId', context) === 500 &&
      w3VisibleSnapshot() === exactOwnershipSnapshot;

    resetW3List();
    elements.get('chatWindow').replaceChildren();
    vm.runInContext("activeStreamAssistant = null; clearActiveRunIdentity(); rememberCurrentSessionId(510);", context);
    context.__w3QualityOwnershipRows = [{ id: 511, title: 'Older tokenless candidate', answerMode: 'RAG', lastTraceTurnId: 951 }];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3QualityOwnershipRows, state: 'ready', retryable: false })", context);
    context.__w3DetailScenario = {
      status: 200,
      deferJson: true,
      body: { id: 511, messages: [{ role: 'assistant', content: 'older tokenless detail' }], settings: {} }
    };
    const tokenlessOwnershipSelection = vm.runInContext('selectSessionCandidate(511)', context);
    await waitForW3Turn();
    const resolveTokenlessOwnershipDetail = context.__w3ResolveDetailJson;
    elements.get('chatWindow').replaceChildren();
    vm.runInContext("appendMessage('user', 'newer tokenless user'); appendMessage('assistant', 'newer tokenless answer');", context);
    const tokenlessAssistant = fakeElement('w3-quality-tokenless-assistant');
    elements.set('w3-quality-tokenless-assistant', tokenlessAssistant);
    context.__streamMode = 'w1-sse-frames';
    context.__w1StreamChunks = [
      'event: token\ndata: {"type":"token","data":"newer tokenless stream"}\n\n'
    ];
    elements.get('coreStatusRail').dataset.coreStatus = 'idle';
    await vm.runInContext("streamChat({ message: 'quality tokenless ownership' }, 'w3-quality-tokenless-assistant')", context);
    const tokenlessOwnershipSnapshot = w3VisibleSnapshot();
    resolveTokenlessOwnershipDetail();
    const tokenlessOwnershipResult = await tokenlessOwnershipSelection;
    const tokenlessOwnershipPreserved = tokenlessOwnershipResult === false &&
      vm.runInContext('state.currentSessionId', context) === 510 &&
      w3VisibleSnapshot() === tokenlessOwnershipSnapshot;
    w3Quality.ownershipCycleAtomic = exactOwnershipPreserved && tokenlessOwnershipPreserved;
    elements.delete('w3-quality-tokenless-assistant');
    context.__streamMode = null;
    context.__w1StreamChunks = [];

    resetW3List();
    vm.runInContext("activeStreamAssistant = null; clearActiveRunIdentity(); rememberCurrentSessionId(601);", context);
    context.__w3QualityNewChatRows = [
      { id: 601, title: 'Selected before new chat', answerMode: 'CHAT', lastTraceTurnId: 960 },
      { id: 602, title: 'Retained choice', answerMode: 'RAG', lastTraceTurnId: 961 }
    ];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3QualityNewChatRows, state: 'ready', retryable: false })", context);
    const newChatRowsBefore = w3OwnedRows('[data-session-list-row]');
    const newChatSelectedBefore = newChatRowsBefore.filter((row) =>
      row.dataset.sessionSelected === 'true' && row.getAttribute('aria-pressed') === 'true');
    elements.get('messageInput').disabled = false;
    const newChatResult = vm.runInContext('startNewChatSession()', context);
    const newChatRowsAfter = w3OwnedRows('[data-session-list-row]');
    w3Quality.newChatSelectionCleared = newChatSelectedBefore.length === 1 &&
      newChatResult === true && vm.runInContext('state.currentSessionId', context) === null &&
      context.window.sessionStorage.getItem('chat.currentSessionId') === null &&
      newChatRowsAfter.length === 2 && newChatRowsAfter.every((row) =>
        row.dataset.sessionSelected === 'false' && row.getAttribute('aria-pressed') === 'false');

    resetW3List();
    vm.runInContext('rememberCurrentSessionId(701)', context);
    context.__w3QualityDuplicateRows = [
      { id: 701, title: 'First duplicate wins', answerMode: 'CHAT', lastTraceTurnId: 970 },
      { id: 701, title: 'Second duplicate rejected', answerMode: 'RAG', lastTraceTurnId: 971 },
      { id: 702, title: 'Next unique row', answerMode: 'RAG', lastTraceTurnId: 972 }
    ];
    vm.runInContext("renderSessionList({ rows: globalThis.__w3QualityDuplicateRows, state: 'ready', retryable: false })", context);
    const duplicateRows = w3OwnedRows('[data-session-list-row]');
    const duplicateIdRows = duplicateRows.filter((row) => row.dataset.sessionId === '701');
    const duplicateSelectedRows = duplicateRows.filter((row) =>
      row.dataset.sessionSelected === 'true' && row.getAttribute('aria-pressed') === 'true');
    w3Quality.duplicateIdCollapsed = duplicateRows.length === 2 && duplicateIdRows.length === 1 &&
      duplicateIdRows[0].textContent.includes('First duplicate wins') &&
      !duplicateIdRows[0].textContent.includes('Second duplicate rejected') &&
      duplicateSelectedRows.length === 1 && duplicateSelectedRows[0] === duplicateIdRows[0];

    context.console = w3OriginalContextConsole;
    delete context.__w3ListScenario;
    delete context.__w3DetailScenario;
    delete context.__w3SelectionRows;
    delete context.__w3StaleRows;
    delete context.__w3QualityOwnershipRows;
    delete context.__w3QualityNewChatRows;
    delete context.__w3QualityDuplicateRows;
  }

  w3Check(w3.listRequest, 'session-list-request-missing');
  w3Check(w3.listCache, 'session-list-cache-policy-missing');
  w3Check(w3.listRequestId, 'session-list-request-id-missing');
  w3Check(w3.listCorrelation, 'session-list-current-session-correlation-missing');
  w3Check(w3.noFabricatedSession, 'session-list-session-id-fabricated');
  w3Check(w3.validRow, 'session-list-valid-row-not-rendered');
  w3Check(w3.missingIdRejected, 'session-list-missing-id-accepted');
  w3Check(w3.zeroIdRejected, 'session-list-zero-id-accepted');
  w3Check(w3.negativeIdRejected, 'session-list-negative-id-accepted');
  w3Check(w3.fractionalIdRejected, 'session-list-fractional-id-accepted');
  w3Check(w3.stringIdRejected, 'session-list-string-id-accepted');
  w3Check(w3.bounded, 'session-list-render-bounds-missing');
  w3Check(w3.empty, 'session-list-empty-state-missing');
  w3Check(w3.diagnosticOwnership, 'session-list-diagnostic-row-collision');
  w3Check(w3.forbidden, 'session-list-forbidden-map-missing');
  w3Check(w3.unavailable, 'session-list-unavailable-map-missing');
  w3Check(w3.invalidJson, 'session-list-invalid-json-map-missing');
  w3Check(w3.nonArray, 'session-list-non-array-map-missing');
  w3Check(w3.network, 'session-list-network-map-missing');
  w3Check(w3.activeRunGuard, 'session-selection-active-run-guard-missing');
  w3Check(w3.detailUrl, 'session-detail-url-missing');
  w3Check(w3.detailRequestId, 'session-detail-request-id-missing');
  w3Check(w3.detailCorrelation, 'session-detail-session-id-mismatch');
  w3Check(w3.precommit, 'session-detail-precommit-detected');
  w3Check(w3.mismatchAtomic, 'session-detail-id-mismatch-not-atomic');
  w3Check(w3.invalidAtomic, 'session-detail-invalid-not-atomic');
  w3Check(w3.failureAtomic, 'session-detail-failure-not-atomic');
  w3Check(w3.staleSelectionAtomic, 'session-detail-stale-generation-not-atomic');
  w3Check(w3.atomicCommit, 'session-selection-atomic-commit-missing');
  w3Check(w3.terminalRefresh, 'session-list-terminal-refresh-missing');
  w3Check(w3.refreshOverlap, 'session-list-refresh-overlap');
  w3Check(w3.staleRefresh, 'session-list-stale-refresh-overwrite');

  const selectedW3Reason = String(process.env.AWX_W3_REASON || '').trim();
  const activeW3Failures = selectedW3Reason
    ? w3FailureRows.filter((row) => row.reason === selectedW3Reason)
    : w3FailureRows;
  assert(
    activeW3Failures.length === 0,
    activeW3Failures.map((row) => `${row.reason}${row.detail ? `: ${row.detail}` : ''}`).join('\n')
  );
  assert(w3FailureRows.length === 0 || Boolean(selectedW3Reason), 'session-list-contract-green');

  const w3QualityCheck = (condition, reason) => {
    if (!condition) w3QualityFailureRows.push({ reason });
  };
  w3QualityCheck(w3Quality.ownershipCycleAtomic, 'session-detail-ownership-cycle-not-atomic');
  w3QualityCheck(w3Quality.newChatSelectionCleared, 'session-list-new-chat-selection-not-cleared');
  w3QualityCheck(w3Quality.duplicateIdCollapsed, 'session-list-duplicate-id-not-collapsed');
  const activeW3QualityFailures = selectedW3Reason
    ? w3QualityFailureRows.filter((row) => row.reason === selectedW3Reason)
    : w3QualityFailureRows;
  assert(
    activeW3QualityFailures.length === 0,
    activeW3QualityFailures.map((row) => row.reason).join('\n')
  );
  console.log('[AWX][chat-ui] stream heartbeat contract OK');
})().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
