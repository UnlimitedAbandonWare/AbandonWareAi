(function (root, factory) {
  const api = factory(typeof module === 'object' && module.exports ? require('../assets/interview/interview-core.js') : root.InterviewCore);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ChatDisplayBridge = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (core) {
  'use strict';
  const MOUNT_ID = 'display-bridge-mount';
  const LABELS = { question: '질문', hint: '힌트', answer: '짧은 답변' };

  // chat.displayBridge.enabled — off by default. Sources, in priority order:
  // ?displayBridge=1|0 > mount data-enabled > <meta name="chat-display-bridge"> >
  // window.CHAT_DISPLAY_BRIDGE_ENABLED > localStorage['chat.displayBridge.enabled'].
  function truthy(value) {
    return value === true || value === '1' || value === 'true' || value === 'on';
  }
  function falsy(value) {
    return value === false || value === '0' || value === 'false' || value === 'off';
  }
  function resolveEnabled(env = {}) {
    const location = env.location || globalThis.location;
    const mountEl = env.mountEl || null;
    const doc = env.document || globalThis.document;
    const win = env.window || globalThis;
    const storage = env.storage || (win.localStorage || null);
    const query = (() => { try { return new URLSearchParams(location && location.search || '').get('displayBridge'); } catch (_) { return null; } })();
    if (truthy(query)) return true;
    if (falsy(query)) return false;
    const dataFlag = mountEl && mountEl.dataset ? mountEl.dataset.enabled : null;
    if (truthy(dataFlag)) return true;
    if (falsy(dataFlag)) return false;
    const meta = (() => { try { return doc && doc.querySelector ? doc.querySelector('meta[name="chat-display-bridge"]') : null; } catch (_) { return null; } })();
    if (meta && truthy(meta.content)) return true;
    if (meta && falsy(meta.content)) return false;
    if (win.CHAT_DISPLAY_BRIDGE_ENABLED !== undefined) return truthy(win.CHAT_DISPLAY_BRIDGE_ENABLED);
    try {
      const stored = storage && storage.getItem ? storage.getItem('chat.displayBridge.enabled') : null;
      if (truthy(stored)) return true;
      if (falsy(stored)) return false;
    } catch (_) { /* storage unavailable */ }
    return false;
  }

  function isSkipNode(node) {
    return node && node.nodeType === 1 && typeof node.matches === 'function' && node.matches(
      '.evidence-rail,[data-live-region="excluded"],.message-debug-fx,.message-actions,.answer-integrity-warning,.awx-trace,[data-trace-root]');
  }
  function textOf(node) {
    let out = '';
    for (const child of Array.from(node.childNodes || [])) {
      if (isSkipNode(child)) continue;
      out += child.textContent || '';
    }
    return out.trim();
  }
  function latestAssistantBubble(doc) {
    const win = doc && doc.getElementById ? doc.getElementById('chatWindow') : null;
    if (!win || !win.querySelectorAll) return null;
    const items = win.querySelectorAll('.message[data-speaker="assistant"],.message.assistant');
    for (let i = items.length - 1; i >= 0; i--) {
      const node = items[i];
      if (node && node.dataset && node.dataset.state !== 'pending' && textOf(node)) return node;
    }
    return null;
  }
  function extractAnswerText(doc) {
    const bubble = latestAssistantBubble(doc);
    return bubble ? textOf(bubble) : '';
  }
  function sourceTitlesOf(bubble) {
    if (!bubble) return [];
    const items = Array.isArray(bubble.__awxEvidenceRailItems) ? bubble.__awxEvidenceRailItems : null;
    if (items && items.length) {
      return items.slice(0, 4).map(item => [item && item.marker, item && item.title].filter(v => typeof v === 'string' && v.trim()).join(' ').trim())
        .map(title => Array.from(String(title).replace(/[\u0000-\u001F\u007F-\u009F]/g, ' ').trim()).slice(0, 120).join(''))
        .filter(Boolean);
    }
    if (!bubble.querySelectorAll) return [];
    return Array.from(bubble.querySelectorAll('.evidence-rail .evidence-chip')).slice(0, 4)
      .map(chip => Array.from(String(chip.textContent || '').trim()).slice(0, 120).join('')).filter(Boolean);
  }

  function mount(env = {}) {
    const doc = env.document || globalThis.document;
    const mountEl = env.mountEl || (doc && doc.getElementById ? doc.getElementById(MOUNT_ID) : null);
    if (!mountEl) return null;
    if (!resolveEnabled({ ...env, mountEl })) return null;
    return build(env, doc, mountEl);
  }

  function build(env, doc, mountEl) {
    const fetchImpl = env.fetch || (globalThis.fetch ? globalThis.fetch.bind(globalThis) : null);
    const location = env.location || globalThis.location || { protocol: 'http:', origin: 'http://localhost' };
    const EventSourceImpl = env.EventSource || globalThis.EventSource;
    const el = (tag, cls, text) => { const n = doc.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; };
    let snapshot = null, sent = null, lastVersion = 0, generation = 0, monitor = null, polling = null, busy = false, submittedCard = '', uncertainSend = false, publicUrl = '';
    const usePolling = location.protocol === 'https:';
    const monitorClient = core ? core.outputClientId() : 'disabled';

    const details = el('details', 'cdb');
    const summary = el('summary', 'cdb-summary');
    summary.append(el('span', 'cdb-title', '디스플레이로 보내기'));
    const stateTag = el('span', 'cdb-state', '로컬 도구');
    summary.append(stateTag);
    details.append(summary);
    const body = el('div', 'cdb-body');
    details.append(body);
    mountEl.appendChild ? mountEl.appendChild(details) : mountEl.append(details);

    const note = el('p', 'cdb-note', '마지막 답변·힌트·직접 적은 문구를 출력 클라이언트로 보냅니다. 전송 전 내용을 확인하세요.');
    body.append(note);
    const rowTake = el('div', 'cdb-row');
    const bAnswer = el('button', 'secondary', '마지막 답변 가져오기'); bAnswer.type = 'button';
    const bHint = el('button', 'secondary', '120자 힌트 추출'); bHint.type = 'button';
    const bQuestion = el('button', 'secondary', '입력창 내용 가져오기'); bQuestion.type = 'button';
    rowTake.append(bAnswer, bHint, bQuestion); body.append(rowTake);

    const kinds = el('div', 'cdb-row cdb-kinds');
    ['question', 'hint', 'answer'].forEach(k => {
      const lab = el('label', 'cdb-kind');
      const radio = doc.createElement('input'); radio.type = 'radio'; radio.name = 'cdb-kind'; radio.value = k; if (k === 'hint') radio.checked = true;
      lab.append(radio); lab.append(doc.createTextNode(LABELS[k])); kinds.append(lab);
      radio.addEventListener && radio.addEventListener('change', updatePreview);
    });
    body.append(kinds);
    const text = el('textarea', 'cdb-text'); text.rows = 3; text.placeholder = '120자 힌트 추출로 답변 문장을 가져오거나 직접 입력하세요.';
    body.append(text);
    const rowSend = el('div', 'cdb-row');
    const count = el('span', 'cdb-count', '0 / 120');
    const bConnect = el('button', 'secondary', '연결 준비'); bConnect.type = 'button';
    const bSend = el('button', 'primary', '디스플레이로 전송'); bSend.type = 'button'; bSend.disabled = true;
    const bStop = el('button', 'secondary', '연결 종료'); bStop.type = 'button'; bStop.disabled = true;
    rowSend.append(count, bConnect, bSend, bStop); body.append(rowSend);
    const delivery = el('div', 'cdb-delivery');
    const status = el('strong', 'cdb-status', '출력 연결을 준비하세요'); status.setAttribute && status.setAttribute('role', 'status');
    const cServer = el('span', 'cdb-check', '○ 서버 접수');
    const cAck = el('span', 'cdb-check', '○ 수신 ACK');
    const link = el('a', 'cdb-receiver', '출력 클라이언트 열기 ↗'); link.hidden = true; link.target = '_blank'; link.rel = 'noopener noreferrer';
    delivery.append(status, cServer, cAck, link); body.append(delivery);
    const detail = el('p', 'cdb-detail', ''); body.append(detail);

    if (!core) { status.textContent = 'InterviewCore를 불러오지 못했습니다'; return { root: details, enabled: true, core: false }; }

    function kind() { const sel = kinds.querySelector && kinds.querySelector('input[name="cdb-kind"]:checked'); return sel ? sel.value : 'hint'; }
    async function api(path, bodyObj) {
      const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 10000);
      try {
        const res = await fetchImpl(path, { method: bodyObj === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json' }, body: bodyObj === undefined ? undefined : JSON.stringify(bodyObj), signal: controller.signal });
        if (!res.ok) { const e = new Error('http-' + res.status); e.httpStatus = res.status; throw e; }
        return { data: await res.json(), httpStatus: res.status };
      } finally { clearTimeout(timeout); }
    }
    function failStatus(e) {
      if (e && e.httpStatus === 404) return '이 프로파일은 출력 세션을 제공하지 않습니다(demo_disabled/interview 전용)';
      if (e && (e.httpStatus === 401 || e.httpStatus === 403)) return '출력 세션은 이 프로파일에서 인증이 필요합니다';
      return '출력 연결 준비 실패 — 자동으로 다시 시도하지 않습니다';
    }
    function updatePreview() {
      const value = text.value || ''; const n = Array.from(value).length;
      count.textContent = n + ' / 120' + (n > 120 ? ' · 직접 줄여 주세요' : '');
      let valid = false; try { core.cardText(value, kind()); valid = true; } catch (_) { }
      bSend.disabled = !valid || busy || !snapshot || snapshot.state !== 'RUNNING' || submittedCard === JSON.stringify([kind(), value]);
    }
    function showDelivery() {
      const external = snapshot ? { ...snapshot, outputConnections: Math.max(0, snapshot.outputConnections - 1) } : null;
      const s = core.deliveryState(external, sent);
      const copy = { DISCONNECTED: '출력 연결이 끊겼습니다', CONNECTED: '출력 클라이언트 연결됨', WAITING_CONNECTION: '서버 연결 준비됨', WAITING_RECEIPT: '서버 접수 · 수신 확인 대기', RECEIVER_ACK: '출력 클라이언트 수신 확인', EXPIRED: '문구 표시 시간이 끝났습니다' };
      if (snapshot && !uncertainSend) status.textContent = copy[s] || s;
      cServer.textContent = sent ? '● 서버 접수' : '○ 서버 접수';
      const ack = s === 'RECEIVER_ACK'; cAck.textContent = ack ? '● 수신 ACK' : '○ 수신 ACK';
      updatePreview();
    }
    function setSnapshot(next) { if (!snapshot || next.assistId !== snapshot.assistId || next.version < lastVersion) return; snapshot = next; lastVersion = next.version; showDelivery(); }
    async function poll(gen) {
      if (gen !== generation || !snapshot) return;
      try { const path = '/api/assist/sessions/' + snapshot.assistId + (usePolling ? `/output/poll?epoch=${snapshot.epoch}&client=${monitorClient}` : ''); const r = await api(path); if (gen === generation) setSnapshot(r.data); }
      catch (e) { if (gen === generation) { status.textContent = '수신 상태 확인 실패'; detail.textContent = '전송 완료 여부를 추정하지 않습니다.'; } }
      finally { if (gen === generation && snapshot) polling = setTimeout(() => poll(gen), 1200); }
    }
    bConnect.addEventListener('click', async () => {
      if (busy || snapshot) return; busy = true; bConnect.disabled = true; const gen = ++generation;
      try {
        const r = await api('/api/assist/sessions', {}); if (gen !== generation) return;
        snapshot = r.data; lastVersion = snapshot.version; sent = null; submittedCard = ''; uncertainSend = false;
        if (snapshot.assistId && core) { const url = core.receiverUrl(publicUrl || location.origin, snapshot.assistId, snapshot.epoch); link.href = url; link.hidden = false; }
        if (usePolling) { /* poll loop below */ }
        else if (EventSourceImpl) { monitor = new EventSourceImpl(`/api/assist/sessions/${snapshot.assistId}/output?epoch=${snapshot.epoch}`); monitor.addEventListener('assist', e => { try { if (gen === generation) setSnapshot(JSON.parse(e.data)); } catch (_) { } }); }
        bStop.disabled = false; showDelivery(); poll(gen);
      } catch (e) { status.textContent = failStatus(e); detail.textContent = '서버 상태를 확인하세요. 연결을 자동으로 다시 만들지 않습니다.'; bConnect.disabled = false; }
      finally { busy = false; updatePreview(); }
    });
    bSend.addEventListener('click', async () => {
      if (busy || !snapshot || snapshot.state !== 'RUNNING') return;
      let cardText; try { cardText = core.cardText(text.value, kind()); } catch (_) { return; }
      const identity = JSON.stringify([kind(), cardText]); if (submittedCard === identity) return; submittedCard = identity; uncertainSend = false;
      busy = true; updatePreview(); const gen = generation, current = snapshot;
      try {
        const bubble = latestAssistantBubble(doc);
        const payload = { epoch: current.epoch, kind: kind(), text: cardText, sourceTitles: sourceTitlesOf(bubble) };
        const r = await api(`/api/assist/sessions/${current.assistId}/card`, payload); if (gen !== generation) return;
        sent = { epoch: r.data.epoch, version: r.data.version }; setSnapshot(r.data);
      } catch (e) { if (gen === generation) { sent = null; uncertainSend = true; status.textContent = '전송 결과 확인 필요'; detail.textContent = '자동 재전송하지 않았습니다. 수신 상태를 확인한 뒤 진행하세요.'; } }
      finally { busy = false; updatePreview(); }
    });
    bStop.addEventListener('click', async () => {
      if (!snapshot || busy) return; busy = true; const current = snapshot; ++generation; clearTimeout(polling); if (monitor && monitor.close) monitor.close(); monitor = null;
      try { await api(`/api/assist/sessions/${current.assistId}/control`, { epoch: current.epoch, action: 'stop' }); snapshot = null; sent = null; lastVersion = 0; text.value = ''; link.hidden = true; bConnect.disabled = false; bStop.disabled = true; status.textContent = '연결 종료 · 문구 삭제'; detail.textContent = '새 출력 연결을 준비할 수 있습니다.'; showDelivery(); }
      catch (e) { status.textContent = '종료 확인 실패'; detail.textContent = '서버 종료 결과가 확인되지 않았습니다. 문구는 만료 시 삭제됩니다.'; }
      finally { busy = false; updatePreview(); }
    });
    bAnswer.addEventListener('click', () => { const value = extractAnswerText(doc); if (value) { const r = kinds.querySelector('input[value="answer"]'); if (r) r.checked = true; text.value = value; updatePreview(); } else { detail.textContent = '아직 가져올 답변이 없습니다.'; } });
    bHint.addEventListener('click', () => {
      const answer = text.value && text.value.trim() ? text.value : extractAnswerText(doc);
      if (!answer) { detail.textContent = '힌트를 만들 답변이 없습니다.'; return; }
      try { const hint = core.shortHint(answer); const r = kinds.querySelector('input[value="hint"]'); if (r) r.checked = true; text.value = hint; detail.textContent = `추출 ${Array.from(hint).length}자 — 내용을 확인한 뒤 전송하세요.`; }
      catch (_) { detail.textContent = '조건을 보존한 120자 문장을 찾지 못했습니다. 직접 편집해 주세요.'; }
      updatePreview();
    });
    bQuestion.addEventListener('click', () => { const input = doc.getElementById ? doc.getElementById('messageInput') : null; const value = input ? input.value : ''; if (value && value.trim()) { const r = kinds.querySelector('input[value="question"]'); if (r) r.checked = true; text.value = value; updatePreview(); } else { detail.textContent = '입력창이 비어 있습니다.'; } });
    text.addEventListener && text.addEventListener('input', updatePreview);
    if (typeof window !== 'undefined' && window.addEventListener) window.addEventListener('pagehide', () => { ++generation; clearTimeout(polling); if (monitor && monitor.close) monitor.close(); if (snapshot) try { fetchImpl(`/api/assist/sessions/${snapshot.assistId}/control`, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ epoch: snapshot.epoch, action: 'stop' }), keepalive: true }).catch(() => { }); } catch (_) { } });
    (async () => { try { const r = await api('/api/assist/bootstrap'); publicUrl = core.publicOrigin(r.data && r.data.publicHttpsUrl) || ''; } catch (_) { } })();
    updatePreview();
    return { root: details, enabled: true, core: true };
  }

  const api = { MOUNT_ID, resolveEnabled, extractAnswerText, sourceTitlesOf, mount, shortHint: core ? core.shortHint : null };
  if (typeof module === 'object' && module.exports) return api;
  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => mount());
    else mount();
  }
  return api;
});
