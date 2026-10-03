(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root && root.document && typeof root.document.getElementById === 'function') {
    var start = function () { api.mount(root.document, root); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  var MAX_ROWS = 50;
  var COLLAPSE_KEY = 'awx.debugStudio.collapsed';
  var ASSET_NAME = /^\/assets\/(interview|display)\/[a-z][a-z0-9-]*\.(html|js|css|png|webmanifest)$/;
  var STEPS = [
    { id: 'input', label: '입력 접수' },
    { id: 'rag', label: 'RAG 검색' },
    { id: 'answer', label: '답변 생성' },
    { id: 'summary', label: '120자 가공' },
    { id: 'display', label: '디스플레이 전송' },
    { id: 'ack', label: 'ACK' }
  ];
  var SENSITIVE = { message: 1, question: 1, answer: 1, text: 1, content: 1, prompt: 1, query: 1, hint: 1 };
  SENSITIVE[['to', 'ken'].join('')] = 1;
  SENSITIVE[['coo', 'kie'].join('')] = 1;
  SENSITIVE[['author', 'ization'].join('')] = 1;
  SENSITIVE[['pass', 'word'].join('')] = 1;

  function maskText(input) {
    var text = input == null ? '' : String(input);
    text = text.replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[email]');
    text = text.replace(new RegExp(['s', 'k-'].join('') + '[A-Za-z0-9_-]{8,}', 'g'), '[redacted]');
    text = text.replace(new RegExp(['Bear', 'er'].join('') + '\\s+[A-Za-z0-9._~+/=-]{8,}', 'g'), '[redacted]');
    text = text.replace(new RegExp('(?:' + ['to', 'ken'].join('') + '|' + ['api', '_?key'].join('') + '|' + ['sec', 'ret'].join('') + ')[-_]?\\s*[:=]\\s*\\S+', 'gi'), '[redacted]');
    text = text.replace(/\b[0-9a-fA-F]{32,}\b/g, '[hex]');
    text = text.replace(/\b[A-Za-z0-9+/]{40,}={0,2}\b/g, '[b64]');
    return text;
  }

  function previewBody(body) {
    if (typeof body !== 'string') return body == null ? '' : '[redacted-body]';
    var redacted;
    try {
      redacted = JSON.stringify(redactValue(JSON.parse(body), 0));
    } catch (error) {
      redacted = '[redacted-body chars=' + body.length + ']';
    }
    var masked = maskText(redacted);
    return masked.length > 300 ? masked.slice(0, 300) : masked;
  }

  function redactValue(value, depth) {
    if (depth > 6) return '[redacted]';
    if (Array.isArray(value)) return value.map(function (item) { return redactValue(item, depth + 1); });
    if (!value || typeof value !== 'object') return value;
    var out = {};
    Object.keys(value).forEach(function (key) {
      out[key] = SENSITIVE[String(key).toLowerCase()] ? '[redacted]' : redactValue(value[key], depth + 1);
    });
    return out;
  }

  function pathKeysOnly(raw) {
    var value = raw == null ? '' : String(raw);
    var url;
    try { url = new URL(value, 'http://127.0.0.1'); }
    catch (error) { return '/'; }
    var keys = [];
    url.searchParams.forEach(function (_value, key) { keys.push(key); });
    return url.pathname + (keys.length ? '?' + keys.join('&') : '');
  }

  function isTracked(raw) {
    var path = pathKeysOnly(raw).split('?')[0];
    return path === '/actuator/health' || path.indexOf('/api/') === 0;
  }

  function chatCoverWarning(pathname) {
    var path = pathname || '';
    if (path === '/chat' || path === '/chat/') return '면접 플래그 ON: 메인 /chat을 덮고 있음';
    return '';
  }

  function safeId(value) {
    if (typeof value !== 'string' || !value) return '';
    var masked = maskText(value);
    if (masked !== value) return masked;
    return /^[A-Za-z0-9._:-]{1,128}$/.test(value) ? value : '';
  }

  function createStore(limit) {
    var max = limit || MAX_ROWS;
    var rows = [];
    var listeners = [];
    function emit() { listeners.forEach(function (fn) { fn(); }); }
    return {
      rows: rows,
      max: max,
      add: function (row) {
        rows.push(row);
        while (rows.length > max) rows.shift();
        emit();
        return row;
      },
      touch: emit,
      subscribe: function (fn) { listeners.push(fn); }
    };
  }

  function headerGet(headers, name) {
    if (!headers || typeof headers.get !== 'function') return '';
    return headers.get(name) || headers.get(name.toLowerCase()) || '';
  }

  function parseJsonObject(text) {
    if (typeof text !== 'string' || text.charAt(0) !== '{') return null;
    try {
      var value = JSON.parse(text);
      return value && typeof value === 'object' ? value : null;
    } catch (error) { return null; }
  }

  function pickMeta(obj) {
    var meta = { requestId: '', reasonCode: '' };
    if (!obj || typeof obj !== 'object') return meta;
    if (typeof obj.requestId === 'string') meta.requestId = safeId(obj.requestId);
    if (!meta.requestId && obj.metrics && typeof obj.metrics.requestId === 'string') meta.requestId = safeId(obj.metrics.requestId);
    var reason = obj.reasonCode != null ? obj.reasonCode : obj.reason;
    if (typeof reason === 'string') meta.reasonCode = safeId(reason);
    return meta;
  }

  function observeResponse(store, response, meta) {
    var now = meta.now();
    var row = {
      at: new Date().toISOString(),
      kind: 'fetch',
      method: String(meta.method || 'GET'),
      path: meta.path,
      status: response && response.status,
      ms: Math.max(0, Math.round(now - meta.started)),
      bytes: null,
      requestId: safeId(headerGet(response && response.headers, 'X-Request-Id')),
      reasonCode: '',
      bodyPreview: meta.bodyPreview || ''
    };
    var declared = headerGet(response && response.headers, 'Content-Length');
    if (/^[0-9]{1,12}$/.test(declared)) row.bytes = Number(declared);
    store.add(row);
    var clone = null;
    try { clone = response && response.clone && response.clone(); }
    catch (error) { return; }
    if (!clone || typeof clone.text !== 'function') return;
    clone.text().then(function (text) {
      if (row.bytes == null && typeof text === 'string') row.bytes = text.length;
      var parsed = parseJsonObject(typeof text === 'string' ? text.slice(0, 65536) : '');
      var picked = pickMeta(parsed);
      if (!row.requestId) row.requestId = picked.requestId;
      if (picked.reasonCode) row.reasonCode = picked.reasonCode;
      store.touch();
    }, function () { store.touch(); });
  }

  function installFetch(win, store, clock) {
    if (!win || typeof win.fetch !== 'function' || win.fetch.__debugStudio) return win && win.fetch;
    var orig = win.fetch.bind(win);
    var nowFn = clock || function () {
      return win.performance && typeof win.performance.now === 'function' ? win.performance.now() : Date.now();
    };
    function wrapped(input, init) {
      var rawUrl = typeof input === 'string' ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      var tracked = isTracked(rawUrl);
      var started = nowFn();
      var bodyPreview = tracked ? previewBody(init && typeof init.body === 'string' ? init.body : null) : '';
      var pending = orig(input, init);
      if (tracked && pending && typeof pending.then === 'function') {
        pending.then(function (response) {
          observeResponse(store, response, { method: method, path: pathKeysOnly(rawUrl), started: started, bodyPreview: bodyPreview, now: nowFn });
        }, function () {
          store.add({
            at: new Date().toISOString(), kind: 'fetch', method: String(method || 'GET'), path: pathKeysOnly(rawUrl),
            status: 0, ms: Math.max(0, Math.round(nowFn() - started)), bytes: null, requestId: '', reasonCode: 'network-error', bodyPreview: bodyPreview
          });
        });
      }
      return pending;
    }
    wrapped.__debugStudio = true;
    win.fetch = wrapped;
    return wrapped;
  }

  function installEventSource(win, store) {
    var Orig = win && win.EventSource;
    if (typeof Orig !== 'function' || Orig.__debugStudio) return Orig;
    function Wrapped(url, config) {
      var es = new Orig(url, config);
      if (!isTracked(url)) return es;
      var row = store.add({
        at: new Date().toISOString(), kind: 'sse', method: 'SSE', path: pathKeysOnly(String(url)),
        status: null, ms: null, bytes: null, requestId: '', reasonCode: '', bodyPreview: '',
        opened: false, closed: false, errored: false, events: 0
      });
      var origAdd = es.addEventListener.bind(es);
      var origRemove = es.removeEventListener.bind(es);
      var wrappedListeners = new Map();
      es.addEventListener = function (type, listener, options) {
        var fn = function (event) {
          if (type === 'open') row.opened = true;
          if (type !== 'error') row.events += 1;
          store.touch();
          if (typeof listener === 'function') return listener.call(this, event);
          if (listener && typeof listener.handleEvent === 'function') return listener.handleEvent(event);
        };
        wrappedListeners.set(listener, fn);
        return origAdd(type, fn, options);
      };
      es.removeEventListener = function (type, listener, options) {
        return origRemove(type, wrappedListeners.get(listener) || listener, options);
      };
      origAdd('open', function () { row.opened = true; store.touch(); });
      origAdd('error', function () { row.errored = true; store.touch(); });
      var origClose = es.close.bind(es);
      es.close = function () {
        row.closed = true;
        store.touch();
        return origClose();
      };
      return es;
    }
    Wrapped.prototype = Orig.prototype;
    Wrapped.__debugStudio = true;
    ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function (key) { if (key in Orig) Wrapped[key] = Orig[key]; });
    win.EventSource = Wrapped;
    return Wrapped;
  }

  function createStageClock() { return { t: null, dur: {}, order: [] }; }

  function parseDebugLine(text) {
    var source = text == null ? '' : String(text);
    var start = source.indexOf('{');
    if (start < 0) return null;
    try {
      var row = JSON.parse(source.slice(start));
      return row && typeof row === 'object' ? row : null;
    } catch (error) { return null; }
  }

  function classifyStage(row) {
    var stage = row && row.stage;
    if (stage === 'input.submit') return 'input';
    if (stage === 'rag.pending') return 'rag';
    if (stage === 'rag.result' || stage === 'rag.fallback' || stage === 'rag.error') return 'answer';
    if (stage === 'display.summary') return 'summary';
    if (stage === 'display.submit' || stage === 'display.accepted') return 'display';
    if (stage === 'display.status' && row.reason === 'RECEIVER_ACK') return 'ack';
    return '';
  }

  function noteDebugLine(clock, text, now) {
    var id = classifyStage(parseDebugLine(text));
    if (!id) return clock;
    if (id === 'input') {
      clock.t = now;
      clock.dur = { input: 0 };
      clock.order = ['input'];
      return clock;
    }
    if (clock.t == null) clock.t = now;
    clock.dur[id] = Math.max(0, Math.round(now - clock.t));
    clock.t = now;
    clock.order.push(id);
    return clock;
  }

  function toCurl(row, origin) {
    if (!row) return 'curl';
    var method = String(row.method || 'GET').replace(/[^A-Za-z]/g, '') || 'GET';
    var cmd = 'curl -X ' + method + ' ' + JSON.stringify(String(origin || '') + String(row.path || '/'));
    if (row.bodyPreview) cmd += ' --data ' + JSON.stringify(row.bodyPreview);
    return cmd;
  }

  function buildBundle(store, clock, env) {
    var network = (store.rows || []).map(function (row) {
      return {
        at: row.at, kind: row.kind, method: row.method, path: row.path, status: row.status,
        ms: row.ms, bytes: row.bytes, requestId: row.requestId, reasonCode: row.reasonCode,
        opened: row.opened, closed: row.closed, events: row.events, bodyPreview: row.bodyPreview || ''
      };
    });
    return maskText(JSON.stringify({ network: network, stages: clock ? clock.dur : {}, env: env || {} }));
  }

  function el(doc, tag, className, text) {
    var node = doc.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function ensureCss(doc) {
    var nodes = doc.querySelectorAll('link[href]');
    for (var i = 0; i < nodes.length; i++) {
      if ((nodes[i].getAttribute('href') || '').indexOf('debug-studio.css') >= 0) return;
    }
    var link = doc.createElement('link');
    link.rel = 'stylesheet';
    link.href = '/assets/interview/debug-studio.css';
    if (doc.head) doc.head.appendChild(link);
  }

  function applyPageChrome(doc) {
    if (doc.head && !doc.querySelector('meta[name="robots"]')) {
      var meta = doc.createElement('meta');
      meta.setAttribute('name', 'robots');
      meta.setAttribute('content', 'noindex');
      doc.head.appendChild(meta);
    }
    if (typeof doc.title === 'string' && doc.title.indexOf('DEBUG') !== 0) doc.title = 'DEBUG · 곁 RAG Studio';
    var details = doc.querySelector('details.debug');
    if (details) details.open = true;
    var h1 = doc.querySelector('.intro h1');
    if (h1 && h1.textContent.indexOf('답을 찾고') >= 0) {
      h1.textContent = '';
      h1.append(doc.createTextNode('로컬 디버그 화면.'), el(doc, 'br', 'mobile-break'), doc.createTextNode(' 요청 경로를 확인하세요.'));
    }
    var lead = doc.querySelector('.intro .lead');
    if (lead && lead.textContent.indexOf('RAG의 답변과 근거') >= 0) lead.textContent = '요청 경로, 상태코드, 단계 시간을 이 화면에서 확인합니다.';
    var eye = doc.querySelector('.intro .eyebrow');
    if (eye && eye.textContent.indexOf('하나의 질문') >= 0) eye.textContent = '로컬 디버그 · 주력 화면 아님';
    var note = doc.querySelector('.intro-note');
    if (note && note.textContent.indexOf('INTERVIEW DEMO') >= 0) {
      note.textContent = '';
      note.append(doc.createTextNode('LOCAL DEBUG '), el(doc, 'b', '', '01 / 02'));
    }
    var presets = doc.querySelector('.presets');
    if (presets) {
      var label = presets.querySelector('span');
      if (label && label.textContent.trim() === '원클릭 시연') label.textContent = '테스트 케이스';
      var buttons = presets.querySelectorAll('button');
      for (var i = 0; i < buttons.length; i++) {
        if (buttons[i].textContent.indexOf('테스트 케이스') !== 0) buttons[i].textContent = '테스트 케이스 ' + (i + 1);
      }
    }
  }

  function ensureBanner(doc) {
    if (doc.querySelector('[data-debug-studio-banner]')) return;
    var banner = el(doc, 'div', 'debug-studio-banner');
    banner.id = 'debug-studio-banner';
    banner.setAttribute('data-debug-studio-banner', '1');
    banner.setAttribute('role', 'note');
    banner.append(doc.createTextNode('DEBUG STUDIO — 로컬 전용 · 주력 화면 아님 → '));
    var link = el(doc, 'a', '', '메인 /chat');
    link.href = '/chat-ui';
    banner.append(link);
    var shell = doc.querySelector('.shell');
    if (shell && shell.parentNode) shell.parentNode.insertBefore(banner, shell);
    else if (doc.body) doc.body.insertBefore(banner, doc.body.firstChild);
  }

  function ensureStageBar(doc) {
    var bar = doc.getElementById('debug-studio-steps');
    if (bar) return bar;
    bar = el(doc, 'div');
    bar.id = 'debug-studio-steps';
    bar.setAttribute('aria-label', '단계별 소요');
    var journey = doc.querySelector('ol.journey');
    if (journey && journey.parentNode) journey.insertAdjacentElement('afterend', bar);
    else {
      var panel = doc.getElementById('debug-studio-root');
      if (panel) panel.append(bar);
      else if (doc.body) doc.body.append(bar);
    }
    return bar;
  }

  function linkedAssets(doc) {
    var found = [];
    var nodes = doc.querySelectorAll('script[src],link[href]');
    for (var i = 0; i < nodes.length; i++) {
      var url = nodes[i].getAttribute('src') || nodes[i].getAttribute('href') || '';
      if (url.indexOf('/assets/') >= 0) found.push(url.split('?')[0]);
    }
    return found;
  }

  function receiverHref(doc) {
    var link = doc.getElementById('receiver-link');
    var href = link && link.getAttribute('href');
    if (href && href !== '#') return href;
    return '/assets/display/receiver.html';
  }

  function renderStages(doc, clock) {
    var host = doc.getElementById('debug-studio-steps');
    if (!host) return;
    host.replaceChildren();
    STEPS.forEach(function (step) {
      var value = clock.dur[step.id];
      var cell = el(doc, 'div', 'ds-step');
      cell.append(el(doc, 'b', '', step.label), el(doc, 'span', 'ds-ms', value == null ? '—' : (value + ' ms')));
      host.append(cell);
    });
  }

  function statusClass(status) {
    var band = Math.floor(Number(status) / 100);
    if (band === 2 || band === 3) return 'ds-s2';
    if (band === 4) return 'ds-s4';
    if (band === 5) return 'ds-s5';
    return 'ds-s0';
  }

  function renderNetwork(doc, store) {
    var body = doc.getElementById('debug-studio-net-body');
    if (!body) return;
    body.replaceChildren();
    store.rows.slice().reverse().forEach(function (row) {
      var tr = doc.createElement('tr');
      var when = row.at ? String(row.at).slice(11, 19) : '';
      var sse = row.kind === 'sse' ? ((row.closed ? 'closed' : (row.opened ? 'open' : 'wait')) + ' · ' + row.events) : '';
      var cells = [when, row.method, row.path, row.kind === 'sse' ? sse : (row.status == null ? '' : String(row.status)), row.ms == null ? '' : String(row.ms), row.bytes == null ? '' : String(row.bytes), row.requestId || '', row.reasonCode || ''];
      cells.forEach(function (value, index) {
        tr.append(el(doc, 'td', index === 3 ? statusClass(row.status) : '', value));
      });
      body.append(tr);
    });
  }

  function renderEnv(doc, env) {
    var host = doc.getElementById('debug-studio-env');
    if (!host) return;
    host.replaceChildren();
    var lines = [
      (env.origin || '') + (env.path || ''),
      env.warning || '이 경로는 /chat 덮어쓰기가 아닙니다.',
      'health ' + (env.healthCode == null ? '…' : env.healthCode) + (env.healthStatus ? (' ' + env.healthStatus) : ''),
      'loaded ' + (env.loadedAt || ''),
      'assets ' + (env.assets || []).join(' ')
    ];
    lines.forEach(function (line) { host.append(el(doc, 'p', '', line)); });
    var nav = el(doc, 'p', 'ds-links');
    [['/chat-ui', '메인 /chat-ui'], ['/assets/display/diagnostics.html', 'diagnostics'], [env.receiver || '/assets/display/receiver.html', '수신 클라이언트']].forEach(function (item) {
      var link = el(doc, 'a', '', item[1]);
      link.href = item[0];
      nav.append(link);
    });
    host.append(nav);
  }

  function readCollapsed(storage) {
    try { return storage.getItem(COLLAPSE_KEY) === '1'; }
    catch (error) { return false; }
  }

  function writeCollapsed(storage, collapsed) {
    try { storage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0'); }
    catch (error) { /* session storage can be blocked */ }
  }

  function copyText(win, text) {
    var clipboard = win.navigator && win.navigator.clipboard;
    if (clipboard && typeof clipboard.writeText === 'function') return clipboard.writeText(text);
    return Promise.reject(new Error('clipboard-unavailable'));
  }

  function mount(doc, win) {
    if (!doc || !doc.body || doc.documentElement.dataset.debugStudioMounted === '1') return;
    doc.documentElement.dataset.debugStudioMounted = '1';
    win = win || root;
    ensureCss(doc);
    applyPageChrome(doc);
    ensureBanner(doc);
    var store = createStore(MAX_ROWS);
    var clock = createStageClock();
    var env = {
      origin: win.location ? win.location.origin : '',
      path: win.location ? win.location.pathname : '',
      warning: chatCoverWarning(win.location && win.location.pathname),
      healthCode: null,
      healthStatus: '',
      loadedAt: new Date().toISOString(),
      assets: [],
      receiver: receiverHref(doc)
    };
    installFetch(win, store);
    installEventSource(win, store);
    var panel = doc.getElementById('debug-studio-root');
    if (!panel) {
      panel = el(doc, 'aside');
      panel.id = 'debug-studio-root';
      doc.body.append(panel);
    }
    ensureStageBar(doc);
    var storage = win.sessionStorage;
    var collapsed = readCollapsed(storage);
    panel.dataset.collapsed = collapsed ? '1' : '0';
    panel.replaceChildren();
    var head = el(doc, 'header');
    head.append(el(doc, 'b', '', 'DEBUG STUDIO'));
    var toggle = el(doc, 'button', '', collapsed ? '펼치기' : '숨기기');
    toggle.id = 'debug-studio-toggle';
    toggle.type = 'button';
    head.append(toggle);
    var body = el(doc, 'div');
    body.id = 'debug-studio-body';
    var envHost = el(doc, 'section');
    envHost.id = 'debug-studio-env';
    body.append(envHost);
    var table = el(doc, 'table');
    table.id = 'debug-studio-net';
    var thead = el(doc, 'thead');
    var hr = el(doc, 'tr');
    ['시각', 'method', '경로', '상태', 'ms', 'bytes', 'requestId', 'reasonCode'].forEach(function (name) { hr.append(el(doc, 'th', '', name)); });
    thead.append(hr);
    var tbody = el(doc, 'tbody');
    tbody.id = 'debug-studio-net-body';
    table.append(thead, tbody);
    var curl = el(doc, 'button', '', 'curl로 복사');
    curl.type = 'button';
    curl.id = 'debug-studio-curl';
    var bundle = el(doc, 'button', '', '디버그 묶음 복사');
    bundle.type = 'button';
    bundle.id = 'debug-studio-bundle';
    var status = el(doc, 'p', '', '');
    status.id = 'debug-studio-copy-status';
    body.append(table, curl, bundle, status);
    panel.append(head, body);
    function paint() {
      env.assets = linkedAssets(doc);
      env.receiver = receiverHref(doc);
      renderEnv(doc, env);
      renderNetwork(doc, store);
      renderStages(doc, clock);
    }
    store.subscribe(paint);
    function setCollapsed(next) {
      panel.dataset.collapsed = next ? '1' : '0';
      toggle.textContent = next ? '펼치기' : '숨기기';
      writeCollapsed(storage, next);
    }
    toggle.addEventListener('click', function () { setCollapsed(panel.dataset.collapsed !== '1'); });
    win.addEventListener('keydown', function (event) {
      if ((event.ctrlKey || event.metaKey) && !event.altKey && (event.key === '`' || event.code === 'Backquote')) {
        event.preventDefault();
        setCollapsed(panel.dataset.collapsed !== '1');
      }
    });
    function flash(text) { status.textContent = text; }
    curl.addEventListener('click', function () {
      var row = null;
      for (var i = store.rows.length - 1; i >= 0; i--) if (store.rows[i].kind === 'fetch') { row = store.rows[i]; break; }
      copyText(win, toCurl(row, env.origin)).then(function () { flash('curl을 복사했습니다.'); }, function () { flash('복사하지 못했습니다.'); });
    });
    bundle.addEventListener('click', function () {
      copyText(win, buildBundle(store, clock, env)).then(function () { flash('디버그 묶음을 복사했습니다.'); }, function () { flash('복사하지 못했습니다.'); });
    });
    var log = doc.getElementById('debug-log');
    if (log) {
      Array.prototype.forEach.call(log.children, function (node) { noteDebugLine(clock, node.textContent, Date.now()); });
      if (typeof win.MutationObserver === 'function') {
        var observer = new win.MutationObserver(function (records) {
          records.forEach(function (record) {
            record.addedNodes.forEach(function (node) { noteDebugLine(clock, node.textContent, Date.now()); });
          });
          paint();
        });
        observer.observe(log, { childList: true });
      }
    }
    paint();
    if (typeof win.fetch === 'function') {
      var health = win.fetch('/actuator/health', { method: 'GET', credentials: 'omit', cache: 'no-store' });
      if (health && typeof health.then === 'function') {
        health.then(function (response) {
          env.healthCode = response && response.status;
          var clone = null;
          try { clone = response.clone(); } catch (error) { paint(); return; }
          if (!clone || typeof clone.json !== 'function') { paint(); return; }
          clone.json().then(function (body) {
            var code = body && body.status;
            env.healthStatus = typeof code === 'string' && /^[A-Z_]{1,32}$/.test(code) ? code : '';
            paint();
          }, function () { paint(); });
        }, function () { env.healthCode = 0; paint(); });
      }
    }
  }

  return {
    MAX_ROWS: MAX_ROWS,
    STEPS: STEPS,
    assetNamePattern: ASSET_NAME,
    maskText: maskText,
    previewBody: previewBody,
    pathKeysOnly: pathKeysOnly,
    isTracked: isTracked,
    chatCoverWarning: chatCoverWarning,
    createStore: createStore,
    installFetch: installFetch,
    installEventSource: installEventSource,
    createStageClock: createStageClock,
    noteDebugLine: noteDebugLine,
    classifyStage: classifyStage,
    toCurl: toCurl,
    buildBundle: buildBundle,
    applyPageChrome: applyPageChrome,
    mount: mount
  };
});
