// Synthetic asynchronous image responses. No network, credentials, or image provider.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../main/resources/static/js/chat.js'), 'utf8');
const imageSource = fs.readFileSync(path.join(__dirname, '../main/resources/static/js/image-jobs-ui.js'), 'utf8');
function activeFunction(name) {
  const start = new RegExp(`^(?:async )?function ${name}\\(`, 'm').exec(source)?.index;
  if (start == null) return '';
  const end = source.indexOf('\n}', start);
  assert.ok(end > start);
  return source.slice(start, end + 2);
}
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(initial = { id: 'fixture-job', status: 'PENDING', etaSeconds: 300 }) {
  let now = 0, sequence = 0;
  const timers = new Map(), requests = [], messages = [], updates = [];
  const card = { isConnected: true }, assistant = { isConnected: true, parentElement: {} };
  const context = vm.createContext({
    AbortController, Date: { now: () => now },
    window: {
      setTimeout(fn, delay) { const id = ++sequence; timers.set(id, { fn, due: now + delay }); return id; },
      clearTimeout(id) { timers.delete(id); },
    },
    isAssistantStreamStopped: target => target?.stopped === true,
    dom: {}, imagePromptFromCommand: text => text.replace(/^\/image\s*/, ''),
    withCsrfHeaders: headers => headers, safeDebugCockpitDetail: value => String(value),
    imageJobFailureReason: async response => `image_http_${response.status}`,
    updateOrchestrationSignalBar() {}, setCoreStatus() {},
    setMessageContent(target, role, text, state) { target.text = text; target.state = state; messages.push(text); },
    loadImageJobUi: async () => ({
      renderImageJobCard: () => card,
      updateImageJobCard(target, job) { target.job = job; updates.push(job); },
      attachImageJobDebug: async () => ({ job: { status: 'IN_PROGRESS' } }),
      attachImageJobConfigDebug: async () => ({ imageServiceAvailable: true }),
    }),
    statusResponse: async () => ({ ok: true, json: async () => ({ id: initial.id, status: 'SUCCEEDED' }) }),
    fetch: async (url, options = {}) => {
      requests.push({ url, method: options.method, signal: options.signal });
      if (options.method === 'POST') return { ok: true, json: async () => initial };
      return context.statusResponse(url, options);
    },
  });
  vm.runInContext('const IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST = "IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST";\n' +
    ['imageJobConfigDisabledReason', 'imageJobHandledError', 'rejectImageJobLocally', 'monitorImageJob', 'submitImageJob'].map(activeFunction).join('\n'), context);
  async function step() {
    await flush();
    const entry = [...timers].sort((a, b) => a[1].due - b[1].due)[0];
    if (!entry) return false;
    timers.delete(entry[0]); now = entry[1].due; entry[1].fn(); await flush(); return true;
  }
  async function drain(limit = 200) {
    for (let i = 0; i < limit; i++) if (!await step()) return;
    throw new Error('image monitor did not stop within its request/time ceiling');
  }
  return { context, timers, requests, messages, updates, card, assistant, step, drain,
    now: () => now, submit: () => context.submitImageJob('/image synthetic scene', assistant),
    statusRequests: () => requests.filter(row => row.method === 'GET') };
}

test('accepted pending job stays pending and leaves a bounded status check scheduled', async () => {
  const h = harness(); await h.submit(); await flush();
  assert.ok(!h.messages.some(text => text === '[image generated]'));
  assert.equal(h.card.job.status, 'PENDING');
  assert.ok(h.timers.size > 0);
  assert.equal(h.requests.filter(row => row.method === 'POST').length, 1);
});

test('only authoritative SUCCEEDED status produces one completion', async () => {
  const h = harness(); await h.submit(); await h.drain();
  assert.equal(h.messages.filter(text => text === '[image generated]').length, 1);
  assert.equal(h.card.job.status, 'SUCCEEDED');
  assert.equal(h.statusRequests().length, 1);
  assert.equal(h.statusRequests()[0].url, '/api/image-plugin/jobs/fixture-job');
  assert.equal(h.timers.size, 0);
});

test('authoritative FAILED state never emits generated content', async () => {
  const h = harness();
  h.context.statusResponse = async () => ({ ok: true, json: async () => ({ id: 'fixture-job', status: 'FAILED', reason: 'IMAGE_JOB_FAILED' }) });
  await h.submit(); await h.drain();
  assert.equal(h.card.job.status, 'FAILED');
  assert.ok(h.assistant.text.includes('failed'));
  assert.ok(!h.messages.includes('[image generated]'));
  assert.equal(h.timers.size, 0);
});

test('etaSeconds controls the deadline and polling stops within 90 GET requests', async () => {
  const h = harness();
  h.context.statusResponse = async () => ({ ok: true, json: async () => ({ id: 'fixture-job', status: 'IN_PROGRESS' }) });
  await h.submit(); await h.drain();
  assert.equal(h.card.job.status, 'IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST');
  assert.ok(h.now() >= 345000 && h.now() <= 355000);
  assert.ok(h.statusRequests().length > 0 && h.statusRequests().length <= 90);
  assert.ok(!h.messages.includes('[image generated]'));
  assert.equal(h.timers.size, 0);
});

test('nonfinite ETA still has a finite bounded deadline', async () => {
  const h = harness({ id: 'fixture-job', status: 'PENDING', etaSeconds: Infinity });
  h.context.statusResponse = async () => ({ ok: true, json: async () => ({ id: 'fixture-job', status: 'PENDING' }) });
  await h.submit(); await h.drain();
  assert.ok(Number.isFinite(h.now()) && h.now() >= 120000 && h.now() <= 1800000);
  assert.equal(h.card.job.status, 'IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST');
});

test('removing the card stops later status requests and late UI updates', async () => {
  const h = harness(); await h.submit(); await flush();
  const messagesBefore = h.messages.length;
  h.card.isConnected = false;
  await h.drain();
  assert.equal(h.statusRequests().length, 0);
  assert.equal(h.messages.length, messagesBefore);
  assert.equal(h.timers.size, 0);
});

test('a stopped assistant releases its local monitor without claiming server cancellation', async () => {
  const h = harness(); await h.submit(); await flush();
  const messagesBefore = h.messages.length;
  h.assistant.stopped = true;
  await h.drain();
  assert.equal(h.statusRequests().length, 0);
  assert.equal(h.messages.length, messagesBefore);
  assert.equal(h.timers.size, 0);
});

test('HTTP status failure does not read a private response body or claim generation', async () => {
  const h = harness(); let bodyReads = 0;
  h.context.statusResponse = async () => ({ ok: false, status: 403, text: async () => { bodyReads++; return 'synthetic-private-body'; } });
  await h.submit(); await h.drain();
  assert.equal(h.statusRequests().length, 1);
  assert.equal(bodyReads, 0);
  assert.ok(!h.messages.includes('[image generated]'));
  assert.equal(h.card.job.status, 'STATUS_UNAVAILABLE');
  assert.equal(h.timers.size, 0);
});

test('a stalled status request is aborted and the monitor releases all timers', async () => {
  const h = harness(); let aborted = false;
  h.context.statusResponse = (url, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener('abort', () => { aborted = true; reject(Object.assign(new Error('synthetic timeout'), { name: 'AbortError' })); });
  });
  await h.submit(); await h.drain();
  assert.equal(aborted, true);
  assert.ok(h.now() <= 30000);
  assert.equal(h.card.job.status, 'STATUS_UNAVAILABLE');
  assert.equal(h.timers.size, 0);
});

test('a status response for a different job cannot complete this assistant', async () => {
  const h = harness();
  h.context.statusResponse = async () => ({ ok: true, json: async () => ({ id: 'different-job', status: 'SUCCEEDED' }) });
  await h.submit(); await h.drain();
  assert.ok(!h.messages.includes('[image generated]'));
  assert.equal(h.card.job.status, 'STATUS_UNAVAILABLE');
});

test('already succeeded enqueue response needs no status polling', async () => {
  const h = harness({ id: 'fixture-job', status: 'SUCCEEDED' });
  await h.submit(); await h.drain();
  assert.equal(h.messages.filter(text => text === '[image generated]').length, 1);
  assert.equal(h.statusRequests().length, 0);
  assert.equal(h.timers.size, 0);
});

test('job IDs are encoded as one status path segment', async () => {
  const h = harness({ id: 'fixture/job?part', status: 'PENDING' });
  await h.submit(); await h.drain();
  assert.equal(h.statusRequests()[0]?.url, '/api/image-plugin/jobs/fixture%2Fjob%3Fpart');
});

function imageModuleHarness() {
  function element() {
    return { dataset: {}, children: [], isConnected: true, textContent: '',
      appendChild(child) { this.children.push(child); return child; },
      append(...children) { this.children.push(...children); },
      replaceChildren(...children) { this.children = children; },
      querySelector() { return null; }, setAttribute() {},
    };
  }
  const requests = [];
  const context = vm.createContext({ AbortController, window: { setTimeout, clearTimeout }, document: { createElement: element, createTextNode: text => ({ textContent: text }) },
    fetch: async url => { requests.push(url); return { ok: true, json: async () => ({ job: { status: 'SUCCEEDED', progress: 100 } }) }; } });
  vm.runInContext(imageSource.replace(/^export /gm, ''), context);
  return { context, requests, element };
}

test('card label recognizes the server SUCCEEDED state', () => {
  const h = imageModuleHarness();
  assert.equal(h.context.imageJobPromptLabel({ status: 'SUCCEEDED' }), 'Image generated');
});

test('pending card content cannot override its explicit nonterminal status', () => {
  const h = imageModuleHarness();
  assert.equal(h.context.imageJobPromptLabel({ status: 'PENDING', content: '[image generated]' }), 'Image request submitted');
});

test('diagnostics uses the declared nested debug endpoint without overriding primary status', async () => {
  const h = imageModuleHarness(), card = h.element();
  card.dataset.status = 'PENDING';
  await h.context.attachImageJobDebug(card, 'fixture/job');
  assert.equal(h.requests[0], '/api/diagnostics/image/jobs/fixture%2Fjob/debug');
  assert.equal(card.dataset.status, 'PENDING');
});

test('diagnostic failure cannot relabel an authoritative succeeded card', async () => {
  const h = imageModuleHarness(), card = h.element();
  card.dataset.status = 'SUCCEEDED';
  h.context.fetch = async () => ({ ok: false, status: 404 });
  await h.context.attachImageJobDebug(card, 'fixture-job');
  assert.equal(card.dataset.status, 'SUCCEEDED');
});
