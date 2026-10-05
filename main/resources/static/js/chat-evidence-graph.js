/* Answer-owned public evidence only. No transport, global evidence cache or raw excerpt reads. */
(function (root, factory) {
  'use strict';
  if (typeof module === 'object' && module.exports) module.exports = { create: factory };
  else root.ChatEvidenceGraph = factory({ document: root.document });
})(typeof window === 'object' ? window : globalThis, function create(options) {
  'use strict';
  options = options || {};
  const document = options.document;
  const answers = new WeakMap();
  const VERSION = 1, PAGE_SIZE = 16;
  let presentationSequence = 0;
  const text = (value, max = 256) => typeof value === 'string'
    ? value.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max) : '';
  function identity(value) {
    if (!value || !Number.isSafeInteger(value.sessionId) || value.sessionId <= 0 ||
        !Number.isSafeInteger(value.generation) || value.generation < 0 ||
        typeof value.runToken !== 'string' || !value.runToken.trim()) return null;
    return Object.freeze({ sessionId: value.sessionId, runToken: value.runToken, generation: value.generation });
  }
  const same = (a, b) => Boolean(a && b && a.sessionId === b.sessionId &&
    a.runToken === b.runToken && a.generation === b.generation);
  const attached = answer => Boolean(answer?.parentElement && answer.isConnected !== false);
  function current(record) {
    try { return record.isCurrent(record.identity) === true; } catch { return false; }
  }
  function safeUrl(value) {
    try {
      const url = new URL(text(value, 2048));
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return '';
      if ([...url.searchParams.keys()].some(key => /token|secret|password|api[_-]?key|auth|session|run[_-]?id|owner/i.test(key))) return '';
      url.hash = '';
      return url.href;
    } catch { return ''; }
  }
  function project(evidence, answerText) {
    const markers = new Set();
    for (const match of String(answerText || '').matchAll(/\[([A-Z]\d{1,6})\]/g)) markers.add(match[1]);
    const sources = (Array.isArray(evidence) ? evidence : []).flatMap(item => {
      if (!item || typeof item !== 'object') return [];
      const url = safeUrl(item.source);
      const filename = text(item.attachment?.filename).split(/[\\/]/).pop();
      const title = text(item.title) || filename || (url ? new URL(url).hostname : '');
      if (!title) return [];
      const marker = /^[A-Z]\d{1,6}$/.test(text(item.marker, 20)) ? text(item.marker, 20) : '';
      const start = item.lineStart, end = item.lineEnd;
      const lines = Number.isSafeInteger(start) && start > 0
        ? '줄 ' + start + (Number.isSafeInteger(end) && end >= start ? '–' + end : '') : '';
      return [{ title, url, marker, locator: text(item.attachment?.locator, 160) || lines || 'UNKNOWN',
        revision: Number.isSafeInteger(item.attachment?.revision) && item.attachment.revision > 0
          ? String(item.attachment.revision) : 'UNKNOWN', cited: false, use: 'UNKNOWN', excerpt: '제공되지 않음' }];
    });
    const counts = new Map();
    sources.forEach(source => counts.set(source.marker, (counts.get(source.marker) || 0) + 1));
    sources.forEach(source => { source.cited = Boolean(source.marker && markers.has(source.marker) && counts.get(source.marker) === 1); });
    sources.sort((a, b) => Number(b.cited) - Number(a.cited));
    return Object.freeze(sources.map(source => Object.freeze(source)));
  }
  const unavailable = reason => Object.freeze({ status: 'unavailable', graphKind: 'source-only',
    projectionVersion: VERSION, reason, sources: Object.freeze([]) });
  function element(tag, value = '', role = '') {
    const node = document.createElement(tag);
    if (value) node.textContent = value;
    if (role) node.dataset.role = role;
    return node;
  }
  function button(label, role, handler) {
    const node = element('button', label, role);
    node.setAttribute('type', 'button'); node.setAttribute('aria-label', label);
    node.addEventListener('click', handler);
    return node;
  }
  function insert(answer, panel) {
    const parent = answer.parentElement;
    if (typeof parent.insertBefore === 'function') parent.insertBefore(panel, answer.nextSibling);
    else parent.appendChild(panel);
  }
  function listView(record, parent) {
    const list = element('ol', '', 'source-list');
    const controls = element('nav', '', 'list-pages');
    controls.setAttribute('aria-label', '출처 목록 페이지');
    let page = 0;
    const pageLabel = element('span');
    const previous = button('이전 출처', 'previous-page', () => { if (page > 0) { page--; update(); } });
    const next = button('다음 출처', 'next-page', () => { if ((page + 1) * PAGE_SIZE < record.view.sources.length) { page++; update(); } });
    controls.append(previous, pageLabel, next); parent.append(list, controls);
    function update() {
      try {
        list.replaceChildren();
        record.view.sources.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).forEach(source => {
          const row = element('li');
          const label = (source.marker ? '[' + source.marker + '] ' : '') + source.title;
          const select = button(label, 'list-source', () => record.select?.(source, false));
          row.append(select, element('span', source.cited ? '답변 인용 · 실제 사용 미확인' : '검색 후보 · 인용 미확인'));
          if (source.url) {
            const link = element('a', '출처 열기'); link.setAttribute('href', source.url);
            link.setAttribute('target', '_blank'); link.setAttribute('rel', 'noopener noreferrer'); row.appendChild(link);
          }
          list.appendChild(row);
        });
        previous.disabled = page === 0; next.disabled = (page + 1) * PAGE_SIZE >= record.view.sources.length;
        pageLabel.textContent = record.view.sources.length ? (page + 1) + ' / ' + Math.ceil(record.view.sources.length / PAGE_SIZE) : '0';
      } catch { /* A list failure must not escape into the answer/stream renderer. */ }
    }
    update();
  }
  function sourceDetails(record, inspector, source, relation) {
    inspector.replaceChildren();
    inspector.append(element('strong', source.title),
      element('p', '인용 표식: ' + (source.marker || '미제공')),
      element('p', '위치: ' + source.locator + ' · 출처 revision: ' + source.revision),
      element('p', source.cited ? '답변 인용 확인 · 실제 사용 미확인' : '검색 후보 · 인용 미확인 · 실제 사용 미확인'),
      element('p', '발췌: 제공되지 않음'));
    if (relation) inspector.appendChild(element('p', '연결: 질문 → ' + (source.cited ? '답변 인용' : '검색 후보') + ' · 원문 관계: UNKNOWN'));
    if (source.url) {
      const link = element('a', '출처 열기'); link.setAttribute('href', source.url);
      link.setAttribute('target', '_blank'); link.setAttribute('rel', 'noopener noreferrer'); inspector.appendChild(link);
    }
  }
  function render(answer, record) {
    record.panel?.remove(); record.panel = null;
    if (!attached(answer) || !document) return false;
    try {
      const panel = element('section'); panel.dataset.answerProvenance = ''; panel.dataset.ttsIgnore = '1';
      panel.className = 'answer-provenance'; panel.setAttribute('aria-label', '이 답변의 근거 연결도');
      record.panel = panel;
      if (record.view.status === 'unavailable') {
        panel.appendChild(element('p', '이 답변의 근거 연결도를 확인할 수 없습니다. 답변에 결속된 근거 정보가 없습니다.'));
        insert(answer, panel); return true;
      }
      if (!record.view.sources.length) {
        panel.appendChild(element('p', '이 답변에 표시할 근거가 없습니다'));
        insert(answer, panel); return true;
      }
      const heading = record.view.sources.some(source => source.cited) ? '답변 인용 근거' : '검색 후보 · 인용 미확인';
      const head = element('div'); head.className = 'answer-provenance-head';
      const graph = element('div', '', 'graph'); graph.className = 'answer-provenance-graph';
      const detail = element('div', '', 'details'); detail.className = 'answer-provenance-details'; detail.hidden = true;
      detail.id = 'answer-provenance-' + (++presentationSequence);
      const inspector = element('div', '출처 또는 연결을 선택하면 상세를 표시합니다.', 'inspector');
      inspector.className = 'answer-provenance-inspector'; inspector.setAttribute('aria-live', 'polite');
      const toggle = button('근거 연결도 펼치기', 'expand', () => expand(!record.expanded));
      toggle.setAttribute('aria-expanded', 'false'); toggle.setAttribute('aria-controls', detail.id);
      head.append(element('strong', heading), toggle);
      panel.append(head, element('p', '출처 연결만 표시 · 실제 사용 미확인'), graph, detail);
      detail.appendChild(inspector); listView(record, detail);
      record.expanded = false;
      record.select = (source, relation, origin) => {
        try {
          expand(true); sourceDetails(record, inspector, source, relation);
          let focusRestored = false;
          for (const role of ['source-node', 'relation']) {
            graph.querySelectorAll('[data-role="' + role + '"]').forEach(node => {
              const selected = node.__source === source && (role === 'relation') === relation;
              node.setAttribute('aria-pressed', String(selected));
              if (selected && origin) { node.focus(); focusRestored = true; }
            });
          }
          if (origin && !focusRestored) toggle.focus();
        } catch { /* Selection is independent of streaming. */ }
      };
      function draw() {
        graph.replaceChildren();
        const cited = record.view.sources.filter(source => source.cited);
        const visible = (record.expanded || !cited.length ? record.view.sources : cited).slice(0, record.expanded ? 11 : 4);
        graph.dataset.nodes = String(1 + visible.length); graph.dataset.links = String(visible.length);
        const anchor = element('span', '질문', 'question-anchor'); anchor.className = 'answer-provenance-question'; graph.appendChild(anchor);
        visible.forEach(source => {
          const row = element('div'); row.className = 'answer-provenance-connection';
          const relation = button(source.cited ? '→ 인용' : '→ 검색 후보', 'relation', () => record.select(source, true, relation));
          relation.__source = source; relation.setAttribute('aria-pressed', 'false');
          relation.setAttribute('aria-label', '질문 → ' + source.title + ': ' + (source.cited ? '답변 인용' : '검색 후보 · 인용 미확인'));
          const node = button((source.marker ? '[' + source.marker + '] ' : '') + source.title,
            'source-node', () => record.select(source, false, node));
          node.__source = source; node.setAttribute('aria-pressed', 'false');
          row.append(relation, node); graph.appendChild(row);
        });
      }
      function expand(value) {
        try {
          record.expanded = value; detail.hidden = !value;
          toggle.setAttribute('aria-expanded', String(value));
          toggle.textContent = value ? '근거 연결도 접기' : '근거 연결도 펼치기';
          toggle.setAttribute('aria-label', toggle.textContent);
          draw();
        } catch {
          try { graph.replaceChildren(element('p', '연결도를 표시할 수 없어 아래 출처 목록을 제공합니다.')); } catch { /* No stream impact. */ }
        }
      }
      panel.addEventListener('keydown', event => {
        if (event.key === 'Escape' && record.expanded) {
          event.preventDefault(); try { expand(false); toggle.focus(); } catch { /* No stream impact. */ }
        }
      });
      draw(); insert(answer, panel); return true;
    } catch {
      // Preserve the same allowlisted sources if the visual layout cannot render.
      try {
        record.panel?.remove();
        const panel = element('section'); panel.dataset.answerProvenance = ''; panel.dataset.ttsIgnore = '1';
        panel.className = 'answer-provenance'; record.panel = panel;
        panel.appendChild(element('p', '연결도를 표시할 수 없어 출처 목록을 제공합니다. 실제 사용 미확인'));
        const inspector = element('div', '출처를 선택하면 상세를 표시합니다.', 'inspector');
        inspector.className = 'answer-provenance-inspector'; inspector.setAttribute('aria-live', 'polite');
        panel.appendChild(inspector);
        record.select = (source, relation) => {
          try { sourceDetails(record, inspector, source, relation); } catch { /* No stream impact. */ }
        };
        listView(record, panel); insert(answer, panel); return true;
      } catch { return false; }
    }
  }
  function begin(answer, value, isCurrent) {
    if (!answer || typeof answer !== 'object') return false;
    answers.get(answer)?.panel?.remove();
    const record = { identity: identity(value), isCurrent, panel: null, view: null };
    record.view = record.identity && typeof isCurrent === 'function'
      ? Object.freeze({ status: 'pending', sources: Object.freeze([]), projectionVersion: VERSION, graphKind: 'source-only' })
      : unavailable('identity');
    answers.set(answer, record);
    if (record.view.status === 'unavailable') render(answer, record);
    return record.view.status === 'pending';
  }
  function invalidate(answer, reason = 'invalidated') {
    const record = answers.get(answer);
    if (!record) return false;
    record.view = unavailable(['cancelled', 'removed', 'revoked', 'version'].includes(reason) ? reason : 'invalidated');
    render(answer, record); return true;
  }
  function finalize(answer, packet = {}) {
    if (!answer || typeof answer !== 'object') return false;
    if (!answers.has(answer)) begin(answer, packet.identity, packet.isCurrent);
    const record = answers.get(answer);
    if (record.view.status !== 'pending' || !same(record.identity, identity(packet.identity))) return false;
    const trace = typeof packet.traceTurnId === 'string' ? packet.traceTurnId.trim()
      : Number.isSafeInteger(packet.traceTurnId) && packet.traceTurnId > 0 ? String(packet.traceTurnId) : '';
    if (!attached(answer) || !current(record) || packet.sessionId !== record.identity.sessionId ||
        !trace || /^(ready|unknown|unavailable)$/i.test(trace) ||
        (packet.projectionVersion != null && packet.projectionVersion !== VERSION)) {
      invalidate(answer, 'invalidated'); return false;
    }
    // Raw ownership fields remain in this WeakMap record, never in DOM or the public view.
    record.traceTurnId = trace;
    record.view = Object.freeze({ status: 'ready', graphKind: 'source-only', projectionVersion: VERSION,
      sources: project(packet.evidence, packet.answerText) });
    return render(answer, record);
  }
  function restore(answer) {
    begin(answer, null, null); return view(answer);
  }
  function view(answer) { return answer && typeof answer === 'object' ? answers.get(answer)?.view || null : null; }
  return { begin, finalize, invalidate, restore, view };
});
