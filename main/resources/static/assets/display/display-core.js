(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.DisplayCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  // randomUUID is secure-context-only; getRandomValues is available on LAN HTTP.
  function requestUuid() {
    if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
    const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  function safeSourceUrl(value) {
    if (typeof value !== 'string' || /[\\\s\u0000-\u001f]/.test(value)) return null;
    try {
      const url = new URL(value);
      const host = url.hostname.toLowerCase();
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
      // IPv6 literals and ambiguous/local names remain readable, non-clickable citations.
      if (!host.includes('.') || host.endsWith('.') || host.includes(':') || /(^|\.)(localhost|local|internal)$/.test(host)) return null;
      if (/^[\d.]+$/.test(host)) {
        const [a, b] = host.split('.').map(Number);
        if (a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127) ||
            (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) ||
            (a === 198 && (b === 18 || b === 19))) return null;
      }
      return url.href;
    } catch (_) { return null; }
  }
  function projectResponse(dto) {
    if (!dto || typeof dto.content !== 'string' || !dto.content.trim()) throw new Error('response-contract');
    return {
      answer: dto.content,
      fallback: typeof dto.modelUsed === 'string' && /(^|:)fallback(?:$|:)/.test(dto.modelUsed),
      sessionId: Number.isSafeInteger(dto.sessionId) && dto.sessionId > 0 ? dto.sessionId : null,
      sources: (Array.isArray(dto.evidence) ? dto.evidence : []).filter(e => e && typeof e === 'object').map(e => ({
        title: typeof e.title === 'string' && e.title.trim() ? e.title : '출처',
        marker: typeof e.marker === 'string' ? e.marker : '',
        url: safeSourceUrl(e.source)
      }))
    };
  }
  function paginate(text, size = 108) {
    // Five conservative rows fit the existing 24px Display card, including newlines.
    const pages = []; let part = [], row = 1, column = 0;
    for (const char of Array.from(text)) {
      const lineBreak = char === '\n' || char === '\r', width = char === '\t' ? 8 : 1;
      const wrap = !lineBreak && column + width > 18;
      if (part.length && (part.length >= size || ((lineBreak || wrap) && row === 5))) {
        pages.push(part.join('')); part = []; row = 1; column = 0;
      }
      part.push(char);
      if (lineBreak) { row++; column = 0; }
      else { if (column + width > 18) { row++; column = 0; } column += width; }
    }
    if (part.length) pages.push(part.join(''));
    return pages.length ? pages : [''];
  }
  function isEditingKey(event) {
    return Boolean(event.isComposing || event.keyCode === 229 || event.target?.isContentEditable ||
      /^(INPUT|TEXTAREA|SELECT)$/.test(event.target?.tagName || ''));
  }
  const errors = {
    400: ['invalid-input', '질문을 확인해 주세요.'],
    403: ['session-forbidden', '이 대화를 사용할 수 없습니다. 새 대화로 시작해 주세요.'],
    409: ['request-conflict', '이미 처리 중이거나 종료된 요청입니다. 다시 보내지 않았습니다.'],
    413: ['input-too-large', '질문이 너무 깁니다. 짧게 나누어 주세요.'],
    422: ['request-mismatch', '요청 확인에 실패했습니다. 다시 보내지 않았습니다.'],
    429: ['rate-limited', '요청이 많습니다. 잠시 기다린 뒤 다른 질문을 보내 주세요.'],
    503: ['admission-unavailable', '현재 답변 서버를 사용할 수 없습니다. 서버 연결을 확인해 주세요.']
  };
  function createClient(options = {}) {
    const fetchImpl = options.fetchImpl || globalThis.fetch.bind(globalThis);
    const setTimer = options.setTimer || setTimeout;
    const clearTimer = options.clearTimer || clearTimeout;
    const now = options.now || (() => performance.now());
    const onChange = options.onChange || (() => {});
    let revision = 0, submittedRevision = -1, generation = 0, flight = null;
    let retryNotBefore = 0, cooldownTimer = null;
    const state = { phase: 'IDLE', message: '', sessionId: null, result: null, error: null, metrics: null };
    const notify = () => onChange(state);
    function canSubmit() { return !flight && now() >= retryNotBefore && state.message.trim().length > 0 && state.message.length <= 2000 && revision !== submittedRevision; }
    function setMessage(value) {
      const text = String(value);
      if (text !== state.message) { revision++; state.message = text; }
      notify();
    }
    function unknown() {
      state.phase = 'ERROR';
      state.error = { code: 'outcome-unknown', message: '답변 완료 여부를 확인할 수 없습니다. 같은 질문은 다시 보내지 않았습니다. 서버 처리는 계속될 수 있습니다.' };
    }
    function cancel() {
      if (!flight) return;
      const previous = flight; flight = null; generation++;
      clearTimer(previous.timer); previous.controller.abort(); previous.finish(); unknown(); notify();
    }
    function newConversation() {
      if (flight) cancel();
      generation++; revision++; submittedRevision = -1;
      Object.assign(state, { phase: 'IDLE', message: '', sessionId: null, result: null, error: null, metrics: null }); notify();
    }
    async function submit() {
      if (!canSubmit()) return false;
      submittedRevision = revision;
      const id = requestUuid(); const seq = ++generation; const started = now();
      const body = JSON.stringify({ message: state.message, sessionId: state.sessionId, inputType: 'text',
        ...(options.publicWebSearch === true ? { useWebSearch: true, useRag: false, searchMode: 'FORCE_LIGHT' } : {}) });
      const controller = new AbortController();
      let finish; const stopped = new Promise(resolve => { finish = resolve; });
      const current = { controller, finish, timer: null }; flight = current;
      Object.assign(state, { phase: 'LOADING', result: null, error: null, metrics: {requestId:id,roundTripMs:0,httpStatus:null,sourceCount:0} }); notify();
      current.timer = setTimer(cancel, options.timeoutMs || 90000);
      const work = (async () => {
        try {
          const res = await fetchImpl('/api/chat/sync', { method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json', 'Idempotency-Key': id, 'X-Request-Id': id, 'X-Budget-Ms': '80000' }, body, signal: controller.signal });
          if (seq !== generation) return;
          const requestHeader = res.headers.get('X-Request-Id');
          const requestId = typeof requestHeader === 'string' && /^[A-Za-z0-9._:-]{1,128}$/.test(requestHeader) ? requestHeader : id;
          state.metrics = { roundTripMs: Math.round(now() - started), httpStatus: res.status, requestId, sourceCount: 0 };
          if (!res.ok) {
            const [code, message] = errors[res.status] || ['server-error', '답변을 가져오지 못했습니다. 잠시 후 서버 상태를 확인해 주세요.'];
            const retry = res.headers.get('Retry-After');
            const dateDelay = retry ? Math.ceil((Date.parse(retry) - Date.now()) / 1000) : NaN;
            const seconds = /^\d{1,5}$/.test(retry || '') ? Number(retry) : (Number.isFinite(dateDelay) && dateDelay > 0 ? dateDelay : null);
            if (res.status === 429 && seconds !== null) {
              retryNotBefore = now() + seconds * 1000;
              clearTimer(cooldownTimer);
              cooldownTimer = setTimer(notify, Math.min(seconds * 1000 + 1, 2147483647));
              cooldownTimer?.unref?.();
            }
            state.error = { code, message, retryAfterSeconds: seconds }; state.phase = 'ERROR'; return;
          }
          const dto = await res.json();
          if (seq !== generation) return;
          const result = (options.projectResponse || projectResponse)(dto);
          state.result = result; if (result.sessionId !== null) state.sessionId = result.sessionId;
          state.metrics.roundTripMs = Math.round(now() - started); state.metrics.sourceCount = result.sources.length; state.phase = 'RESULT';
        } catch (_) {
          if (seq === generation) unknown();
        } finally {
          if (seq === generation) { clearTimer(current.timer); flight = null; notify(); }
        }
      })();
      await Promise.race([work, stopped]); return true;
    }
    return { state, setMessage, submit, cancel, newConversation, canSubmit };
  }
  return { requestUuid, safeSourceUrl, projectResponse, paginate, isEditingKey, createClient };
});
